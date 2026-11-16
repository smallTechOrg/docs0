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
        double rank) {}
