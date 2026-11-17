package com.docs0.feedback;

import com.docs0.agent.CorrectionSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Corrections: stored as pending, reviewed by an admin, and searched once approved. */
@Repository
public class CorrectionRepository implements CorrectionSource {

    /** What a correction made in a DM is credited to when it is passed on to the model. */
    public static final String DM_AUTHOR = "a teammate";

    private static final String COLUMNS = """
            id, interaction_id, author_slack_id, author_name, question, original_answer, correction_text,
            edited_text, status, from_dm, created_at, reviewed_at""";

    private static final RowMapper<Correction> ROW = CorrectionRepository::row;

    private final JdbcClient jdbc;

    public CorrectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores a new pending correction and returns its id. The interaction, when given, must belong to the tenant. */
    public long insertPending(long tenantId, Long interactionId, String authorSlackId, String authorName,
            String question, String originalAnswer, String correctionText, boolean fromDm) {
        return jdbc.sql("""
                        INSERT INTO correction (tenant_id, interaction_id, author_slack_id, author_name, question,
                            original_answer, correction_text, from_dm)
                        SELECT :tenantId, CAST(:interactionId AS bigint), :authorId, :authorName, :question, :answer,
                               :text, :fromDm
                        WHERE CAST(:interactionId AS bigint) IS NULL
                           OR EXISTS (SELECT 1 FROM interaction i WHERE i.id = :interactionId AND i.tenant_id = :tenantId)
                        RETURNING id
                        """)
                .param("tenantId", tenantId)
                .param("interactionId", interactionId, java.sql.Types.BIGINT)
                .param("authorId", authorSlackId)
                .param("authorName", authorName)
                .param("question", question)
                .param("answer", originalAnswer == null ? "" : originalAnswer)
                .param("text", correctionText)
                .param("fromDm", fromDm)
                .query(Long.class)
                .single();
    }

    public long countPending(long tenantId) {
        return jdbc.sql("SELECT count(*) FROM correction WHERE tenant_id = :tenantId AND status = 'PENDING'")
                .param("tenantId", tenantId)
                .query(Long.class)
                .single();
    }

    public List<Correction> pending(long tenantId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM correction WHERE tenant_id = :tenantId AND status = 'PENDING'
                        ORDER BY created_at DESC, id DESC LIMIT :limit
                        """)
                .param("tenantId", tenantId).param("limit", limit).query(ROW).list();
    }

    /** The most recently reviewed corrections, approved or rejected. */
    public List<Correction> reviewed(long tenantId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM correction WHERE tenant_id = :tenantId AND status IN ('APPROVED', 'REJECTED')
                        ORDER BY reviewed_at DESC, id DESC LIMIT :limit
                        """)
                .param("tenantId", tenantId).param("limit", limit).query(ROW).list();
    }

    /**
     * Approves a pending correction. {@code editedText} is stored only when it differs from the
     * extracted fact. Returns false when there is no such pending correction.
     */
    public boolean approve(long tenantId, long id, String editedText) {
        String edited = editedText == null || editedText.isBlank() ? null : editedText.strip();
        return jdbc.sql("""
                        UPDATE correction
                        SET status = 'APPROVED', reviewed_at = now(),
                            edited_text = CASE WHEN CAST(:edited AS text) IS NULL
                                                    OR CAST(:edited AS text) = correction_text
                                               THEN NULL ELSE CAST(:edited AS text) END
                        WHERE id = :id AND tenant_id = :tenantId AND status = 'PENDING'
                        """)
                .param("edited", edited, java.sql.Types.VARCHAR)
                .param("id", id).param("tenantId", tenantId)
                .update() == 1;
    }

    public boolean reject(long tenantId, long id) {
        return jdbc.sql("""
                        UPDATE correction SET status = 'REJECTED', reviewed_at = now()
                        WHERE id = :id AND tenant_id = :tenantId AND status = 'PENDING'
                        """)
                .param("id", id).param("tenantId", tenantId).update() == 1;
    }

    /** Takes an approved correction back out of use. */
    public boolean unapprove(long tenantId, long id) {
        return jdbc.sql("""
                        UPDATE correction SET status = 'REJECTED', reviewed_at = now()
                        WHERE id = :id AND tenant_id = :tenantId AND status = 'APPROVED'
                        """)
                .param("id", id).param("tenantId", tenantId).update() == 1;
    }

    /**
     * Full-text match of the question against approved corrections. Any shared word counts
     * (a question is a sentence, a correction a short fact), and more shared words rank higher.
     */
    @Override
    public List<ApprovedCorrection> find(long tenantId, String question, int limit) {
        if (question == null || question.isBlank() || limit <= 0) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT c.id, c.question, coalesce(c.edited_text, c.correction_text) AS fact,
                               c.author_name, c.from_dm, c.created_at
                        FROM correction c,
                             (SELECT replace(websearch_to_tsquery('english', :question)::text, ' & ', ' | ')::tsquery AS q) m
                        WHERE c.tenant_id = :tenantId AND c.status = 'APPROVED' AND c.search @@ m.q
                        ORDER BY ts_rank(c.search, m.q) DESC, c.reviewed_at DESC, c.id DESC
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId)
                .param("question", question)
                .param("limit", limit)
                .query((rs, n) -> {
                    boolean dm = rs.getBoolean("from_dm");
                    return new ApprovedCorrection(rs.getLong("id"), dm ? null : rs.getString("question"),
                            rs.getString("fact"), dm ? DM_AUTHOR : rs.getString("author_name"),
                            instant(rs, "created_at"));
                })
                .list();
    }

    private static Correction row(ResultSet rs, int n) throws SQLException {
        long interaction = rs.getLong("interaction_id");
        return new Correction(rs.getLong("id"), rs.wasNull() ? null : interaction, rs.getString("author_slack_id"),
                rs.getString("author_name"), rs.getString("question"), rs.getString("original_answer"),
                rs.getString("correction_text"), rs.getString("edited_text"),
                Correction.Status.valueOf(rs.getString("status")), rs.getBoolean("from_dm"),
                instant(rs, "created_at"), instant(rs, "reviewed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
