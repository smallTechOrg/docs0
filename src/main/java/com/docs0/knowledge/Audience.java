package com.docs0.knowledge;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who will see an answer: every member of the channel, or the one person in a DM. A restricted
 * document is eligible only if every member is covered by the document's principals (the member's
 * Slack id or email). An audience with no members sees no restricted documents at all.
 * Bots and deactivated users are not members; whoever builds the audience drops them.
 */
public record Audience(Set<Member> members) {

    /** One person: their Slack user id and, when known, their email. */
    public record Member(String slackUserId, String email) {

        public Member {
            slackUserId = slackUserId == null ? "" : slackUserId.strip();
            email = email == null || email.isBlank() ? null : email.strip().toLowerCase(Locale.ROOT);
        }

        public Member(String slackUserId) {
            this(slackUserId, null);
        }
    }

    public Audience {
        members = members == null ? Set.of() : Set.copyOf(members);
    }

    public static Audience of(String... slackUserIds) {
        return forIds(Set.of(slackUserIds));
    }

    public static Audience forIds(Collection<String> slackUserIds) {
        return new Audience(slackUserIds.stream().map(Member::new).collect(Collectors.toSet()));
    }

    /** No specific people: only shared knowledge is eligible. */
    public static Audience sharedOnly() {
        return new Audience(Set.of());
    }

    public Set<String> slackUserIds() {
        return members.stream().map(Member::slackUserId).collect(Collectors.toSet());
    }
}
