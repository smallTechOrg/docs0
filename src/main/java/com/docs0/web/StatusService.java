package com.docs0.web;

import com.docs0.budget.BudgetService;
import com.docs0.tenant.Tenant;
import com.docs0.tenant.TenantService;
import com.docs0.web.StatusContributor.ComponentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Gathers everything the status page and /health show. Reads only; never exposes secrets. */
@Service
public class StatusService {

    public record LastSync(String sourceName, String status, Instant startedAt, Instant finishedAt,
                           int added, int updated, int removed, int skipped, int failed, String error) {}

    public record Snapshot(String tenantName, List<ComponentStatus> components, long documents,
                           long questions, BigDecimal spentThisMonth, BigDecimal monthlyCap,
                           Optional<LastSync> lastSync) {}

    private final JdbcClient jdbc;
    private final TenantService tenants;
    private final BudgetService budget;
    private final List<StatusContributor> contributors;

    public StatusService(JdbcClient jdbc, TenantService tenants, BudgetService budget,
                         List<StatusContributor> contributors) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.budget = budget;
        this.contributors = contributors;
    }

    public Snapshot snapshot() {
        Tenant tenant = tenants.current();
        List<ComponentStatus> components = new ArrayList<>();
        components.add(ComponentStatus.up("Database", "connected"));
        for (StatusContributor c : contributors) {
            try {
                components.add(c.status());
            } catch (RuntimeException e) {
                components.add(ComponentStatus.down(c.getClass().getSimpleName(), e.getClass().getSimpleName()));
            }
        }
        components.sort(Comparator.comparing(ComponentStatus::name));

        long documents = jdbc.sql("SELECT count(*) FROM document WHERE tenant_id = :t AND NOT deleted")
                .param("t", tenant.id()).query(Long.class).single();
        long questions = jdbc.sql("SELECT count(*) FROM interaction WHERE tenant_id = :t")
                .param("t", tenant.id()).query(Long.class).single();
        Optional<LastSync> lastSync = jdbc.sql("""
                        SELECT s.name, r.status, r.started_at, r.finished_at, r.added, r.updated, r.removed,
                               r.skipped, r.failed, r.error
                        FROM sync_run r JOIN source s ON s.id = r.source_id
                        WHERE r.tenant_id = :t ORDER BY r.started_at DESC LIMIT 1
                        """)
                .param("t", tenant.id())
                .query((rs, n) -> new LastSync(rs.getString("name"), rs.getString("status"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                        rs.getInt("added"), rs.getInt("updated"), rs.getInt("removed"), rs.getInt("skipped"),
                        rs.getInt("failed"), rs.getString("error")))
                .optional();

        return new Snapshot(tenant.name(), components, documents, questions,
                budget.spentThisMonth(tenant.id()), budget.monthlyCap(), lastSync);
    }
}
