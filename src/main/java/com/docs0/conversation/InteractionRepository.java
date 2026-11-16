package com.docs0.conversation;

import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Citation;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
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
        List<Citation> citations = result.citations() == null ? List.of() : result.citations();
        return insertRow(tenantId, conversationId, slackUserId, question, result.answer(), result.kind().name(),
                result.confidence() == null ? null : result.confidence().name(), JSON.writeValueAsString(citations),
                result.steps(), result.inputTokens(), result.outputTokens(),
                result.costUsd() == null ? BigDecimal.ZERO : result.costUsd(), result.durationMs(), result.error(),
                answerTs);
    }

    /** Stores a question that was refused because the monthly budget is used up. No model call, no cost. */
    public long insertOverBudget(long tenantId, long conversationId, String slackUserId, String question,
            String reply, String answerTs) {
        return insertRow(tenantId, conversationId, slackUserId, question, reply, KIND_OVER_BUDGET, null, "[]",
                0, 0, 0, BigDecimal.ZERO, 0, null, answerTs);
    }

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
            BigDecimal costUsd, long durationMs, String error, String answerTs) {
        return jdbc.sql("""
                        INSERT INTO interaction (tenant_id, conversation_id, slack_user_id, question, answer, kind,
                            confidence, citations, steps, input_tokens, output_tokens, cost_usd, duration_ms, error,
                            answer_ts)
                        SELECT c.tenant_id, c.id, :slackUserId, :question, :answer, :kind,
                            :confidence, CAST(:citations AS jsonb), :steps, :inputTokens, :outputTokens, :costUsd,
                            :durationMs, :error, :answerTs
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
                .query(Long.class)
                .single();
    }
}
