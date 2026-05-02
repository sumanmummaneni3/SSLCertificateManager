package com.certguard.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for AgentBundleCrypto — no Spring context required.
 *
 * Covers:
 * - Round-trip seal / unseal with correct key and salt
 * - Tamper detection: flipping one byte of ciphertext → AEADBadTagException
 * - Wrong key detection: different install key → AEADBadTagException
 * - Bad magic header → IllegalArgumentException
 * - Bad version → IllegalArgumentException
 */
class AgentBundleCryptoTest {

    private AgentBundleCrypto crypto;
    private final SecureRandom rng = new SecureRandom();

    @BeforeEach
    void setUp() {
        crypto = new AgentBundleCrypto();
    }

    @Nested
    class RoundTrip {

        @Test
        void sealAndUnseal_withCorrectKey_returnsOriginalPlaintext() throws Exception {
            byte[] salt       = randomBytes(16);
            char[] installKey = "CGK-TEST-KEY-FOR-ROUND-TRIP-1234".toCharArray();
            byte[] plaintext  = "Hello, CertGuard bundle!".getBytes();

            byte[] wrappingKey = crypto.deriveWrappingKey(installKey, salt, 65536, 3, 1);
            byte[] sealed      = crypto.sealPayload(plaintext, wrappingKey, salt);
            byte[] recovered   = crypto.unsealPayload(sealed, wrappingKey);

            assertThat(recovered).isEqualTo(plaintext);
        }

        @Test
        void sealAndUnseal_withLargePayload_returnsOriginalPlaintext() throws Exception {
            byte[] salt       = randomBytes(16);
            char[] installKey = "CGK-LARGE-PAYLOAD-TEST-KEY-567".toCharArray();
            byte[] plaintext  = randomBytes(4096);

            byte[] wrappingKey = crypto.deriveWrappingKey(installKey, salt, 65536, 3, 1);
            byte[] sealed      = crypto.sealPayload(plaintext, wrappingKey, salt);
            byte[] recovered   = crypto.unsealPayload(sealed, wrappingKey);

            assertThat(recovered).isEqualTo(plaintext);
        }

        @Test
        void sealAndUnseal_differentInvocations_producesDifferentCiphertext() throws Exception {
            byte[] salt       = randomBytes(16);
            char[] installKey = "CGK-NONCE-UNIQUENESS-TEST-KEY-1".toCharArray();
            byte[] plaintext  = "same plaintext".getBytes();

            byte[] wrappingKey = crypto.deriveWrappingKey(installKey, salt, 65536, 3, 1);
            byte[] sealed1 = crypto.sealPayload(plaintext, wrappingKey, salt);
            byte[] sealed2 = crypto.sealPayload(plaintext, wrappingKey, salt);

            // AES-GCM with a random IV means two seals must produce different bytes
            assertThat(sealed1).isNotEqualTo(sealed2);
        }
    }

    @Nested
    class TamperDetection {

        @Test
        void unseal_withFlippedCiphertextByte_throwsAEADBadTagException() throws Exception {
            byte[] salt       = randomBytes(16);
            char[] installKey = "CGK-TAMPER-DETECT-TEST-KEY-1234".toCharArray();
            byte[] plaintext  = "tamper-me".getBytes();

            byte[] wrappingKey = crypto.deriveWrappingKey(installKey, salt, 65536, 3, 1);
            byte[] sealed      = crypto.sealPayload(plaintext, wrappingKey, salt);

            // Flip a byte in the ciphertext region (skip the 4+1+1+16+12 byte header)
            int headerLen = 4 + 1 + 1 + salt.length + 12; // MAGIC+VER+SALT_LEN+SALT+IV
            byte[] tampered = Arrays.copyOf(sealed, sealed.length);
            tampered[headerLen] ^= 0xFF;

            assertThatThrownBy(() -> crypto.unsealPayload(tampered, wrappingKey))
                    .isInstanceOf(AEADBadTagException.class);
        }

        @Test
        void unseal_withTruncatedPayload_throwsException() throws Exception {
            byte[] salt       = randomBytes(16);
            char[] installKey = "CGK-TRUNCATE-TEST-KEY-123456789".toCharArray();
            byte[] plaintext  = "truncate-me".getBytes();

            byte[] wrappingKey = crypto.deriveWrappingKey(installKey, salt, 65536, 3, 1);
            byte[] sealed      = crypto.sealPayload(plaintext, wrappingKey, salt);
            byte[] truncated   = Arrays.copyOf(sealed, sealed.length - 4);

            assertThatThrownBy(() -> crypto.unsealPayload(truncated, wrappingKey))
                    .isInstanceOf(Exception.class);
        }
    }

    @Nested
    class WrongKey {

        @Test
        void unseal_withDifferentKey_throwsAEADBadTagException() throws Exception {
            byte[] salt        = randomBytes(16);
            char[] correctKey  = "CGK-CORRECT-KEY-FOR-WRONG-KEY-1".toCharArray();
            char[] wrongKey    = "CGK-WRONG-KEY-FOR-WRONG-KEY-123".toCharArray();
            byte[] plaintext   = "secret config".getBytes();

            byte[] wrappingKey = crypto.deriveWrappingKey(correctKey, salt, 65536, 3, 1);
            byte[] sealed      = crypto.sealPayload(plaintext, wrappingKey, salt);

            byte[] wrongWrappingKey = crypto.deriveWrappingKey(wrongKey, salt, 65536, 3, 1);

            assertThatThrownBy(() -> crypto.unsealPayload(sealed, wrongWrappingKey))
                    .isInstanceOf(AEADBadTagException.class);
        }
    }

    @Nested
    class HeaderValidation {

        @Test
        void unseal_withBadMagic_throwsIllegalArgumentException() throws Exception {
            byte[] salt       = randomBytes(16);
            char[] installKey = "CGK-BAD-MAGIC-TEST-KEY-12345678".toCharArray();
            byte[] plaintext  = "magic-test".getBytes();

            byte[] wrappingKey = crypto.deriveWrappingKey(installKey, salt, 65536, 3, 1);
            byte[] sealed      = crypto.sealPayload(plaintext, wrappingKey, salt);

            // Corrupt the magic bytes
            byte[] corrupted = Arrays.copyOf(sealed, sealed.length);
            corrupted[0] = 'X';
            corrupted[1] = 'X';

            assertThatThrownBy(() -> crypto.unsealPayload(corrupted, wrappingKey))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("magic");
        }

        @Test
        void unseal_withBadVersion_throwsIllegalArgumentException() throws Exception {
            byte[] salt       = randomBytes(16);
            char[] installKey = "CGK-BAD-VERSION-TEST-KEY-1234567".toCharArray();
            byte[] plaintext  = "version-test".getBytes();

            byte[] wrappingKey = crypto.deriveWrappingKey(installKey, salt, 65536, 3, 1);
            byte[] sealed      = crypto.sealPayload(plaintext, wrappingKey, salt);

            // Corrupt version byte (index 4, after "CGBV")
            byte[] corrupted = Arrays.copyOf(sealed, sealed.length);
            corrupted[4] = 0x09; // unknown version

            assertThatThrownBy(() -> crypto.unsealPayload(corrupted, wrappingKey))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("version");
        }
    }

    @Nested
    class KdfParameters {

        @Test
        void deriveWrappingKey_withSameInputs_returnsSameKey() {
            byte[] salt       = randomBytes(16);
            char[] installKey = "CGK-KDF-DETERMINISM-TEST-KEY-12".toCharArray();

            byte[] key1 = crypto.deriveWrappingKey(installKey, salt, 65536, 3, 1);
            byte[] key2 = crypto.deriveWrappingKey(installKey, salt, 65536, 3, 1);

            assertThat(key1).isEqualTo(key2);
        }

        @Test
        void deriveWrappingKey_withDifferentSalts_returnsDifferentKeys() {
            byte[] salt1      = randomBytes(16);
            byte[] salt2      = randomBytes(16);
            char[] installKey = "CGK-KDF-SALT-ISOLATION-TEST-12".toCharArray();

            byte[] key1 = crypto.deriveWrappingKey(installKey, salt1, 65536, 3, 1);
            byte[] key2 = crypto.deriveWrappingKey(installKey, salt2, 65536, 3, 1);

            assertThat(key1).isNotEqualTo(key2);
        }

        @Test
        void deriveWrappingKey_returns32Bytes() {
            byte[] salt       = randomBytes(16);
            char[] installKey = "CGK-KEY-LENGTH-TEST-123456789".toCharArray();

            byte[] key = crypto.deriveWrappingKey(installKey, salt, 65536, 3, 1);

            assertThat(key).hasSize(32);
        }
    }

    @Nested
    class ExtractSalt {

        @Test
        void extractSalt_returnsTheSaltEmbeddedBySealing() throws Exception {
            byte[] expectedSalt = randomBytes(16);
            char[] installKey   = "CGK-SALT-EXTRACT-TEST-1234567".toCharArray();
            byte[] plaintext    = "salt-extract".getBytes();

            byte[] wrappingKey = crypto.deriveWrappingKey(installKey, expectedSalt, 65536, 3, 1);
            byte[] sealed      = crypto.sealPayload(plaintext, wrappingKey, expectedSalt);

            byte[] extractedSalt = crypto.extractSalt(sealed);
            assertThat(extractedSalt).isEqualTo(expectedSalt);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private byte[] randomBytes(int count) {
        byte[] b = new byte[count];
        rng.nextBytes(b);
        return b;
    }
}
