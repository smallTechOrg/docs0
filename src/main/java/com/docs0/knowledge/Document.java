package com.docs0.knowledge;

import java.time.Instant;

/** One indexed item of knowledge with its full extracted text. */
public record Document(
        long id,
        long tenantId,
        long sourceId,
        String sourceKind,
        String externalId,
        String title,
        String mimeType,
        String url,
        String ownerName,
        String ownerEmail,
        Instant modifiedAt,
        String body) {}
