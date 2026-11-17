package com.docs0.source.gmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.source.SourceAccessException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real service-account token flow (Google's auth library) against a local token endpoint, with a
 * throwaway key: proves the request asks for the right person and scope. It cannot prove how Google's
 * own endpoint words its refusals; those texts come from memory of its documented error codes.
 */
class GoogleTokenSourceTest {

    HttpServer server;
    final List<String> forms = new ArrayList<>();
    volatile int status = 200;
    volatile String reply = "{\"access_token\":\"ya29.fake-token\",\"token_type\":\"Bearer\",\"expires_in\":3599}";
    String key;

    private GoogleTokenSource source() {
        return new GoogleTokenSource("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/token", exchange -> {
            forms.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        key = TestKeys.json("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private JsonObject assertion() {
        String form = forms.get(forms.size() - 1);
        String assertion = null;
        for (String pair : form.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals("assertion")) {
                assertion = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
            if (kv[0].equals("grant_type")) {
                assertThat(URLDecoder.decode(kv[1], StandardCharsets.UTF_8))
                        .isEqualTo("urn:ietf:params:oauth:grant-type:jwt-bearer");
            }
        }
        String payload = new String(Base64.getUrlDecoder().decode(assertion.split("\\.")[1]), StandardCharsets.UTF_8);
        return new Gson().fromJson(payload, JsonObject.class);
    }

    @Test
    void theTokenIsRequestedForTheMailboxAndTheScopeAndComesBackWithItsExpiry() throws Exception {
        TokenSource.Token token = source().token(key, "ana@example.com", HttpGmailClient.GMAIL_SCOPE);

        assertThat(token.value()).isEqualTo("ya29.fake-token");
        assertThat(token.expiresAt()).isAfter(Instant.now().plusSeconds(3000)).isBefore(Instant.now().plusSeconds(3700));
        JsonObject claims = assertion();
        assertThat(claims.get("sub").getAsString()).isEqualTo("ana@example.com");
        assertThat(claims.get("iss").getAsString()).isEqualTo(TestKeys.EMAIL);
        assertThat(claims.get("scope").getAsString()).isEqualTo(HttpGmailClient.GMAIL_SCOPE);
        assertThat(token.toString()).doesNotContain("ya29");
    }

    @Test
    void theDirectoryScopeAndTheAdminAreUsedWhenAsked() throws Exception {
        source().token(key, "admin@example.com", HttpGmailClient.DIRECTORY_SCOPE);

        assertThat(assertion().get("sub").getAsString()).isEqualTo("admin@example.com");
        assertThat(assertion().get("scope").getAsString()).isEqualTo(HttpGmailClient.DIRECTORY_SCOPE);
    }

    @Test
    void anUnauthorisedClientMeansDelegationIsNotSetUp() {
        status = 400; // Google answers token-request refusals with 400
        reply = "{\"error\":\"unauthorized_client\",\"error_description\":\"Client is unauthorized to retrieve access tokens using this method, or client not authorized for any of the scopes requested.\"}";

        assertThatThrownBy(() -> source().token(key, "ana@example.com", HttpGmailClient.GMAIL_SCOPE))
                .isExactlyInstanceOf(SourceAccessException.class)
                .hasMessage("Gmail access isn't authorised for ana@example.com yet. Check domain-wide delegation in the Workspace Admin console.");
    }

    @Test
    void anUnknownOrSuspendedPersonIsNamedAndABadKeyStopsTheWholeRun() {
        status = 400;
        reply = "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid email or User ID\"}";
        assertThatThrownBy(() -> source().token(key, "ghost@example.com", HttpGmailClient.GMAIL_SCOPE))
                .isExactlyInstanceOf(SourceAccessException.class)
                .hasMessage("ghost@example.com can't be accessed. The person may not exist or may be suspended.");

        reply = "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}";
        assertThatThrownBy(() -> source().token(key, "ana@example.com", HttpGmailClient.GMAIL_SCOPE))
                .isInstanceOf(GmailClient.KeyRejectedException.class).hasMessage(HttpGmailClient.KEY_REJECTED);
    }

    @Test
    void aKeyThatCannotBeReadIsRejectedWithoutQuotingIt() {
        assertThatThrownBy(() -> source().token("{\"type\":\"service_account\",\"private_key\":\"nope\"}",
                "ana@example.com", HttpGmailClient.GMAIL_SCOPE))
                .isInstanceOf(GmailClient.KeyRejectedException.class).hasMessage(HttpGmailClient.KEY_REJECTED);
    }

    @Test
    void aKeyThatNamesAnotherTokenHostIsRefusedAndTheStandardOneIsAlwaysUsed() throws Exception {
        assertThat(GmailKey.parse(TestKeys.json("https://evil.example/token"))).isEmpty();
        assertThat(GmailKey.parse(TestKeys.json("http://169.254.169.254/latest"))).isEmpty();
        assertThat(GmailKey.parse(TestKeys.json("https://oauth2.googleapis.com.evil.example/token"))).isEmpty();
        // No token_uri at all is fine: Google's is used.
        String withoutUri = TestKeys.json().replaceAll(",\"token_uri\":\"[^\"]*\"", "");
        assertThat(withoutUri).doesNotContain("token_uri");
        assertThat(GmailKey.parse(withoutUri)).isPresent();

        // A key stored before this rule, naming another host, still only ever talks to Google.
        var credentials = GoogleTokenSource.credentials(TestKeys.json("https://evil.example/token"),
                GmailKey.TOKEN_URI, "ana@example.com", HttpGmailClient.GMAIL_SCOPE);
        assertThat(credentials.getTokenServerUri()).hasToString("https://oauth2.googleapis.com/token");
    }

    @Test
    void absurdlyLongKeyFieldsAreRefused() {
        assertThat(GmailKey.parse(TestKeys.json().replace("abcdef0123456789", "k".repeat(501)))).isEmpty();
        assertThat(GmailKey.parse(TestKeys.json().replace(TestKeys.EMAIL, "a".repeat(501) + "@example.com"))).isEmpty();
        assertThat(GmailKey.parse(TestKeys.json().replace("abcdef0123456789", "k".repeat(500)))).isPresent();
    }

    @Test
    void onlyARealServiceAccountKeyIsAccepted() {
        assertThat(GmailKey.parse(TestKeys.json())).contains(new GmailKey(TestKeys.EMAIL, TestKeys.CLIENT_ID));

        assertThat(GmailKey.parse(null)).isEmpty();
        assertThat(GmailKey.parse("   ")).isEmpty();
        assertThat(GmailKey.parse("not json at all")).isEmpty();
        assertThat(GmailKey.parse("[1,2]")).isEmpty();
        assertThat(GmailKey.parse(TestKeys.json().replace("service_account", "authorized_user"))).isEmpty();
        assertThat(GmailKey.parse(TestKeys.json().replace(TestKeys.EMAIL, ""))).isEmpty();
        assertThat(GmailKey.parse(TestKeys.json().replace(TestKeys.CLIENT_ID, "abc"))).isEmpty();
        assertThat(GmailKey.parse(TestKeys.json().replaceAll("\"private_key\":\"[^\"]*\"", "\"private_key\":\"-----BEGIN PRIVATE KEY-----\\\\nAAAA\\\\n-----END PRIVATE KEY-----\\\\n\""))).isEmpty();
        assertThat(GmailKey.parse("{\"type\":\"service_account\"}")).isEmpty();
        assertThat(GmailKey.parse("x".repeat(30_000))).isEmpty();
        assertThat(GmailKey.parse(TestKeys.json()).orElseThrow().toString()).doesNotContain("PRIVATE").contains(TestKeys.EMAIL);
    }
}
