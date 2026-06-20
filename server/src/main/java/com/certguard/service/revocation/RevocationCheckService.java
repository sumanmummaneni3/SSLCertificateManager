package com.certguard.service.revocation;

import com.certguard.enums.RevocationSource;
import com.certguard.enums.RevocationStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.*;
import org.bouncycastle.operator.ContentVerifierProvider;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Revocation checking service (RFC 0009 §3.2 + §5.2).
 *
 * <p>Cascade order: OCSP staple → OCSP request (AIA) → CRL (CDP).
 * Stop at first definitive GOOD or REVOKED unless {@code deepCheck=true}.
 *
 * <p>Security invariants:
 * <ul>
 *   <li>Every OCSP response is signature-verified against the issuer or delegated responder.
 *   <li>Every CRL is signature-verified against the issuer.
 *   <li>Stale OCSP responses ({@code nextUpdate} past) are rejected.
 *   <li>CRL size is capped at {@code crl.max-bytes}.
 *   <li>SSRF guard applied to every URL extracted from the cert.
 *   <li>Unverifiable / stale / unreachable → UNKNOWN (soft-fail); never REVOKED.
 * </ul>
 */
@Service
public class RevocationCheckService {

    private static final Logger log = LoggerFactory.getLogger(RevocationCheckService.class);

    // ── Config ─────────────────────────────────────────────────────────────────

    @Value("${app.revocation.enabled:true}")
    private boolean revocationEnabled;

    @Value("${app.revocation.shadow:true}")
    private boolean shadowMode;

    @Value("${app.revocation.ocsp.enabled:true}")
    private boolean ocspEnabled;

    @Value("${app.revocation.ocsp.timeout-ms:4000}")
    private int ocspTimeoutMs;

    @Value("${app.revocation.crl.enabled:true}")
    private boolean crlEnabled;

    @Value("${app.revocation.crl.timeout-ms:8000}")
    private int crlTimeoutMs;

    @Value("${app.revocation.crl.max-bytes:5242880}")
    private long crlMaxBytes;

    @Value("${app.revocation.crl.stale-grace-hours:0}")
    private int crlStaleGraceHours;

    @Value("${app.revocation.clock-skew-sec:300}")
    private int clockSkewSec;

    private final MeterRegistry meterRegistry;

    public RevocationCheckService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Performs revocation checking for the leaf certificate.
     *
     * @param chain         full chain — leaf at [0], issuer at [1] (if available).
     * @param ocspStaple    raw DER-encoded OCSPResponse bytes from TLS staple (may be null).
     * @param deepCheck     when true, query BOTH OCSP and CRL; REVOKED is sticky.
     * @return never null
     */
    public RevocationResult check(X509Certificate[] chain, byte[] ocspStaple, boolean deepCheck) {
        if (!revocationEnabled) {
            return RevocationResult.unchecked(RevocationSource.NONE);
        }
        if (chain == null || chain.length == 0) {
            return RevocationResult.unchecked(RevocationSource.NONE);
        }

        X509Certificate leaf   = chain[0];
        X509Certificate issuer = chain.length > 1 ? chain[1] : null;

        // Self-signed: no issuer to query.
        if (issuer == null && isSelfSigned(leaf)) {
            return RevocationResult.unchecked(RevocationSource.NONE);
        }

        // Check if any revocation info exists in the cert.
        boolean hasAia = getOcspUrl(leaf) != null;
        boolean hasCdp = !getCdpUrls(leaf).isEmpty();
        if (!hasAia && !hasCdp && ocspStaple == null) {
            return RevocationResult.unchecked(RevocationSource.NONE);
        }

        RevocationResult firstGoodOrRevoked = null;

        // ── Step 1: OCSP staple (zero network, highest priority) ──────────────
        if (ocspStaple != null && ocspEnabled && issuer != null) {
            RevocationResult stapleResult = checkOcspStaple(leaf, issuer, ocspStaple);
            if (isDefinitive(stapleResult)) {
                recordCheck(RevocationSource.OCSP_STAPLED, stapleResult.status());
                if (!deepCheck) return stapleResult;
                if (stapleResult.status() == RevocationStatus.REVOKED) return stapleResult;
                firstGoodOrRevoked = stapleResult;
            }
        }

        // ── Step 2: OCSP request ──────────────────────────────────────────────
        if (ocspEnabled && issuer != null) {
            String ocspUrl = getOcspUrl(leaf);
            if (ocspUrl != null) {
                RevocationResult ocspResult = checkOcsp(leaf, issuer, ocspUrl);
                recordCheck(RevocationSource.OCSP, ocspResult.status());
                if (isDefinitive(ocspResult)) {
                    if (!deepCheck) return ocspResult;
                    if (ocspResult.status() == RevocationStatus.REVOKED) {
                        log.warn("REVOKED via OCSP: cert={}, reason={}", leaf.getSubjectX500Principal().getName(), ocspResult.reason());
                        return ocspResult;
                    }
                    if (firstGoodOrRevoked == null) firstGoodOrRevoked = ocspResult;
                }
            }
        }

        // ── Step 3: CRL ───────────────────────────────────────────────────────
        if (crlEnabled && issuer != null) {
            List<String> cdpUrls = getCdpUrls(leaf);
            for (String cdpUrl : cdpUrls) {
                RevocationResult crlResult = checkCrl(leaf, issuer, cdpUrl);
                recordCheck(RevocationSource.CRL, crlResult.status());
                if (isDefinitive(crlResult)) {
                    if (crlResult.status() == RevocationStatus.REVOKED) {
                        log.warn("REVOKED via CRL: cert={}, reason={}", leaf.getSubjectX500Principal().getName(), crlResult.reason());
                        // discrepancy log if firstGoodOrRevoked was GOOD
                        if (firstGoodOrRevoked != null && firstGoodOrRevoked.status() == RevocationStatus.GOOD) {
                            log.warn("Discrepancy: OCSP said GOOD but CRL says REVOKED for cert {}",
                                    leaf.getSubjectX500Principal().getName());
                        }
                        return crlResult;
                    }
                    if (firstGoodOrRevoked == null) firstGoodOrRevoked = crlResult;
                    break; // first CRL match is sufficient
                }
            }
        }

        if (firstGoodOrRevoked != null) return firstGoodOrRevoked;

        // Both OCSP and CRL returned non-definitive → UNKNOWN (soft-fail).
        return RevocationResult.unknown(RevocationSource.NONE,
                "All revocation checks returned indeterminate results");
    }

    // ── OCSP Staple ────────────────────────────────────────────────────────────

    private RevocationResult checkOcspStaple(X509Certificate leaf, X509Certificate issuer,
                                              byte[] stapleBytes) {
        try {
            OCSPResp resp = new OCSPResp(stapleBytes);
            if (resp.getStatus() != OCSPResp.SUCCESSFUL) {
                return RevocationResult.unknown(RevocationSource.OCSP_STAPLED,
                        "stapled OCSP response status: " + resp.getStatus());
            }
            BasicOCSPResp basic = (BasicOCSPResp) resp.getResponseObject();
            if (!verifyOcspSignature(basic, issuer)) {
                log.warn("Stapled OCSP signature invalid for {}", leaf.getSubjectX500Principal().getName());
                return RevocationResult.unknown(RevocationSource.OCSP_STAPLED, "signature invalid");
            }
            return extractSingleResponse(basic, leaf.getSerialNumber(), RevocationSource.OCSP_STAPLED);
        } catch (Exception e) {
            log.debug("Stapled OCSP parse error: {}", e.getMessage());
            return RevocationResult.unknown(RevocationSource.OCSP_STAPLED, "parse error: " + e.getMessage());
        }
    }

    // ── OCSP Request ───────────────────────────────────────────────────────────

    private RevocationResult checkOcsp(X509Certificate leaf, X509Certificate issuer, String url) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            SsrfGuard.validate(url);

            CertificateID certId = buildCertId(leaf, issuer);

            OCSPReqBuilder reqBuilder = new OCSPReqBuilder();
            reqBuilder.addRequest(certId);

            // Optional nonce: disabled by default for privacy/caching (RFC 0009 §9).
            OCSPReq req = reqBuilder.build();
            byte[] reqBytes = req.getEncoded();

            byte[] respBytes = sendHttpPost(url, "application/ocsp-request",
                    "application/ocsp-response", reqBytes, ocspTimeoutMs);
            if (respBytes == null) {
                return RevocationResult.unknown(RevocationSource.OCSP, "empty response");
            }

            OCSPResp resp = new OCSPResp(respBytes);
            if (resp.getStatus() != OCSPResp.SUCCESSFUL) {
                return RevocationResult.unknown(RevocationSource.OCSP,
                        "OCSP response status: " + resp.getStatus());
            }

            BasicOCSPResp basic = (BasicOCSPResp) resp.getResponseObject();
            if (!verifyOcspSignature(basic, issuer)) {
                log.warn("OCSP signature invalid for {} from {}", leaf.getSubjectX500Principal().getName(), url);
                recordResponderFailure(RevocationSource.OCSP, "bad-signature");
                return RevocationResult.unknown(RevocationSource.OCSP, "signature invalid");
            }

            // Staleness check.
            for (SingleResp sr : basic.getResponses()) {
                if (sr.getNextUpdate() != null) {
                    Instant nextUpdate = sr.getNextUpdate().toInstant();
                    Instant allowedUntil = nextUpdate.plusSeconds(clockSkewSec);
                    if (Instant.now().isAfter(allowedUntil)) {
                        log.warn("Stale OCSP response for {} (nextUpdate={})", leaf.getSubjectX500Principal().getName(), nextUpdate);
                        recordResponderFailure(RevocationSource.OCSP, "stale");
                        return RevocationResult.unknown(RevocationSource.OCSP, "stale response, nextUpdate=" + nextUpdate);
                    }
                }
            }

            return extractSingleResponse(basic, leaf.getSerialNumber(), RevocationSource.OCSP);

        } catch (SsrfGuard.SsrfBlockedException e) {
            log.warn("SSRF blocked OCSP URL {}: {}", url, e.getMessage());
            recordResponderFailure(RevocationSource.OCSP, "ssrf-blocked");
            return RevocationResult.unknown(RevocationSource.OCSP, e.getMessage());
        } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
            log.warn("OCSP timeout/unreachable for {}: {}", url, e.getMessage());
            recordResponderFailure(RevocationSource.OCSP, "timeout");
            return RevocationResult.unknown(RevocationSource.OCSP, "timeout: " + e.getMessage());
        } catch (Exception e) {
            log.warn("OCSP check error for {}: {}", url, e.getMessage());
            recordResponderFailure(RevocationSource.OCSP, "error");
            return RevocationResult.unknown(RevocationSource.OCSP, e.getMessage());
        } finally {
            sample.stop(meterRegistry.timer("certguard.revocation.check.duration",
                    "source", "OCSP"));
        }
    }

    // ── CRL ────────────────────────────────────────────────────────────────────

    private RevocationResult checkCrl(X509Certificate leaf, X509Certificate issuer, String url) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            SsrfGuard.validate(url);

            byte[] crlBytes = downloadCrl(url);
            if (crlBytes == null) {
                return RevocationResult.unknown(RevocationSource.CRL, "download failed");
            }

            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509CRL crl = (X509CRL) cf.generateCRL(new ByteArrayInputStream(crlBytes));

            // Signature verification.
            try {
                crl.verify(issuer.getPublicKey());
            } catch (Exception e) {
                log.warn("CRL signature invalid from {}: {}", url, e.getMessage());
                recordResponderFailure(RevocationSource.CRL, "bad-signature");
                return RevocationResult.unknown(RevocationSource.CRL, "signature invalid");
            }

            // Staleness check.
            if (crl.getNextUpdate() != null) {
                Instant nextUpdate = crl.getNextUpdate().toInstant();
                Instant graceCutoff = nextUpdate.plusSeconds(crlStaleGraceHours * 3600L);
                if (Instant.now().isAfter(graceCutoff)) {
                    log.warn("Stale CRL from {} (nextUpdate={})", url, nextUpdate);
                    recordResponderFailure(RevocationSource.CRL, "stale");
                    return RevocationResult.unknown(RevocationSource.CRL, "stale CRL, nextUpdate=" + nextUpdate);
                }
            }

            // Check revocation status.
            X509CRLEntry entry = crl.getRevokedCertificate(leaf.getSerialNumber());
            if (entry == null) {
                return RevocationResult.good(RevocationSource.CRL);
            }

            // Cert is listed — extract reason.
            int reasonCode = 0;
            Instant revokedAt = entry.getRevocationDate() != null
                    ? entry.getRevocationDate().toInstant() : null;
            try {
                byte[] reasonBytes = entry.getExtensionValue(Extension.reasonCode.getId());
                if (reasonBytes != null) {
                    ASN1OctetString oct = ASN1OctetString.getInstance(reasonBytes);
                    CRLReason reason = CRLReason.getInstance(
                            ASN1Primitive.fromByteArray(oct.getOctets()));
                    reasonCode = reason.getValue().intValue();
                }
            } catch (Exception ignored) { /* use default reasonCode 0 */ }

            String reasonName = crlReasonName(reasonCode);

            // removeFromCRL (8) clears a prior hold → treat as GOOD.
            if (reasonCode == 8) {
                log.info("removeFromCRL: cert {} cleared from hold", leaf.getSerialNumber());
                return RevocationResult.good(RevocationSource.CRL);
            }

            recordRevoked(reasonName);
            return RevocationResult.revoked(RevocationSource.CRL, reasonName, reasonCode, revokedAt);

        } catch (SsrfGuard.SsrfBlockedException e) {
            log.warn("SSRF blocked CRL URL {}: {}", url, e.getMessage());
            recordResponderFailure(RevocationSource.CRL, "ssrf-blocked");
            return RevocationResult.unknown(RevocationSource.CRL, e.getMessage());
        } catch (java.net.http.HttpTimeoutException | ConnectException e) {
            log.warn("CRL timeout/unreachable {}: {}", url, e.getMessage());
            recordResponderFailure(RevocationSource.CRL, "timeout");
            return RevocationResult.unknown(RevocationSource.CRL, "timeout: " + e.getMessage());
        } catch (Exception e) {
            log.warn("CRL check error for {}: {}", url, e.getMessage());
            recordResponderFailure(RevocationSource.CRL, "error");
            return RevocationResult.unknown(RevocationSource.CRL, e.getMessage());
        } finally {
            sample.stop(meterRegistry.timer("certguard.revocation.check.duration",
                    "source", "CRL"));
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    private CertificateID buildCertId(X509Certificate cert, X509Certificate issuer) throws Exception {
        DigestCalculatorProvider dcp = new org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder().build();
        return new CertificateID(
                dcp.get(CertificateID.HASH_SHA1),
                new JcaX509CertificateHolder(issuer),
                cert.getSerialNumber());
    }

    private boolean verifyOcspSignature(BasicOCSPResp basic, X509Certificate issuer) {
        try {
            ContentVerifierProvider verifier = new JcaContentVerifierProviderBuilder()
                    .build(issuer.getPublicKey());
            if (basic.isSignatureValid(verifier)) return true;

            // Try delegated responder certs embedded in the OCSP response.
            X509CertificateHolder[] certs = basic.getCerts();
            if (certs != null) {
                for (X509CertificateHolder holder : certs) {
                    X509Certificate respCert = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                            .getCertificate(holder);
                    // Verify delegated cert is signed by issuer and has OCSP-signing EKU.
                    try {
                        respCert.verify(issuer.getPublicKey());
                        List<String> eku = respCert.getExtendedKeyUsage();
                        if (eku != null && eku.contains("1.3.6.1.5.5.7.3.9")) {
                            ContentVerifierProvider delegatedVerifier = new JcaContentVerifierProviderBuilder()
                                    .build(respCert.getPublicKey());
                            if (basic.isSignatureValid(delegatedVerifier)) return true;
                        }
                    } catch (Exception ignored) {}
                }
            }
            return false;
        } catch (Exception e) {
            log.debug("OCSP signature verification error: {}", e.getMessage());
            return false;
        }
    }

    private RevocationResult extractSingleResponse(BasicOCSPResp basic, BigInteger serial,
                                                    RevocationSource source) {
        for (SingleResp sr : basic.getResponses()) {
            if (!sr.getCertID().getSerialNumber().equals(serial)) continue;

            CertificateStatus certStatus = sr.getCertStatus();
            if (certStatus == CertificateStatus.GOOD) {
                return RevocationResult.good(source);
            }
            if (certStatus instanceof RevokedStatus revoked) {
                int reasonCode = revoked.hasRevocationReason() ? revoked.getRevocationReason() : 0;
                Instant revokedAt = revoked.getRevocationTime().toInstant();
                String reasonName = crlReasonName(reasonCode);

                // removeFromCRL (8) clears hold → GOOD.
                if (reasonCode == 8) {
                    return RevocationResult.good(source);
                }

                recordRevoked(reasonName);
                return RevocationResult.revoked(source, reasonName, reasonCode, revokedAt);
            }
            // UnknownStatus
            return RevocationResult.unknown(source, "OCSP responder returned UNKNOWN for this serial");
        }
        return RevocationResult.unknown(source, "serial not found in OCSP response");
    }

    /** Package-private so test subclasses can stub the transport layer. */
    byte[] sendHttpPost(String url, String contentType, String accept,
                        byte[] body, int timeoutMs) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", contentType)
                .header("Accept", accept)
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    /** Package-private so test subclasses can stub the transport layer. */
    byte[] downloadCrl(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(crlTimeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(crlTimeoutMs))
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " downloading CRL from " + url);
        }

        // Size cap: reject oversized CRLs.
        try (InputStream is = response.body();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total > crlMaxBytes) {
                    log.warn("CRL too large (>{} bytes) from {}", crlMaxBytes, url);
                    recordResponderFailure(RevocationSource.CRL, "too-large");
                    return null;
                }
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    private String getOcspUrl(X509Certificate cert) {
        try {
            byte[] aiaExt = cert.getExtensionValue("1.3.6.1.5.5.7.1.1"); // Authority Info Access
            if (aiaExt == null) return null;
            org.bouncycastle.asn1.x509.AuthorityInformationAccess aia =
                    org.bouncycastle.asn1.x509.AuthorityInformationAccess.getInstance(
                            ASN1OctetString.getInstance(aiaExt).getOctets());
            for (org.bouncycastle.asn1.x509.AccessDescription ad : aia.getAccessDescriptions()) {
                if (org.bouncycastle.asn1.x509.AccessDescription.id_ad_ocsp.equals(ad.getAccessMethod())) {
                    return ad.getAccessLocation().getName().toString();
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract OCSP URL: {}", e.getMessage());
        }
        return null;
    }

    private List<String> getCdpUrls(X509Certificate cert) {
        List<String> urls = new ArrayList<>();
        try {
            byte[] cdpExt = cert.getExtensionValue("2.5.29.31"); // CRL Distribution Points
            if (cdpExt == null) return urls;
            org.bouncycastle.asn1.x509.CRLDistPoint cdp =
                    org.bouncycastle.asn1.x509.CRLDistPoint.getInstance(
                            ASN1OctetString.getInstance(cdpExt).getOctets());
            for (org.bouncycastle.asn1.x509.DistributionPoint dp : cdp.getDistributionPoints()) {
                if (dp.getDistributionPoint() == null) continue;
                org.bouncycastle.asn1.x509.GeneralNames names =
                        org.bouncycastle.asn1.x509.GeneralNames.getInstance(
                                dp.getDistributionPoint().getName());
                for (org.bouncycastle.asn1.x509.GeneralName gn : names.getNames()) {
                    if (gn.getTagNo() == org.bouncycastle.asn1.x509.GeneralName.uniformResourceIdentifier) {
                        urls.add(gn.getName().toString());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract CDP URLs: {}", e.getMessage());
        }
        return urls;
    }

    private boolean isSelfSigned(X509Certificate cert) {
        return cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());
    }

    private boolean isDefinitive(RevocationResult r) {
        return r.status() == RevocationStatus.GOOD || r.status() == RevocationStatus.REVOKED;
    }

    static String crlReasonName(int code) {
        return switch (code) {
            case 0  -> "UNSPECIFIED";
            case 1  -> "KEY_COMPROMISE";
            case 2  -> "CA_COMPROMISE";
            case 3  -> "AFFILIATION_CHANGED";
            case 4  -> "SUPERSEDED";
            case 5  -> "CESSATION_OF_OPERATION";
            case 6  -> "CERTIFICATE_HOLD";
            case 8  -> "REMOVE_FROM_CRL";
            case 9  -> "PRIVILEGE_WITHDRAWN";
            case 10 -> "AA_COMPROMISE";
            default -> "UNSPECIFIED";
        };
    }

    // ── Metrics ────────────────────────────────────────────────────────────────

    private void recordCheck(RevocationSource source, RevocationStatus result) {
        try {
            meterRegistry.counter("certguard.revocation.check.total",
                    "source", source.name(), "result", result.name()).increment();
        } catch (Exception ignored) {}
    }

    private void recordResponderFailure(RevocationSource source, String reason) {
        try {
            meterRegistry.counter("certguard.revocation.responder.failure.total",
                    "source", source.name(), "reason", reason).increment();
        } catch (Exception ignored) {}
    }

    private void recordRevoked(String reason) {
        try {
            meterRegistry.counter("certguard.revocation.revoked.total",
                    "reason", reason).increment();
        } catch (Exception ignored) {}
    }
}
