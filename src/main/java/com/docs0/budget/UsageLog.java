package com.docs0.budget;

import java.math.BigDecimal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Model calls that are not a user's question: judging a repeat question, clustering themes. Their cost
 * counts toward the monthly cap together with the interaction log (see {@link BudgetService}).
 */
@Repository
public class UsageLog {

    public static final String PROACTIVE_JUDGE = "proactive_judge";
    public static final String PROACTIVE_ANSWER = "proactive_answer";
    public static final String THEMES = "themes";

    private final JdbcClient jdbc;

    public UsageLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(long tenantId, String purpose, long inputTokens, long outputTokens, BigDecimal costUsd) {
        jdbc.sql("""
                        INSERT INTO usage_log (tenant_id, purpose, input_tokens, output_tokens, cost_usd)
                        VALUES (:tenantId, :purpose, :inputTokens, :outputTokens, :costUsd)
                        """)
                .param("tenantId", tenantId).param("purpose", purpose).param("inputTokens", inputTokens)
                .param("outputTokens", outputTokens).param("costUsd", costUsd == null ? BigDecimal.ZERO : costUsd)
                .update();
    }
}
