package com.docs0.slack;

import java.io.IOException;

/** The network edge of "Add to Slack" (OAuth v2). Tests substitute a fake. */
public interface SlackOAuth {

    /**
     * What Slack returned for an install code.
     *
     * @param installerUserId the person who clicked Add to Slack
     */
    record Installed(String teamId, String teamName, String accessToken, String botUserId, String scopes,
            String installerUserId) {

        /** Never prints the token. */
        @Override
        public String toString() {
            return "Installed[teamId=" + teamId + ", teamName=" + teamName + ", botUserId=" + botUserId + "]";
        }
    }

    /** The Slack page the installer is sent to. {@code state} comes back unchanged on the callback. */
    String authorizeUrl(String state);

    /** Exchanges the code from the callback for the workspace's bot token. */
    Installed exchange(String code) throws IOException;
}
