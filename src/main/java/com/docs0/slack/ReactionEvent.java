package com.docs0.slack;

/**
 * A reaction added to or removed from a message, free of Bolt types.
 *
 * @param reaction the emoji name without colons, e.g. "+1" or "thumbsdown::skin-tone-3"
 * @param itemTs   the ts of the message that was reacted to
 */
public record ReactionEvent(boolean added, String channel, String user, String reaction, String itemTs) {}
