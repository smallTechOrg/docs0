package com.docs0.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.TestData;
import com.docs0.TestDatabase;
import com.docs0.config.Docs0Properties;
import com.docs0.conversation.Conversation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Model calls that are not a question count toward the monthly cap and are reported on their own. */
class UsageLogBudgetTest {

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final UsageLog usage = new UsageLog(jdbc);
    private long tenant;

    @BeforeEach
    void setUp() {
        tenant = TestDatabase.resetWithTenant();
    }

    private BudgetService budget(String cap) {
        return new BudgetService(jdbc, new Docs0Properties(new Docs0Properties.Tenant("smalltech", "smallTech"), null,
                new Docs0Properties.Budget(new BigDecimal(cap)), null, null, null, "", "", ""));
    }

    @Test
    void thisMonthsSpendIsQuestionsPlusOtherCallsAndTheCapSeesBoth() {
        Conversation c = TestData.conversation(jdbc, tenant, "C1", "1.1", false);
        TestData.interaction(jdbc, c, "U1", "q?", "a", "9.1", "0.60");
        usage.record(tenant, UsageLog.THEMES, 100, 10, new BigDecimal("0.30"));
        usage.record(tenant, UsageLog.PROACTIVE_JUDGE, 100, 10, new BigDecimal("0.15"));

        BudgetService budget = budget("1.00");

        assertThat(budget.questionSpentThisMonth(tenant)).isEqualByComparingTo("0.60");
        assertThat(budget.otherSpentThisMonth(tenant)).isEqualByComparingTo("0.45");
        assertThat(budget.spentThisMonth(tenant)).isEqualByComparingTo("1.05");
        assertThat(budget.exhausted(tenant)).isTrue();
        assertThat(budget("2.00").exhausted(tenant)).isFalse();
    }

    @Test
    void otherCallsAloneCanUseUpTheCapAndOnlyThisTenantsAreCounted() {
        usage.record(tenant, UsageLog.THEMES, 1, 1, new BigDecimal("1.00"));
        long other = jdbc.sql("INSERT INTO tenant (slug, name) VALUES ('o', 'O') RETURNING id").query(Long.class).single();
        usage.record(other, UsageLog.THEMES, 1, 1, new BigDecimal("50.00"));

        assertThat(budget("1.00").exhausted(tenant)).isTrue();
        assertThat(budget("1.00").spentThisMonth(tenant)).isEqualByComparingTo("1.00");
    }

    @Test
    void lastMonthsOtherCallsDoNotCount() {
        usage.record(tenant, UsageLog.THEMES, 1, 1, new BigDecimal("9.00"));
        jdbc.sql("UPDATE usage_log SET created_at = :t").param("t", java.sql.Timestamp.from(Instant.now().minus(45, ChronoUnit.DAYS))).update();

        assertThat(budget("1.00").spentThisMonth(tenant)).isEqualByComparingTo("0");
        assertThat(new CostReport(jdbc).otherSpend(tenant, 30)).isEqualByComparingTo("0");
        assertThat(new CostReport(jdbc).otherSpend(tenant, 90)).isEqualByComparingTo("9.00");
    }

    private BudgetService budgetAt(String cap, String now) {
        return new BudgetService(jdbc, new Docs0Properties(new Docs0Properties.Tenant("smalltech", "smallTech"), null,
                new Docs0Properties.Budget(new BigDecimal(cap)), null, null, null, "", "", ""),
                java.time.Clock.fixed(Instant.parse(now), java.time.ZoneOffset.UTC));
    }

    @Test
    void theMonthBeginsAtMidnightUtcOfTheFirstByTheInjectedClock() {
        Conversation c = TestData.conversation(jdbc, tenant, "C1", "1.1", false);
        long november = TestData.interaction(jdbc, c, "U1", "q?", "a", "9.1", "0.60");
        TestData.backdate(jdbc, november, Instant.parse("2026-11-30T23:59:00Z"));
        usage.record(tenant, UsageLog.THEMES, 1, 1, new BigDecimal("0.30"));
        jdbc.sql("UPDATE usage_log SET created_at = :t").param("t", java.sql.Timestamp.from(Instant.parse("2026-12-01T00:00:00Z"))).update();

        // Still November: only the November question counts (the December usage row is in the future).
        BudgetService lastSecond = budgetAt("1.00", "2026-11-30T23:59:59Z");
        assertThat(lastSecond.questionSpentThisMonth(tenant)).isEqualByComparingTo("0.60");
        // One second later it is December: the question has dropped out, the usage row counts.
        BudgetService firstSecond = budgetAt("1.00", "2026-12-01T00:00:00Z");
        assertThat(firstSecond.questionSpentThisMonth(tenant)).isEqualByComparingTo("0");
        assertThat(firstSecond.otherSpentThisMonth(tenant)).isEqualByComparingTo("0.30");
        assertThat(firstSecond.exhausted(tenant)).isFalse();
    }

    @Test
    void aTenantCapOfZeroRefusesEverythingAndNullMeansTheGlobalDefault() {
        BudgetService budget = budget("100");
        assertThat(budget.monthlyCap(tenant)).isEqualByComparingTo("100");
        assertThat(budget.exhausted(tenant)).isFalse();

        new com.docs0.tenant.TenantService(jdbc).setMonthlyBudget(tenant, BigDecimal.ZERO);
        assertThat(budget.monthlyCap(tenant)).isEqualByComparingTo("0");
        assertThat(budget.exhausted(tenant)).isTrue();

        new com.docs0.tenant.TenantService(jdbc).setMonthlyBudget(tenant, new BigDecimal("7"));
        assertThat(budget.monthlyCap(tenant)).isEqualByComparingTo("7");
        assertThat(budget.exhausted(tenant)).isFalse();

        new com.docs0.tenant.TenantService(jdbc).setMonthlyBudget(tenant, null);
        assertThat(budget.monthlyCap(tenant)).isEqualByComparingTo("100");
    }
}
