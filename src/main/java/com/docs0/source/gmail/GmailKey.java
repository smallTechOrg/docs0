package com.docs0.source.gmail;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * What docs0 reads out of a service account key: who the account is. The key itself is never
 * kept in this object, printed or put into a message.
 */
public record GmailKey(String email, String clientId) {

    private static final int MAX_CHARS = 20_000;
    private static final int MAX_FIELD_CHARS = 500;
    /** The only place a stored key may ever ask for a token. A key naming another host is refused. */
    static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final Gson GSON = new Gson();

    /**
     * Checks that the text is a Google service account key file with a usable private key.
     * Empty when it is not; nothing about the text is reported.
     */
    public static Optional<GmailKey> parse(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_CHARS) {
            return Optional.empty();
        }
        try {
            JsonElement root = GSON.fromJson(json, JsonElement.class);
            if (!(root instanceof JsonObject o) || !"service_account".equals(text(o, "type"))) {
                return Optional.empty();
            }
            String email = text(o, "client_email");
            String clientId = text(o, "client_id");
            String privateKey = text(o, "private_key");
            String tokenUri = text(o, "token_uri");
            String keyId = text(o, "private_key_id");
            if (tokenUri != null && !TOKEN_URI.equals(tokenUri)
                    || email != null && email.length() > MAX_FIELD_CHARS
                    || keyId != null && keyId.length() > MAX_FIELD_CHARS) {
                return Optional.empty();
            }
            if (email == null || clientId == null || privateKey == null || !privateKey.contains("PRIVATE KEY")
                    || !clientId.matches("[0-9]{5,40}")) {
                return Optional.empty();
            }
            // Also proves the private key itself can be read. A key without token_uri gets the standard one.
            o.addProperty("token_uri", TOKEN_URI);
            ServiceAccountCredentials.fromStream(new ByteArrayInputStream(
                    GSON.toJson(o).getBytes(StandardCharsets.UTF_8)));
            return Optional.of(new GmailKey(email.strip(), clientId.strip()));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String text(JsonObject o, String name) {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive() || e.getAsString().isBlank()) {
            return null;
        }
        return e.getAsString();
    }

    /** Never prints more than the account. */
    @Override
    public String toString() {
        return "GmailKey[" + email + "]";
    }
}
