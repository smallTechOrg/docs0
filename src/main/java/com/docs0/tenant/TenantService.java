package com.docs0.tenant;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Tenants: create, find, suspend. Knows nothing about which tenant "this" request is: callers say so. */
@Service
public class TenantService {

    private static final String COLUMNS =
            "id, slug, name, slack_team_id, monthly_budget_usd, status, admin_emails";

    private final JdbcClient jdbc;

    public TenantService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The tenant with this slug, created with this name if it does not exist; an existing one is renamed. */
    public Tenant ensure(String slug, String name) {
        return jdbc.sql("""
                        INSERT INTO tenant (slug, name) VALUES (:slug, :name)
                        ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name
                        RETURNING %s
                        """.formatted(COLUMNS))
                .param("slug", slug)
                .param("name", name)
                .query((rs, n) -> row(rs))
                .single();
    }

    /** A new tenant. Fails when the slug is taken. */
    public Tenant create(String slug, String name) {
        return jdbc.sql("INSERT INTO tenant (slug, name) VALUES (:slug, :name) RETURNING " + COLUMNS)
                .param("slug", slug)
                .param("name", name)
                .query((rs, n) -> row(rs))
                .single();
    }

    /** A new tenant with its own monthly cap in USD (0: no budget at all; null: the global default). */
    public Tenant create(String slug, String name, BigDecimal monthlyBudgetUsd) {
        return jdbc.sql("INSERT INTO tenant (slug, name, monthly_budget_usd) VALUES (:slug, :name, :budget) RETURNING "
                        + COLUMNS)
                .param("slug", slug)
                .param("name", name)
                .param("budget", monthlyBudgetUsd, java.sql.Types.NUMERIC)
                .query((rs, n) -> row(rs))
                .single();
    }

    public Optional<Tenant> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tenant WHERE id = :id")
                .param("id", id).query((rs, n) -> row(rs)).optional();
    }

    /** The tenant's display name, or an empty string for an unknown tenant. */
    public String name(long tenantId) {
        return findById(tenantId).map(Tenant::name).orElse("");
    }

    public Optional<Tenant> findBySlug(String slug) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tenant WHERE slug = :slug")
                .param("slug", slug).query((rs, n) -> row(rs)).optional();
    }

    public Optional<Tenant> findBySlackTeamId(String teamId) {
        if (teamId == null || teamId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM tenant WHERE slack_team_id = :team")
                .param("team", teamId).query((rs, n) -> row(rs)).optional();
    }

    /** Every tenant, oldest first. */
    public List<Tenant> all() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tenant ORDER BY id").query((rs, n) -> row(rs)).list();
    }

    /** The tenants whose syncs and scheduled jobs run. */
    public List<Tenant> active() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tenant WHERE status = 'ACTIVE' ORDER BY id")
                .query((rs, n) -> row(rs)).list();
    }

    /** False for an unknown or suspended tenant: when in doubt, do nothing for it. */
    public boolean isActive(long tenantId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM tenant WHERE id = :id AND status = 'ACTIVE')")
                .param("id", tenantId).query(Boolean.class).single();
    }

    public void suspend(long tenantId) {
        setStatus(tenantId, Tenant.SUSPENDED);
    }

    public void activate(long tenantId) {
        setStatus(tenantId, Tenant.ACTIVE);
    }

    private void setStatus(long tenantId, String status) {
        jdbc.sql("UPDATE tenant SET status = :status WHERE id = :id")
                .param("status", status).param("id", tenantId).update();
    }

    /** Sets the tenant's own monthly cap; null goes back to the global default. */
    public void setMonthlyBudget(long tenantId, BigDecimal usd) {
        jdbc.sql("UPDATE tenant SET monthly_budget_usd = :usd WHERE id = :id")
                .param("usd", usd, java.sql.Types.NUMERIC).param("id", tenantId).update();
    }

    public void setAdminEmails(long tenantId, String emails) {
        jdbc.sql("UPDATE tenant SET admin_emails = :emails WHERE id = :id")
                .param("emails", emails, java.sql.Types.VARCHAR).param("id", tenantId).update();
    }

    private static Tenant row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Tenant(rs.getLong("id"), rs.getString("slug"), rs.getString("name"),
                rs.getString("slack_team_id"), rs.getBigDecimal("monthly_budget_usd"), rs.getString("status"),
                rs.getString("admin_emails"));
    }
}
