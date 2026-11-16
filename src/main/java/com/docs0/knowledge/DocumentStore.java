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
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    private final JdbcClient jdbc;

    public DocumentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts or replaces the document with this external id, and brings it back if it was deleted. */
    public void upsert(long tenantId, long sourceId, DocumentWrite doc) {
        insert(tenantId, sourceId, doc, false, null);
    }

    /**
     * As {@link #upsert(long, long, DocumentWrite)} for a document only some people may see: it is
     * marked restricted and its principal set is replaced by {@code principals} (lowercase Slack user
     * ids and emails), so a membership change is picked up by the next sync. An empty set hides the
     * document from everyone.
     */
    @Transactional
    public void upsertRestricted(long tenantId, long sourceId, DocumentWrite doc, Set<String> principals) {
        upsertRestricted(tenantId, sourceId, doc, principals, null);
    }

    /** As above, also storing the source's {@code version} of the item (see {@link #versions}). */
    @Transactional
    public void upsertRestricted(long tenantId, long sourceId, DocumentWrite doc, Set<String> principals,
                                 String version) {
        long id = insert(tenantId, sourceId, doc, true, version);
        jdbc.sql("DELETE FROM document_principal WHERE tenant_id = :tenant AND document_id = :id")
                .param("tenant", tenantId).param("id", id).update();
        for (String principal : normalise(principals)) {
            jdbc.sql("""
                            INSERT INTO document_principal (tenant_id, document_id, principal)
                            VALUES (:tenant, :id, :principal) ON CONFLICT DO NOTHING
                            """)
                    .param("tenant", tenantId).param("id", id).param("principal", principal).update();
        }
    }

    /**
     * Sets who may see every document of the source whose external id starts with {@code prefix},
     * documents that have not changed included: restricted with exactly these principals, or, when
     * {@code restricted} is false, open to everyone with no principals. Run on every sync so a
     * membership change reaches documents that are not rewritten.
     */
    @Transactional
    public void refreshAccess(long tenantId, long sourceId, String prefix, boolean restricted, Set<String> principals) {
        String json = GSON.toJson(restricted ? normalise(principals) : Set.of());
        jdbc.sql("""
                        UPDATE document SET restricted = :restricted
                        WHERE tenant_id = :tenant AND source_id = :source AND starts_with(external_id, :prefix)
                          AND restricted <> :restricted
                        """)
                .param("restricted", restricted).param("tenant", tenantId).param("source", sourceId)
                .param("prefix", prefix).update();
        jdbc.sql("""
                        DELETE FROM document_principal p USING document d
                        WHERE p.document_id = d.id AND d.tenant_id = :tenant AND d.source_id = :source
                          AND starts_with(d.external_id, :prefix)
                          AND p.principal NOT IN (SELECT jsonb_array_elements_text(CAST(:principals AS jsonb)))
                        """)
                .param("tenant", tenantId).param("source", sourceId).param("prefix", prefix)
                .param("principals", json).update();
        if (restricted) {
            jdbc.sql("""
                            INSERT INTO document_principal (tenant_id, document_id, principal)
                            SELECT d.tenant_id, d.id, pr FROM document d
                            CROSS JOIN jsonb_array_elements_text(CAST(:principals AS jsonb)) AS pr
                            WHERE d.tenant_id = :tenant AND d.source_id = :source AND starts_with(d.external_id, :prefix)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("tenant", tenantId).param("source", sourceId).param("prefix", prefix)
                    .param("principals", json).update();
        }
    }

    private long insert(long tenantId, long sourceId, DocumentWrite doc, boolean restricted, String version) {
        String body = clean(doc.body());
        String title = clean(doc.title());
        return jdbc.sql("""
                        INSERT INTO document (tenant_id, source_id, external_id, title, mime_type, url, owner_name,
                                              owner_email, modified_at, body, body_chars, content_hash, deleted,
                                              restricted, version, synced_at)
                        VALUES (:tenant, :source, :externalId, :title, :mimeType, :url, :ownerName,
                                :ownerEmail, :modifiedAt, :body, :bodyChars, :hash, FALSE, :restricted, :version, now())
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
                            restricted = EXCLUDED.restricted,
                            version = EXCLUDED.version,
                            synced_at = now()
                        RETURNING id
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
                .param("restricted", restricted)
                .param("version", version, java.sql.Types.VARCHAR)
                .query(Long.class)
                .single();
    }

    /** External id -> the version stored with every live document of the source that has one. */
    public Map<String, String> versions(long tenantId, long sourceId) {
        Map<String, String> result = new HashMap<>();
        jdbc.sql("""
                        SELECT external_id, version FROM document
                        WHERE tenant_id = :tenant AND source_id = :source AND NOT deleted AND version IS NOT NULL
                        """)
                .param("tenant", tenantId)
                .param("source", sourceId)
                .query(rs -> {
                    result.put(rs.getString("external_id"), rs.getString("version"));
                });
        return result;
    }

    /** What is stored about one live document, for deciding whether a rewrite is needed. */
    public record Stored(Instant modifiedAt, String contentHash, String url) {}

    /** External id -> stored state of every live document of the source. */
    public Map<String, Stored> stored(long tenantId, long sourceId) {
        Map<String, Stored> result = new HashMap<>();
        jdbc.sql("""
                        SELECT external_id, modified_at, content_hash, url FROM document
                        WHERE tenant_id = :tenant AND source_id = :source AND NOT deleted
                        """)
                .param("tenant", tenantId)
                .param("source", sourceId)
                .query(rs -> {
                    OffsetDateTime t = rs.getObject("modified_at", OffsetDateTime.class);
                    result.put(rs.getString("external_id"), new Stored(t == null ? null : t.toInstant(),
                            rs.getString("content_hash"), rs.getString("url")));
                });
        return result;
    }

    /** The hash {@link #upsert} stores for this body. */
    public static String contentHash(String body) {
        return sha256(clean(body));
    }

    private static Set<String> normalise(Set<String> principals) {
        Set<String> result = new java.util.TreeSet<>();
        if (principals != null) {
            for (String principal : principals) {
                if (principal != null && !principal.isBlank()) {
                    result.add(principal.strip().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return result;
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

    /** Marks every document of the source deleted. Returns how many changed. */
    public int markAllDeleted(long tenantId, long sourceId) {
        return jdbc.sql("""
                        UPDATE document SET deleted = TRUE, synced_at = now()
                        WHERE tenant_id = :tenant AND source_id = :source AND NOT deleted
                        """)
                .param("tenant", tenantId)
                .param("source", sourceId)
                .update();
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

    /** Live documents of the tenant by source kind, e.g. {@code GOOGLE_DRIVE -> 12}. Kinds with none are absent. */
    public Map<String, Long> countsByKind(long tenantId) {
        Map<String, Long> counts = new java.util.TreeMap<>();
        jdbc.sql("""
                        SELECT s.kind, count(*) AS n FROM document d
                        JOIN source s ON s.id = d.source_id AND s.tenant_id = d.tenant_id
                        WHERE d.tenant_id = :tenant AND NOT d.deleted
                        GROUP BY s.kind
                        """)
                .param("tenant", tenantId)
                .query(rs -> {
                    counts.put(rs.getString("kind"), rs.getLong("n"));
                });
        return counts;
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
