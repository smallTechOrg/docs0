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

    /** A message only {@code user} sees. {@code threadTs} is null for an unthreaded message. */
    void postEphemeral(String channel, String threadTs, String user, String text);

    /** Replaces the content of an existing message. */
    void updateMessage(String channel, String ts, String text, List<LayoutBlock> blocks);

    /** User ids of every member of a channel. */
    Set<String> channelMembers(String channel);

    /** The name people see for a user: display name, else real name, else the user name. */
    String userDisplayName(String userId);

    /** docs0's own Slack user id. */
    String botUserId();

    // ---- reading workspace content (Slack history, identities) ----------------------------------------

    /** A public or private channel the bot can see. {@code member} is true when the bot is in it. */
    record ChannelInfo(String id, String name, boolean isPrivate, boolean member, int memberCount) {}

    /**
     * One message as Slack returns it. {@code user} is null for some bot posts; {@code replyCount} and
     * {@code latestReply} are set on the root of a thread; {@code editedTs} only on an edited message.
     */
    record SlackMessage(String ts, String threadTs, String user, String botId, String username, String subtype,
            String text, int replyCount, String latestReply, String editedTs, List<String> fileTitles) {}

    /** A Slack user as docs0 needs them. {@code email} is null when the app may not read emails. */
    record SlackUser(String id, String email, String displayName, boolean bot, boolean deleted) {}

    /** Non-archived public and private channels the bot can see. Private ones are only those it was invited to. */
    List<ChannelInfo> listChannels();

    /** Top-level messages of a channel since {@code oldest} (Slack returns replies only through {@link #replies}). */
    List<SlackMessage> history(String channel, java.time.Instant oldest);

    /** A thread: the root message first, then every reply. */
    List<SlackMessage> replies(String channel, String threadTs);

    /** A link to one message. */
    String permalink(String channel, String ts);

    /** Joins a public channel. */
    void joinChannel(String channel);

    /** Who a user is. */
    SlackUser userInfo(String userId);

    /** True when Slack says this person is an admin or owner of the workspace (users.info). */
    default boolean isWorkspaceAdmin(String userId) {
        return false;
    }

    /** The Slack user with this email, or empty when there is none (or the workspace hides emails from the app). */
    java.util.Optional<String> lookupByEmail(String email);

    /** Opens (or finds) the direct message with a user and returns its channel id. Needs the im:write scope. */
    String openDm(String userId);

    /** The scopes granted to the bot token, or an empty set when Slack did not say. */
    Set<String> botScopes();

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
