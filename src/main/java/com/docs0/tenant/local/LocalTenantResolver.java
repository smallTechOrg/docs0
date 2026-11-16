package com.docs0.tenant.local;

import com.docs0.config.Docs0Properties;
import com.docs0.tenant.Tenant;
import com.docs0.tenant.TenantService;
import org.springframework.stereotype.Component;

/**
 * Single-tenant compatibility layer: maps the configured {@code docs0.tenant.*} to a tenant, creating it on first
 * use. It serves the pilot deployment only: the password-protected admin pages, the public status page and the
 * Socket Mode Slack connection. Nothing else may use it; a test enforces that.
 */
@Component
public class LocalTenantResolver {

    private final TenantService tenants;
    private final Docs0Properties props;
    private volatile Tenant local;

    public LocalTenantResolver(TenantService tenants, Docs0Properties props) {
        this.tenants = tenants;
        this.props = props;
    }

    /** The configured tenant, created if it does not exist yet. */
    public Tenant tenant() {
        Tenant t = local;
        if (t == null) {
            synchronized (this) {
                t = local;
                if (t == null) {
                    t = tenants.ensure(props.tenant().slug(), props.tenant().name());
                    local = t;
                }
            }
        }
        return t;
    }

    /** Forgets the cached tenant. For tests that reset the database. */
    public void reset() {
        local = null;
    }
}
