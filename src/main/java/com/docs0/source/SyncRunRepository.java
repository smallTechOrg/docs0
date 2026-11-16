package com.docs0.source;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The sync log. */
@Repository
public class SyncRunRepository {

    private static final String COLUMNS =
            "id, tenant_id, source_id, status, started_at, finished_at, added, updated, removed, skipped, failed, error";

    private static final RowMapper<SyncRun> MAPPER = (rs, n) -> new SyncRun(rs.getLong("id"), rs.getLong("tenant_id"),
            rs.getLong("source_id"), rs.getString("status"), instant(rs, "started_at"), instant(rs, "finished_at"),
            new SyncCounts(rs.getInt("added"), rs.getInt("updated"), rs.getInt("removed"), rs.getInt("skipped"),
                    rs.getInt("failed")),
            rs.getString("error"));

    private final JdbcClient jdbc;

    public SyncRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records that a run has begun and returns its id. */
    public long start(long tenantId, long sourceId) {
        return jdbc.sql("INSERT INTO sync_run (tenant_id, source_id) VALUES (:tenant, :source) RETURNING id")
                .param("tenant", tenantId)
                .param("source", sourceId)
                .query(Long.class)
                .single();
    }

    public SyncRun finish(long tenantId, long runId, String status, SyncCounts counts, String error) {
        return jdbc.sql("""
                        UPDATE sync_run
                        SET finished_at = now(), status = :status, added = :added, updated = :updated,
                            removed = :removed, skipped = :skipped, failed = :failed, error = :error
                        WHERE tenant_id = :tenant AND id = :id
                        RETURNING %s
                        """.formatted(COLUMNS))
                .param("status", status)
                .param("added", counts.added())
                .param("updated", counts.updated())
                .param("removed", counts.removed())
                .param("skipped", counts.skipped())
                .param("failed", counts.failed())
                .param("error", error)
                .param("tenant", tenantId)
                .param("id", runId)
                .query(MAPPER)
                .single();
    }

    /** The most recently started run of a source. */
    public Optional<SyncRun> latest(long tenantId, long sourceId) {
        return jdbc.sql("""
                        SELECT %s FROM sync_run
                        WHERE tenant_id = :tenant AND source_id = :source
                        ORDER BY started_at DESC, id DESC LIMIT 1
                        """.formatted(COLUMNS))
                .param("tenant", tenantId)
                .param("source", sourceId)
                .query(MAPPER)
                .optional();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
