package com.docs0.knowledge;

import java.time.Instant;

/** A search result: enough to decide whether the document is worth reading. */
public record SearchHit(
        long documentId,
        String title,
        String sourceKind,
        String url,
        String ownerName,
        Instant modifiedAt,
        String snippet,
        double rank,
        /** True when the document is restricted (a private channel, a mailbox). */
        boolean restricted) {

    /** A hit on a shared document. */
    public SearchHit(long documentId, String title, String sourceKind, String url, String ownerName,
            Instant modifiedAt, String snippet, double rank) {
        this(documentId, title, sourceKind, url, ownerName, modifiedAt, snippet, rank, false);
    }
}
