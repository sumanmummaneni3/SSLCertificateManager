package com.certguard.service;

import com.certguard.entity.Agent;
import com.certguard.entity.CertificateRecord;
import com.certguard.entity.Target;
import com.certguard.enums.CertStatus;
import com.certguard.enums.ScanSourceType;
import com.certguard.repository.CertificateRecordRepository;
import com.certguard.service.chain.ChainValidationResult;
import com.certguard.service.chain.ChainValidationService;
import com.certguard.service.revocation.RevocationCheckService;
import com.certguard.service.revocation.RevocationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Single shared persistence path for scanned certificate data (RFC 0013 §8).
 *
 * <p>Replaces duplicated upsert+chain+revocation+status+notify logic that previously
 * existed in both {@code AgentService.processFull/processDelta} and
 * {@code SslScannerService.persistCertificates}. All scan result sinks funnel
 * through this bean:
 * <ul>
 *   <li>{@link AgentService#submitResult} (FULL + DELTA agent paths)
 *   <li>{@link SslScannerService#persistCertificates} (direct/fallback path, retained
 *       in DIRECT and HYBRID modes)
 * </ul>
 *
 * <p>Calling this service from a Spring-managed caller fixes the {@code @Transactional}
 * self-invocation bug in {@code SslScannerService}: {@code scheduledPublicScan} previously
 * called {@code scanSingleTarget} on the same bean instance, bypassing the proxy so
 * {@code @Transactional} was never applied. Moving persistence here means any
 * {@code @Transactional} on this bean's public methods is always respected
 * (RFC 0013 §4 / §8).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CertificatePersistenceService {

    private final CertificateRecordRepository certRepository;
    private final ChainValidationService chainValidationService;
    private final RevocationCheckService revocationCheckService;
    private final ExpiryEvaluationService expiryEvaluationService;

    @Value("${app.revocation.shadow:true}")
    private boolean revocationShadowMode;

    // ── FULL result ───────────────────────────────────────────────────────────

    /**
     * Upserts a CertificateRecord from a FULL scan result.
     *
     * <p>Steps:
     * <ol>
     *   <li>Upsert the record (find by serial+target or create new).
     *   <li>Set all leaf fields.
     *   <li>Build the chain and run chain validation + revocation.
     *   <li>Apply shadow-mode status logic.
     *   <li>Save and trigger the RFC 0008 evaluateAndNotify AFTER_COMMIT hook.
     * </ol>
     *
     * @param target               the certificate's target
     * @param scannedByAgent       the agent that submitted the result (null for direct/fallback path)
     * @param serialNumber         hex serial
     * @param commonName           leaf CN
     * @param issuer               leaf issuer DN
     * @param notBefore            leaf notBefore
     * @param notAfter             leaf notAfter (expiry)
     * @param keyAlgorithm         leaf key algorithm
     * @param keySize              leaf key size in bits
     * @param signatureAlgorithm   leaf signature algorithm
     * @param subjectAltNames      leaf SANs
     * @param chainDepth           total chain length
     * @param publicCertB64        base64 DER of the leaf
     * @param chainB64             ordered base64 DER list (leaf + intermediates); may be null
     * @param ocspStapleBytes      raw OCSP staple bytes; null if none
     * @param evalMode             SCHEDULED or FORCE (for RFC 0008 notification logic)
     * @param previousLastScannedAt prior lastScannedAt of target (force-scan debounce)
     * @param scanSourceType       persisted provenance for the scanSource API field
     *                             (RFC 0013 §9); never null for post-V42 call sites —
     *                             callers always know whether they are the cloud
     *                             scanner or a customer agent.
     */
    @Transactional
    public void persistFull(Target target,
                            Agent scannedByAgent,
                            String serialNumber,
                            String commonName,
                            String issuer,
                            Instant notBefore,
                            Instant notAfter,
                            String keyAlgorithm,
                            Integer keySize,
                            String signatureAlgorithm,
                            List<String> subjectAltNames,
                            Integer chainDepth,
                            String publicCertB64,
                            List<String> chainB64,
                            byte[] ocspStapleBytes,
                            ExpiryEvaluationService.EvaluationMode evalMode,
                            Instant previousLastScannedAt,
                            ScanSourceType scanSourceType) {

        CertificateRecord record = certRepository
                .findByTargetIdAndSerialNumber(target.getId(), serialNumber)
                .orElse(CertificateRecord.builder()
                        .target(target)
                        .orgId(target.getOrganization().getId())
                        .serialNumber(serialNumber)
                        .build());

        record.setCommonName(commonName);
        record.setIssuer(issuer);
        record.setNotBefore(notBefore);
        record.setExpiryDate(notAfter);
        record.setKeyAlgorithm(keyAlgorithm);
        record.setKeySize(keySize);
        record.setSignatureAlgorithm(signatureAlgorithm);
        record.setSubjectAltNames(subjectAltNames);
        record.setChainDepth(chainDepth);
        record.setPublicCertB64(publicCertB64);
        record.setScannedByAgent(scannedByAgent);
        record.setScannedAt(Instant.now());
        record.setScanSourceType(scanSourceType);

        // Chain validation + revocation.
        X509Certificate[] chain = buildChain(chainB64, publicCertB64);
        ChainValidationResult chainResult = chainValidationService.validate(chain);
        RevocationResult revResult = revocationCheckService.check(
                chain, ocspStapleBytes, record.isRevocationDeepCheck());

        applyChainAndRevocation(record, chainResult, revResult);
        applyStatus(record, notAfter, revResult, chainResult, target);

        certRepository.save(record);

        expiryEvaluationService.evaluateAndNotify(record, evalMode, previousLastScannedAt);

        log.info("CertificatePersistenceService FULL — CN: {}, expires: {}, status: {}, revocation: {}/{}",
                commonName, notAfter, record.getStatus(), revResult.status(), revResult.source());
    }

    // ── DELTA result ──────────────────────────────────────────────────────────

    /**
     * Updates an existing CertificateRecord from a DELTA scan result.
     *
     * <p>DELTA only updates the expiry and re-runs revocation against the stored leaf
     * (RFC 0009 §3.2 — DELTA cannot observe between-scan revocations otherwise).
     * Chain validation is not repeated; the stored chain_trusted result is reused.
     *
     * @param target               the certificate's target
     * @param certificateId        UUID of the existing CertificateRecord
     * @param newNotAfter          updated expiry date
     * @param evalMode             SCHEDULED or FORCE
     * @param previousLastScannedAt prior lastScannedAt
     * @param scanSourceType       persisted provenance for the scanSource API field
     *                             (RFC 0013 §9); reflects the actor of THIS delta
     *                             completion (may differ from the original FULL scan).
     * @throws com.certguard.exception.ResourceNotFoundException if the record is not found
     */
    @Transactional
    public void persistDelta(Target target,
                             java.util.UUID certificateId,
                             Instant newNotAfter,
                             ExpiryEvaluationService.EvaluationMode evalMode,
                             Instant previousLastScannedAt,
                             ScanSourceType scanSourceType) {

        CertificateRecord existing = certRepository.findById(certificateId)
                .orElseThrow(() -> new com.certguard.exception.ResourceNotFoundException(
                        "Certificate record not found: " + certificateId));

        existing.setExpiryDate(newNotAfter);
        existing.setScannedAt(Instant.now());
        existing.setScanSourceType(scanSourceType);

        // RFC 0009: DELTA — run revocation on the STORED leaf, not a re-sent chain.
        X509Certificate[] chain = buildChain(null, existing.getPublicCertB64());
        RevocationResult revResult = revocationCheckService.check(
                chain, null, existing.isRevocationDeepCheck());

        // Update only revocation fields (chain trust result unchanged).
        existing.setRevocationStatus(revResult.status());
        existing.setRevocationSource(revResult.source());
        existing.setRevocationCheckedAt(revResult.checkedAt());
        existing.setRevocationReason(revResult.reason());
        existing.setRevocationReasonCode(revResult.reasonCode());
        existing.setRevokedAt(revResult.revokedAt());

        // Reconstruct stored chain result for status derivation.
        ChainValidationResult storedChain = Boolean.FALSE.equals(existing.getChainTrusted())
                ? ChainValidationResult.failed(
                        existing.getChainValidationError(),
                        existing.getChainDepth() != null ? existing.getChainDepth() : 1)
                : ChainValidationResult.trusted(
                        existing.getChainDepth() != null ? existing.getChainDepth() : 1);

        applyStatus(existing, newNotAfter, revResult, storedChain, target);

        certRepository.save(existing);

        expiryEvaluationService.evaluateAndNotify(existing, evalMode, previousLastScannedAt);

        log.info("CertificatePersistenceService DELTA — cert: {}, expires: {}, status: {}",
                certificateId, newNotAfter, existing.getStatus());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applyChainAndRevocation(CertificateRecord record,
                                         ChainValidationResult chainResult,
                                         RevocationResult revResult) {
        record.setChainTrusted(chainResult.trusted());
        record.setChainValidationError(chainResult.error());
        record.setRevocationStatus(revResult.status());
        record.setRevocationSource(revResult.source());
        record.setRevocationCheckedAt(revResult.checkedAt());
        record.setRevocationReason(revResult.reason());
        record.setRevocationReasonCode(revResult.reasonCode());
        record.setRevokedAt(revResult.revokedAt());
    }

    private void applyStatus(CertificateRecord record,
                              Instant notAfter,
                              RevocationResult revResult,
                              ChainValidationResult chainResult,
                              Target target) {
        CertStatus newStatus = expiryEvaluationService.determineCertStatus(
                notAfter, revResult, chainResult, target, target.getOrganization().getId());

        if (revocationShadowMode) {
            CertStatus shadowStatus = expiryEvaluationService.determineCertStatus(
                    notAfter, null, null, target, target.getOrganization().getId());
            record.setStatus(shadowStatus);
            if (newStatus == CertStatus.REVOKED || newStatus == CertStatus.INVALID) {
                log.info("[SHADOW] CertificatePersistenceService: would set status={} for cert {} but shadow=true",
                        newStatus, record.getCommonName());
            }
        } else {
            record.setStatus(newStatus);
        }
    }

    /**
     * Builds an X509Certificate[] from base64-encoded DER strings (RFC 0009 §3.4).
     *
     * <p>If {@code chainB64} is non-empty, use it (leaf first, then intermediates).
     * Otherwise decode the single {@code leafB64}.
     */
    private X509Certificate[] buildChain(List<String> chainB64, String leafB64) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            if (chainB64 != null && !chainB64.isEmpty()) {
                X509Certificate[] chain = new X509Certificate[chainB64.size()];
                for (int i = 0; i < chainB64.size(); i++) {
                    byte[] der = Base64.getDecoder().decode(chainB64.get(i));
                    chain[i] = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
                }
                return chain;
            }
            if (leafB64 != null && !leafB64.isBlank()) {
                byte[] der = Base64.getDecoder().decode(leafB64);
                X509Certificate leaf = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
                return new X509Certificate[]{ leaf };
            }
        } catch (Exception e) {
            log.warn("CertificatePersistenceService: could not build chain from B64: {}", e.getMessage());
        }
        return new X509Certificate[0];
    }
}
