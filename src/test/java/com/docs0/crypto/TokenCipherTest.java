package com.docs0.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TokenCipherTest {

    private static final String KEY = key('k');
    private static final String OTHER_KEY = key('x');

    private static String key(char fill) {
        return Base64.getEncoder().encodeToString(String.valueOf(fill).repeat(32).getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void roundTripsAndTheOutputDoesNotContainThePlaintext() {
        TokenCipher cipher = new TokenCipher(KEY);
        String token = "1//0g-refresh-token-with-ünicode";

        String encrypted = cipher.encrypt(token);

        assertThat(cipher.available()).isTrue();
        assertThat(encrypted).doesNotContain("refresh-token");
        assertThat(new String(Base64.getDecoder().decode(encrypted), StandardCharsets.ISO_8859_1))
                .doesNotContain("refresh-token");
        assertThat(cipher.decrypt(encrypted)).isEqualTo(token);
        // 12-byte nonce + ciphertext + 16-byte tag.
        assertThat(Base64.getDecoder().decode(encrypted))
                .hasSize(12 + token.getBytes(StandardCharsets.UTF_8).length + 16);
    }

    @Test
    void everyEncryptionUsesAFreshNonce() {
        TokenCipher cipher = new TokenCipher(KEY);
        Set<String> nonces = new HashSet<>();
        Set<String> outputs = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            String encrypted = cipher.encrypt("same plaintext");
            outputs.add(encrypted);
            nonces.add(Base64.getEncoder().encodeToString(
                    java.util.Arrays.copyOf(Base64.getDecoder().decode(encrypted), 12)));
        }

        assertThat(nonces).hasSize(200);
        assertThat(outputs).hasSize(200);
    }

    @Test
    void anyAlteredByteIsDetected() {
        TokenCipher cipher = new TokenCipher(KEY);
        byte[] original = Base64.getDecoder().decode(cipher.encrypt("refresh-token"));

        for (int i = 0; i < original.length; i++) {
            byte[] tampered = original.clone();
            tampered[i] ^= 0x01;
            String encoded = Base64.getEncoder().encodeToString(tampered);
            assertThatThrownBy(() -> cipher.decrypt(encoded)).as("byte %d", i)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> cipher.decrypt("not base64 !!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(new byte[12])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDifferentKeyCannotDecryptAndTheErrorSaysNothingAboutTheValue() {
        String encrypted = new TokenCipher(KEY).encrypt("refresh-token");

        assertThatThrownBy(() -> new TokenCipher(OTHER_KEY).decrypt(encrypted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Stored value cannot be decrypted")
                .hasNoCause();
    }

    @Test
    void unavailableWithoutAKeyOf32Bytes() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        String longKey = Base64.getEncoder().encodeToString(new byte[33]);

        for (String bad : new String[] {null, "", "   ", "not base64 !!", shortKey, longKey}) {
            TokenCipher cipher = new TokenCipher(bad);
            assertThat(cipher.available()).as(String.valueOf(bad)).isFalse();
            assertThatThrownBy(() -> cipher.encrypt("x")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> cipher.decrypt("x")).isInstanceOf(IllegalStateException.class);
        }
        assertThat(new TokenCipher(" " + KEY + "\n").available()).isTrue();
    }
}
