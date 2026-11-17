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
        String body,
        /** True when the document is restricted (a private channel, a mailbox): only some people may see it. */
        boolean restricted) {

    /** A shared document. */
    public Document(long id, long tenantId, long sourceId, String sourceKind, String externalId, String title,
            String mimeType, String url, String ownerName, String ownerEmail, Instant modifiedAt, String body) {
        this(id, tenantId, sourceId, sourceKind, externalId, title, mimeType, url, ownerName, ownerEmail, modifiedAt,
                body, false);
    }
}
