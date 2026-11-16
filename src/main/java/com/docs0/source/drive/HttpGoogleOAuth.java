package com.docs0.source.drive;

import com.docs0.config.Docs0Properties;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Google's OAuth 2.0 authorization-code flow for a web server app, over plain HTTPS.
 * Never logs the code, the client secret or any token; errors carry only the HTTP status
 * and Google's short error code.
 */
@Component
public class HttpGoogleOAuth implements GoogleOAuth {

    static final String AUTH_URI = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    static final String USERINFO_URI = "https://openidconnect.googleapis.com/v1/userinfo";
    static final String SCOPES = DRIVE_READONLY_SCOPE + " openid email";

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String tokenUri;
    private final String userinfoUri;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @Autowired
    public HttpGoogleOAuth(Docs0Properties props) {
        this(props.google() == null ? null : props.google().oauthClientId(),
                props.google() == null ? null : props.google().oauthClientSecret(),
                props.googleRedirectUri(), TOKEN_URI, USERINFO_URI);
    }

    HttpGoogleOAuth(String clientId, String clientSecret, String redirectUri, String tokenUri, String userinfoUri) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.redirectUri = redirectUri;
        this.tokenUri = tokenUri;
        this.userinfoUri = userinfoUri;
    }

    @Override
    public String authorizationUrl(String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("response_type", "code");
        params.put("scope", SCOPES);
        params.put("access_type", "offline");
        params.put("prompt", "consent");
        params.put("include_granted_scopes", "true");
        params.put("state", state);
        return AUTH_URI + "?" + form(params);
    }

    @Override
    public Grant exchange(String code) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", code);
        params.put("client_id", clientId);
        params.put("client_secret", clientSecret);
        params.put("redirect_uri", redirectUri);
        params.put("grant_type", "authorization_code");
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(params)))
                .build();
        HttpResponse<String> response = send(request);
        JsonObject body = json(response.body());
        if (response.statusCode() != 200) {
            throw new IOException("Google token exchange failed: HTTP " + response.statusCode()
                    + errorCode(body));
        }
        String accessToken = text(body, "access_token");
        String email = emailFromIdToken(text(body, "id_token"));
        if (email == null && accessToken != null) {
            email = emailFromUserinfo(accessToken);
        }
        return new Grant(text(body, "refresh_token"), email, text(body, "scope"));
    }

    /** The ID token came straight from Google over TLS, so its payload is read without checking the signature. */
    static String emailFromIdToken(String idToken) {
        if (idToken == null) {
            return null;
        }
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return text(json(payload), "email");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String emailFromUserinfo(String accessToken) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(userinfoUri))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        return response.statusCode() == 200 ? text(json(response.body()), "email") : null;
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling Google");
        }
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    /** An empty object when the body is not a JSON object. */
    private static JsonObject json(String body) {
        try {
            var parsed = JsonParser.parseString(body == null ? "" : body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static String text(JsonObject object, String field) {
        var value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        String text = value.getAsString();
        return text.isBlank() ? null : text;
    }

    /** Google's short error code (e.g. invalid_grant), which holds no secret. */
    private static String errorCode(JsonObject body) {
        String error = text(body, "error");
        return error == null ? "" : " (" + error.replaceAll("[^A-Za-z0-9_]", "") + ")";
    }
}
