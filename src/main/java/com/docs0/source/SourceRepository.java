package com.docs0.source;

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
}
