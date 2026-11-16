package com.docs0;

import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Kind;
import com.docs0.conversation.Conversation;
import com.docs0.conversation.ConversationRepository;
import com.docs0.conversation.InteractionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Rows for tests, written through the real repositories. */
public final class TestData {

    private TestData() {}

    public static Conversation conversation(JdbcClient jdbc, long tenantId, String channel, String threadTs,
            boolean dm) {
        return new ConversationRepository(jdbc).findOrCreate(tenantId, channel, threadTs, dm);
    }

    /** One logged answer with the given Slack reply ts and cost; returns the interaction id. */
    public static long interaction(JdbcClient jdbc, Conversation conversation, String user, String question,
            String answer, String answerTs, String cost) {
        AgentResult result = new AgentResult(Kind.ANSWER, answer, Confidence.HIGH, List.of(), List.of(), List.of(), 2,
                1000, 100, new BigDecimal(cost), 10, null);
        return new InteractionRepository(jdbc).insert(conversation.tenantId(), conversation.id(), user, question,
                result, answerTs);
    }

    /** Moves an interaction in time, for per-day and per-month reports. */
    public static void backdate(JdbcClient jdbc, long interactionId, Instant createdAt) {
        jdbc.sql("UPDATE interaction SET created_at = :t WHERE id = :id")
                .param("t", java.sql.Timestamp.from(createdAt)).param("id", interactionId).update();
    }

    /** A correction row in the given status; returns its id. */
    public static long correction(JdbcClient jdbc, long tenantId, String status, String author, String question,
            String text, String editedText, boolean fromDm) {
        return jdbc.sql("""
                        INSERT INTO correction (tenant_id, author_slack_id, author_name, question, original_answer,
                            correction_text, edited_text, status, from_dm, reviewed_at)
                        VALUES (:t, 'U9', :author, :question, 'The old answer.', :text, :edited, :status, :dm,
                            CASE WHEN :status = 'PENDING' THEN NULL ELSE now() END)
                        RETURNING id
                        """)
                .param("t", tenantId).param("author", author).param("question", question).param("text", text)
                .param("edited", editedText, java.sql.Types.VARCHAR).param("status", status).param("dm", fromDm)
                .query(Long.class).single();
    }
}
