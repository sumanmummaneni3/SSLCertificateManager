package com.certguard.service.chain;

import com.certguard.enums.ChainValidationError;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ChainValidationService} (BE-4).
 *
 * <p>All certs are generated in-memory with BouncyCastle; no network required.
 */
class ChainValidationServiceTest {

    static KeyPair rootKp;
    static KeyPair leafKp;

    private final ChainValidationService service = new ChainValidationService(new SimpleMeterRegistry());

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        rootKp = gen.generateKeyPair();
        leafKp = gen.generateKeyPair();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static X509Certificate buildRootCA(KeyPair kp, String subject, Date from, Date to) throws Exception {
        X500Name name = new X500Name("CN=" + subject + ", O=Test");
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.ONE, from, to, name, kp.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static X509Certificate buildLeaf(KeyPair leafKp, KeyPair issuerKp,
                                              X509Certificate issuerCert,
                                              Date from, Date to) throws Exception {
        X500Name issuerName = new X500Name(issuerCert.getSubjectX500Principal().getName("RFC2253"));
        X500Name leafName   = new X500Name("CN=leaf.example.com, O=Test");
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuerName, BigInteger.TEN, from, to, leafName, leafKp.getPublic());
        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(issuerKp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Nested
    class NullOrEmptyChain {
        @Test
        void null_chain_returns_incomplete() {
            ChainValidationResult result = service.validate(null);
            assertThat(result.trusted()).isFalse();
            assertThat(result.error()).isEqualTo(ChainValidationError.INCOMPLETE_CHAIN);
        }

        @Test
        void empty_chain_returns_incomplete() {
            ChainValidationResult result = service.validate(new X509Certificate[0]);
            assertThat(result.trusted()).isFalse();
            assertThat(result.error()).isEqualTo(ChainValidationError.INCOMPLETE_CHAIN);
        }
    }

    @Nested
    class SelfSigned {
        @Test
        void self_signed_leaf_returns_self_signed_error() throws Exception {
            Date now = Date.from(Instant.now());
            Date future = Date.from(Instant.now().plus(365, ChronoUnit.DAYS));
            X509Certificate selfSigned = buildRootCA(rootKp, "test", now, future);

            ChainValidationResult result = service.validate(new X509Certificate[]{selfSigned});
            assertThat(result.trusted()).isFalse();
            assertThat(result.error()).isEqualTo(ChainValidationError.SELF_SIGNED);
            assertThat(result.isSelfSigned()).isTrue();
        }
    }

    @Nested
    class LeafOnly {
        @Test
        void leaf_without_chain_returns_incomplete() throws Exception {
            Date now = Date.from(Instant.now());
            Date future = Date.from(Instant.now().plus(365, ChronoUnit.DAYS));
            X509Certificate root = buildRootCA(rootKp, "root-ca", now, future);
            X509Certificate leaf = buildLeaf(leafKp, rootKp, root, now, future);

            ChainValidationResult result = service.validate(new X509Certificate[]{leaf});
            assertThat(result.trusted()).isFalse();
            assertThat(result.error()).isEqualTo(ChainValidationError.INCOMPLETE_CHAIN);
        }
    }

    @Nested
    class UntrustedAnchor {
        @Test
        void unknown_ca_returns_untrusted() throws Exception {
            Date now = Date.from(Instant.now());
            Date future = Date.from(Instant.now().plus(365, ChronoUnit.DAYS));
            // Create a root CA that isn't in any trust store
            X509Certificate unknownRoot = buildRootCA(rootKp, "unknown-ca", now, future);
            X509Certificate leaf = buildLeaf(leafKp, rootKp, unknownRoot, now, future);

            ChainValidationResult result = service.validate(new X509Certificate[]{leaf, unknownRoot});
            // Should fail — unknownRoot not in JDK trust store
            assertThat(result.trusted()).isFalse();
            assertThat(result.error()).isIn(
                    ChainValidationError.UNTRUSTED_ANCHOR,
                    ChainValidationError.CHAIN_ERROR);
        }
    }

    @Nested
    class ChainDepth {
        @Test
        void depth_matches_array_length() throws Exception {
            Date now = Date.from(Instant.now());
            Date future = Date.from(Instant.now().plus(365, ChronoUnit.DAYS));
            X509Certificate root = buildRootCA(rootKp, "root-ca", now, future);
            X509Certificate leaf = buildLeaf(leafKp, rootKp, root, now, future);

            // We force it to test the depth tracking — validate with self-signed root
            ChainValidationResult result = service.validate(new X509Certificate[]{root});
            // Root is self-signed → SELF_SIGNED error, depth == 1
            assertThat(result.chainDepth()).isEqualTo(1);

            // With two certs (leaf + root), depth should be 2 regardless of outcome
            ChainValidationResult result2 = service.validate(new X509Certificate[]{leaf, root});
            assertThat(result2.chainDepth()).isEqualTo(2);
        }
    }

    @Nested
    class FactoryMethods {
        @Test
        void trusted_factory_sets_fields() {
            ChainValidationResult r = ChainValidationResult.trusted(3);
            assertThat(r.trusted()).isTrue();
            assertThat(r.error()).isNull();
            assertThat(r.chainDepth()).isEqualTo(3);
        }

        @Test
        void failed_factory_with_detail() {
            ChainValidationResult r = ChainValidationResult.failed(
                    ChainValidationError.PATH_LEN_VIOLATION, "exceeded 2", 4);
            assertThat(r.trusted()).isFalse();
            assertThat(r.error()).isEqualTo(ChainValidationError.PATH_LEN_VIOLATION);
            assertThat(r.errorDetail()).isEqualTo("exceeded 2");
            assertThat(r.chainDepth()).isEqualTo(4);
        }

        @Test
        void failed_factory_without_detail() {
            ChainValidationResult r = ChainValidationResult.failed(
                    ChainValidationError.INCOMPLETE_CHAIN, 1);
            assertThat(r.trusted()).isFalse();
            assertThat(r.errorDetail()).isNull();
        }
    }
}
