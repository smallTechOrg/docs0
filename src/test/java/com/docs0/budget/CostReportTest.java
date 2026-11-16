package com.docs0.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.TestData;
import com.docs0.TestDatabase;
import com.docs0.conversation.Conversation;
import com.docs0.budget.CostReport.Day;
import com.docs0.budget.CostReport.Expensive;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class CostReportTest {

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final CostReport report = new CostReport(jdbc);
    private long tenant;
    private Conversation thread;
    private Conversation dm;

    @BeforeEach
    void setUp() {
        tenant = TestDatabase.resetWithTenant();
        thread = TestData.conversation(jdbc, tenant, "C1", "1.1", false);
        dm = TestData.conversation(jdbc, tenant, "D1", "", true);
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    private long add(Conversation c, String question, String cost, Instant when) {
        long id = TestData.interaction(jdbc, c, "U1", question, "a", "9." + question.hashCode(), cost);
        TestData.backdate(jdbc, id, when);
        return id;
    }

    @Test
    void perDayAddsUpQuestionsTokensAndCostNewestDayFirst() {
        add(thread, "a", "0.50", daysAgo(0));
        add(thread, "b", "0.25", daysAgo(0));
        add(dm, "c", "1.00", daysAgo(2));

        List<Day> days = report.perDay(tenant, 30);

        assertThat(days).hasSize(2);
        assertThat(days.get(0).day()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        assertThat(days.get(0).questions()).isEqualTo(2);
        assertThat(days.get(0).inputTokens()).isEqualTo(2000);
        assertThat(days.get(0).outputTokens()).isEqualTo(200);
        assertThat(days.get(0).costUsd()).isEqualByComparingTo("0.75");
        assertThat(days.get(1).questions()).isEqualTo(1);
        assertThat(days.get(1).costUsd()).isEqualByComparingTo("1.00");
    }

    @Test
    void daysOlderThanTheWindowAreLeftOut() {
        add(thread, "recent", "0.10", daysAgo(29));
        add(thread, "old", "5.00", daysAgo(31));

        assertThat(report.perDay(tenant, 30)).singleElement()
                .satisfies(d -> assertThat(d.costUsd()).isEqualByComparingTo("0.10"));
    }

    @Test
    void topQuestionsAreThisMonthsDearestFirstAndCappedAtTheLimit() {
        add(thread, "cheap", "0.01", Instant.now());
        add(thread, "dear", "0.90", Instant.now());
        add(dm, "dm dear", "0.95", Instant.now());
        add(thread, "last month", "9.00",
                LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC)
                        .minus(1, ChronoUnit.DAYS));
        add(thread, "free", "0", Instant.now());

        List<Expensive> top = report.topQuestionsThisMonth(tenant, 10);

        assertThat(top).extracting(Expensive::question).containsExactly("dm dear", "dear", "cheap");
        assertThat(top.get(0).dm()).isTrue();
        assertThat(top.get(1).dm()).isFalse();
        assertThat(report.topQuestionsThisMonth(tenant, 2)).hasSize(2);
    }

    @Test
    void otherTenantsAreNeverIncluded() {
        long other = jdbc.sql("INSERT INTO tenant (slug, name) VALUES ('other', 'Other') RETURNING id")
                .query(Long.class).single();
        Conversation theirs = TestData.conversation(jdbc, other, "C9", "1.1", false);
        add(theirs, "theirs", "7.00", Instant.now());
        add(thread, "ours", "0.20", Instant.now());

        assertThat(report.perDay(tenant, 30)).singleElement()
                .satisfies(d -> assertThat(d.costUsd()).isEqualByComparingTo("0.20"));
        assertThat(report.topQuestionsThisMonth(tenant, 10)).extracting(Expensive::question).containsExactly("ours");
        assertThat(new BudgetService(jdbc, null).spentThisMonth(other)).isEqualByComparingTo("7.00");
    }
}
