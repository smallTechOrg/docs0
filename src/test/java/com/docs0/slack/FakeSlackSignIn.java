package com.docs0.slack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Sign in with Slack, scripted. Echoes the nonce it was given unless told otherwise. */
public class FakeSlackSignIn implements SlackSignIn {

    public final List<String> exchangedCodes = new ArrayList<>();
    public String lastNonce;
    /** Who signs in next; its nonce is replaced by the last one issued unless {@link #wrongNonce} is set. */
    public Identity next;
    public String wrongNonce;
    public boolean fails;

    public FakeSlackSignIn() {
        reset();
    }

    public void reset() {
        exchangedCodes.clear();
        lastNonce = null;
        next = new Identity("ina@acme.example", "T_ACME", "UINA", "Ina", null);
        wrongNonce = null;
        fails = false;
    }

    public void signsInAs(String email, String teamId, String userId, String name) {
        next = new Identity(email, teamId, userId, name, null);
    }

    @Override
    public String authorizeUrl(String state, String nonce) {
        lastNonce = nonce;
        return "https://slack.example/openid?state=" + state;
    }

    @Override
    public Identity exchange(String code) throws IOException {
        exchangedCodes.add(code);
        if (fails) {
            throw new IOException("Slack call failed: HTTP 200 (invalid_code)");
        }
        return new Identity(next.email(), next.teamId(), next.userId(), next.name(),
                wrongNonce != null ? wrongNonce : lastNonce);
    }
}
