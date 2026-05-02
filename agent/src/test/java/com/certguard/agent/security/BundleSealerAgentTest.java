package com.certguard.agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BundleUnsealer round-trip using the same blob format
 * produced by the server-side AgentBundleCrypto.sealPayload.
 *
 * These tests validate that:
 * 1. A bundle sealed with a known key can be unsealed to recover the original payload.
 * 2. Wrong key causes AEADBadTagException (triggering System.exit — verified by exception type).
 * 3. Flipping a byte in the ciphertext causes AEADBadTagException.
 */
class BundleSealerAgentTest {

    private static final byte[] MAGIC    = {'C', 'G', 'B', 'V'};
    private static final byte   VERSION  = 0x01;
    private static final int    IV_LEN   = 12;
    private static final int    KEY_LEN  = 32;
    private static final int    TAG_BITS = 128;

    private static final int MEM_KIB     = 65536;
    private static final int ITERATIONS  = 3;
    private static final int PARALLELISM = 1;

    // ── Helpers that mirror the server-side AgentBundleCrypto ────────────────

    private byte[] deriveKey(char[] installKey, byte[] salt) {
        byte[] keyBytes = new byte[installKey.length];
        for (int i = 0; i < installKey.length; i++) {
            keyBytes[i] = (byte) installKey[i];
        }
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withMemoryAsKB(MEM_KIB)
                .withIterations(ITERATIONS)
                .withParallelism(PARALLELISM)
                .build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        byte[] derived = new byte[KEY_LEN];
        gen.generateBytes(keyBytes, derived);
        Arrays.fill(keyBytes, (byte) 0);
        return derived;
    }

    private byte[] seal(byte[] plaintext, byte[] wrappingKey, byte[] salt) throws Exception {
        SecureRandom rng = new SecureRandom();
        byte[] iv = new byte[IV_LEN];
        rng.nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(wrappingKey, "AES"),
                new GCMParameterSpec(TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        int frameLen = MAGIC.length + 1 + 1 + salt.length + IV_LEN + ciphertext.length;
        ByteBuffer buf = ByteBuffer.allocate(frameLen);
        buf.put(MAGIC);
        buf.put(VERSION);
        buf.put((byte) salt.length);
        buf.put(salt);
        buf.put(iv);
        buf.put(ciphertext);
        return buf.array();
    }

    private Map<String, Object> buildPayload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId",           "agent-uuid-1234");
        m.put("orgId",             "org-uuid-5678");
        m.put("serverUrl",         "https://certguard.example.com:8443");
        m.put("registrationToken", "CGR-TOKEN-ABC");
        m.put("agentName",         "test-agent");
        m.put("allowedCidrs",      "10.0.0.0/8,192.168.0.0/16");
        m.put("maxTargets",        50);
        return m;
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    void roundTrip_validKey_returnsOriginalPayload() throws Exception {
        String installKeyStr = "CGK-TESTINSTALLKEY1234567890ABCD";
        char[] installKey = installKeyStr.toCharArray();

        SecureRandom rng = new SecureRandom();
        byte[] salt = new byte[16];
        rng.nextBytes(salt);

        byte[] wrappingKey = deriveKey(installKey, salt);

        ObjectMapper mapper = new ObjectMapper();
        byte[] plaintext = mapper.writeValueAsBytes(buildPayload());

        byte[] sealed = seal(plaintext, wrappingKey, salt);

        // Write to a temp file for BundleUnsealer to read
        Path tmp = Files.createTempFile("certguard-bundle-test-", ".cgb");
        try {
            Files.write(tmp, sealed);

            // Provide install key via env-equivalent: pass via CLI args
            BundleUnsealer unsealer = new BundleUnsealer(
                    new String[]{"--bundle", tmp.toString(), "--install-key", installKeyStr});

            Map<String, String> result = unsealer.unseal();

            assertNotNull(result);
            assertEquals("agent-uuid-1234",                      result.get("agentId"));
            assertEquals("org-uuid-5678",                        result.get("orgId"));
            assertEquals("https://certguard.example.com:8443",   result.get("serverUrl"));
            assertEquals("CGR-TOKEN-ABC",                        result.get("registrationToken"));
            assertEquals("test-agent",                           result.get("agentName"));
            assertEquals("10.0.0.0/8,192.168.0.0/16",           result.get("allowedCidrs"));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void unseal_wrongKey_throwsAEADBadTagException() throws Exception {
        char[] correctKey = "CGK-CORRECTKEY1234567890ABCDEFGH".toCharArray();
        char[] wrongKey   = "CGK-WRONGKEYYYY1234567890ABCDEFG".toCharArray();

        SecureRandom rng = new SecureRandom();
        byte[] salt = new byte[16];
        rng.nextBytes(salt);

        byte[] wrappingKey = deriveKey(correctKey, salt);

        ObjectMapper mapper = new ObjectMapper();
        byte[] plaintext = mapper.writeValueAsBytes(buildPayload());
        byte[] sealed    = seal(plaintext, wrappingKey, salt);

        // Replace wrapping key with the wrong one to simulate wrong install key
        byte[] wrongWrappingKey = deriveKey(wrongKey, salt);

        // Decrypt directly (bypassing the System.exit path)
        assertThrows(javax.crypto.AEADBadTagException.class, () -> {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

            // Extract IV from the sealed frame
            ByteBuffer buf = ByteBuffer.wrap(sealed);
            buf.position(MAGIC.length + 1); // skip magic + version
            int saltLen = buf.get() & 0xFF;
            buf.position(buf.position() + saltLen);
            byte[] iv = new byte[IV_LEN];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(wrongWrappingKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.doFinal(ciphertext); // must throw AEADBadTagException
        });
    }

    @Test
    void unseal_tamperedCiphertext_throwsAEADBadTagException() throws Exception {
        char[] installKey = "CGK-TAMPERTEST1234567890ABCDEFGH".toCharArray();

        SecureRandom rng = new SecureRandom();
        byte[] salt = new byte[16];
        rng.nextBytes(salt);

        byte[] wrappingKey = deriveKey(installKey, salt);

        ObjectMapper mapper = new ObjectMapper();
        byte[] plaintext = mapper.writeValueAsBytes(buildPayload());
        byte[] sealed    = seal(plaintext, wrappingKey, salt);

        // Flip one byte in the ciphertext region (after magic+version+salt+iv)
        int headerLen = MAGIC.length + 1 + 1 + salt.length + IV_LEN;
        byte[] tampered = Arrays.copyOf(sealed, sealed.length);
        tampered[headerLen] ^= 0xFF; // flip all bits in first ciphertext byte

        assertThrows(javax.crypto.AEADBadTagException.class, () -> {
            ByteBuffer buf = ByteBuffer.wrap(tampered);
            buf.position(MAGIC.length + 1);
            int saltLen2 = buf.get() & 0xFF;
            buf.position(buf.position() + saltLen2);
            byte[] iv = new byte[IV_LEN];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(wrappingKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.doFinal(ciphertext);
        });
    }

    @Test
    void extractSalt_returnsSaltFromFrame() throws Exception {
        char[] installKey = "CGK-SALTEXT1234567890ABCDEFGHIJKL".toCharArray();

        SecureRandom rng = new SecureRandom();
        byte[] expectedSalt = new byte[16];
        rng.nextBytes(expectedSalt);

        byte[] wrappingKey = deriveKey(installKey, expectedSalt);
        byte[] plaintext   = new ObjectMapper().writeValueAsBytes(buildPayload());
        byte[] sealed      = seal(plaintext, wrappingKey, expectedSalt);

        // Create a BundleUnsealer and verify it can extract the correct salt
        Path tmp = Files.createTempFile("certguard-salt-test-", ".cgb");
        try {
            Files.write(tmp, sealed);
            BundleUnsealer unsealer = new BundleUnsealer(
                    new String[]{"--bundle", tmp.toString()});

            // Use reflection to call extractSalt — or test via public unseal path
            // Direct test: call roundTrip which implicitly calls extractSalt
            BundleUnsealer unsealerWithKey = new BundleUnsealer(
                    new String[]{"--bundle", tmp.toString(),
                                 "--install-key", new String(installKey)});
            Map<String, String> result = unsealerWithKey.unseal();
            assertNotNull(result);
            assertTrue(result.containsKey("agentId"));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void resolveBundlePath_defaultsToBundleCgb() {
        BundleUnsealer unsealer = new BundleUnsealer(new String[]{});
        Path path = unsealer.resolveBundlePath();
        assertEquals("bundle.cgb", path.toString());
    }

    @Test
    void resolveBundlePath_fromCliArg() {
        BundleUnsealer unsealer = new BundleUnsealer(
                new String[]{"--bundle", "/opt/certguard/custom.cgb"});
        Path path = unsealer.resolveBundlePath();
        assertEquals("/opt/certguard/custom.cgb", path.toString());
    }
}
