package com.docs0.source.gmail;

import com.google.gson.Gson;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Makes service account key files for tests: a real throwaway RSA key, nothing that works anywhere. */
public final class TestKeys {

    public static final String EMAIL = "docs0-reader@test-project.iam.gserviceaccount.com";
    public static final String CLIENT_ID = "112233445566778899001";

    private static final String PRIVATE_KEY = generate();
    /** A distinctive line of the private key, to prove it is never shown or logged. */
    public static final String KEY_FRAGMENT = PRIVATE_KEY.lines().skip(2).findFirst().orElseThrow();

    private TestKeys() {}

    private static String generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded());
            return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The JSON of a key file whose token endpoint is {@code tokenUri}. */
    public static String json(String tokenUri) {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("type", "service_account");
        key.put("project_id", "test-project");
        key.put("private_key_id", "abcdef0123456789");
        key.put("private_key", PRIVATE_KEY);
        key.put("client_email", EMAIL);
        key.put("client_id", CLIENT_ID);
        key.put("auth_uri", "https://accounts.google.com/o/oauth2/auth");
        key.put("token_uri", tokenUri);
        return new Gson().toJson(key);
    }

    public static String json() {
        return json("https://oauth2.googleapis.com/token");
    }
}
