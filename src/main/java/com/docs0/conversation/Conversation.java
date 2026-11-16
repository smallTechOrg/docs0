package com.docs0.conversation;

/** One Slack thread, or a whole DM when {@code threadTs} is the empty string. */
public record Conversation(long id, long tenantId, String channelId, String threadTs, boolean dm) {}
