package com.docs0.knowledge;

import java.util.Set;

/**
 * Who will see an answer: every member of the channel, or the one person in a DM.
 * Restricted documents are eligible only if every audience member may see them.
 * Phase 1 has only shared sources, so repositories accept this and ignore it.
 */
public record Audience(Set<String> slackUserIds) {

    public Audience {
        slackUserIds = slackUserIds == null ? Set.of() : Set.copyOf(slackUserIds);
    }

    public static Audience of(String... slackUserIds) {
        return new Audience(Set.of(slackUserIds));
    }

    /** No specific people: only shared knowledge is eligible. */
    public static Audience sharedOnly() {
        return new Audience(Set.of());
    }
}
