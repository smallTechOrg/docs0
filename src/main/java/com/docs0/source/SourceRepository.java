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
}
