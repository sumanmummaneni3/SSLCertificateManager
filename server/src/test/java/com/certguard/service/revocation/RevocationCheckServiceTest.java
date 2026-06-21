package com.certguard.service.revocation;

import com.certguard.enums.RevocationSource;
import com.certguard.enums.RevocationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.ocsp.*;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RevocationCheckService} — BE-5 acceptance criteria.
 *
 * <h2>Transport isolation</h2>
 * {@link StubTransportRevocationService} overrides the package-private
 * {@code sendHttpPost} and {@code downloadCrl} so no live HTTP calls are made.
 * BouncyCastle fixtures supply DER-encoded OCSP responses and CRLs in-memory.
 *
 * <h2>SSRF guard</h2>
 * Fixture URLs use {@code *.certguard-test.example}: not on the blocked-by-name
 * list, DNS resolution fails (UnknownHostException), which SsrfGuard treats as
 * connectivity error and lets through. The stub intercepts before any real TCP
 * connection is made.
 *
 * <h2>Soft-fail invariant</h2>
 * Only a signature-verified, non-stale, definitive REVOKED response sets
 * {@link RevocationStatus#REVOKED}. Every other failure path → UNKNOWN.
 */
class RevocationCheckServiceTest {

    // ── Shared key material ─────────────────────────────────────────────────────

    static KeyPair issuerKp;
    static KeyPair leafKp;
    static KeyPair rogueKp; // for "wrong signer" scenarios

    static X509Certificate issuerCert;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        issuerKp   = gen.generateKeyPair();
        leafKp     = gen.generateKeyPair();
        rogueKp    = gen.generateKeyPair();
        issuerCert = Fixtures.buildSelfSignedCA(issuerKp, "CN=Test CA, O=Test");
    }

    // ── Service factory ─────────────────────────────────────────────────────────

    private StubTransportRevocationService serviceWith(
            byte[] ocspResponse, byte[] crlBytes) {
        StubTransportRevocationService svc =
                new StubTransportRevocationService(new SimpleMeterRegistry(),
                        ocspResponse, crlBytes);
        // Enable everything; shadow off (irrelevant here — check() just returns result)
        ReflectionTestUtils.setField(svc, "revocationEnabled", true);
        ReflectionTestUtils.setField(svc, "ocspEnabled",       true);
        ReflectionTestUtils.setField(svc, "crlEnabled",        true);
        ReflectionTestUtils.setField(svc, "ocspTimeoutMs",     4000);
        ReflectionTestUtils.setField(svc, "crlTimeoutMs",      8000);
        ReflectionTestUtils.setField(svc, "crlMaxBytes",       5_242_880L);
        ReflectionTestUtils.setField(svc, "crlStaleGraceHours", 0);
        ReflectionTestUtils.setField(svc, "clockSkewSec",      300);
        return svc;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // OCSP Staple Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    class OcspStaple {

        @Test
        void stapled_good_returns_GOOD() throws Exception {
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert, BigInteger.ONE,
                    Fixtures.ocspUrl(), null);
            byte[] staple = Fixtures.buildOcspResponse(issuerKp, issuerCert, leaf.getSerialNumber(),
                    CertificateStatus.GOOD);

            // Leaf has no AIA/CDP — only the staple is checked.
            X509Certificate leafNoExt = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    BigInteger.ONE, null, null);

            RevocationResult result = serviceWith(null, null)
                    .check(new X509Certificate[]{leafNoExt, issuerCert}, staple, false);

            assertThat(result.status()).isEqualTo(RevocationStatus.GOOD);
            assertThat(result.source()).isEqualTo(RevocationSource.OCSP_STAPLED);
        }

        @Test
        void stapled_revoked_keyCompromise_returns_REVOKED() throws Exception {
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    BigInteger.TWO, null, null);
            RevokedStatus revokedStatus = new RevokedStatus(
                    Date.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                    CRLReason.keyCompromise);   // reason code 1
            byte[] staple = Fixtures.buildOcspResponse(issuerKp, issuerCert, leaf.getSerialNumber(),
                    revokedStatus);

            RevocationResult result = serviceWith(null, null)
                    .check(new X509Certificate[]{leaf, issuerCert}, staple, false);

            assertThat(result.status()).isEqualTo(RevocationStatus.REVOKED);
            assertThat(result.source()).isEqualTo(RevocationSource.OCSP_STAPLED);
            assertThat(result.reasonCode()).isEqualTo(1);
            assertThat(result.reason()).isEqualTo("KEY_COMPROMISE");
            assertThat(result.revokedAt()).isNotNull();
            assertThat(result.isOnHold()).isFalse();
        }

        @Test
        void stapled_invalid_signature_returns_UNKNOWN_not_REVOKED() throws Exception {
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    BigInteger.valueOf(3), null, null);
            // Sign with rogue key — signature won't verify against issuerCert
            RevokedStatus revokedStatus = new RevokedStatus(new Date(), CRLReason.keyCompromise);
            byte[] staple = Fixtures.buildOcspResponse(rogueKp, issuerCert, leaf.getSerialNumber(),
                    revokedStatus);

            RevocationResult result = serviceWith(null, null)
                    .check(new X509Certificate[]{leaf, issuerCert}, staple, false);

            // Signature invalid → soft-fail → UNKNOWN, never REVOKED.
            // Source is NONE because the indeterminate result is non-definitive and
            // the final catch-all return uses RevocationSource.NONE.
            assertThat(result.status()).isEqualTo(RevocationStatus.UNKNOWN);
            assertThat(result.status()).isNotEqualTo(RevocationStatus.REVOKED);
        }

        @Test
        void stapled_certificateHold_code6_returns_REVOKED_and_onHold() throws Exception {
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    BigInteger.valueOf(4), null, null);
            RevokedStatus holdStatus = new RevokedStatus(new Date(), CRLReason.certificateHold);
            byte[] staple = Fixtures.buildOcspResponse(issuerKp, issuerCert, leaf.getSerialNumber(),
                    holdStatus);

            RevocationResult result = serviceWith(null, null)
                    .check(new X509Certificate[]{leaf, issuerCert}, staple, false);

            assertThat(result.status()).isEqualTo(RevocationStatus.REVOKED);
            assertThat(result.reasonCode()).isEqualTo(6);
            assertThat(result.reason()).isEqualTo("CERTIFICATE_HOLD");
            assertThat(result.isOnHold()).isTrue();
        }

        @Test
        void stapled_removeFromCRL_code8_clears_hold_returns_GOOD() throws Exception {
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    BigInteger.valueOf(5), null, null);
            // removeFromCRL (8) means hold was cleared → treat as GOOD
            RevokedStatus removeStatus = new RevokedStatus(new Date(), CRLReason.removeFromCRL);
            byte[] staple = Fixtures.buildOcspResponse(issuerKp, issuerCert, leaf.getSerialNumber(),
                    removeStatus);

            RevocationResult result = serviceWith(null, null)
                    .check(new X509Certificate[]{leaf, issuerCert}, staple, false);

            assertThat(result.status()).isEqualTo(RevocationStatus.GOOD);
        }

    }

    // ═══════════════════════════════════════════════════════════════════════════
    // OCSP Request Tests (via stub HTTP transport)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    class OcspRequest {

        /**
         * Stale OCSP: {@code nextUpdate} well in the past (beyond clockSkewSec grace).
         *
         * <p>Staleness is checked on the live-OCSP-request path (step 2), after
         * signature verification. A stale response must not be trusted → UNKNOWN.
         * (TLS staples have their freshness enforced at the TLS handshake level;
         * the staple path in this service does not re-check staleness.)
         */
        @Test
        void stale_ocsp_nextUpdate_in_past_returns_UNKNOWN() throws Exception {
            // Leaf has AIA URL → live OCSP request (step 2) runs.
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    BigInteger.valueOf(6), Fixtures.ocspUrl(), null);
            // nextUpdate 24 h ago; clockSkewSec=300 → allowedUntil = now − 23h 55m → still past.
            byte[] staleOcsp = Fixtures.buildOcspResponseWithDates(issuerKp, issuerCert,
                    leaf.getSerialNumber(), CertificateStatus.GOOD,
                    Date.from(Instant.now().minus(48, ChronoUnit.HOURS)),  // thisUpdate: 48h ago
                    Date.from(Instant.now().minus(24, ChronoUnit.HOURS))); // nextUpdate: 24h ago

            // No staple (null) → only step 2 (live OCSP) runs; staleness check fires.
            // After stale OCSP returns UNKNOWN (non-definitive) with no CRL fallback,
            // the catch-all return uses RevocationSource.NONE.
            RevocationResult result = serviceWith(staleOcsp, null)
                    .check(new X509Certificate[]{leaf, issuerCert}, null, false);

            assertThat(result.status()).isEqualTo(RevocationStatus.UNKNOWN);
        }

        @Test
        void ocsp_request_good_returns_GOOD() throws Exception {
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    BigInteger.valueOf(10), Fixtures.ocspUrl(), null);
            byte[] ocspResp = Fixtures.buildOcspResponse(issuerKp, issuerCert,
                    leaf.getSerialNumber(), CertificateStatus.GOOD);

            RevocationResult result = serviceWith(ocspResp, null)
                    .check(new X509Certificate[]{leaf, issuerCert}, null, false);

            assertThat(result.status()).isEqualTo(RevocationStatus.GOOD);
            assertThat(result.source()).isEqualTo(RevocationSource.OCSP);
        }

        @Test
        void ocsp_request_revoked_returns_REVOKED_with_reason() throws Exception {
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    BigInteger.valueOf(11), Fixtures.ocspUrl(), null);
            RevokedStatus revoked = new RevokedStatus(
                    Date.from(Instant.now().minus(2, ChronoUnit.HOURS)),
                    CRLReason.cACompromise);  // reason code 2 (note: cACompromise in BC)
            byte[] ocspResp = Fixtures.buildOcspResponse(issuerKp, issuerCert,
                    leaf.getSerialNumber(), revoked);

            RevocationResult result = serviceWith(ocspResp, null)
                    .check(new X509Certificate[]{leaf, issuerCert}, null, false);

            assertThat(result.status()).isEqualTo(RevocationStatus.REVOKED);
            assertThat(result.source()).isEqualTo(RevocationSource.OCSP);
            assertThat(result.reasonCode()).isEqualTo(2);
            assertThat(result.reason()).isEqualTo("CA_COMPROMISE");
        }

        @Test
        void ocsp_unknown_status_returns_UNKNOWN_not_REVOKED() throws Exception {
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    BigInteger.valueOf(12), Fixtures.ocspUrl(), null);
            // UnknownStatus — responder doesn't know this serial
            byte[] ocspResp = Fixtures.buildOcspResponse(issuerKp, issuerCert,
                    leaf.getSerialNumber(), new UnknownStatus());

            RevocationResult result = serviceWith(ocspResp, null)
                    .check(new X509Certificate[]{leaf, issuerCert}, null, false);

            assertThat(result.status()).isEqualTo(RevocationStatus.UNKNOWN);
        }

        @Test
        void ocsp_invalid_signature_returns_UNKNOWN_not_REVOKED() throws Exception {
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    BigInteger.valueOf(13), Fixtures.ocspUrl(), null);
            // Rogue signer — sig won't verify against issuerCert
            RevokedStatus revoked = new RevokedStatus(new Date(), CRLReason.keyCompromise);
            byte[] ocspResp = Fixtures.buildOcspResponse(rogueKp, issuerCert,
                    leaf.getSerialNumber(), revoked);

            RevocationResult result = serviceWith(ocspResp, null)
                    .check(new X509Certificate[]{leaf, issuerCert}, null, false);

            // Must NOT trust an unverified response claiming REVOKED
            assertThat(result.status()).isEqualTo(RevocationStatus.UNKNOWN);
            assertThat(result.status()).isNotEqualTo(RevocationStatus.REVOKED);
        }

        @Test
        void ocsp_responder_unreachable_falls_through_to_crl() throws Exception {
            // Leaf has both AIA (OCSP) and CDP (CRL); stub throws for OCSP, returns good CRL
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    BigInteger.valueOf(14), Fixtures.ocspUrl(), Fixtures.crlUrl());
            byte[] goodCrl = Fixtures.buildCrl(issuerKp, issuerCert, Collections.emptyList());

            StubTransportRevocationService svc = new StubTransportRevocationService(
                    new SimpleMeterRegistry(), null, goodCrl) {
                @Override
                byte[] sendHttpPost(String url, String contentType, String accept,
                                    byte[] body, int timeoutMs) throws Exception {
                    throw new java.net.ConnectException("simulated: OCSP responder unreachable");
                }
            };
            applyDefaults(svc);

            RevocationResult result = svc.check(new X509Certificate[]{leaf, issuerCert}, null, false);

            // OCSP failed → fell through to CRL → GOOD
            assertThat(result.status()).isEqualTo(RevocationStatus.GOOD);
            assertThat(result.source()).isEqualTo(RevocationSource.CRL);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CRL Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    class CrlChecks {

        @Test
        void crl_leaf_not_listed_returns_GOOD() throws Exception {
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    BigInteger.valueOf(20), null, Fixtures.crlUrl());
            // Empty CRL — leaf serial not listed
            byte[] crl = Fixtures.buildCrl(issuerKp, issuerCert, Collections.emptyList());

            RevocationResult result = serviceWith(null, crl)
                    .check(new X509Certificate[]{leaf, issuerCert}, null, false);

            assertThat(result.status()).isEqualTo(RevocationStatus.GOOD);
            assertThat(result.source()).isEqualTo(RevocationSource.CRL);
        }

        @Test
        void crl_leaf_listed_keyCompromise_returns_REVOKED() throws Exception {
            BigInteger serial = BigInteger.valueOf(21);
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    serial, null, Fixtures.crlUrl());
            byte[] crl = Fixtures.buildCrl(issuerKp, issuerCert,
                    List.of(new Fixtures.RevokedEntry(serial, CRLReason.keyCompromise,
                            Date.from(Instant.now().minus(1, ChronoUnit.HOURS)))));

            RevocationResult result = serviceWith(null, crl)
                    .check(new X509Certificate[]{leaf, issuerCert}, null, false);

            assertThat(result.status()).isEqualTo(RevocationStatus.REVOKED);
            assertThat(result.source()).isEqualTo(RevocationSource.CRL);
            assertThat(result.reasonCode()).isEqualTo(1); // keyCompromise = 1
            assertThat(result.reason()).isEqualTo("KEY_COMPROMISE");
        }

        @Test
        void crl_certificateHold_code6_returns_REVOKED_and_onHold() throws Exception {
            BigInteger serial = BigInteger.valueOf(22);
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    serial, null, Fixtures.crlUrl());
            byte[] crl = Fixtures.buildCrl(issuerKp, issuerCert,
                    List.of(new Fixtures.RevokedEntry(serial, CRLReason.certificateHold,
                            new Date())));

            RevocationResult result = serviceWith(null, crl)
                    .check(new X509Certificate[]{leaf, issuerCert}, null, false);

            assertThat(result.status()).isEqualTo(RevocationStatus.REVOKED);
            assertThat(result.reasonCode()).isEqualTo(6);
            assertThat(result.isOnHold()).isTrue();
        }

        @Test
        void crl_removeFromCRL_code8_clears_hold_returns_GOOD() throws Exception {
            // A cert previously on hold is now removed → removeFromCRL (8) → GOOD
            BigInteger serial = BigInteger.valueOf(23);
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    serial, null, Fixtures.crlUrl());
            byte[] crl = Fixtures.buildCrl(issuerKp, issuerCert,
                    List.of(new Fixtures.RevokedEntry(serial, CRLReason.removeFromCRL,
                            new Date())));

            RevocationResult result = serviceWith(null, crl)
                    .check(new X509Certificate[]{leaf, issuerCert}, null, false);

            // removeFromCRL means hold cleared → GOOD (reversibility path)
            assertThat(result.status()).isEqualTo(RevocationStatus.GOOD);
        }

        @Test
        void crl_bad_signature_returns_UNKNOWN_not_REVOKED() throws Exception {
            BigInteger serial = BigInteger.valueOf(24);
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    serial, null, Fixtures.crlUrl());
            // CRL signed by rogue key — signature invalid
            byte[] crl = Fixtures.buildCrl(rogueKp, issuerCert,
                    List.of(new Fixtures.RevokedEntry(serial, CRLReason.keyCompromise, new Date())));

            RevocationResult result = serviceWith(null, crl)
                    .check(new X509Certificate[]{leaf, issuerCert}, null, false);

            // Must NOT trust unverified CRL claiming REVOKED
            assertThat(result.status()).isEqualTo(RevocationStatus.UNKNOWN);
        }

        @Test
        void crl_unreachable_returns_UNKNOWN() throws Exception {
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    BigInteger.valueOf(25), null, Fixtures.crlUrl());

            StubTransportRevocationService svc = new StubTransportRevocationService(
                    new SimpleMeterRegistry(), null, null) {
                @Override
                byte[] downloadCrl(String url) throws Exception {
                    throw new java.net.ConnectException("simulated: CRL server unreachable");
                }
            };
            applyDefaults(svc);

            RevocationResult result = svc.check(new X509Certificate[]{leaf, issuerCert}, null, false);

            assertThat(result.status()).isEqualTo(RevocationStatus.UNKNOWN);
        }

        @Test
        void crl_parse_failure_returns_UNKNOWN() throws Exception {
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    BigInteger.valueOf(26), null, Fixtures.crlUrl());
            // Garbage bytes — CertificateFactory.generateCRL will throw
            byte[] garbage = "not a real CRL".getBytes();

            RevocationResult result = serviceWith(null, garbage)
                    .check(new X509Certificate[]{leaf, issuerCert}, null, false);

            assertThat(result.status()).isEqualTo(RevocationStatus.UNKNOWN);
        }

        @Test
        void crl_reason_name_mapping() {
            assertThat(RevocationCheckService.crlReasonName(0)).isEqualTo("UNSPECIFIED");
            assertThat(RevocationCheckService.crlReasonName(1)).isEqualTo("KEY_COMPROMISE");
            assertThat(RevocationCheckService.crlReasonName(2)).isEqualTo("CA_COMPROMISE");
            assertThat(RevocationCheckService.crlReasonName(3)).isEqualTo("AFFILIATION_CHANGED");
            assertThat(RevocationCheckService.crlReasonName(4)).isEqualTo("SUPERSEDED");
            assertThat(RevocationCheckService.crlReasonName(5)).isEqualTo("CESSATION_OF_OPERATION");
            assertThat(RevocationCheckService.crlReasonName(6)).isEqualTo("CERTIFICATE_HOLD");
            assertThat(RevocationCheckService.crlReasonName(8)).isEqualTo("REMOVE_FROM_CRL");
            assertThat(RevocationCheckService.crlReasonName(9)).isEqualTo("PRIVILEGE_WITHDRAWN");
            assertThat(RevocationCheckService.crlReasonName(10)).isEqualTo("AA_COMPROMISE");
            assertThat(RevocationCheckService.crlReasonName(99)).isEqualTo("UNSPECIFIED"); // default
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Cascade and Guard Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    class CascadeAndGuard {

        @Test
        void no_aia_no_cdp_no_staple_returns_UNCHECKED() throws Exception {
            // Cert has no AIA and no CDP — nothing to check
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    BigInteger.valueOf(30), null, null);

            RevocationResult result = serviceWith(null, null)
                    .check(new X509Certificate[]{leaf, issuerCert}, null, false);

            assertThat(result.status()).isEqualTo(RevocationStatus.UNCHECKED);
            assertThat(result.source()).isEqualTo(RevocationSource.NONE);
        }

        @Test
        void null_chain_returns_UNCHECKED() {
            RevocationResult result = serviceWith(null, null)
                    .check(null, null, false);
            assertThat(result.status()).isEqualTo(RevocationStatus.UNCHECKED);
        }

        @Test
        void empty_chain_returns_UNCHECKED() {
            RevocationResult result = serviceWith(null, null)
                    .check(new X509Certificate[0], null, false);
            assertThat(result.status()).isEqualTo(RevocationStatus.UNCHECKED);
        }

        @Test
        void revocation_disabled_returns_UNCHECKED() throws Exception {
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    BigInteger.valueOf(31), Fixtures.ocspUrl(), Fixtures.crlUrl());
            StubTransportRevocationService svc = serviceWith(null, null);
            ReflectionTestUtils.setField(svc, "revocationEnabled", false);

            RevocationResult result = svc.check(new X509Certificate[]{leaf, issuerCert}, null, false);

            assertThat(result.status()).isEqualTo(RevocationStatus.UNCHECKED);
        }

        @Test
        void deepCheck_true_both_queried_revoked_is_sticky() throws Exception {
            // Staple says GOOD; OCSP request says REVOKED.
            // deepCheck=true → query all; REVOKED is sticky.
            BigInteger serial = BigInteger.valueOf(32);
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    serial, Fixtures.ocspUrl(), null);

            byte[] goodStaple = Fixtures.buildOcspResponse(issuerKp, issuerCert,
                    serial, CertificateStatus.GOOD);
            RevokedStatus revoked = new RevokedStatus(new Date(), CRLReason.keyCompromise);
            byte[] revokedOcsp = Fixtures.buildOcspResponse(issuerKp, issuerCert,
                    serial, revoked);

            // Stub returns revokedOcsp for the live OCSP request
            StubTransportRevocationService svc = serviceWith(revokedOcsp, null);

            // Pass goodStaple — staple says GOOD
            RevocationResult result = svc.check(
                    new X509Certificate[]{leaf, issuerCert}, goodStaple, /* deepCheck= */ true);

            // REVOKED is sticky even though staple was GOOD
            assertThat(result.status()).isEqualTo(RevocationStatus.REVOKED);
        }

        @Test
        void deepCheck_false_staple_good_stops_cascade() throws Exception {
            // deepCheck=false: staple GOOD → stop, don't query OCSP or CRL
            BigInteger serial = BigInteger.valueOf(33);
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    serial, Fixtures.ocspUrl(), Fixtures.crlUrl());
            byte[] goodStaple = Fixtures.buildOcspResponse(issuerKp, issuerCert,
                    serial, CertificateStatus.GOOD);

            // CRL says REVOKED — but should never be reached
            byte[] revokedCrl = Fixtures.buildCrl(issuerKp, issuerCert,
                    List.of(new Fixtures.RevokedEntry(serial, CRLReason.keyCompromise, new Date())));
            // OCSP stub also says REVOKED — should not be reached
            RevokedStatus revoked = new RevokedStatus(new Date(), CRLReason.keyCompromise);
            byte[] revokedOcsp = Fixtures.buildOcspResponse(issuerKp, issuerCert, serial, revoked);

            StubTransportRevocationService svc = serviceWith(revokedOcsp, revokedCrl);

            RevocationResult result = svc.check(
                    new X509Certificate[]{leaf, issuerCert}, goodStaple, /* deepCheck= */ false);

            // Staple GOOD + deepCheck=false → stop at GOOD
            assertThat(result.status()).isEqualTo(RevocationStatus.GOOD);
            assertThat(result.source()).isEqualTo(RevocationSource.OCSP_STAPLED);
        }

        @Test
        void both_ocsp_and_crl_unknown_returns_UNKNOWN() throws Exception {
            X509Certificate leaf = Fixtures.buildLeaf(leafKp, issuerKp, issuerCert,
                    BigInteger.valueOf(34), Fixtures.ocspUrl(), Fixtures.crlUrl());

            // OCSP returns bad sig → UNKNOWN; CRL parse fails → UNKNOWN
            StubTransportRevocationService svc = new StubTransportRevocationService(
                    new SimpleMeterRegistry(), null, "garbage".getBytes()) {
                @Override
                byte[] sendHttpPost(String url, String contentType, String accept,
                                    byte[] body, int timeoutMs) throws Exception {
                    throw new java.io.IOException("simulated OCSP failure");
                }
            };
            applyDefaults(svc);

            RevocationResult result = svc.check(new X509Certificate[]{leaf, issuerCert}, null, false);

            // All checks indeterminate → UNKNOWN (soft-fail)
            assertThat(result.status()).isEqualTo(RevocationStatus.UNKNOWN);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RevocationResult record semantics
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    class RevocationResultSemantics {

        @Test
        void good_result_not_on_hold() {
            assertThat(RevocationResult.good(RevocationSource.OCSP).isOnHold()).isFalse();
        }

        @Test
        void revoked_code6_is_on_hold() {
            RevocationResult r = RevocationResult.revoked(RevocationSource.CRL,
                    "CERTIFICATE_HOLD", 6, Instant.now());
            assertThat(r.isOnHold()).isTrue();
        }

        @Test
        void revoked_code1_not_on_hold() {
            RevocationResult r = RevocationResult.revoked(RevocationSource.OCSP,
                    "KEY_COMPROMISE", 1, Instant.now());
            assertThat(r.isOnHold()).isFalse();
        }

        @Test
        void unknown_not_on_hold() {
            assertThat(RevocationResult.unknown(RevocationSource.OCSP, "responder down")
                    .isOnHold()).isFalse();
        }

        @Test
        void unchecked_factory() {
            RevocationResult r = RevocationResult.unchecked(RevocationSource.NONE);
            assertThat(r.status()).isEqualTo(RevocationStatus.UNCHECKED);
            assertThat(r.reason()).isNull();
            assertThat(r.reasonCode()).isNull();
            assertThat(r.revokedAt()).isNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private void applyDefaults(StubTransportRevocationService svc) {
        ReflectionTestUtils.setField(svc, "revocationEnabled",  true);
        ReflectionTestUtils.setField(svc, "ocspEnabled",        true);
        ReflectionTestUtils.setField(svc, "crlEnabled",         true);
        ReflectionTestUtils.setField(svc, "ocspTimeoutMs",      4000);
        ReflectionTestUtils.setField(svc, "crlTimeoutMs",       8000);
        ReflectionTestUtils.setField(svc, "crlMaxBytes",        5_242_880L);
        ReflectionTestUtils.setField(svc, "crlStaleGraceHours", 0);
        ReflectionTestUtils.setField(svc, "clockSkewSec",       300);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Stub transport subclass
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Subclass that replaces HTTP calls with in-memory canned data.
     *
     * <p>In the real service, {@code SsrfGuard.validate(url)} runs before
     * {@code sendHttpPost}/{@code downloadCrl}. Fixture URLs use
     * {@code *.certguard-test.example}: not on the blocked-name list, and DNS
     * resolution fails (UnknownHostException), which the guard treats as a
     * connectivity error and passes silently. The stub then intercepts before any
     * real TCP connection is attempted.
     */
    static class StubTransportRevocationService extends RevocationCheckService {

        private final byte[] ocspResponseBytes;
        private final byte[] crlBytes;

        StubTransportRevocationService(io.micrometer.core.instrument.MeterRegistry mr,
                                        byte[] ocspResponseBytes, byte[] crlBytes) {
            super(mr);
            this.ocspResponseBytes = ocspResponseBytes;
            this.crlBytes = crlBytes;
        }

        @Override
        byte[] sendHttpPost(String url, String ct, String accept, byte[] body, int timeout)
                throws Exception {
            return ocspResponseBytes;
        }

        @Override
        byte[] downloadCrl(String url) throws Exception {
            return crlBytes;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BC Fixture Factory
    // ═══════════════════════════════════════════════════════════════════════════

    static final class Fixtures {

        // Placeholder URLs that pass SsrfGuard name-check (not localhost/192.168/etc.)
        // and whose DNS failure is treated as connectivity error (not SSRF block).
        static String ocspUrl() { return "http://ocsp.certguard-test.example/"; }
        static String crlUrl()  { return "http://crl.certguard-test.example/crl.crl"; }

        record RevokedEntry(BigInteger serial, int reasonCode, Date revokedAt) {}

        static X509Certificate buildSelfSignedCA(KeyPair kp, String dn) throws Exception {
            X500Name name = new X500Name(dn);
            Date from = Date.from(Instant.now().minus(365, ChronoUnit.DAYS));
            Date to   = Date.from(Instant.now().plus(3650, ChronoUnit.DAYS));
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    name, BigInteger.ONE, from, to, name, kp.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption")
                    .build(kp.getPrivate());
            return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        }

        /**
         * Builds a leaf cert with optional AIA (OCSP) and CDP (CRL) extensions.
         * Pass null to omit the respective extension.
         */
        static X509Certificate buildLeaf(KeyPair leafKp, KeyPair issuerKp,
                                          X509Certificate issuerCert,
                                          BigInteger serial,
                                          String ocspUrl, String crlUrl) throws Exception {
            X500Name issuerName = new X500Name(
                    issuerCert.getSubjectX500Principal().getName("RFC2253"));
            X500Name leafName = new X500Name("CN=leaf.test, O=Test");
            Date from = Date.from(Instant.now().minus(30, ChronoUnit.DAYS));
            Date to   = Date.from(Instant.now().plus(365, ChronoUnit.DAYS));

            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    issuerName, serial, from, to, leafName, leafKp.getPublic());
            builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));

            if (ocspUrl != null) {
                AccessDescription ocspAd = new AccessDescription(
                        AccessDescription.id_ad_ocsp,
                        new GeneralName(GeneralName.uniformResourceIdentifier, ocspUrl));
                builder.addExtension(Extension.authorityInfoAccess, false,
                        new AuthorityInformationAccess(new AccessDescription[]{ocspAd}));
            }

            if (crlUrl != null) {
                GeneralName gn = new GeneralName(GeneralName.uniformResourceIdentifier, crlUrl);
                DistributionPoint dp = new DistributionPoint(
                        new DistributionPointName(new GeneralNames(gn)), null, null);
                builder.addExtension(Extension.cRLDistributionPoints, false,
                        new CRLDistPoint(new DistributionPoint[]{dp}));
            }

            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption")
                    .build(issuerKp.getPrivate());
            return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        }

        /** Build a signed OCSPResponse with default thisUpdate=now, nextUpdate=now+1h. */
        static byte[] buildOcspResponse(KeyPair signerKp, X509Certificate issuerCert,
                                         BigInteger serial, CertificateStatus certStatus)
                throws Exception {
            Date now    = new Date();
            Date future = Date.from(Instant.now().plus(1, ChronoUnit.HOURS));
            return buildOcspResponseWithDates(signerKp, issuerCert, serial, certStatus, now, future);
        }

        static byte[] buildOcspResponseWithDates(KeyPair signerKp, X509Certificate issuerCert,
                                                   BigInteger serial, CertificateStatus certStatus,
                                                   Date thisUpdate, Date nextUpdate) throws Exception {
            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption")
                    .build(signerKp.getPrivate());
            DigestCalculatorProvider localDcp = new JcaDigestCalculatorProviderBuilder().build();

            CertificateID certId = new CertificateID(
                    localDcp.get(CertificateID.HASH_SHA1),
                    new JcaX509CertificateHolder(issuerCert),
                    serial);

            BasicOCSPRespBuilder respBuilder = new BasicOCSPRespBuilder(
                    new org.bouncycastle.cert.ocsp.RespID(
                            new JcaX509CertificateHolder(issuerCert).getSubject()));
            respBuilder.addResponse(certId, certStatus, thisUpdate, nextUpdate, null);

            BasicOCSPResp basicResp = respBuilder.build(signer,
                    new X509CertificateHolder[]{new JcaX509CertificateHolder(issuerCert)}, new Date());

            OCSPRespBuilder ob = new OCSPRespBuilder();
            OCSPResp ocspResp = ob.build(OCSPRespBuilder.SUCCESSFUL, basicResp);
            return ocspResp.getEncoded();
        }

        /** Build a signed CRL. revokedEntries may be empty (all-good CRL). */
        static byte[] buildCrl(KeyPair signerKp, X509Certificate issuerCert,
                                List<RevokedEntry> revokedEntries) throws Exception {
            X500Name issuerName = new X500Name(
                    issuerCert.getSubjectX500Principal().getName("RFC2253"));
            Date now    = new Date();
            Date future = Date.from(Instant.now().plus(1, ChronoUnit.HOURS));

            X509v2CRLBuilder crlBuilder = new X509v2CRLBuilder(issuerName, now);
            crlBuilder.setNextUpdate(future);

            for (RevokedEntry e : revokedEntries) {
                org.bouncycastle.asn1.x509.ExtensionsGenerator extGen =
                        new org.bouncycastle.asn1.x509.ExtensionsGenerator();
                extGen.addExtension(Extension.reasonCode, false,
                        CRLReason.lookup(e.reasonCode()));
                crlBuilder.addCRLEntry(e.serial(), e.revokedAt(),
                        extGen.generate());
            }

            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption")
                    .build(signerKp.getPrivate());
            byte[] crlBytes = crlBuilder.build(signer).getEncoded();

            // Verify it round-trips through CertificateFactory (catch BC API misuse early).
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            cf.generateCRL(new ByteArrayInputStream(crlBytes));
            return crlBytes;
        }

        // Prevent instantiation
        private Fixtures() {}
    }
}
