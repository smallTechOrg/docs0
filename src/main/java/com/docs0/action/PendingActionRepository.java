package com.docs0.action;

import com.docs0.action.PendingAction.Kind;
import com.docs0.action.PendingAction.Status;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Drafts waiting for a person's confirmation. Every statement is tenant-scoped. The status
 * changes are single UPDATE ... WHERE status = 'PENDING' statements, so of two clicks on the
 * same button exactly one wins.
 */
@Repository
public class PendingActionRepository {

    private static final Gson GSON = new Gson();
    private static final String COLUMNS = "id, tenant_id, conversation_id, interaction_id, requested_by_slack_id, "
            + "kind, CAST(params AS text) AS params, status, result_url, error, created_at, resolved_at";

    private static final RowMapper<PendingAction> MAPPER = (rs, n) -> new PendingAction(rs.getLong("id"),
            rs.getLong("tenant_id"), rs.getLong("conversation_id"), (Long) rs.getObject("interaction_id"),
            rs.getString("requested_by_slack_id"), Kind.valueOf(rs.getString("kind")),
            GSON.<Map<String, String>>fromJson(rs.getString("params"), new TypeToken<Map<String, String>>() {}.getType()),
            Status.valueOf(rs.getString("status")), rs.getString("result_url"), rs.getString("error"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(), instant(rs.getObject("resolved_at", OffsetDateTime.class)));

    private final JdbcClient jdbc;

    public PendingActionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores a new draft, PENDING. The conversation must belong to the tenant. */
    public PendingAction insert(long tenantId, long conversationId, String requestedBy, Kind kind,
                                Map<String, String> params) {
        return jdbc.sql("""
                        INSERT INTO pending_action (tenant_id, conversation_id, requested_by_slack_id, kind, params)
                        SELECT c.tenant_id, c.id, :requestedBy, :kind, CAST(:params AS jsonb) FROM conversation c
                        WHERE c.tenant_id = :tenant AND c.id = :conversation
                        RETURNING %s
                        """.formatted(COLUMNS))
                .param("tenant", tenantId)
                .param("conversation", conversationId)
                .param("requestedBy", requestedBy)
                .param("kind", kind.name())
                .param("params", GSON.toJson(new LinkedHashMap<>(params)))
                .query(MAPPER)
                .single();
    }

    public Optional<PendingAction> find(long tenantId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM pending_action WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenantId)
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /** Links drafts to the interaction (the question and answer) they came from. */
    public void attachInteraction(long tenantId, Collection<Long> ids, long interactionId) {
        for (long id : ids) {
            jdbc.sql("""
                            UPDATE pending_action SET interaction_id = :interaction
                            WHERE tenant_id = :tenant AND id = :id
                              AND EXISTS (SELECT 1 FROM interaction i WHERE i.id = :interaction AND i.tenant_id = :tenant)
                            """)
                    .param("tenant", tenantId)
                    .param("id", id)
                    .param("interaction", interactionId)
                    .update();
        }
    }

    /**
     * Takes the draft for creation: PENDING to DONE, only for the person who asked and only
     * when it is newer than {@code notBefore}. Empty when someone else, a second click or time
     * got there first.
     */
    public Optional<PendingAction> claim(long tenantId, long id, String user, Instant notBefore) {
        return jdbc.sql("""
                        UPDATE pending_action SET status = 'DONE', resolved_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND status = 'PENDING'
                          AND requested_by_slack_id = :user AND created_at > :notBefore
                        RETURNING %s
                        """.formatted(COLUMNS))
                .param("tenant", tenantId)
                .param("id", id)
                .param("user", user)
                .param("notBefore", notBefore.atOffset(ZoneOffset.UTC))
                .query(MAPPER)
                .optional();
    }

    /** Records where a claimed draft was created. */
    public void markCreated(long tenantId, long id, String url) {
        jdbc.sql("UPDATE pending_action SET result_url = :url WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenantId).param("id", id).param("url", url).update();
    }

    /** A claimed draft that could not be created. */
    public void markFailed(long tenantId, long id, String error) {
        jdbc.sql("""
                        UPDATE pending_action SET status = 'FAILED', error = :error, resolved_at = now()
                        WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("tenant", tenantId).param("id", id).param("error", error, Types.VARCHAR).update();
    }

    /** PENDING to CANCELLED, only for the person who asked. True when it changed. */
    public boolean cancel(long tenantId, long id, String user) {
        return jdbc.sql("""
                        UPDATE pending_action SET status = 'CANCELLED', resolved_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND status = 'PENDING' AND requested_by_slack_id = :user
                        """)
                .param("tenant", tenantId).param("id", id).param("user", user).update() > 0;
    }

    /** PENDING to CANCELLED without asking who: a draft that never reached the thread. */
    public void discard(long tenantId, long id) {
        jdbc.sql("""
                        UPDATE pending_action SET status = 'CANCELLED', resolved_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND status = 'PENDING'
                        """)
                .param("tenant", tenantId).param("id", id).update();
    }

    /** PENDING to EXPIRED when it is not newer than {@code notBefore}. True when it changed. */
    public boolean expire(long tenantId, long id, Instant notBefore) {
        return jdbc.sql("""
                        UPDATE pending_action SET status = 'EXPIRED', resolved_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND status = 'PENDING' AND created_at <= :notBefore
                        """)
                .param("tenant", tenantId).param("id", id).param("notBefore", notBefore.atOffset(ZoneOffset.UTC))
                .update() > 0;
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
