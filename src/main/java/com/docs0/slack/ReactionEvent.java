package com.docs0.slack;

/**
 * A reaction added to or removed from a message, free of Bolt types.
 *
 * @param reaction the emoji name without colons, e.g. "+1" or "thumbsdown::skin-tone-3"
 * @param itemTs   the ts of the message that was reacted to
 * @param teamId   the Slack workspace of the reaction; the gateway resolves the tenant from it. May be null
 */
public record ReactionEvent(boolean added, String channel, String user, String reaction, String itemTs, String teamId) {

    /** Without a workspace: for code that has already resolved the tenant itself. */
    public ReactionEvent(boolean added, String channel, String user, String reaction, String itemTs) {
        this(added, channel, user, reaction, itemTs, null);
    }

    public ReactionEvent withTeam(String team) {
        return new ReactionEvent(added, channel, user, reaction, itemTs, team);
    }
}
