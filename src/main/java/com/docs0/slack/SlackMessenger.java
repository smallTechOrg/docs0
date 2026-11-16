package com.docs0.slack;

import com.slack.api.model.block.LayoutBlock;
import java.util.List;
import java.util.Set;

/** The Slack Web API calls docs0 makes. An interface so tests can substitute a fake. */
public interface SlackMessenger {

    /**
     * Posts a message and returns its ts. {@code threadTs} is null for an unthreaded message.
     * {@code blocks} may be null or empty for a plain text message; {@code text} is then the
     * message, otherwise it is the notification fallback.
     */
    String postMessage(String channel, String threadTs, String text, List<LayoutBlock> blocks);

    /** Replaces the content of an existing message. */
    void updateMessage(String channel, String ts, String text, List<LayoutBlock> blocks);

    /** User ids of every member of a channel. */
    Set<String> channelMembers(String channel);

    /** The name people see for a user: display name, else real name, else the user name. */
    String userDisplayName(String userId);

    /** docs0's own Slack user id. */
    String botUserId();

    /** A Slack call that did not succeed. {@code code} is Slack's error code or a short reason; never a secret. */
    class SlackCallException extends RuntimeException {

        private final String code;

        public SlackCallException(String method, String code, Throwable cause) {
            super(method + " failed: " + code, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
