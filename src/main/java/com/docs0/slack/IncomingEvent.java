package com.docs0.slack;

/**
 * One Slack message that may be a question for docs0, free of Bolt types.
 *
 * @param threadTs the thread the message was written in, or null when it is top-level
 * @param eventId  Slack's event id, used to drop duplicate deliveries; may be null
 * @param subtype  Slack message subtype (edits, deletions, joins, ...), or null for a normal message
 * @param fromBot  true when a bot or app wrote the message
 * @param teamId   the Slack workspace the message was written in; the gateway resolves the tenant from it. May be null
 */
public record IncomingEvent(
        Kind kind,
        String channel,
        String user,
        String text,
        String ts,
        String threadTs,
        String eventId,
        String subtype,
        boolean fromBot,
        String teamId) {

    /** Without a workspace: for code that has already resolved the tenant itself. */
    public IncomingEvent(Kind kind, String channel, String user, String text, String ts, String threadTs,
            String eventId, String subtype, boolean fromBot) {
        this(kind, channel, user, text, ts, threadTs, eventId, subtype, fromBot, null);
    }

    public IncomingEvent withTeam(String team) {
        return new IncomingEvent(kind, channel, user, text, ts, threadTs, eventId, subtype, fromBot, team);
    }

    public enum Kind {
        /** docs0 was @mentioned in a channel. */
        MENTION,
        /** Any message in a DM with docs0. */
        DIRECT_MESSAGE,
        /** A reply without a mention in a channel thread. */
        THREAD_REPLY,
        /** A top-level message in a public or private channel that does not mention docs0. */
        CHANNEL_MESSAGE
    }

    /** A normal message written by a person. */
    public static IncomingEvent of(Kind kind, String channel, String user, String text, String ts, String threadTs,
            String eventId) {
        return new IncomingEvent(kind, channel, user, text, ts, threadTs, eventId, null, false);
    }
}
