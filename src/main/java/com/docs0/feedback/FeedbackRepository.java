package com.docs0.feedback;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Reactions on answers, and the "what was wrong?" prompts that wait for a correction. */
@Repository
public class FeedbackRepository {

    public enum Rating { UP, DOWN }

    /** How long a "what was wrong?" prompt waits for a reply. */
    public static final String PROMPT_LIFETIME = "24 hours";

    /** An answer someone gave a thumbs down. {@code citations} lists the sources it cited. */
    public record UnhappyAnswer(long interactionId, String question, String answer, String askerSlackId, boolean dm,
            List<CitedSource> citations, int thumbsDown, int thumbsUp, Instant createdAt) {}

    public record CitedSource(String title, String url) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    public FeedbackRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records a user's reaction to an answer, replacing an earlier one by the same user. */
    public void record(long tenantId, long interactionId, String slackUserId, Rating rating) {
        jdbc.sql("""
                        INSERT INTO feedback (tenant_id, interaction_id, slack_user_id, rating)
                        SELECT i.tenant_id, i.id, :user, :rating FROM interaction i
                        WHERE i.id = :interactionId AND i.tenant_id = :tenantId
                        ON CONFLICT (interaction_id, slack_user_id) DO UPDATE SET rating = EXCLUDED.rating
                        """)
                .param("tenantId", tenantId).param("interactionId", interactionId)
                .param("user", slackUserId).param("rating", rating.name())
                .update();
    }

    /** Deletes the user's reaction, but only while it is still this rating. */
    public void remove(long tenantId, long interactionId, String slackUserId, Rating rating) {
        jdbc.sql("""
                        DELETE FROM feedback
                        WHERE tenant_id = :tenantId AND interaction_id = :interactionId
                          AND slack_user_id = :user AND rating = :rating
                        """)
                .param("tenantId", tenantId).param("interactionId", interactionId)
                .param("user", slackUserId).param("rating", rating.name())
                .update();
    }

    public int count(long tenantId, long interactionId, Rating rating) {
        return jdbc.sql("""
                        SELECT count(*) FROM feedback
                        WHERE tenant_id = :tenantId AND interaction_id = :interactionId AND rating = :rating
                        """)
                .param("tenantId", tenantId).param("interactionId", interactionId).param("rating", rating.name())
                .query(Integer.class).single();
    }

    // ---- prompts -----------------------------------------------------------------------------

    /** Marks the thread as waiting for a correction. False when this answer was prompted before. */
    public boolean createPrompt(long tenantId, long conversationId, long interactionId) {
        return jdbc.sql("""
                        INSERT INTO feedback_prompt (tenant_id, conversation_id, interaction_id)
                        SELECT i.tenant_id, i.conversation_id, i.id FROM interaction i
                        WHERE i.id = :interactionId AND i.tenant_id = :tenantId AND i.conversation_id = :conversationId
                        ON CONFLICT (interaction_id) DO NOTHING
                        """)
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .param("interactionId", interactionId)
                .update() == 1;
    }

    /** Forgets a prompt that could not be posted, so a later thumbs down can try again. */
    public void deletePrompt(long tenantId, long interactionId) {
        jdbc.sql("DELETE FROM feedback_prompt WHERE tenant_id = :tenantId AND interaction_id = :interactionId")
                .param("tenantId", tenantId).param("interactionId", interactionId).update();
    }

    /** The answer the thread's pending prompt is about, if a prompt is still waiting (under 24 hours old). */
    public Optional<Long> pendingPrompt(long tenantId, long conversationId) {
        return jdbc.sql("""
                        SELECT interaction_id FROM feedback_prompt
                        WHERE tenant_id = :tenantId AND conversation_id = :conversationId AND cleared_at IS NULL
                          AND created_at > now() - CAST(:lifetime AS interval)
                        ORDER BY created_at DESC LIMIT 1
                        """)
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .param("lifetime", PROMPT_LIFETIME)
                .query(Long.class).optional();
    }

    public void clearPrompts(long tenantId, long conversationId) {
        jdbc.sql("""
                        UPDATE feedback_prompt SET cleared_at = now()
                        WHERE tenant_id = :tenantId AND conversation_id = :conversationId AND cleared_at IS NULL
                        """)
                .param("tenantId", tenantId).param("conversationId", conversationId).update();
    }

    // ---- the dashboard ---------------------------------------------------------------------------

    /** Interactions with at least one thumbs down, newest first. */
    public List<UnhappyAnswer> unhappy(long tenantId, int limit) {
        return jdbc.sql("""
                        SELECT i.id, i.question, i.answer, i.slack_user_id, c.is_dm, i.citations::text AS citations,
                               i.created_at,
                               count(*) FILTER (WHERE f.rating = 'DOWN') AS down,
                               count(*) FILTER (WHERE f.rating = 'UP') AS up
                        FROM interaction i
                        JOIN conversation c ON c.id = i.conversation_id AND c.tenant_id = i.tenant_id
                        JOIN feedback f ON f.interaction_id = i.id AND f.tenant_id = i.tenant_id
                        WHERE i.tenant_id = :tenantId
                        GROUP BY i.id, c.is_dm
                        HAVING count(*) FILTER (WHERE f.rating = 'DOWN') > 0
                        ORDER BY i.created_at DESC, i.id DESC
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId).param("limit", limit)
                .query((rs, n) -> new UnhappyAnswer(rs.getLong("id"), rs.getString("question"),
                        rs.getString("answer"), rs.getString("slack_user_id"), rs.getBoolean("is_dm"),
                        sources(rs.getString("citations")), rs.getInt("down"), rs.getInt("up"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private static List<CitedSource> sources(String json) {
        try {
            List<Map<String, Object>> items = JSON.readValue(json, new TypeReference<>() {});
            return items.stream()
                    .map(m -> new CitedSource(String.valueOf(m.getOrDefault("title", "")),
                            m.get("url") instanceof String url ? url : null))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
