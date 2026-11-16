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
        TokenSource.Token token = new GoogleTokenSource().token(key, "ana@example.com", HttpGmailClient.GMAIL_SCOPE);

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
        new GoogleTokenSource().token(key, "admin@example.com", HttpGmailClient.DIRECTORY_SCOPE);

        assertThat(assertion().get("sub").getAsString()).isEqualTo("admin@example.com");
        assertThat(assertion().get("scope").getAsString()).isEqualTo(HttpGmailClient.DIRECTORY_SCOPE);
    }

    @Test
    void anUnauthorisedClientMeansDelegationIsNotSetUp() {
        status = 400; // Google answers token-request refusals with 400
        reply = "{\"error\":\"unauthorized_client\",\"error_description\":\"Client is unauthorized to retrieve access tokens using this method, or client not authorized for any of the scopes requested.\"}";

        assertThatThrownBy(() -> new GoogleTokenSource().token(key, "ana@example.com", HttpGmailClient.GMAIL_SCOPE))
                .isExactlyInstanceOf(SourceAccessException.class)
                .hasMessage("Gmail access isn't authorised for ana@example.com yet. Check domain-wide delegation in the Workspace Admin console.");
    }

    @Test
    void anUnknownOrSuspendedPersonIsNamedAndABadKeyStopsTheWholeRun() {
        status = 400;
        reply = "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid email or User ID\"}";
        assertThatThrownBy(() -> new GoogleTokenSource().token(key, "ghost@example.com", HttpGmailClient.GMAIL_SCOPE))
                .isExactlyInstanceOf(SourceAccessException.class)
                .hasMessage("ghost@example.com can't be accessed. The person may not exist or may be suspended.");

        reply = "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}";
        assertThatThrownBy(() -> new GoogleTokenSource().token(key, "ana@example.com", HttpGmailClient.GMAIL_SCOPE))
                .isInstanceOf(GmailClient.KeyRejectedException.class).hasMessage(HttpGmailClient.KEY_REJECTED);
    }

    @Test
    void aKeyThatCannotBeReadIsRejectedWithoutQuotingIt() {
        assertThatThrownBy(() -> new GoogleTokenSource().token("{\"type\":\"service_account\",\"private_key\":\"nope\"}",
                "ana@example.com", HttpGmailClient.GMAIL_SCOPE))
                .isInstanceOf(GmailClient.KeyRejectedException.class).hasMessage(HttpGmailClient.KEY_REJECTED);
    }

    @Test
    void onlyARealServiceAccountKeyIsAccepted() {
        assertThat(GmailKey.parse(key)).contains(new GmailKey(TestKeys.EMAIL, TestKeys.CLIENT_ID));

        assertThat(GmailKey.parse(null)).isEmpty();
        assertThat(GmailKey.parse("   ")).isEmpty();
        assertThat(GmailKey.parse("not json at all")).isEmpty();
        assertThat(GmailKey.parse("[1,2]")).isEmpty();
        assertThat(GmailKey.parse(key.replace("service_account", "authorized_user"))).isEmpty();
        assertThat(GmailKey.parse(key.replace(TestKeys.EMAIL, ""))).isEmpty();
        assertThat(GmailKey.parse(key.replace(TestKeys.CLIENT_ID, "abc"))).isEmpty();
        assertThat(GmailKey.parse(key.replaceAll("\"private_key\":\"[^\"]*\"", "\"private_key\":\"-----BEGIN PRIVATE KEY-----\\\\nAAAA\\\\n-----END PRIVATE KEY-----\\\\n\""))).isEmpty();
        assertThat(GmailKey.parse("{\"type\":\"service_account\"}")).isEmpty();
        assertThat(GmailKey.parse("x".repeat(30_000))).isEmpty();
        assertThat(GmailKey.parse(key).orElseThrow().toString()).doesNotContain("PRIVATE").contains(TestKeys.EMAIL);
    }
}
