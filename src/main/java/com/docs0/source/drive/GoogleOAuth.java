package com.docs0.source.drive;

import java.io.IOException;

/** The network edge to Google's OAuth endpoints. Tests substitute a fake. */
public interface GoogleOAuth {

    String DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly";

    /**
     * What Google returned for an authorization code.
     *
     * @param refreshToken null when Google did not issue one
     * @param accountEmail the signed-in account, null when Google did not say
     * @param scope        the space-separated scopes actually granted
     */
    record Grant(String refreshToken, String accountEmail, String scope) {

        public boolean canReadDrive() {
            return scope != null && (" " + scope + " ").contains(" " + DRIVE_READONLY_SCOPE + " ");
        }

        /** Never prints the token. */
        @Override
        public String toString() {
            return "Grant[accountEmail=" + accountEmail + ", scope=" + scope + "]";
        }
    }

    /** The Google page the admin is sent to. {@code state} comes back unchanged on the callback. */
    String authorizationUrl(String state);

    /** Exchanges the code from the callback for a grant. */
    Grant exchange(String code) throws IOException;
}
