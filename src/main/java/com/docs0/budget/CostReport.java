package com.docs0.budget;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** What the questions cost: per day and the dearest of the month. Reads the interaction log; the model calls
 * that are not a question (usage log) are reported as their own line. */
@Service
public class CostReport {

    /** One UTC day that had at least one question. */
    public record Day(LocalDate day, long questions, long inputTokens, long outputTokens, BigDecimal costUsd) {}

    /** One expensive question. {@code dm} questions are kept anonymous by whoever shows this. */
    public record Expensive(long interactionId, String question, boolean dm, BigDecimal costUsd, Instant createdAt) {}

    private final JdbcClient jdbc;

    public CostReport(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Totals for each of the last {@code days} UTC days (today included) that had questions, newest first. */
    public List<Day> perDay(long tenantId, int days) {
        var from = LocalDate.now(ZoneOffset.UTC).minusDays(days - 1L).atStartOfDay().atOffset(ZoneOffset.UTC);
        return jdbc.sql("""
                        SELECT (created_at AT TIME ZONE 'UTC')::date AS day, count(*) AS questions,
                               sum(input_tokens) AS input_tokens, sum(output_tokens) AS output_tokens,
                               sum(cost_usd) AS cost_usd
                        FROM interaction
                        WHERE tenant_id = :tenantId AND created_at >= :from
                        GROUP BY 1 ORDER BY 1 DESC
                        """)
                .param("tenantId", tenantId).param("from", from)
                .query((rs, n) -> new Day(rs.getObject("day", LocalDate.class), rs.getLong("questions"),
                        rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getBigDecimal("cost_usd")))
                .list();
    }

    /** What the model calls that are not a question cost in the last {@code days} UTC days (today included). */
    public BigDecimal otherSpend(long tenantId, int days) {
        var from = LocalDate.now(ZoneOffset.UTC).minusDays(days - 1L).atStartOfDay().atOffset(ZoneOffset.UTC);
        return jdbc.sql("""
                        SELECT coalesce(sum(cost_usd), 0) FROM usage_log
                        WHERE tenant_id = :tenantId AND created_at >= :from
                        """)
                .param("tenantId", tenantId).param("from", from)
                .query(BigDecimal.class).single();
    }

    /** The questions that cost the most this calendar month (UTC), dearest first. */
    public List<Expensive> topQuestionsThisMonth(long tenantId, int limit) {
        var monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        return jdbc.sql("""
                        SELECT i.id, i.question, c.is_dm, i.cost_usd, i.created_at
                        FROM interaction i JOIN conversation c ON c.id = i.conversation_id AND c.tenant_id = i.tenant_id
                        WHERE i.tenant_id = :tenantId AND i.created_at >= :monthStart AND i.cost_usd > 0
                        ORDER BY i.cost_usd DESC, i.id DESC
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId).param("monthStart", monthStart).param("limit", limit)
                .query((rs, n) -> new Expensive(rs.getLong("id"), rs.getString("question"), rs.getBoolean("is_dm"),
                        rs.getBigDecimal("cost_usd"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
