package com.certguard.service;

import com.certguard.entity.CertificateRecord;
import com.certguard.entity.Target;
import com.certguard.enums.CertStatus;
import com.certguard.enums.ScanSourceType;
import com.certguard.enums.ScanningMode;
import com.certguard.repository.CertificateRecordRepository;
import com.certguard.repository.TargetRepository;
import com.certguard.security.PublicAddressGuard;
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
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * In-process SSL scanner — the "DIRECT" scan path (RFC 0013 §7).
 *
 * <p>In DIRECT mode (default at merge) this is the only public-scan path and the
 * scheduledPublicScan cron is active. In HYBRID mode the cron is replaced by
 * PublicScanEnqueueScheduler; this service is retained as the fallback executor.
 * In POOL mode this service is disabled entirely.
 *
 * <p>The @Transactional self-invocation bug (RFC 0013 §4 / §8) is fixed: persistence
 * now delegates to CertificatePersistenceService, which is a separate Spring bean
 * and therefore always runs inside a proper transaction proxy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SslScannerService {

    private final TargetRepository targetRepository;
    private final CertificateRecordRepository certRepository;
    private final CertificatePersistenceService certPersistenceService;

    @Value("${app.scanning.public.thread-pool-size:20}") private int threadPoolSize;
    @Value("${app.scanning.public.connect-timeout-ms:10000}") private int connectTimeoutMs;
    @Value("${app.scanning.public.retry-max-attempts:3}") private int maxRetries;
    @Value("${app.scanning.mode:DIRECT}") private String scanningMode;

    /**
     * Scheduled public scan — active only in DIRECT mode (RFC 0013 §7).
     * In HYBRID/POOL modes the PublicScanEnqueueScheduler takes over and this cron
     * should be disabled (set schedule-cron to a future timestamp or remove the lock).
     */
    @Scheduled(cron = "${app.scanning.public.schedule-cron}")
    @SchedulerLock(name = "SslScannerService_scheduledPublicScan",
                   lockAtMostFor = "PT30M", lockAtLeastFor = "PT10M")
    public void scheduledPublicScan() {
        ScanningMode mode = parseScanningMode();
        if (mode != ScanningMode.DIRECT) {
            log.debug("scheduledPublicScan skipped — mode={} (pool handles public scans)", mode);
            return;
        }
        log.info("Starting scheduled public certificate scan (DIRECT mode)");
        List<Target> targets = targetRepository.findAllByIsPrivateFalseAndEnabledTrue();
        log.info("Found {} public targets to scan", targets.size());
        scanTargets(targets, ExpiryEvaluationService.EvaluationMode.SCHEDULED);
    }

    /**
     * User-triggered (FORCE) scan of a single public target.
     * In DIRECT mode: executes immediately (existing behaviour).
     * In HYBRID/POOL modes: TargetService.triggerScan routes public scans through the
     * pool instead, so this method is not called for public targets.
     *
     * <p>The self-invocation @Transactional bug is fixed: scanSingleTarget is now an
     * internal method that calls certPersistenceService (a separate bean) for persistence.
     */
    @Transactional
    public void scanTarget(Target target) {
        Instant previousLastScannedAt = target.getLastScannedAt();
        scanSingleTarget(target, ExpiryEvaluationService.EvaluationMode.FORCE, previousLastScannedAt);
    }

    public void scanTargetAsync(Target target) {
        CompletableFuture.runAsync(() -> {
            try {
                scanSingleTarget(target, ExpiryEvaluationService.EvaluationMode.SCHEDULED, null);
            } catch (Exception e) {
                log.error("Async scan failed for {}: {}", target.getHost(), e.getMessage());
            }
        });
    }

    /**
     * Executes a HYBRID fallback scan for a stale PUBLIC_POOL job.
     * Called by PublicScanFallbackScheduler when a pool job has been PENDING > 10 min.
     * Returns true if the scan succeeded. RFC 0013 §7.
     *
     * <p>PublicAddressGuard runs first: in HYBRID mode this method makes the server
     * itself open a socket to the target (same as the DIRECT path), so it is just as
     * much an SSRF vector as the agent-side pool scan and must be guarded identically.
     * Re-resolves at scan time (not just at enqueue) since DNS can change between the
     * original enqueue and the fallback firing up to 10+ minutes later.
     */
    @Transactional
    public boolean executeFallbackScan(Target target, Instant previousLastScannedAt) {
        String rejectionReason = checkPublicAddressGuard(target.getHost());
        if (rejectionReason != null) {
            log.warn("HYBRID fallback rejected by PublicAddressGuard for {}:{} — {}",
                    target.getHost(), target.getPort(), rejectionReason);
            return false;
        }
        try {
            ScanChainResult scanResult = fetchCertificateChain(target.getHost(), target.getPort());
            if (scanResult != null && scanResult.chain().length > 0) {
                persistViaService(target, scanResult,
                        ExpiryEvaluationService.EvaluationMode.SCHEDULED, previousLastScannedAt);
                return true;
            }
        } catch (Exception e) {
            log.warn("Fallback scan failed for {}:{} — {}", target.getHost(), target.getPort(), e.getMessage());
        }
        return false;
    }

    /**
     * RFC 0013 §7 — extracted as a pure static method (mirrors
     * {@code PollLoop.checkPublicAddressGuard} on the agent side) so this invariant is
     * directly unit-testable without any network I/O or timing assumptions.
     *
     * @return the rejection reason if the host resolves to a disallowed address, or
     *         {@code null} if the host is publicly routable and dialing may proceed.
     */
    static String checkPublicAddressGuard(String host) {
        try {
            PublicAddressGuard.assertPubliclyRoutable(host);
            return null;
        } catch (PublicAddressGuard.PublicAddressGuardException e) {
            return e.getMessage();
        }
    }

    // ── Internal scanning ─────────────────────────────────────────────────────

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

    private void scanSingleTarget(Target target,
                                  ExpiryEvaluationService.EvaluationMode mode,
                                  Instant previousLastScannedAt) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                ScanChainResult scanResult = fetchCertificateChain(target.getHost(), target.getPort());
                if (scanResult != null && scanResult.chain().length > 0) {
                    persistViaService(target, scanResult, mode, previousLastScannedAt);
                    return;
                }
            } catch (Exception e) {
                log.warn("Scan attempt {}/{} failed for {}:{} — {}",
                        attempt, maxRetries, target.getHost(), target.getPort(), e.getMessage());
                if (attempt == maxRetries) markTargetUnreachable(target, e.getMessage());
                else sleep(attempt * 2000L);
            }
        }
    }

    /**
     * Delegates certificate persistence to CertificatePersistenceService (RFC 0013 §8).
     * Fixes the @Transactional self-invocation bug: this call crosses a bean boundary
     * so Spring's proxy intercepts and the transaction is properly applied.
     */
    private void persistViaService(Target target, ScanChainResult scanResult,
                                   ExpiryEvaluationService.EvaluationMode mode,
                                   Instant previousLastScannedAt) throws Exception {
        X509Certificate[] chain = scanResult.chain();
        X509Certificate leaf = chain[0];

        String serial    = leaf.getSerialNumber().toString(16);
        String cn        = extractCN(leaf.getSubjectX500Principal().getName());
        String issuer    = leaf.getIssuerX500Principal().getName();
        String b64       = Base64.getEncoder().encodeToString(leaf.getEncoded());
        Instant expiry   = leaf.getNotAfter().toInstant();
        Instant notBefore = leaf.getNotBefore().toInstant();

        // Build chainB64 list (leaf first, then intermediates) for chain validation.
        List<String> chainB64 = new ArrayList<>();
        for (X509Certificate cert : chain) {
            chainB64.add(Base64.getEncoder().encodeToString(cert.getEncoded()));
        }

        // Extract SANs from leaf.
        List<String> sans = extractSANs(leaf);

        // RFC 0013 §9: both the plain-DIRECT path and the HYBRID fallback path run
        // in-process on the server — from the tenant's perspective both are
        // "CertGuard Cloud Scanner", so always stamp CLOUD_SCANNER here.
        certPersistenceService.persistFull(
                target,
                null,           // scannedByAgent = null (direct path, not via agent)
                serial, cn, issuer, notBefore, expiry,
                leaf.getPublicKey().getAlgorithm(),
                keySize(leaf.getPublicKey()),
                leaf.getSigAlgName(),
                sans,
                chain.length,
                b64,
                chainB64,
                scanResult.ocspStaple(),
                mode,
                previousLastScannedAt,
                ScanSourceType.CLOUD_SCANNER);

        // Stamp the target's lastScannedAt and clear any error.
        target.setLastScannedAt(Instant.now());
        target.setLastErrorMessage(null);
        target.setLastErrorAt(null);
        targetRepository.save(target);
    }

    // ── TLS connection ────────────────────────────────────────────────────────

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

                byte[] staple = null;
                SSLSession session = ssl.getSession();
                if (session instanceof ExtendedSSLSession extSession) {
                    List<byte[]> statusResponses = extSession.getStatusResponses();
                    if (statusResponses != null && !statusResponses.isEmpty()) {
                        staple = statusResponses.get(0);
                    }
                }
                return new ScanChainResult(chain, staple);
            }
        }
    }

    @Transactional
    public void markTargetUnreachable(Target target, String errorMessage) {
        certRepository.findAllByTargetId(target.getId()).forEach(cert -> {
            cert.setStatus(CertStatus.UNREACHABLE);
            cert.setScannedAt(Instant.now());
            certRepository.save(cert);
        });
        target.setLastErrorMessage(errorMessage);
        target.setLastErrorAt(Instant.now());
        targetRepository.save(target);
        log.warn("Target UNREACHABLE: {}:{} — {}", target.getHost(), target.getPort(), errorMessage);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ScanningMode parseScanningMode() {
        try {
            return ScanningMode.valueOf(scanningMode.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown scanning mode '{}' — defaulting to DIRECT", scanningMode);
            return ScanningMode.DIRECT;
        }
    }

    private String extractCN(String dn) {
        for (String part : dn.split(",")) {
            String t = part.trim();
            if (t.startsWith("CN=")) return t.substring(3);
        }
        return dn;
    }

    private int keySize(PublicKey key) {
        if (key instanceof java.security.interfaces.RSAPublicKey rsa)
            return rsa.getModulus().bitLength();
        if (key instanceof java.security.interfaces.ECPublicKey ec)
            return ec.getParams().getCurve().getField().getFieldSize();
        return 0;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractSANs(X509Certificate cert) {
        try {
            java.util.Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans == null) return List.of();
            return sans.stream()
                    .filter(s -> s.size() >= 2)
                    .map(s -> {
                        int type = ((Number) s.get(0)).intValue();
                        return (type == 2 ? "DNS:" : type == 7 ? "IP:" : "OTHER:") + s.get(1);
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
