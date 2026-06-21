package com.certguard.service.chain;

import com.certguard.enums.ChainValidationError;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.TrustManagerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.*;
import java.util.*;

/**
 * Validates the full certificate chain against a trust store and maps failures to
 * structured {@link ChainValidationError} codes (RFC 0009 §5.1).
 *
 * <p>Trust anchor source (§10.1):
 * <ul>
 *   <li>{@code app.chain.trust-store=SYSTEM} — JDK cacerts (default)</li>
 *   <li>path string — load that file as a KeyStore (JKS or PKCS12)</li>
 * </ul>
 *
 * <p>Public/private leniency is applied by the <em>caller</em> based on the target
 * type; this service is pure validator and does not make that call.
 */
@Service
public class ChainValidationService {

    private static final Logger log = LoggerFactory.getLogger(ChainValidationService.class);

    @Value("${app.chain.trust-store:SYSTEM}")
    private String trustStorePath;

    private final MeterRegistry meterRegistry;

    /** Lazily-initialised anchor set — rebuilt if trust-store config changes (rare). */
    private volatile Set<TrustAnchor> cachedAnchors;

    public ChainValidationService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Validates the supplied chain.
     *
     * @param chain full chain: leaf at [0], intermediates at [1..n-1], optional root at [n].
     *              Must contain at least one entry.
     * @return never null; {@link ChainValidationResult#trusted()} when the path validates.
     */
    public ChainValidationResult validate(X509Certificate[] chain) {
        if (chain == null || chain.length == 0) {
            return ChainValidationResult.failed(ChainValidationError.INCOMPLETE_CHAIN, 0);
        }

        X509Certificate leaf = chain[0];
        int depth = chain.length;

        // Self-signed check: subject == issuer.
        if (isSelfSigned(leaf)) {
            log.debug("Self-signed cert: {}", leaf.getSubjectX500Principal().getName());
            record("SELF_SIGNED");
            return ChainValidationResult.failed(ChainValidationError.SELF_SIGNED, depth);
        }

        if (depth == 1) {
            // Leaf only with no issuer chain provided.
            log.debug("Incomplete chain — leaf only, no intermediates");
            record("INCOMPLETE_CHAIN");
            return ChainValidationResult.failed(ChainValidationError.INCOMPLETE_CHAIN, depth);
        }

        try {
            Set<TrustAnchor> anchors = resolveAnchors();
            CertPathValidator validator = CertPathValidator.getInstance("PKIX");
            CertificateFactory cf = CertificateFactory.getInstance("X.509");

            // Build path: leaf + intermediates (exclude root if already in trust store).
            List<X509Certificate> pathCerts = new ArrayList<>(Arrays.asList(chain));
            CertPath certPath = cf.generateCertPath(pathCerts);

            PKIXParameters params = new PKIXParameters(anchors);
            params.setRevocationEnabled(false); // RevocationCheckService handles this
            params.setDate(new Date());

            validator.validate(certPath, params);

            log.debug("Chain validated OK for {}", leaf.getSubjectX500Principal().getName());
            record("TRUSTED");
            return ChainValidationResult.trusted(depth);

        } catch (CertPathValidatorException ex) {
            ChainValidationError error = mapValidatorException(ex);
            String detail = ex.getMessage();
            log.warn("Chain validation failed [{}] for {}: {}",
                    error, leaf.getSubjectX500Principal().getName(), detail);
            record(error.name());
            return ChainValidationResult.failed(error, detail, depth);

        } catch (Exception ex) {
            log.warn("Unexpected chain validation error for {}: {}",
                    leaf.getSubjectX500Principal().getName(), ex.getMessage(), ex);
            record("CHAIN_ERROR");
            return ChainValidationResult.failed(ChainValidationError.CHAIN_ERROR,
                    ex.getMessage(), depth);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private boolean isSelfSigned(X509Certificate cert) {
        return cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());
    }

    private ChainValidationError mapValidatorException(CertPathValidatorException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";

        // Use the BasicReason enum when available (JDK 7+).
        if (ex.getReason() != null) {
            CertPathValidatorException.BasicReason reason =
                    (ex.getReason() instanceof CertPathValidatorException.BasicReason)
                    ? (CertPathValidatorException.BasicReason) ex.getReason()
                    : null;
            if (reason != null) {
                return switch (reason) {
                    case EXPIRED                        -> ChainValidationError.EXPIRED_CHAIN_ELEMENT;
                    case NOT_YET_VALID                  -> ChainValidationError.EXPIRED_CHAIN_ELEMENT;
                    case INVALID_SIGNATURE              -> ChainValidationError.SIGNATURE_INVALID;
                    case ALGORITHM_CONSTRAINED          -> ChainValidationError.WEAK_ALGORITHM;
                    case UNDETERMINED_REVOCATION_STATUS,
                         REVOKED                       -> ChainValidationError.CHAIN_ERROR;
                    default                             -> ChainValidationError.CHAIN_ERROR;
                };
            }
        }

        // Fallback: string matching on the message.
        if (msg.contains("path len") || msg.contains("pathlength") || msg.contains("path length"))
            return ChainValidationError.PATH_LEN_VIOLATION;
        if (msg.contains("name constraint") || msg.contains("nameconstraint"))
            return ChainValidationError.NAME_CONSTRAINT_VIOLATION;
        if (msg.contains("basic constraint") || msg.contains("basicconstraint"))
            return ChainValidationError.BASIC_CONSTRAINT_VIOLATION;
        if (msg.contains("signature") || msg.contains("invalid key"))
            return ChainValidationError.SIGNATURE_INVALID;
        if (msg.contains("algorithm") || msg.contains("disabled"))
            return ChainValidationError.WEAK_ALGORITHM;
        if (msg.contains("expir") || msg.contains("not valid"))
            return ChainValidationError.EXPIRED_CHAIN_ELEMENT;
        if (msg.contains("trust") || msg.contains("anchor") || msg.contains("untrusted"))
            return ChainValidationError.UNTRUSTED_ANCHOR;

        return ChainValidationError.CHAIN_ERROR;
    }

    private Set<TrustAnchor> resolveAnchors() throws Exception {
        if (cachedAnchors != null) return cachedAnchors;
        synchronized (this) {
            if (cachedAnchors != null) return cachedAnchors;
            cachedAnchors = loadAnchors();
            return cachedAnchors;
        }
    }

    private Set<TrustAnchor> loadAnchors() throws Exception {
        KeyStore ks;
        if ("SYSTEM".equalsIgnoreCase(trustStorePath)) {
            // JDK cacerts
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            javax.net.ssl.X509TrustManager tm = null;
            for (javax.net.ssl.TrustManager m : tmf.getTrustManagers()) {
                if (m instanceof javax.net.ssl.X509TrustManager xtm) { tm = xtm; break; }
            }
            if (tm == null) throw new IllegalStateException("No X509TrustManager in JDK cacerts");
            Set<TrustAnchor> anchors = new HashSet<>();
            for (X509Certificate ca : tm.getAcceptedIssuers()) {
                anchors.add(new TrustAnchor(ca, null));
            }
            log.info("Loaded {} trust anchors from JDK cacerts", anchors.size());
            return anchors;
        } else {
            // Custom trust store path
            ks = KeyStore.getInstance(KeyStore.getDefaultType());
            try (InputStream is = Files.newInputStream(Paths.get(trustStorePath))) {
                ks.load(is, null);
            }
            Set<TrustAnchor> anchors = new HashSet<>();
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (ks.isCertificateEntry(alias)) {
                    X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
                    anchors.add(new TrustAnchor(cert, null));
                }
            }
            log.info("Loaded {} trust anchors from custom trust store: {}", anchors.size(), trustStorePath);
            return anchors;
        }
    }

    private void record(String result) {
        try {
            meterRegistry.counter("certguard.chain.validation.total",
                    "result", result).increment();
        } catch (Exception ignored) { /* metrics never crash the main path */ }
    }
}
