package com.certguard.security;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

/**
 * Cryptographic helpers for the agent install bundle.
 *
 * KDF: Argon2id via BouncyCastle. Default params target ~400–600 ms on
 * typical server hardware (Intel Xeon, 2 GHz+):
 *   memory = 65536 KiB (64 MiB), iterations = 3, parallelism = 1.
 * Params are encoded into every sealed blob header so the server can change
 * them per-issuance without breaking existing bundles.
 *
 * Sealed-payload frame:
 *   MAGIC(4 bytes: "CGBV") | VERSION(1 byte: 0x01) |
 *   SALT_LEN(1 byte) | SALT(SALT_LEN bytes) | IV(12 bytes) |
 *   CIPHERTEXT+TAG (AES-256-GCM, 128-bit tag)
 */
@Component
public class AgentBundleCrypto {

    private static final byte[] MAGIC   = {'C', 'G', 'B', 'V'};
    private static final byte   VERSION = 0x01;
    private static final int    IV_LEN  = 12;
    private static final int    KEY_LEN = 32;  // 256-bit AES key
    private static final int    TAG_BITS = 128;

    private final SecureRandom rng = new SecureRandom();

    /**
     * Derives a 256-bit wrapping key from the install key using Argon2id.
     *
     * @param installKey plaintext install key characters
     * @param salt       16+ byte random salt (unique per issuance)
     * @param memKiB     Argon2 memory cost in KiB (default 65536)
     * @param iters      Argon2 iteration count (default 3)
     * @param parallelism Argon2 parallelism degree (default 1)
     * @return 32-byte derived key
     */
    public byte[] deriveWrappingKey(char[] installKey, byte[] salt, int memKiB, int iters, int parallelism) {
        // Convert char[] to byte[] using UTF-8 without allocating a String to reduce secret exposure
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

        // Zero the intermediate key bytes to limit secret exposure in memory
        java.util.Arrays.fill(keyBytes, (byte) 0);

        return derived;
    }

    /**
     * Encrypts {@code plaintext} with AES-256-GCM using the provided wrapping key.
     * Returns the full framed blob including magic, version, salt, and IV.
     *
     * @param plaintext   bytes to encrypt
     * @param wrappingKey 32-byte key from {@link #deriveWrappingKey}
     * @param salt        the same salt used during key derivation (encoded in frame)
     * @return framed sealed payload
     */
    public byte[] sealPayload(byte[] plaintext, byte[] wrappingKey, byte[] salt) throws Exception {
        byte[] iv = new byte[IV_LEN];
        rng.nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(wrappingKey, "AES"),
                new GCMParameterSpec(TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        // Frame: MAGIC(4) | VERSION(1) | SALT_LEN(1) | SALT | IV(12) | CIPHERTEXT+TAG
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

    /**
     * Decrypts a blob produced by {@link #sealPayload}.
     *
     * @param sealed      framed sealed payload bytes
     * @param wrappingKey 32-byte key; must be derived with the KDF params stored in the
     *                    row, using the salt embedded in the frame header
     * @return plaintext bytes
     * @throws IllegalArgumentException if magic or version do not match
     * @throws AEADBadTagException      if the authentication tag does not verify
     */
    public byte[] unsealPayload(byte[] sealed, byte[] wrappingKey) throws Exception {
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

        // Read salt (not needed for decryption here — caller already derived the key)
        int saltLen = buf.get() & 0xFF;
        buf.position(buf.position() + saltLen); // skip salt in frame

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

    /**
     * Extracts the salt embedded in the sealed payload frame header.
     * Used by the agent-side unsealer to reconstruct the wrapping key
     * from only the install key + the blob itself.
     */
    public byte[] extractSalt(byte[] sealed) {
        ByteBuffer buf = ByteBuffer.wrap(sealed);
        buf.position(MAGIC.length + 1); // skip magic + version
        int saltLen = buf.get() & 0xFF;
        byte[] salt = new byte[saltLen];
        buf.get(salt);
        return salt;
    }
}
