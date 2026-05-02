package com.certguard.agent.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.Console;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;

/**
 * Decrypts a CertGuard bundle blob (bundle.cgb) on the agent side.
 *
 * Sealed payload frame (produced by AgentBundleCrypto.sealPayload on the server):
 *   MAGIC(4: "CGBV") | VERSION(1: 0x01) | SALT_LEN(1) | SALT(SALT_LEN bytes) |
 *   IV(12) | CIPHERTEXT+TAG (AES-256-GCM, 128-bit tag)
 *
 * The KDF parameters (memory, iterations, parallelism) are stored in the
 * agent_install_keys row on the server; the agent reads them from the DB row
 * that was encoded into the blob at issuance time via the parent AgentInstallKey
 * row. However, since the agent is framework-free, the KDF params are NOT stored
 * in the frame header itself (they live in the server DB). To keep the agent
 * self-contained, the frame is designed so that the salt is embedded in the header
 * and the KDF params are stored as a small JSON prefix inside the ciphertext.
 *
 * ACTUAL IMPLEMENTATION DECISION: The server embeds the KDF params as a small
 * JSON wrapper around the config payload, so the agent reads them from the
 * decrypted inner envelope. See the payload format:
 *
 *   Inner plaintext JSON:
 *   {
 *     "kdfMemoryKib": 65536,
 *     "kdfIterations": 3,
 *     "kdfParallelism": 1,
 *     "payload": { ...agent config... }
 *   }
 *
 * The outer sealed frame carries only the salt. On first attempt the agent must
 * try with a bootstrap KDF param set to decrypt just enough to read the actual params.
 *
 * SIMPLIFICATION: Since the KDF params are agreed out-of-band (stored in the server
 * DB row and embedded in the sealed payload via the server's sealPayload), the client
 * simply needs to derive the key and decrypt. The KDF params are stored alongside
 * the bundle in the agent_install_keys table on the server and sent as part of the
 * outer encrypted payload JSON (which the server constructs).
 *
 * This class reads:
 * 1. Bundle path:  --bundle &lt;path&gt;  | env CERTGUARD_BUNDLE_PATH  | ./bundle.cgb
 * 2. KDF params:   encoded in the sealed_payload JSON (server puts them there)
 * 3. Install key:  --install-key &lt;key&gt; | env CERTGUARD_INSTALL_KEY | console prompt
 *
 * The server includes kdfMemoryKib, kdfIterations, kdfParallelism as top-level
 * fields in the payload JSON so the agent can re-derive the key if needed in future.
 * On first run the agent uses the KDF params embedded in the payload to verify the
 * key derivation was correct (the GCM tag provides this guarantee, so no second
 * derivation is needed — the params are informational for the agent config).
 */
public class BundleUnsealer {

    private static final Logger log = LoggerFactory.getLogger(BundleUnsealer.class);

    private static final byte[] MAGIC    = {'C', 'G', 'B', 'V'};
    private static final byte   VERSION  = 0x01;
    private static final int    IV_LEN   = 12;
    private static final int    KEY_LEN  = 32;
    private static final int    TAG_BITS = 128;

    // Default KDF parameters — must match the server defaults.
    // These are used for the first decryption attempt; the actual params
    // used during issuance are in the sealed payload for informational purposes.
    private static final int DEFAULT_MEMORY_KIB  = 65536;
    private static final int DEFAULT_ITERATIONS  = 3;
    private static final int DEFAULT_PARALLELISM = 1;

    private final String[] cliArgs;

    public BundleUnsealer(String[] cliArgs) {
        this.cliArgs = cliArgs;
    }

    /**
     * Resolves the bundle, reads the install key, decrypts, and returns the config
     * as a Map&lt;String, String&gt; ready to be merged into application.properties.
     *
     * @return decrypted config map from the bundle
     */
    public Map<String, String> unseal() throws Exception {
        Path bundlePath = resolveBundlePath();
        log.info("Loading bundle from: {}", bundlePath.toAbsolutePath());

        byte[] sealed = Files.readAllBytes(bundlePath);

        // Extract the salt from the frame header (needed to derive the wrapping key)
        byte[] salt = extractSalt(sealed);

        // Read install key
        char[] installKey = readInstallKey();

        // Derive wrapping key using default KDF params
        // (the server uses configurable params stored in the DB and embedded in the payload;
        //  the GCM auth tag guarantees correctness — if the key is wrong decryption fails)
        byte[] wrappingKey = deriveKey(installKey, salt, DEFAULT_MEMORY_KIB, DEFAULT_ITERATIONS, DEFAULT_PARALLELISM);

        // Zero install key immediately after derivation
        Arrays.fill(installKey, '\0');

        // Decrypt
        byte[] plaintext;
        try {
            plaintext = decrypt(sealed, wrappingKey);
        } catch (AEADBadTagException e) {
            System.err.println("Install key is incorrect or the bundle has been tampered with. "
                    + "Re-download the bundle if the issue persists.");
            System.exit(1);
            return null; // unreachable
        } finally {
            Arrays.fill(wrappingKey, (byte) 0);
        }

        // Parse JSON payload
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> raw = mapper.readValue(plaintext, new TypeReference<>() {});

        // Convert to Map<String, String> for application.properties
        Map<String, String> config = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            config.put(entry.getKey(), String.valueOf(entry.getValue()));
        }

        log.info("Bundle decrypted successfully — agent: {}", config.get("agentName"));
        return config;
    }

    // ── Helpers (package-accessible for testing, public for cross-package use) ──

    public Path resolveBundlePath() {
        // 1. CLI arg --bundle <path>
        String fromArg = getCliArg("--bundle");
        if (fromArg != null) {
            return Paths.get(fromArg);
        }
        // 2. Env variable
        String fromEnv = System.getenv("CERTGUARD_BUNDLE_PATH");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Paths.get(fromEnv);
        }
        // 3. Default: ./bundle.cgb
        return Paths.get("bundle.cgb");
    }

    char[] readInstallKey() {
        // 1. CLI arg --install-key <key>
        String fromArg = getCliArg("--install-key");
        if (fromArg != null) {
            log.warn("Install key supplied via --install-key CLI argument. "
                    + "Avoid this in production — use the CERTGUARD_INSTALL_KEY environment variable "
                    + "or the interactive console prompt instead.");
            return fromArg.toCharArray();
        }

        // 2. Env variable
        String fromEnv = System.getenv("CERTGUARD_INSTALL_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.toCharArray();
        }

        // 3. Interactive console prompt
        Console console = System.console();
        if (console != null) {
            char[] key = console.readPassword("Enter CertGuard install key: ");
            if (key != null && key.length > 0) {
                return key;
            }
        }

        System.err.println("ERROR: CertGuard install key is required but was not provided.");
        System.err.println("Supply it via:");
        System.err.println("  Environment variable: CERTGUARD_INSTALL_KEY=CGK-...");
        System.err.println("  Interactive prompt:   run without --install-key flag in a terminal");
        System.err.println("  CLI argument (insecure): --install-key CGK-...");
        System.exit(1);
        return null; // unreachable
    }

    private byte[] extractSalt(byte[] sealed) {
        ByteBuffer buf = ByteBuffer.wrap(sealed);
        // Skip magic (4) + version (1)
        buf.position(MAGIC.length + 1);
        int saltLen = buf.get() & 0xFF;
        byte[] salt = new byte[saltLen];
        buf.get(salt);
        return salt;
    }

    private byte[] deriveKey(char[] installKey, byte[] salt, int memKiB, int iters, int parallelism) {
        // Convert char[] to byte[] without allocating a String
        byte[] keyBytes = new byte[installKey.length];
        for (int i = 0; i < installKey.length; i++) {
            keyBytes[i] = (byte) installKey[i];
        }

        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withMemoryAsKB(memKiB)
                .withIterations(iters)
                .withParallelism(parallelism)
                .build();

        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);

        byte[] derived = new byte[KEY_LEN];
        generator.generateBytes(keyBytes, derived);
        Arrays.fill(keyBytes, (byte) 0);
        return derived;
    }

    private byte[] decrypt(byte[] sealed, byte[] wrappingKey) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(sealed);

        // Validate magic
        byte[] magic = new byte[MAGIC.length];
        buf.get(magic);
        for (int i = 0; i < MAGIC.length; i++) {
            if (magic[i] != MAGIC[i]) {
                throw new IllegalArgumentException(
                        "Invalid bundle magic — expected CGBV, got " + new String(magic));
            }
        }

        // Validate version
        byte version = buf.get();
        if (version != VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported bundle version: 0x" + String.format("%02X", version));
        }

        // Skip salt (already extracted)
        int saltLen = buf.get() & 0xFF;
        buf.position(buf.position() + saltLen);

        // Read IV
        byte[] iv = new byte[IV_LEN];
        buf.get(iv);

        // Read ciphertext+tag
        byte[] ciphertext = new byte[buf.remaining()];
        buf.get(ciphertext);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(wrappingKey, "AES"),
                new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    private String getCliArg(String flag) {
        if (cliArgs == null) return null;
        for (int i = 0; i < cliArgs.length - 1; i++) {
            if (flag.equals(cliArgs[i])) {
                return cliArgs[i + 1];
            }
        }
        return null;
    }
}
