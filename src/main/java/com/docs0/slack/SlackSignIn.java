package com.docs0.slack;

import java.io.IOException;

/** The network edge of "Sign in with Slack" (OpenID Connect). Tests substitute a fake. */
public interface SlackSignIn {

    /**
     * Who Slack says signed in.
     *
     * @param nonce the nonce Slack echoed back in the ID token; null when it did not
     */
    record Identity(String email, String teamId, String userId, String name, String nonce) {}

    /** The Slack page the admin is sent to. {@code state} and {@code nonce} come back on the callback. */
    String authorizeUrl(String state, String nonce);

    /** Exchanges the code for the signed-in person. */
    Identity exchange(String code) throws IOException;
}
