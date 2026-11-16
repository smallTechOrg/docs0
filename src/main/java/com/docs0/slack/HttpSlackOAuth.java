package com.docs0.slack;

import com.docs0.config.Docs0Properties;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Slack OAuth v2 install over plain HTTPS: oauth.v2.access. Errors carry only Slack's short error code. */
@Component
public class HttpSlackOAuth implements SlackOAuth {

    static final String AUTHORIZE_URI = "https://slack.com/oauth/v2/authorize";
    static final String ACCESS_URI = "https://slack.com/api/oauth.v2.access";

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String accessUri;

    @Autowired
    public HttpSlackOAuth(Docs0Properties props) {
        this(props.slack() == null ? null : props.slack().clientId(),
                props.slack() == null ? null : props.slack().clientSecret(),
                props.publicBaseUrl() + "/slack/oauth/callback", ACCESS_URI);
    }

    HttpSlackOAuth(String clientId, String clientSecret, String redirectUri, String accessUri) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.redirectUri = redirectUri;
        this.accessUri = accessUri;
    }

    @Override
    public String authorizeUrl(String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", clientId);
        params.put("scope", String.join(",", SlackScopes.BOT));
        params.put("redirect_uri", redirectUri);
        params.put("state", state);
        return AUTHORIZE_URI + "?" + SlackHttp.form(params);
    }

    @Override
    public Installed exchange(String code) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", code);
        params.put("client_id", clientId);
        params.put("client_secret", clientSecret);
        params.put("redirect_uri", redirectUri);
        JsonObject body = SlackHttp.post(accessUri, params);
        if (SlackHttp.bool(body, "is_enterprise_install")) {
            throw new IOException("Slack call failed: organization-wide installs are not supported");
        }
        JsonObject team = SlackHttp.object(body, "team");
        String botToken = SlackHttp.text(body, "access_token");
        String teamId = SlackHttp.text(team, "id");
        if (botToken == null || teamId == null) {
            throw new IOException("Slack call failed: the answer had no bot token or workspace");
        }
        return new Installed(teamId, SlackHttp.text(team, "name"), botToken, SlackHttp.text(body, "bot_user_id"),
                SlackHttp.text(body, "scope"), SlackHttp.text(SlackHttp.object(body, "authed_user"), "id"));
    }
}
