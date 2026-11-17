package com.docs0.conversation;

import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Citation;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** The log of every question docs0 was asked and what it replied. */
@Repository
public class InteractionRepository {

    public static final String KIND_OVER_BUDGET = "OVER_BUDGET";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    public InteractionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores one agent result (kind ANSWER, CLARIFY, UNKNOWN or ERROR). {@code answerTs} is the
     * Slack ts of docs0's reply. Returns the new id.
     */
    public long insert(long tenantId, long conversationId, String slackUserId, String question,
            AgentResult result, String answerTs) {
        List<Long> correctionIds = result.correctionIds() == null ? List.of() : result.correctionIds();
        List<Citation> citations = result.citations() == null ? List.of() : result.citations();
        boolean restricted = result.usedRestricted() || anyRestricted(tenantId, citations.stream().map(Citation::documentId).toList());
        return insertRow(tenantId, conversationId, slackUserId, question, result.answer(), result.kind().name(),
                result.confidence() == null ? null : result.confidence().name(), JSON.writeValueAsString(citations),
                result.steps(), result.inputTokens(), result.outputTokens(),
                result.costUsd() == null ? BigDecimal.ZERO : result.costUsd(), result.durationMs(), result.error(),
                answerTs, JSON.writeValueAsString(correctionIds), restricted);
    }

    /** True when any of these documents is restricted (a private channel, a mailbox). */
    private boolean anyRestricted(long tenantId, List<Long> documentIds) {
        if (documentIds.isEmpty()) {
            return false;
        }
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM document WHERE tenant_id = :tenantId AND restricted AND id IN (:ids))")
                .param("tenantId", tenantId).param("ids", documentIds)
                .query(Boolean.class).single();
    }

    /**
     * Which of these answers used a restricted document, so admin pages must not show their text. An
     * answer counts when it was logged as such, or when any document it cited is restricted now (which
     * also covers answers logged before the flag existed). Err on the side of hiding.
     */
    public Set<Long> usedRestricted(long tenantId, Collection<Long> interactionIds) {
        if (interactionIds == null || interactionIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                        SELECT i.id FROM interaction i
                        WHERE i.tenant_id = :tenantId AND i.id IN (:ids)
                          AND (i.used_restricted OR EXISTS (
                                SELECT 1 FROM jsonb_array_elements(
                                         CASE WHEN jsonb_typeof(i.citations) = 'array' THEN i.citations ELSE '[]'::jsonb END) c
                                JOIN document d ON d.tenant_id = i.tenant_id AND CAST(d.id AS text) = c ->> 'documentId'
                                WHERE d.restricted))
                        """)
                .param("tenantId", tenantId).param("ids", List.copyOf(interactionIds))
                .query(Long.class).list());
    }

    /** Stores a question that was refused because the monthly budget is used up. No model call, no cost. */
    public long insertOverBudget(long tenantId, long conversationId, String slackUserId, String question,
            String reply, String answerTs) {
        return insertRow(tenantId, conversationId, slackUserId, question, reply, KIND_OVER_BUDGET, null, "[]",
                0, 0, 0, BigDecimal.ZERO, 0, null, answerTs, "[]", false);
    }

    /** A logged answer as feedback and corrections need to see it. */
    public record Logged(long id, long conversationId, String threadTs, boolean dm, String question, String answer) {}

    /** The interaction whose reply is the Slack message {@code answerTs} in {@code channel}, if any. */
    public Optional<Logged> findByAnswerTs(long tenantId, String channel, String answerTs) {
        return jdbc.sql(LOGGED + """
                         WHERE i.tenant_id = :tenantId AND c.channel_id = :channel AND i.answer_ts = :ts
                        ORDER BY i.id DESC LIMIT 1
                        """)
                .param("tenantId", tenantId).param("channel", channel).param("ts", answerTs)
                .query(INTERACTION).optional();
    }

    public Optional<Logged> findById(long tenantId, long interactionId) {
        return jdbc.sql(LOGGED + " WHERE i.tenant_id = :tenantId AND i.id = :id")
                .param("tenantId", tenantId).param("id", interactionId)
                .query(INTERACTION).optional();
    }

    /** The latest real answer (not an error or a budget refusal) of a conversation. */
    public Optional<Logged> latestAnswer(long tenantId, long conversationId) {
        return jdbc.sql(LOGGED + """
                         WHERE i.tenant_id = :tenantId AND i.conversation_id = :conversationId
                          AND i.answer IS NOT NULL AND i.kind IN ('ANSWER', 'CLARIFY', 'UNKNOWN')
                        ORDER BY i.id DESC LIMIT 1
                        """)
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .query(INTERACTION).optional();
    }

    /** Adds the cost of a side call (such as classifying a reply) to the answer it was about. */
    public void addCost(long tenantId, long interactionId, long inputTokens, long outputTokens, BigDecimal costUsd) {
        jdbc.sql("""
                        UPDATE interaction SET input_tokens = input_tokens + :inputTokens,
                            output_tokens = output_tokens + :outputTokens, cost_usd = cost_usd + :costUsd
                        WHERE id = :id AND tenant_id = :tenantId
                        """)
                .param("id", interactionId).param("tenantId", tenantId).param("inputTokens", inputTokens)
                .param("outputTokens", outputTokens).param("costUsd", costUsd)
                .update();
    }

    /** A past question that looks like a new one. */
    public record SimilarQuestion(long id, double rank) {}

    /**
     * Past questions of the tenant that look like {@code question}, best first: asked in a channel (never a DM),
     * answered (kind ANSWER) with MEDIUM or HIGH confidence, without a 👎, without a private source, and not
     * asked in the thread {@code (excludeChannel, excludeThreadTs)}. Any shared word counts (like the
     * corrections matcher) and more shared words rank higher; matches ranked below {@code minRank} are dropped.
     */
    public List<SimilarQuestion> similarAnswered(long tenantId, String question, String excludeChannel,
            String excludeThreadTs, double minRank, int limit) {
        if (question == null || question.isBlank() || limit <= 0) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT i.id, ts_rank(i.question_search, m.q) AS rank
                        FROM interaction i
                        JOIN conversation c ON c.id = i.conversation_id AND c.tenant_id = i.tenant_id,
                             (SELECT replace(websearch_to_tsquery('english', :question)::text, ' & ', ' | ')::tsquery AS q) m
                        WHERE i.tenant_id = :tenantId AND NOT c.is_dm AND c.channel_private = FALSE
                          AND i.kind = 'ANSWER' AND i.confidence IN ('MEDIUM', 'HIGH') AND NOT i.used_restricted
                          AND i.question_search @@ m.q
                          AND NOT (c.channel_id = :channel AND c.thread_ts = :threadTs)
                          AND ts_rank(i.question_search, m.q) >= :minRank
                          AND NOT EXISTS (SELECT 1 FROM feedback f WHERE f.tenant_id = i.tenant_id
                                          AND f.interaction_id = i.id AND f.rating = 'DOWN')
                          AND NOT EXISTS (SELECT 1 FROM proactive_offer o
                                          JOIN feedback f ON f.tenant_id = o.tenant_id AND f.interaction_id = o.interaction_id
                                                         AND f.rating = 'DOWN'
                                          WHERE o.tenant_id = i.tenant_id AND o.matched_interaction_id = i.id)
                        ORDER BY rank DESC, i.id DESC
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId).param("question", question).param("channel", excludeChannel)
                .param("threadTs", excludeThreadTs).param("minRank", minRank).param("limit", limit)
                .query((rs, n) -> new SimilarQuestion(rs.getLong("id"), rs.getDouble("rank")))
                .list();
    }

    /** The text of past questions, by id, for the ones that belong to the tenant. */
    public java.util.Map<Long, String> questionTexts(long tenantId, Collection<Long> ids) {
        java.util.Map<Long, String> texts = new java.util.LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return texts;
        }
        jdbc.sql("SELECT id, question FROM interaction WHERE tenant_id = :tenantId AND id IN (:ids) ORDER BY id")
                .param("tenantId", tenantId).param("ids", List.copyOf(ids))
                .query((rs, n) -> new java.util.AbstractMap.SimpleEntry<>(rs.getLong("id"), rs.getString("question")))
                .list().forEach(e -> texts.put(e.getKey(), e.getValue()));
        return texts;
    }

    private static final String LOGGED = """
            SELECT i.id, i.conversation_id, c.thread_ts, c.is_dm, i.question, i.answer
            FROM interaction i JOIN conversation c ON c.id = i.conversation_id AND c.tenant_id = i.tenant_id
            """;

    private static final RowMapper<Logged> INTERACTION = (rs, n) -> new Logged(rs.getLong("id"),
            rs.getLong("conversation_id"), rs.getString("thread_ts"), rs.getBoolean("is_dm"),
            rs.getString("question"), rs.getString("answer"));

    /** Number of interactions logged for the tenant. */
    public long count(long tenantId) {
        return jdbc.sql("SELECT count(*) FROM interaction WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query(Long.class)
                .single();
    }

    // The SELECT ... FROM conversation makes the insert a no-op (and single() fail) if the
    // conversation does not belong to the tenant.
    private long insertRow(long tenantId, long conversationId, String slackUserId, String question, String answer,
            String kind, String confidence, String citationsJson, int steps, long inputTokens, long outputTokens,
            BigDecimal costUsd, long durationMs, String error, String answerTs, String correctionIdsJson, boolean usedRestricted) {
        return jdbc.sql("""
                        INSERT INTO interaction (tenant_id, conversation_id, slack_user_id, question, answer, kind,
                            confidence, citations, steps, input_tokens, output_tokens, cost_usd, duration_ms, error,
                            answer_ts, correction_ids, used_restricted)
                        SELECT c.tenant_id, c.id, :slackUserId, :question, :answer, :kind,
                            :confidence, CAST(:citations AS jsonb), :steps, :inputTokens, :outputTokens, :costUsd,
                            :durationMs, :error, :answerTs, CAST(:correctionIds AS jsonb), :usedRestricted
                        FROM conversation c
                        WHERE c.id = :conversationId AND c.tenant_id = :tenantId
                        RETURNING id
                        """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("slackUserId", slackUserId)
                .param("question", question)
                .param("answer", answer, Types.VARCHAR)
                .param("kind", kind)
                .param("confidence", confidence, Types.VARCHAR)
                .param("citations", citationsJson)
                .param("steps", steps)
                .param("inputTokens", inputTokens)
                .param("outputTokens", outputTokens)
                .param("costUsd", costUsd)
                .param("durationMs", durationMs)
                .param("error", error, Types.VARCHAR)
                .param("answerTs", answerTs, Types.VARCHAR)
                .param("correctionIds", correctionIdsJson)
                .param("usedRestricted", usedRestricted)
                .query(Long.class)
                .single();
    }
}
