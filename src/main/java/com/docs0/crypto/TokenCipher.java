package com.docs0.crypto;

import com.docs0.config.Docs0Properties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Encrypts stored grants with AES-256-GCM. Output is base64(nonce || ciphertext) with a
 * fresh random 12-byte nonce per call. Never logs keys or plaintext.
 */
@Component
public class TokenCipher {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public TokenCipher(Docs0Properties props) {
        this(props.encryptionKey());
    }

    /** @param base64Key base64 of 32 bytes; anything else leaves the cipher unavailable. */
    public TokenCipher(String base64Key) {
        this.key = parse(base64Key);
    }

    /** False when DOCS0_ENCRYPTION_KEY is missing or is not base64 of exactly 32 bytes. */
    public boolean available() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(encrypted, 0, out, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** Throws {@link IllegalArgumentException} when the value was not produced with this key or was altered. */
    public String decrypt(String encoded) {
        requireKey();
        try {
            byte[] in = Base64.getDecoder().decode(encoded);
            if (in.length <= NONCE_BYTES) {
                throw new IllegalArgumentException("Stored value cannot be decrypted");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, NONCE_BYTES));
            byte[] plain = cipher.doFinal(in, NONCE_BYTES, in.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // No cause attached: nothing about the key or the value belongs in a log.
            throw new IllegalArgumentException("Stored value cannot be decrypted");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("DOCS0_ENCRYPTION_KEY is not set to 32 base64-encoded bytes");
        }
    }

    private static SecretKeySpec parse(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (bytes.length != KEY_BYTES) {
            return null;
        }
        SecretKeySpec spec = new SecretKeySpec(bytes, "AES");
        Arrays.fill(bytes, (byte) 0);
        return spec;
    }
}
