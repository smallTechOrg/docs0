package com.docs0.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Write side of the knowledge base, used by source sync. Every statement is tenant-scoped. */
@Repository
public class DocumentStore {

    /** What a connector knows about one item after extracting its text. */
    public record DocumentWrite(
            String externalId,
            String title,
            String mimeType,
            String url,
            String ownerName,
            String ownerEmail,
            Instant modifiedAt,
            String body) {}

    private final JdbcClient jdbc;

    public DocumentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts or replaces the document with this external id, and brings it back if it was deleted. */
    public void upsert(long tenantId, long sourceId, DocumentWrite doc) {
        String body = clean(doc.body());
        String title = clean(doc.title());
        jdbc.sql("""
                        INSERT INTO document (tenant_id, source_id, external_id, title, mime_type, url, owner_name,
                                              owner_email, modified_at, body, body_chars, content_hash, deleted,
                                              synced_at)
                        VALUES (:tenant, :source, :externalId, :title, :mimeType, :url, :ownerName,
                                :ownerEmail, :modifiedAt, :body, :bodyChars, :hash, FALSE, now())
                        ON CONFLICT (tenant_id, source_id, external_id) DO UPDATE SET
                            title = EXCLUDED.title,
                            mime_type = EXCLUDED.mime_type,
                            url = EXCLUDED.url,
                            owner_name = EXCLUDED.owner_name,
                            owner_email = EXCLUDED.owner_email,
                            modified_at = EXCLUDED.modified_at,
                            body = EXCLUDED.body,
                            body_chars = EXCLUDED.body_chars,
                            content_hash = EXCLUDED.content_hash,
                            deleted = FALSE,
                            synced_at = now()
                        """)
                .param("tenant", tenantId)
                .param("source", sourceId)
                .param("externalId", doc.externalId())
                .param("title", title.isBlank() ? "(untitled)" : title)
                .param("mimeType", doc.mimeType())
                .param("url", doc.url())
                .param("ownerName", stripNul(doc.ownerName()))
                .param("ownerEmail", stripNul(doc.ownerEmail()))
                .param("modifiedAt", doc.modifiedAt() == null ? null : doc.modifiedAt().atOffset(ZoneOffset.UTC))
                .param("body", body)
                .param("bodyChars", body.length())
                .param("hash", sha256(body))
                .update();
    }

    /** External id -> modified time of every live document of the source. The time may be null. */
    public Map<String, Instant> modifiedTimes(long tenantId, long sourceId) {
        Map<String, Instant> result = new HashMap<>();
        jdbc.sql("""
                        SELECT external_id, modified_at FROM document
                        WHERE tenant_id = :tenant AND source_id = :source AND NOT deleted
                        """)
                .param("tenant", tenantId)
                .param("source", sourceId)
                .query(rs -> {
                    OffsetDateTime t = rs.getObject("modified_at", OffsetDateTime.class);
                    result.put(rs.getString("external_id"), t == null ? null : t.toInstant());
                });
        return result;
    }

    /** Marks the given documents deleted, so they drop out of search. Returns how many changed. */
    public int markDeleted(long tenantId, long sourceId, Collection<String> externalIds) {
        int removed = 0;
        for (String externalId : externalIds) {
            removed += jdbc.sql("""
                            UPDATE document SET deleted = TRUE, synced_at = now()
                            WHERE tenant_id = :tenant AND source_id = :source AND external_id = :externalId
                              AND NOT deleted
                            """)
                    .param("tenant", tenantId)
                    .param("source", sourceId)
                    .param("externalId", externalId)
                    .update();
        }
        return removed;
    }

    /** Live documents of one source. */
    public long count(long tenantId, long sourceId) {
        return jdbc.sql("""
                        SELECT count(*) FROM document
                        WHERE tenant_id = :tenant AND source_id = :source AND NOT deleted
                        """)
                .param("tenant", tenantId)
                .param("source", sourceId)
                .query(Long.class)
                .single();
    }

    private static String clean(String text) {
        return text == null ? "" : stripNul(text);
    }

    /** Postgres text cannot hold NUL characters; extracted PDF text sometimes contains them. */
    private static String stripNul(String text) {
        return text == null ? null : text.replace("\u0000", "");
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
