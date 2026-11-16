package com.docs0.tenant;

import com.docs0.config.Docs0Properties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Resolves the tenant a request belongs to. Phase 1 has one tenant, created from
 * configuration on first use; every caller still passes its id explicitly.
 */
@Service
public class TenantService {

    private final JdbcClient jdbc;
    private final Docs0Properties props;
    private volatile Tenant current;

    public TenantService(JdbcClient jdbc, Docs0Properties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** The configured tenant, created if it does not exist yet. */
    public Tenant current() {
        Tenant t = current;
        if (t == null) {
            synchronized (this) {
                t = current;
                if (t == null) {
                    t = ensure(props.tenant().slug(), props.tenant().name());
                    current = t;
                }
            }
        }
        return t;
    }

    public Tenant ensure(String slug, String name) {
        return jdbc.sql("""
                        INSERT INTO tenant (slug, name) VALUES (:slug, :name)
                        ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name
                        RETURNING id, slug, name, slack_team_id
                        """)
                .param("slug", slug)
                .param("name", name)
                .query((rs, n) -> new Tenant(rs.getLong("id"), rs.getString("slug"), rs.getString("name"),
                        rs.getString("slack_team_id")))
                .single();
    }

    /** Clears the cached tenant. For tests that reset the database. */
    public void reset() {
        current = null;
    }
}
