package com.docs0.knowledge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Postgres full-text implementation of the read side. Every query is tenant-scoped. */
@Repository
public class JdbcKnowledgeSearch implements KnowledgeSearch {

    static final int MAX_LIMIT = 20;
    private static final int MAX_QUERY_CHARS = 500;
    private static final int RECENT_SNIPPET_CHARS = 300;

    /** Plain-text highlighting: the snippet is read by the model and shown in Slack, never rendered as HTML. */
    private static final String HEADLINE_OPTIONS =
            "MaxFragments=2, MaxWords=35, MinWords=10, StartSel=**, StopSel=**, FragmentDelimiter=\" ... \"";

    private static final String STRICT_QUERY = "websearch_to_tsquery('english', CAST(:q AS text))";
    private static final String ANY_TERM_QUERY = "CAST(:q AS tsquery)";

    /**
     * Ranks in the inner query and builds the (expensive) headline only for the rows that
     * survive the limit. %s is the tsquery expression.
     * Phase 4: the audience filter for RESTRICTED sources goes in the inner WHERE.
     */
    private static final String SEARCH_SQL = """
            SELECT r.id, r.title, r.kind, r.url, r.owner_name, r.modified_at, r.rank,
                   ts_headline('english', left(d.body, 200000), r.tsq, :options) AS snippet
            FROM (
                SELECT d.id, d.title, s.kind, d.url, d.owner_name, d.modified_at, q.tsq,
                       ts_rank_cd(d.search, q.tsq) AS rank
                FROM document d
                JOIN source s ON s.id = d.source_id AND s.tenant_id = d.tenant_id
                CROSS JOIN (SELECT %s AS tsq) q
                WHERE d.tenant_id = :tenant AND NOT d.deleted AND d.search @@ q.tsq
                ORDER BY rank DESC, d.modified_at DESC NULLS LAST, d.id DESC
                LIMIT :limit
            ) r
            JOIN document d ON d.id = r.id AND d.tenant_id = :tenant
            ORDER BY r.rank DESC, r.modified_at DESC NULLS LAST, r.id DESC
            """;

    private final JdbcClient jdbc;

    public JdbcKnowledgeSearch(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SearchResult search(long tenantId, Audience audience, String query, int limit) {
        if (query == null || query.isBlank()) {
            return new SearchResult(List.of(), false);
        }
        String q = clean(query);
        int capped = clampLimit(limit);

        // A query of stop words only reduces to an empty tsquery: nothing to search for.
        int nodes = jdbc.sql("SELECT numnode(" + STRICT_QUERY + ")").param("q", q).query(Integer.class).single();
        if (nodes == 0) {
            return new SearchResult(List.of(), false);
        }
        List<SearchHit> hits = runSearch(STRICT_QUERY, tenantId, q, capped);
        if (!hits.isEmpty()) {
            return new SearchResult(hits, false);
        }

        // Nothing matched every term: retry matching any term. Pointless for a single term.
        List<String> lexemes = jdbc.sql("SELECT unnest(tsvector_to_array(to_tsvector('english', CAST(:q AS text))))")
                .param("q", q).query(String.class).list();
        if (lexemes.size() < 2) {
            return new SearchResult(List.of(), false);
        }
        String anyTerm = lexemes.stream().map(JdbcKnowledgeSearch::quoteLexeme).collect(Collectors.joining(" | "));
        return new SearchResult(runSearch(ANY_TERM_QUERY, tenantId, anyTerm, capped), true);
    }

    private List<SearchHit> runSearch(String tsqueryExpression, long tenantId, String q, int limit) {
        return jdbc.sql(SEARCH_SQL.formatted(tsqueryExpression))
                .param("q", q)
                .param("tenant", tenantId)
                .param("limit", limit)
                .param("options", HEADLINE_OPTIONS)
                .query((rs, n) -> hit(rs, rs.getDouble("rank")))
                .list();
    }

    @Override
    public Optional<Document> findById(long tenantId, Audience audience, long documentId) {
        // Phase 4: the audience filter for RESTRICTED sources goes in this WHERE.
        return jdbc.sql("""
                        SELECT d.id, d.tenant_id, d.source_id, s.kind, d.external_id, d.title, d.mime_type, d.url,
                               d.owner_name, d.owner_email, d.modified_at, d.body
                        FROM document d
                        JOIN source s ON s.id = d.source_id AND s.tenant_id = d.tenant_id
                        WHERE d.tenant_id = :tenant AND d.id = :id AND NOT d.deleted
                        """)
                .param("tenant", tenantId)
                .param("id", documentId)
                .query((rs, n) -> new Document(rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("source_id"),
                        rs.getString("kind"), rs.getString("external_id"), rs.getString("title"),
                        rs.getString("mime_type"), rs.getString("url"), rs.getString("owner_name"),
                        rs.getString("owner_email"), instant(rs, "modified_at"), rs.getString("body")))
                .optional();
    }

    @Override
    public List<SearchHit> recent(long tenantId, Audience audience, int limit) {
        // Phase 4: the audience filter for RESTRICTED sources goes in this WHERE.
        return jdbc.sql("""
                        SELECT d.id, d.title, s.kind, d.url, d.owner_name, d.modified_at,
                               left(d.body, :chars) AS snippet
                        FROM document d
                        JOIN source s ON s.id = d.source_id AND s.tenant_id = d.tenant_id
                        WHERE d.tenant_id = :tenant AND NOT d.deleted
                        ORDER BY d.modified_at DESC NULLS LAST, d.id DESC
                        LIMIT :limit
                        """)
                .param("tenant", tenantId)
                .param("chars", RECENT_SNIPPET_CHARS)
                .param("limit", clampLimit(limit))
                .query((rs, n) -> hit(rs, 0.0))
                .list();
    }

    @Override
    public long count(long tenantId) {
        return jdbc.sql("SELECT count(*) FROM document WHERE tenant_id = :tenant AND NOT deleted")
                .param("tenant", tenantId)
                .query(Long.class)
                .single();
    }

    private static SearchHit hit(ResultSet rs, double rank) throws SQLException {
        String snippet = rs.getString("snippet");
        return new SearchHit(rs.getLong("id"), rs.getString("title"), rs.getString("kind"), rs.getString("url"),
                rs.getString("owner_name"), instant(rs, "modified_at"),
                snippet == null ? "" : snippet.replaceAll("\\s+", " ").trim(), rank);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private static String clean(String query) {
        String q = query.replace("\u0000", "").trim();
        return q.length() > MAX_QUERY_CHARS ? q.substring(0, MAX_QUERY_CHARS) : q;
    }

    /** Quotes one lexeme for tsquery input syntax. */
    private static String quoteLexeme(String lexeme) {
        return "'" + lexeme.replace("\\", "\\\\").replace("'", "''") + "'";
    }
}
