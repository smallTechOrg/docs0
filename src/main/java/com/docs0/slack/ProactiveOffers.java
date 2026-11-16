package com.docs0.slack;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The log of channel messages docs0 considered answering unprompted. One row per message (the
 * channel and ts are unique), so a message is never offered twice, and the rows drive the rate limit.
 */
@Repository
public class ProactiveOffers {

    private final JdbcClient jdbc;

    public ProactiveOffers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Reserves the message. Empty when it was reserved before. {@code matchedInteractionId} is the best match. */
    public OptionalLong claim(long tenantId, String channel, String ts, long matchedInteractionId) {
        return jdbc.sql("""
                        INSERT INTO proactive_offer (tenant_id, channel_id, message_ts, matched_interaction_id)
                        VALUES (:tenantId, :channel, :ts, :matched)
                        ON CONFLICT (tenant_id, channel_id, message_ts) DO NOTHING
                        RETURNING id
                        """)
                .param("tenantId", tenantId).param("channel", channel).param("ts", ts).param("matched", matchedInteractionId)
                .query(Long.class).optional().map(OptionalLong::of).orElse(OptionalLong.empty());
    }

    public boolean exists(long tenantId, String channel, String ts) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM proactive_offer
                                       WHERE tenant_id = :tenantId AND channel_id = :channel AND message_ts = :ts)
                        """)
                .param("tenantId", tenantId).param("channel", channel).param("ts", ts)
                .query(Boolean.class).single();
    }

    /** The judge said the message repeats an earlier question. */
    public void markSame(long tenantId, long id) {
        jdbc.sql("UPDATE proactive_offer SET judged_same = true WHERE id = :id AND tenant_id = :tenantId")
                .param("id", id).param("tenantId", tenantId).update();
    }

    /** Ties the offer to the answer that was posted. */
    public void attachInteraction(long tenantId, long id, long interactionId) {
        jdbc.sql("UPDATE proactive_offer SET interaction_id = :interactionId WHERE id = :id AND tenant_id = :tenantId")
                .param("id", id).param("tenantId", tenantId).param("interactionId", interactionId).update();
    }

    /** Offers made in the channel since the given time. */
    public long offersSince(long tenantId, String channel, Instant since) {
        return jdbc.sql("""
                        SELECT count(*) FROM proactive_offer
                        WHERE tenant_id = :tenantId AND channel_id = :channel AND judged_same AND created_at >= :since
                        """)
                .param("tenantId", tenantId).param("channel", channel).param("since", java.sql.Timestamp.from(since))
                .query(Long.class).single();
    }

    /** Messages judged in the channel since the given time, whatever the verdict. Each cost a model call. */
    public long judgedSince(long tenantId, String channel, Instant since) {
        return jdbc.sql("""
                        SELECT count(*) FROM proactive_offer
                        WHERE tenant_id = :tenantId AND channel_id = :channel AND created_at >= :since
                        """)
                .param("tenantId", tenantId).param("channel", channel).param("since", java.sql.Timestamp.from(since))
                .query(Long.class).single();
    }

    public Optional<Instant> lastOfferAt(long tenantId, String channel) {
        return jdbc.sql("""
                        SELECT max(created_at) FROM proactive_offer
                        WHERE tenant_id = :tenantId AND channel_id = :channel AND judged_same
                        """)
                .param("tenantId", tenantId).param("channel", channel)
                .query((rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant())
                .optional().filter(java.util.Objects::nonNull);
    }

    /** True when someone gave a thumbs down to an unprompted answer in the channel since the given time. */
    public boolean mutedSince(long tenantId, String channel, Instant since) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM proactive_offer o
                            JOIN feedback f ON f.tenant_id = o.tenant_id AND f.interaction_id = o.interaction_id
                                           AND f.rating = 'DOWN'
                            WHERE o.tenant_id = :tenantId AND o.channel_id = :channel AND o.created_at >= :since)
                        """)
                .param("tenantId", tenantId).param("channel", channel).param("since", java.sql.Timestamp.from(since))
                .query(Boolean.class).single();
    }
}
