package com.docs0.slack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Slack's install endpoint, scripted. */
public class FakeSlackOAuth implements SlackOAuth {

    public static final String BOT_TOKEN = "xoxb-fake-secret-token";

    public final List<String> exchangedCodes = new ArrayList<>();
    public Installed next;
    public boolean fails;

    public FakeSlackOAuth() {
        reset();
    }

    public void reset() {
        exchangedCodes.clear();
        next = new Installed("T_ACME", "Acme Inc.", BOT_TOKEN, "UBOTACME", "chat:write,users:read", "UINSTALLER");
        fails = false;
    }

    @Override
    public String authorizeUrl(String state) {
        return "https://slack.example/authorize?state=" + state;
    }

    @Override
    public Installed exchange(String code) throws IOException {
        exchangedCodes.add(code);
        if (fails) {
            throw new IOException("Slack call failed: HTTP 200 (invalid_code)");
        }
        return next;
    }
}
