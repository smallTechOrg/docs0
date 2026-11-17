package com.docs0.source.drive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The real OAuth client against a local stand-in for Google's token and userinfo endpoints. */
class HttpGoogleOAuthTest {

    private static final String REDIRECT = "http://localhost:8080/admin/sources/google/callback";

    HttpServer google;
    HttpGoogleOAuth oauth;

    int tokenStatus = 200;
    String tokenBody = "{}";
    String tokenRequestBody;
    String tokenContentType;
    String userinfoBody = "{}";
    String userinfoAuthorization;

    @BeforeEach
    void startServer() throws IOException {
        google = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        google.createContext("/token", exchange -> {
            tokenRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            tokenContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            byte[] body = tokenBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(tokenStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        google.createContext("/userinfo", exchange -> {
            userinfoAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = userinfoBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        google.start();
        String base = "http://127.0.0.1:" + google.getAddress().getPort();
        oauth = new HttpGoogleOAuth("client id+1", "s3cret&=", REDIRECT, base + "/token", base + "/userinfo");
    }

    @AfterEach
    void stopServer() {
        google.stop(0);
    }

    @Test
    void authorizationUrlAsksForOfflineReadOnlyDriveAccessWithConsent() {
        URI url = URI.create(oauth.authorizationUrl("state/+ 123"));

        assertThat(url.getScheme() + "://" + url.getHost() + url.getPath())
                .isEqualTo("https://accounts.google.com/o/oauth2/v2/auth");
        Map<String, String> params = form(url.getRawQuery());
        assertThat(params).containsOnlyKeys("client_id", "redirect_uri", "response_type", "scope", "access_type",
                "prompt", "include_granted_scopes", "state");
        assertThat(params.get("client_id")).isEqualTo("client id+1");
        assertThat(params.get("redirect_uri")).isEqualTo(REDIRECT);
        assertThat(params.get("response_type")).isEqualTo("code");
        assertThat(params.get("scope").split(" "))
                .containsExactlyInAnyOrder("https://www.googleapis.com/auth/drive.readonly", "openid", "email");
        assertThat(params.get("access_type")).isEqualTo("offline");
        assertThat(params.get("prompt")).isEqualTo("consent");
        assertThat(params.get("include_granted_scopes")).isEqualTo("true");
        assertThat(params.get("state")).isEqualTo("state/+ 123");
        assertThat(url.toString()).doesNotContain("s3cret");
    }

    @Test
    void exchangePostsTheCodeAsAFormAndReadsTheGrantWithTheEmailFromTheIdToken() throws IOException {
        tokenBody = """
                {"access_token": "at-1", "expires_in": 3599, "refresh_token": "rt-1", "token_type": "Bearer",
                 "scope": "openid https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/userinfo.email",
                 "id_token": "%s"}
                """.formatted(idToken("{\"sub\":\"1\",\"email\":\"asha@example.com\",\"email_verified\":true}"));

        GoogleOAuth.Grant grant = oauth.exchange("4/code+with/odd&chars=");

        assertThat(grant.refreshToken()).isEqualTo("rt-1");
        assertThat(grant.accountEmail()).isEqualTo("asha@example.com");
        assertThat(grant.canReadDrive()).isTrue();
        assertThat(grant.toString()).doesNotContain("rt-1");
        assertThat(tokenContentType).isEqualTo("application/x-www-form-urlencoded");
        assertThat(form(tokenRequestBody)).isEqualTo(Map.of(
                "code", "4/code+with/odd&chars=",
                "client_id", "client id+1",
                "client_secret", "s3cret&=",
                "redirect_uri", REDIRECT,
                "grant_type", "authorization_code"));
        // The ID token named the account, so userinfo was not needed.
        assertThat(userinfoAuthorization).isNull();
    }

    @Test
    void emailComesFromUserinfoWhenThereIsNoIdToken() throws IOException {
        tokenBody = "{\"access_token\": \"at-2\", \"refresh_token\": \"rt-2\", "
                + "\"scope\": \"https://www.googleapis.com/auth/drive.readonly\"}";
        userinfoBody = "{\"sub\": \"1\", \"email\": \"ben@example.com\"}";

        GoogleOAuth.Grant grant = oauth.exchange("code");

        assertThat(grant.accountEmail()).isEqualTo("ben@example.com");
        assertThat(userinfoAuthorization).isEqualTo("Bearer at-2");
    }

    @Test
    void aResponseWithoutARefreshTokenOrDriveScopeIsReportedAsSuch() throws IOException {
        tokenBody = "{\"access_token\": \"at-3\", \"scope\": \"openid email\", \"id_token\": \"garbage\"}";

        GoogleOAuth.Grant grant = oauth.exchange("code");

        assertThat(grant.refreshToken()).isNull();
        assertThat(grant.accountEmail()).isNull();
        assertThat(grant.canReadDrive()).isFalse();
    }

    @Test
    void aRefusedExchangeThrowsWithGooglesErrorCodeAndNoSecrets() {
        tokenStatus = 400;
        tokenBody = "{\"error\": \"invalid_grant\", \"error_description\": \"Bad Request for s3cret&= the-code\"}";

        assertThatThrownBy(() -> oauth.exchange("the-code"))
                .isInstanceOf(IOException.class)
                .hasMessage("Google token exchange failed: HTTP 400 (invalid_grant)");
    }

    @Test
    void canReadDriveLooksForTheExactScope() {
        assertThat(new GoogleOAuth.Grant("rt", "a@b.c", GoogleOAuth.DRIVE_READONLY_SCOPE).canReadDrive()).isTrue();
        assertThat(new GoogleOAuth.Grant("rt", "a@b.c", "https://www.googleapis.com/auth/drive.readonly.other")
                .canReadDrive()).isFalse();
        assertThat(new GoogleOAuth.Grant("rt", "a@b.c", null).canReadDrive()).isFalse();
    }

    private static String idToken(String payloadJson) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    private static Map<String, String> form(String encoded) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }
}
