package com.certguard.service;

import com.certguard.entity.CertificateRecord;
import com.certguard.entity.Target;
import com.certguard.enums.CertStatus;
import com.certguard.repository.CertificateRecordRepository;
import com.certguard.repository.TargetRepository;
import com.certguard.service.chain.ChainValidationResult;
import com.certguard.service.chain.ChainValidationService;
import com.certguard.service.revocation.RevocationResult;
import com.certguard.service.revocation.RevocationCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class SslScannerService {

    private final TargetRepository targetRepository;
    private final CertificateRecordRepository certRepository;
    private final ExpiryEvaluationService expiryEvaluationService;
    private final ChainValidationService chainValidationService;
    private final RevocationCheckService revocationCheckService;

    @Value("${app.scanning.public.thread-pool-size:20}") private int threadPoolSize;
    @Value("${app.scanning.public.connect-timeout-ms:10000}") private int connectTimeoutMs;
    @Value("${app.scanning.public.retry-max-attempts:3}") private int maxRetries;
    @Value("${app.revocation.shadow:true}") private boolean revocationShadowMode;

    @Scheduled(cron = "${app.scanning.public.schedule-cron}")
    @SchedulerLock(name = "SslScannerService_scheduledPublicScan",
                   lockAtMostFor = "PT30M", lockAtLeastFor = "PT10M")
    public void scheduledPublicScan() {
        log.info("Starting scheduled public certificate scan");
        List<Target> targets = targetRepository.findAllByIsPrivateFalseAndEnabledTrue();
        log.info("Found {} public targets to scan", targets.size());
        scanTargets(targets, ExpiryEvaluationService.EvaluationMode.SCHEDULED);
    }

    /**
     * User-triggered (FORCE) scan of a single public target.
     * Captures the target's prior lastScannedAt for the force-scan debounce check.
     */
    @Transactional
    public void scanTarget(Target target) {
        Instant previousLastScannedAt = target.getLastScannedAt();
        scanSingleTarget(target, ExpiryEvaluationService.EvaluationMode.FORCE, previousLastScannedAt);
    }

    public void scanTargetAsync(Target target) {
        CompletableFuture.runAsync(() -> {
            try {
                // Initial scan on new-target creation — treat as SCHEDULED (no prior lastScannedAt).
                scanSingleTarget(target, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);
            }
            catch (Exception e) { log.error("Async scan failed for {}: {}", target.getHost(), e.getMessage()); }
        });
    }

    private void scanTargets(List<Target> targets, ExpiryEvaluationService.EvaluationMode mode) {
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        for (Target target : targets) {
            executor.submit(() -> {
                try { scanSingleTarget(target, mode, null); }
                catch (Exception e) { log.error("Error scanning {}: {}", target.getHost(), e.getMessage()); }
            });
        }
        executor.shutdown();
        try { executor.awaitTermination(30, TimeUnit.MINUTES); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Transactional
    protected void scanSingleTarget(Target target,
                                    ExpiryEvaluationService.EvaluationMode mode,
                                    Instant previousLastScannedAt) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                ScanChainResult scanResult = fetchCertificateChain(target.getHost(), target.getPort());
                if (scanResult != null && scanResult.chain().length > 0) {
                    persistCertificates(target, scanResult, mode, previousLastScannedAt);
                    return;
                }
            } catch (Exception e) {
                log.warn("Scan attempt {}/{} failed for {}:{} — {}", attempt, maxRetries, target.getHost(), target.getPort(), e.getMessage());
                if (attempt == maxRetries) markTargetUnreachable(target, e.getMessage());
                else sleep(attempt * 2000L);
            }
        }
    }

    /**
     * Bundles the TLS peer chain with an optional OCSP staple from the SSL session.
     * The staple is only available if the server is configured for OCSP stapling.
     */
    record ScanChainResult(X509Certificate[] chain, byte[] ocspStaple) {}

    private ScanChainResult fetchCertificateChain(String host, int port) throws Exception {
        TrustManager[] trustAll = new TrustManager[]{ new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new SecureRandom());
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            try (SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true)) {
                ssl.setSoTimeout(connectTimeoutMs);
                if (!host.matches("(\\d{1,3}\\.){3}\\d{1,3}")) {
                    SSLParameters params = ssl.getSSLParameters();
                    params.setServerNames(List.of(new SNIHostName(host)));
                    ssl.setSSLParameters(params);
                }
                ssl.startHandshake();
                X509Certificate[] chain = (X509Certificate[]) ssl.getSession().getPeerCertificates();

                // Attempt to extract OCSP staple from the extended session.
                byte[] staple = null;
                SSLSession session = ssl.getSession();
                if (session instanceof ExtendedSSLSession extSession) {
                    List<byte[]> statusResponses = extSession.getStatusResponses();
                    if (statusResponses != null && !statusResponses.isEmpty()) {
                        staple = statusResponses.get(0); // TLS 1.2: single entry for leaf
                    }
                }

                return new ScanChainResult(chain, staple);
            }
        }
    }

    private void persistCertificates(Target target, ScanChainResult scanResult,
                                     ExpiryEvaluationService.EvaluationMode mode,
                                     Instant previousLastScannedAt) {
        X509Certificate[] chain = scanResult.chain();
        X509Certificate leaf = chain[0];
        try {
            Instant expiry    = leaf.getNotAfter().toInstant();
            Instant notBefore = leaf.getNotBefore().toInstant();
            String serial     = leaf.getSerialNumber().toString(16);
            String cn         = extractCN(leaf.getSubjectX500Principal().getName());
            String issuer     = leaf.getIssuerX500Principal().getName();
            String b64        = Base64.getEncoder().encodeToString(leaf.getEncoded());

            CertificateRecord record = certRepository
                    .findByTargetIdAndSerialNumber(target.getId(), serial)
                    .orElse(CertificateRecord.builder()
                            .target(target).orgId(target.getOrganization().getId()).serialNumber(serial).build());

            // RFC 0009: Chain validation (BE-4) and revocation checking (BE-5).
            ChainValidationResult chainResult = chainValidationService.validate(chain);
            RevocationResult revResult = revocationCheckService.check(
                    chain, scanResult.ocspStaple(), record.isRevocationDeepCheck());

            Instant now = Instant.now();
            record.setCommonName(cn); record.setIssuer(issuer);
            record.setExpiryDate(expiry); record.setNotBefore(notBefore);
            record.setPublicCertB64(b64);
            record.setChainDepth(chain.length);

            // Persist chain fields.
            record.setChainTrusted(chainResult.trusted());
            record.setChainValidationError(chainResult.error());

            // Persist revocation fields (always, even in shadow mode — we still record).
            record.setRevocationStatus(revResult.status());
            record.setRevocationSource(revResult.source());
            record.setRevocationCheckedAt(revResult.checkedAt());
            record.setRevocationReason(revResult.reason());
            record.setRevocationReasonCode(revResult.reasonCode());
            record.setRevokedAt(revResult.revokedAt());

            // Status: shadow mode = compute but don't set REVOKED/INVALID.
            CertStatus newStatus = expiryEvaluationService.determineCertStatus(
                    expiry, revResult, chainResult, target, target.getOrganization().getId());
            if (revocationShadowMode) {
                // Shadow: don't elevate to REVOKED/INVALID; use expiry-only status.
                CertStatus shadowStatus = expiryEvaluationService.determineCertStatus(
                        expiry, null, null, target, target.getOrganization().getId());
                record.setStatus(shadowStatus);
                if (newStatus == CertStatus.REVOKED || newStatus == CertStatus.INVALID) {
                    log.info("[SHADOW] Would set status={} for cert {} but shadow=true",
                            newStatus, cn);
                }
            } else {
                record.setStatus(newStatus);
            }

            record.setScannedAt(now);
            certRepository.save(record);

            // Post-scan expiry evaluation (RFC 0008 §7).
            expiryEvaluationService.evaluateAndNotify(record, mode, previousLastScannedAt);

            // Clear any previous scan error and stamp the successful scan time.
            target.setLastErrorMessage(null);
            target.setLastErrorAt(null);
            target.setLastScannedAt(now);
            targetRepository.save(target);

            log.info("Certificate saved — CN: {}, Expires: {}, Status: {}, Revocation: {}/{}",
                    cn, expiry, record.getStatus(), revResult.status(), revResult.source());
        } catch (CertificateEncodingException e) {
            log.error("Failed to encode cert for {}: {}", target.getHost(), e.getMessage());
        }
    }

    private void markTargetUnreachable(Target target, String errorMessage) {
        certRepository.findAllByTargetId(target.getId()).forEach(cert -> {
            cert.setStatus(CertStatus.UNREACHABLE); cert.setScannedAt(Instant.now());
            certRepository.save(cert);
        });
        target.setLastErrorMessage(errorMessage);
        target.setLastErrorAt(Instant.now());
        targetRepository.save(target);
        log.warn("Target UNREACHABLE: {}:{} — {}", target.getHost(), target.getPort(), errorMessage);
    }

    private String extractCN(String dn) {
        for (String part : dn.split(",")) {
            String t = part.trim();
            if (t.startsWith("CN=")) return t.substring(3);
        }
        return dn;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
