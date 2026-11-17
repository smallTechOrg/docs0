package com.docs0.source.drive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Google's OAuth endpoints, scripted. Records the codes it was asked to exchange. */
public class FakeGoogleOAuth implements GoogleOAuth {

    public static final String ALL_SCOPES = "openid https://www.googleapis.com/auth/userinfo.email "
            + DRIVE_READONLY_SCOPE;

    public final List<String> exchangedCodes = new ArrayList<>();
    public Grant grant;
    public boolean fails;

    public FakeGoogleOAuth() {
        reset();
    }

    public void reset() {
        exchangedCodes.clear();
        grant = new Grant("refresh-token-1", "asha@example.com", ALL_SCOPES);
        fails = false;
    }

    @Override
    public String authorizationUrl(String state) {
        return "https://accounts.example/auth?state=" + state;
    }

    @Override
    public Grant exchange(String code) throws IOException {
        exchangedCodes.add(code);
        if (fails) {
            throw new IOException("Google token exchange failed: HTTP 400 (invalid_grant)");
        }
        return grant;
    }
}
