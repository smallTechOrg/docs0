package com.docs0.budget;

import com.docs0.config.Docs0Properties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Enforces the tenant's hard monthly spend cap, based on the logged cost of every interaction and every other model call. */
@Service
public class BudgetService {

    private final JdbcClient jdbc;
    private final Docs0Properties props;

    public BudgetService(JdbcClient jdbc, Docs0Properties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** Spend so far in the current calendar month (UTC), in USD: questions plus the other model calls. */
    public BigDecimal spentThisMonth(long tenantId) {
        return questionSpentThisMonth(tenantId).add(otherSpentThisMonth(tenantId));
    }

    /** This month's cost of answering questions (the interaction log). */
    public BigDecimal questionSpentThisMonth(long tenantId) {
        return jdbc.sql("""
                        SELECT coalesce(sum(cost_usd), 0) FROM interaction
                        WHERE tenant_id = :tenantId AND created_at >= :monthStart
                        """)
                .param("tenantId", tenantId)
                .param("monthStart", monthStart())
                .query(BigDecimal.class)
                .single();
    }

    /** This month's cost of model calls that are not a question: repeat-question checks, themes. */
    public BigDecimal otherSpentThisMonth(long tenantId) {
        return jdbc.sql("""
                        SELECT coalesce(sum(cost_usd), 0) FROM usage_log
                        WHERE tenant_id = :tenantId AND created_at >= :monthStart
                        """)
                .param("tenantId", tenantId)
                .param("monthStart", monthStart())
                .query(BigDecimal.class)
                .single();
    }

    private static java.time.OffsetDateTime monthStart() {
        return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /** The configured cap in USD, or null when uncapped. */
    public BigDecimal monthlyCap() {
        return props.budget().capped() ? props.budget().monthlyUsd() : null;
    }

    /** True when the cap is set and this month's spend has reached it. New questions must be refused. */
    public boolean exhausted(long tenantId) {
        BigDecimal cap = monthlyCap();
        return cap != null && spentThisMonth(tenantId).compareTo(cap) >= 0;
    }
}
