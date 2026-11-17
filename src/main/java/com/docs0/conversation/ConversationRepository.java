package com.docs0.conversation;

import com.docs0.agent.Agent.Exchange;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Conversations: one per Slack thread, or one for a whole DM (thread_ts = ''). */
@Repository
public class ConversationRepository {

    /** How many earlier exchanges are replayed to the agent. */
    public static final int HISTORY_LIMIT = 10;

    private final JdbcClient jdbc;

    public ConversationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the conversation for this channel and thread, creating it if needed.
     * Pass the empty string as {@code threadTs} for a DM written outside a thread.
     */
    public Conversation findOrCreate(long tenantId, String channelId, String threadTs, boolean dm) {
        return findOrCreate(tenantId, channelId, threadTs, dm, dm ? Boolean.TRUE : null);
    }

    /**
     * As above, recording whether the channel is private: TRUE (a DM is always TRUE), FALSE, or null when unknown.
     * A known value is never replaced by null; an unknown one is filled in once the privacy is known.
     */
    public Conversation findOrCreate(long tenantId, String channelId, String threadTs, boolean dm,
            Boolean channelPrivate) {
        return jdbc.sql("""
                        INSERT INTO conversation (tenant_id, channel_id, thread_ts, is_dm, channel_private)
                        VALUES (:tenantId, :channelId, :threadTs, :dm, :channelPrivate)
                        ON CONFLICT (tenant_id, channel_id, thread_ts)
                        DO UPDATE SET is_dm = conversation.is_dm,
                                      channel_private = coalesce(conversation.channel_private, EXCLUDED.channel_private)
                        RETURNING id, tenant_id, channel_id, thread_ts, is_dm
                        """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("threadTs", threadTs == null ? "" : threadTs)
                .param("dm", dm)
                .param("channelPrivate", channelPrivate, java.sql.Types.BOOLEAN)
                .query((rs, n) -> new Conversation(rs.getLong("id"), rs.getLong("tenant_id"),
                        rs.getString("channel_id"), rs.getString("thread_ts"), rs.getBoolean("is_dm")))
                .single();
    }

    /** The conversation of this channel and thread, if there is one. */
    public Optional<Conversation> find(long tenantId, String channelId, String threadTs) {
        return jdbc.sql("""
                        SELECT id, tenant_id, channel_id, thread_ts, is_dm FROM conversation
                        WHERE tenant_id = :tenantId AND channel_id = :channelId AND thread_ts = :threadTs
                        """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("threadTs", threadTs == null ? "" : threadTs)
                .query((rs, n) -> new Conversation(rs.getLong("id"), rs.getLong("tenant_id"),
                        rs.getString("channel_id"), rs.getString("thread_ts"), rs.getBoolean("is_dm")))
                .optional();
    }

    /** True when docs0 has already replied to at least one question in this channel and thread. */
    public boolean hasInteractions(long tenantId, String channelId, String threadTs) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM conversation c
                            JOIN interaction i ON i.conversation_id = c.id AND i.tenant_id = c.tenant_id
                            WHERE c.tenant_id = :tenantId AND c.channel_id = :channelId AND c.thread_ts = :threadTs)
                        """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("threadTs", threadTs == null ? "" : threadTs)
                .query(Boolean.class)
                .single();
    }

    /**
     * The last {@link #HISTORY_LIMIT} answered exchanges of a conversation, oldest first.
     * Errors and over-budget replies are not part of the history.
     */
    public List<Exchange> history(long tenantId, long conversationId) {
        return jdbc.sql("""
                        SELECT question, answer FROM (
                            SELECT id, question, answer FROM interaction
                            WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                              AND answer IS NOT NULL AND kind IN ('ANSWER', 'CLARIFY', 'UNKNOWN')
                            ORDER BY id DESC
                            LIMIT :limit) recent
                        ORDER BY id ASC
                        """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("limit", HISTORY_LIMIT)
                .query((rs, n) -> new Exchange(rs.getString("question"), rs.getString("answer")))
                .list();
    }
}
