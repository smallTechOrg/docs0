package com.docs0.slack;

import com.docs0.config.Docs0Properties;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Sign in with Slack over plain HTTPS: openid.connect.token, then openid.connect.userInfo. */
@Component
public class HttpSlackSignIn implements SlackSignIn {

    static final String AUTHORIZE_URI = "https://slack.com/openid/connect/authorize";
    static final String TOKEN_URI = "https://slack.com/api/openid.connect.token";
    static final String USERINFO_URI = "https://slack.com/api/openid.connect.userInfo";

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String tokenUri;
    private final String userinfoUri;

    @Autowired
    public HttpSlackSignIn(Docs0Properties props) {
        this(props.slack() == null ? null : props.slack().clientId(),
                props.slack() == null ? null : props.slack().clientSecret(),
                props.publicBaseUrl() + "/admin/login/callback", TOKEN_URI, USERINFO_URI);
    }

    HttpSlackSignIn(String clientId, String clientSecret, String redirectUri, String tokenUri, String userinfoUri) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.redirectUri = redirectUri;
        this.tokenUri = tokenUri;
        this.userinfoUri = userinfoUri;
    }

    @Override
    public String authorizeUrl(String state, String nonce) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("scope", String.join(" ", SlackScopes.SIGN_IN));
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("state", state);
        params.put("nonce", nonce);
        return AUTHORIZE_URI + "?" + SlackHttp.form(params);
    }

    @Override
    public Identity exchange(String code) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", code);
        params.put("client_id", clientId);
        params.put("client_secret", clientSecret);
        params.put("redirect_uri", redirectUri);
        params.put("grant_type", "authorization_code");
        JsonObject token = SlackHttp.post(tokenUri, params);
        String accessToken = SlackHttp.text(token, "access_token");
        if (accessToken == null) {
            throw new IOException("Slack call failed: the answer had no access token");
        }
        JsonObject user = SlackHttp.get(userinfoUri, accessToken);
        // The ID token came straight from Slack over TLS, so its payload is read without checking the signature.
        String nonce = SlackHttp.text(idTokenClaims(SlackHttp.text(token, "id_token")), "nonce");
        return new Identity(SlackHttp.text(user, "email"), SlackHttp.text(user, "https://slack.com/team_id"),
                SlackHttp.text(user, "https://slack.com/user_id"), SlackHttp.text(user, "name"), nonce);
    }

    static JsonObject idTokenClaims(String idToken) {
        if (idToken == null) {
            return new JsonObject();
        }
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            return new JsonObject();
        }
        try {
            return SlackHttp.json(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return new JsonObject();
        }
    }
}
