package com.docs0.insights;

import com.docs0.conversation.InteractionRepository;
import com.docs0.stale.StaleDocs;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * What the interaction log says about the last few days: totals, the questions docs0 could not answer well and
 * the stale documents people rely on. Read-only. Privacy rules: a DM question is only ever counted, and the
 * question behind an answer that used a private source is never shown.
 */
@Service
public class InsightsService {

    public static final List<Integer> PERIODS = List.of(7, 30, 90);
    public static final int DEFAULT_DAYS = 30;
    private static final int GAP_ROWS = 500;
    private static final int STALE_ROWS = 20;
    private static final String COUNTED = "kind IN ('ANSWER', 'CLARIFY', 'UNKNOWN')";

    /** Numbers for the period. {@code spendUsd} is questions plus the other model calls. */
    public record Totals(long questions, long thumbsUp, long thumbsDown, long repeatAnswers, long repeatThumbsDown,
            BigDecimal spendUsd) {

        /** Share of 👍 and 👎 reactions that are 👎, 0 to 100, or -1 when nobody reacted. */
        public int thumbsDownPercent() {
            long rated = thumbsUp + thumbsDown;
            return rated == 0 ? -1 : (int) Math.round(100.0 * thumbsDown / rated);
        }

        /** Share of unprompted answers that got a 👎, 0 to 100, or -1 when there were none. */
        public int repeatThumbsDownPercent() {
            return repeatAnswers == 0 ? -1 : (int) Math.round(100.0 * repeatThumbsDown / repeatAnswers);
        }
    }

    /**
     * A question docs0 could not answer well. {@code question} is null when it must not be shown (the answer used
     * a private source). {@code times} counts similar questions in the period. {@code channelId} is where the shown
     * question was asked.
     */
    public record Gap(String question, boolean redacted, long times, Instant latest, String reason, String channelId) {}

    /** The gaps, newest first, and how many gap questions were asked privately (in DMs) and are only counted. */
    public record Gaps(List<Gap> items, long privateCount) {}

    private final JdbcClient jdbc;
    private final InteractionRepository interactions;
    private final StaleDocs stale;

    public InsightsService(JdbcClient jdbc, InteractionRepository interactions, StaleDocs stale) {
        this.jdbc = jdbc;
        this.interactions = interactions;
        this.stale = stale;
    }

    public static int period(Integer days) {
        return days != null && PERIODS.contains(days) ? days : DEFAULT_DAYS;
    }

    public Totals totals(long tenantId, Instant since) {
        var from = java.sql.Timestamp.from(since);
        long questions = jdbc.sql("SELECT count(*) FROM interaction WHERE tenant_id = :t AND created_at >= :from AND "
                        + COUNTED)
                .param("t", tenantId).param("from", from).query(Long.class).single();
        long up = reactions(tenantId, from, "UP");
        long down = reactions(tenantId, from, "DOWN");
        long offered = jdbc.sql("""
                        SELECT count(*) FROM proactive_offer
                        WHERE tenant_id = :t AND created_at >= :from AND judged_same AND interaction_id IS NOT NULL
                        """)
                .param("t", tenantId).param("from", from).query(Long.class).single();
        long offeredDown = jdbc.sql("""
                        SELECT count(*) FROM proactive_offer o
                        WHERE o.tenant_id = :t AND o.created_at >= :from AND o.judged_same AND o.interaction_id IS NOT NULL
                          AND EXISTS (SELECT 1 FROM feedback f WHERE f.tenant_id = o.tenant_id
                                      AND f.interaction_id = o.interaction_id AND f.rating = 'DOWN')
                        """)
                .param("t", tenantId).param("from", from).query(Long.class).single();
        BigDecimal spend = jdbc.sql("""
                        SELECT (SELECT coalesce(sum(cost_usd), 0) FROM interaction WHERE tenant_id = :t AND created_at >= :from)
                             + (SELECT coalesce(sum(cost_usd), 0) FROM usage_log WHERE tenant_id = :t AND created_at >= :from)
                        """)
                .param("t", tenantId).param("from", from).query(BigDecimal.class).single();
        return new Totals(questions, up, down, offered, offeredDown, spend);
    }

    private long reactions(long tenantId, java.sql.Timestamp from, String rating) {
        return jdbc.sql("SELECT count(*) FROM feedback WHERE tenant_id = :t AND created_at >= :from AND rating = :r")
                .param("t", tenantId).param("from", from).param("r", rating).query(Long.class).single();
    }

    private record GapRow(long id, String question, String kind, String confidence, boolean dm, boolean down,
            String channelId, Instant createdAt) {}

    /**
     * Questions in the period that got no answer, a low-confidence answer or a 👎, newest first. Similar
     * questions (same words, ignoring case and punctuation) are grouped. DM questions are only counted, and
     * a question whose answer used a private source is listed without its text.
     */
    public Gaps gaps(long tenantId, Instant since) {
        List<GapRow> rows = jdbc.sql("""
                        SELECT i.id, i.question, i.kind, i.confidence, c.is_dm, c.channel_id, i.created_at,
                               EXISTS (SELECT 1 FROM feedback f WHERE f.tenant_id = i.tenant_id
                                       AND f.interaction_id = i.id AND f.rating = 'DOWN') AS down
                        FROM interaction i JOIN conversation c ON c.id = i.conversation_id AND c.tenant_id = i.tenant_id
                        WHERE i.tenant_id = :t AND i.created_at >= :from AND i.kind IN ('ANSWER', 'CLARIFY', 'UNKNOWN')
                          AND (i.kind = 'UNKNOWN' OR i.confidence = 'LOW' OR EXISTS (
                                SELECT 1 FROM feedback f WHERE f.tenant_id = i.tenant_id
                                AND f.interaction_id = i.id AND f.rating = 'DOWN'))
                        ORDER BY i.created_at DESC, i.id DESC
                        LIMIT :limit
                        """)
                .param("t", tenantId).param("from", java.sql.Timestamp.from(since)).param("limit", GAP_ROWS)
                .query((rs, n) -> new GapRow(rs.getLong("id"), rs.getString("question"), rs.getString("kind"),
                        rs.getString("confidence"), rs.getBoolean("is_dm"), rs.getBoolean("down"),
                        rs.getString("channel_id"), rs.getTimestamp("created_at").toInstant()))
                .list();
        Set<Long> privateAnswers = interactions.usedRestricted(tenantId, rows.stream().map(GapRow::id).toList());

        long privateCount = 0;
        List<Gap> items = new ArrayList<>();
        Map<String, Integer> groupIndex = new LinkedHashMap<>();
        for (GapRow row : rows) {
            if (row.dm()) {
                privateCount++;
            } else if (privateAnswers.contains(row.id())) {
                items.add(new Gap(null, true, 1, row.createdAt(), reason(row), row.channelId()));
            } else {
                String key = normalise(row.question());
                Integer at = groupIndex.get(key);
                if (at == null) {
                    groupIndex.put(key, items.size());
                    items.add(new Gap(row.question(), false, 1, row.createdAt(), reason(row), row.channelId()));
                } else {
                    Gap g = items.get(at);
                    items.set(at, new Gap(g.question(), false, g.times() + 1, g.latest(), g.reason(), g.channelId()));
                }
            }
        }
        items.sort(Comparator.comparing(Gap::latest).reversed());
        return new Gaps(List.copyOf(items), privateCount);
    }

    private static String reason(GapRow row) {
        if ("UNKNOWN".equals(row.kind())) {
            return "No answer found";
        }
        if (row.down()) {
            return "Got a thumbs down";
        }
        return "Low confidence";
    }

    /** Lowercase letters and digits only, single spaces: two spellings of one question group together. */
    static String normalise(String question) {
        return question == null ? "" : question.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    public List<StaleDocs.Row> staleDocuments(long tenantId, Instant since) {
        return stale.report(tenantId, since, STALE_ROWS);
    }
}
