package com.docs0.action;

import java.time.Instant;
import java.util.Map;

/**
 * An issue or card docs0 has drafted and will create only after the person who asked confirms.
 * {@code params} are plain strings: for Linear teamId, teamKey, teamName, title, description and
 * optionally priority; for Trello boardId, boardName, listId, listName, title and description.
 */
public record PendingAction(
        long id,
        long tenantId,
        long conversationId,
        Long interactionId,
        String requestedBy,
        Kind kind,
        Map<String, String> params,
        Status status,
        String resultUrl,
        String error,
        Instant createdAt,
        Instant resolvedAt) {

    public enum Kind { LINEAR_ISSUE, TRELLO_CARD }

    public enum Status { PENDING, DONE, CANCELLED, EXPIRED, FAILED }

    public String title() {
        return params.getOrDefault("title", "");
    }

    public String description() {
        return params.getOrDefault("description", "");
    }

    /** Where it will be created: "Linear, team ENG (Engineering)" or "Trello, board Roadmap, list Doing". */
    public String target() {
        return switch (kind) {
            case LINEAR_ISSUE -> "Linear, team " + params.getOrDefault("teamKey", "?")
                    + (params.containsKey("teamName") ? " (" + params.get("teamName") + ")" : "");
            case TRELLO_CARD -> "Trello, board " + params.getOrDefault("boardName", "?") + ", list "
                    + params.getOrDefault("listName", "?");
        };
    }
}
