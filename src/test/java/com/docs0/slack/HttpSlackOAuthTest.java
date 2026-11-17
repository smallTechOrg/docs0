package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The real Slack OAuth and OpenID clients against a local stand-in for slack.com. The answers are as documented, not recorded. */
class HttpSlackOAuthTest {

    private static final String REDIRECT = "https://docs0.example/slack/oauth/callback";
    private static final String LOGIN_REDIRECT = "https://docs0.example/admin/login/callback";

    HttpServer slack;
    String base;
    String accessBody = "{}";
    int accessStatus = 200;
    String accessRequest;
    String tokenBody = "{}";
    String tokenRequest;
    String userinfoBody = "{}";
    String userinfoAuthorization;

    @BeforeEach
    void start() throws IOException {
        slack = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        slack.createContext("/access", exchange -> {
            accessRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            reply(exchange, accessStatus, accessBody);
        });
        slack.createContext("/token", exchange -> {
            tokenRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            reply(exchange, 200, tokenBody);
        });
        slack.createContext("/userinfo", exchange -> {
            userinfoAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            reply(exchange, 200, userinfoBody);
        });
        slack.start();
        base = "http://127.0.0.1:" + slack.getAddress().getPort();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @AfterEach
    void stop() {
        slack.stop(0);
    }

    private static Map<String, String> form(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    @Test
    void theInstallLinkCarriesTheClientTheScopesTheRedirectAndTheState() {
        URI url = URI.create(new HttpSlackOAuth("client id+1", "s3cret", REDIRECT, base + "/access")
                .authorizeUrl("state/+ 1"));

        assertThat(url.getScheme() + "://" + url.getHost() + url.getPath())
                .isEqualTo("https://slack.com/oauth/v2/authorize");
        Map<String, String> params = form(url.getRawQuery());
        assertThat(params).containsOnlyKeys("client_id", "scope", "redirect_uri", "state");
        assertThat(params).containsEntry("client_id", "client id+1").containsEntry("redirect_uri", REDIRECT)
                .containsEntry("state", "state/+ 1");
        assertThat(params.get("scope").split(",")).containsExactlyInAnyOrderElementsOf(SlackScopes.BOT);
    }

    @Test
    void exchangingACodeReadsTheWorkspaceTheBotTokenAndTheInstaller() throws Exception {
        accessBody = "{\"ok\":true,\"access_token\":\"xoxb-secret\",\"token_type\":\"bot\","
                + "\"scope\":\"chat:write,users:read\",\"bot_user_id\":\"UBOT\",\"app_id\":\"A1\","
                + "\"team\":{\"name\":\"Acme Inc.\",\"id\":\"T1\"},\"enterprise\":null,"
                + "\"authed_user\":{\"id\":\"UINST\"},\"is_enterprise_install\":false}";

        SlackOAuth.Installed installed = new HttpSlackOAuth("cid", "s3cret&=", REDIRECT, base + "/access")
                .exchange("the+code");

        assertThat(installed).isEqualTo(new SlackOAuth.Installed("T1", "Acme Inc.", "xoxb-secret", "UBOT",
                "chat:write,users:read", "UINST"));
        Map<String, String> sent = form(accessRequest);
        assertThat(sent).containsEntry("code", "the+code").containsEntry("client_id", "cid")
                .containsEntry("client_secret", "s3cret&=").containsEntry("redirect_uri", REDIRECT);
        assertThat(installed.toString()).doesNotContain("xoxb-secret");
    }

    @Test
    void failuresCarryOnlySlacksShortErrorCodeNeverTheCodeOrSecret() {
        accessBody = "{\"ok\":false,\"error\":\"invalid_code\"}";

        assertThatThrownBy(() -> new HttpSlackOAuth("cid", "s3cret", REDIRECT, base + "/access").exchange("abc123"))
                .isInstanceOf(IOException.class).hasMessageContaining("invalid_code")
                .hasMessageNotContaining("abc123").hasMessageNotContaining("s3cret");
        accessStatus = 500;
        accessBody = "not json";
        assertThatThrownBy(() -> new HttpSlackOAuth("cid", "s3cret", REDIRECT, base + "/access").exchange("abc123"))
                .isInstanceOf(IOException.class).hasMessageContaining("HTTP 500");
    }

    @Test
    void anAnswerWithoutABotTokenOrWorkspaceAndAnOrganizationInstallAreRefused() {
        HttpSlackOAuth oauth = new HttpSlackOAuth("cid", "s", REDIRECT, base + "/access");
        accessBody = "{\"ok\":true,\"team\":{\"id\":\"T1\"}}";
        assertThatThrownBy(() -> oauth.exchange("c")).isInstanceOf(IOException.class);
        accessBody = "{\"ok\":true,\"access_token\":\"xoxb-1\",\"team\":{\"id\":\"T1\"},\"is_enterprise_install\":true}";
        assertThatThrownBy(() -> oauth.exchange("c")).isInstanceOf(IOException.class)
                .hasMessageContaining("organization-wide");
    }

    @Test
    void theSignInLinkAsksForOpenIdEmailAndProfileWithStateAndNonce() {
        URI url = URI.create(new HttpSlackSignIn("cid", "s", LOGIN_REDIRECT, base + "/token", base + "/userinfo")
                .authorizeUrl("st", "no"));

        assertThat(url.getScheme() + "://" + url.getHost() + url.getPath())
                .isEqualTo("https://slack.com/openid/connect/authorize");
        Map<String, String> params = form(url.getRawQuery());
        assertThat(params).containsEntry("response_type", "code").containsEntry("scope", "openid email profile")
                .containsEntry("client_id", "cid").containsEntry("redirect_uri", LOGIN_REDIRECT)
                .containsEntry("state", "st").containsEntry("nonce", "no");
    }

    @Test
    void signingInReadsTheEmailWorkspaceUserAndNonce() throws Exception {
        String claims = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"nonce\":\"no\",\"email\":\"ignored@x.example\"}".getBytes(StandardCharsets.UTF_8));
        tokenBody = "{\"ok\":true,\"access_token\":\"xoxp-user\",\"token_type\":\"Bearer\",\"id_token\":\"h." + claims
                + ".s\"}";
        userinfoBody = "{\"ok\":true,\"sub\":\"UINA\",\"https://slack.com/user_id\":\"UINA\","
                + "\"https://slack.com/team_id\":\"T1\",\"email\":\"ina@acme.example\",\"email_verified\":true,"
                + "\"name\":\"Ina Alvarez\"}";

        SlackSignIn.Identity who = new HttpSlackSignIn("cid", "s3cret", LOGIN_REDIRECT, base + "/token",
                base + "/userinfo").exchange("the-code");

        assertThat(who).isEqualTo(new SlackSignIn.Identity("ina@acme.example", "T1", "UINA", "Ina Alvarez", "no"));
        assertThat(form(tokenRequest)).containsEntry("code", "the-code").containsEntry("client_secret", "s3cret")
                .containsEntry("grant_type", "authorization_code").containsEntry("redirect_uri", LOGIN_REDIRECT);
        assertThat(userinfoAuthorization).isEqualTo("Bearer xoxp-user");
    }

    @Test
    void signInFailuresAreShortAndAnIdTokenWithoutANonceYieldsNone() throws Exception {
        HttpSlackSignIn signIn = new HttpSlackSignIn("cid", "s3cret", LOGIN_REDIRECT, base + "/token", base + "/userinfo");
        tokenBody = "{\"ok\":false,\"error\":\"invalid_code\"}";
        assertThatThrownBy(() -> signIn.exchange("abc")).isInstanceOf(IOException.class)
                .hasMessageContaining("invalid_code").hasMessageNotContaining("abc");

        tokenBody = "{\"ok\":true,\"access_token\":\"xoxp-user\"}";
        userinfoBody = "{\"ok\":true,\"email\":\"a@b.example\",\"https://slack.com/team_id\":\"T1\"}";
        assertThat(signIn.exchange("abc").nonce()).isNull();
        assertThat(HttpSlackSignIn.idTokenClaims("garbage").size()).isZero();
    }
}
