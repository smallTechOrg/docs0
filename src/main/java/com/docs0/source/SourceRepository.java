package com.docs0.source;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SourceRepository {

    private static final RowMapper<Source> MAPPER = (rs, n) -> new Source(rs.getLong("id"), rs.getLong("tenant_id"),
            rs.getString("kind"), rs.getString("name"), rs.getString("visibility"));

    private final JdbcClient jdbc;

    public SourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The tenant's source of this kind and name, created on first use. An existing row keeps its visibility. */
    public Source findOrCreate(long tenantId, String kind, String name, String visibility) {
        return jdbc.sql("""
                        INSERT INTO source (tenant_id, kind, name, visibility)
                        VALUES (:tenant, :kind, :name, :visibility)
                        ON CONFLICT (tenant_id, kind, name) DO UPDATE SET name = EXCLUDED.name
                        RETURNING id, tenant_id, kind, name, visibility
                        """)
                .param("tenant", tenantId)
                .param("kind", kind)
                .param("name", name)
                .param("visibility", visibility)
                .query(MAPPER)
                .single();
    }

    /** The source with this id, whichever tenant it belongs to (ids are unique); callers check the tenant. */
    public Optional<Source> findById(long sourceId) {
        return jdbc.sql("SELECT id, tenant_id, kind, name, visibility FROM source WHERE id = :id")
                .param("id", sourceId)
                .query(MAPPER)
                .optional();
    }

    public Optional<Source> find(long tenantId, String kind, String name) {
        return jdbc.sql("""
                        SELECT id, tenant_id, kind, name, visibility FROM source
                        WHERE tenant_id = :tenant AND kind = :kind AND name = :name
                        """)
                .param("tenant", tenantId)
                .param("kind", kind)
                .param("name", name)
                .query(MAPPER)
                .optional();
    }

    /** The folders chosen for a source, in the order they were added: {@code config.folders}. */
    public List<Folder> folders(long tenantId, long sourceId) {
        return jdbc.sql("""
                        SELECT f.value ->> 'id' AS id, f.value ->> 'name' AS name
                        FROM source s,
                             jsonb_array_elements(coalesce(s.config -> 'folders', '[]'::jsonb)) WITH ORDINALITY AS f
                        WHERE s.tenant_id = :tenant AND s.id = :source
                        ORDER BY f.ordinality
                        """)
                .param("tenant", tenantId)
                .param("source", sourceId)
                .query((rs, n) -> new Folder(rs.getString("id"), rs.getString("name")))
                .list();
    }

    /** Adds a folder to the selection. Adding one that is already chosen only refreshes its name. */
    public void addFolder(long tenantId, long sourceId, Folder folder) {
        jdbc.sql("""
                        UPDATE source s
                        SET config = jsonb_set(s.config, '{folders}',
                                (SELECT coalesce(jsonb_agg(f.value ORDER BY f.ordinality), '[]'::jsonb)
                                 FROM jsonb_array_elements(coalesce(s.config -> 'folders', '[]'::jsonb))
                                      WITH ORDINALITY AS f
                                 WHERE f.value ->> 'id' <> :id)
                                || jsonb_build_object('id', CAST(:id AS text), 'name', CAST(:name AS text)))
                        WHERE s.tenant_id = :tenant AND s.id = :source
                        """)
                .param("id", folder.id())
                .param("name", folder.name())
                .param("tenant", tenantId)
                .param("source", sourceId)
                .update();
    }

    public void removeFolder(long tenantId, long sourceId, String folderId) {
        jdbc.sql("""
                        UPDATE source s
                        SET config = jsonb_set(s.config, '{folders}',
                                (SELECT coalesce(jsonb_agg(f.value ORDER BY f.ordinality), '[]'::jsonb)
                                 FROM jsonb_array_elements(coalesce(s.config -> 'folders', '[]'::jsonb))
                                      WITH ORDINALITY AS f
                                 WHERE f.value ->> 'id' <> :id))
                        WHERE s.tenant_id = :tenant AND s.id = :source
                        """)
                .param("id", folderId)
                .param("tenant", tenantId)
                .param("source", sourceId)
                .update();
    }

    /** One list stored in {@code source.config}, as JSON text ({@code []} when there is none). */
    public String configList(long tenantId, long sourceId, String key) {
        return jdbc.sql("""
                        SELECT CAST(coalesce(s.config -> CAST(:key AS text), '[]'::jsonb) AS text)
                        FROM source s WHERE s.tenant_id = :tenant AND s.id = :source
                        """)
                .param("key", key)
                .param("tenant", tenantId)
                .param("source", sourceId)
                .query(String.class)
                .optional()
                .orElse("[]");
    }

    /** Replaces one list in {@code source.config}; the other keys are kept. */
    public void setConfigList(long tenantId, long sourceId, String key, String json) {
        jdbc.sql("""
                        UPDATE source s
                        SET config = jsonb_set(s.config, ARRAY[CAST(:key AS text)], CAST(:json AS jsonb), TRUE)
                        WHERE s.tenant_id = :tenant AND s.id = :source
                        """)
                .param("key", key)
                .param("json", json)
                .param("tenant", tenantId)
                .param("source", sourceId)
                .update();
    }

    /** One text value stored in {@code source.config}, or null when there is none. */
    public String configText(long tenantId, long sourceId, String key) {
        return jdbc.sql("""
                        SELECT s.config ->> CAST(:key AS text) FROM source s
                        WHERE s.tenant_id = :tenant AND s.id = :source
                        """)
                .param("key", key)
                .param("tenant", tenantId)
                .param("source", sourceId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /** Sets (or, with null, removes) one text value in {@code source.config}; the other keys are kept. */
    public void setConfigText(long tenantId, long sourceId, String key, String value) {
        if (value == null) {
            jdbc.sql("UPDATE source s SET config = s.config - CAST(:key AS text) WHERE s.tenant_id = :tenant AND s.id = :source")
                    .param("key", key).param("tenant", tenantId).param("source", sourceId).update();
            return;
        }
        jdbc.sql("""
                        UPDATE source s
                        SET config = jsonb_set(s.config, ARRAY[CAST(:key AS text)], to_jsonb(CAST(:value AS text)), TRUE)
                        WHERE s.tenant_id = :tenant AND s.id = :source
                        """)
                .param("key", key).param("value", value).param("tenant", tenantId).param("source", sourceId).update();
    }
}
