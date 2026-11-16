package com.docs0.stale;

import com.docs0.agent.Agent.Citation;
import com.docs0.slack.AnswerRenderer;
import com.docs0.slack.SlackMessenger;
import com.docs0.slack.SlackMessengers;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Documents that are old but still being relied on. Answers that cite a document last updated more than
 * {@code docs0.stale.days} ago get a note, and each such answer is remembered so admins can see which stale
 * documents people lean on and nudge the owner. Detection is plain date arithmetic, never a model call.
 */
@Service
public class StaleDocs {

    /** A cited document that is stale. {@code restricted} documents are noted in the answer but never listed to admins. */
    public record Stale(long documentId, Instant modifiedAt, boolean restricted) {}

    /** A stale document people relied on, for the Insights page. {@code ownerName} is null when unknown. */
    public record Row(long documentId, String title, String url, String ownerName, boolean ownerKnown, long reliedOn,
            Instant modifiedAt, Instant pingedAt) {}

    public enum PingResult { SENT, NO_OWNER, NOT_IN_SLACK, TOO_SOON, FAILED, GONE }

    /** At most one nudge per document in this time. */
    public static final Duration PING_INTERVAL = Duration.ofDays(14);
    private static final int MAX_TITLE_CHARS = 150;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    private static final Logger log = LoggerFactory.getLogger(StaleDocs.class);

    private final JdbcClient jdbc;
    private final SlackMessengers messengers;
    private final int staleDays;

    public StaleDocs(JdbcClient jdbc, SlackMessengers messengers, @Value("${docs0.stale.days:365}") int staleDays) {
        this.jdbc = jdbc;
        this.messengers = messengers;
        this.staleDays = staleDays;
    }

    public int staleDays() {
        return staleDays;
    }

    private Instant cutoff() {
        return Instant.now().minus(Duration.ofDays(staleDays));
    }

    /** The cited documents that were last modified before the cutoff. Documents without a date are never stale. */
    public List<Stale> staleAmong(long tenantId, List<Citation> citations) {
        if (citations == null || citations.isEmpty() || staleDays <= 0) {
            return List.of();
        }
        List<Long> ids = citations.stream().map(Citation::documentId).distinct().toList();
        return jdbc.sql("""
                        SELECT id, modified_at, restricted FROM document
                        WHERE tenant_id = :tenantId AND id IN (:ids) AND NOT deleted
                          AND modified_at IS NOT NULL AND modified_at < :cutoff
                        ORDER BY modified_at
                        """)
                .param("tenantId", tenantId).param("ids", ids).param("cutoff", java.sql.Timestamp.from(cutoff()))
                .query((rs, n) -> new Stale(rs.getLong("id"), rs.getTimestamp("modified_at").toInstant(),
                        rs.getBoolean("restricted")))
                .list();
    }

    /** The oldest modification date among the stale documents, or null when none is stale. */
    public static Instant oldest(List<Stale> stale) {
        return stale == null ? null : stale.stream().map(Stale::modifiedAt).min(Comparator.naturalOrder()).orElse(null);
    }

    /** "March 2023", the month the note under an answer names. */
    public static String month(Instant at) {
        return MONTH.format(at);
    }

    /** Remembers that this answer relied on these stale documents. Once per answer and document; private documents are skipped. */
    public void record(long tenantId, long interactionId, List<Stale> stale) {
        for (Stale s : stale) {
            if (s.restricted()) {
                continue;
            }
            jdbc.sql("""
                            INSERT INTO stale_flag (tenant_id, document_id, interaction_id, document_modified_at)
                            SELECT i.tenant_id, d.id, i.id, :modifiedAt FROM interaction i
                            JOIN document d ON d.id = :documentId AND d.tenant_id = i.tenant_id
                            WHERE i.id = :interactionId AND i.tenant_id = :tenantId
                            ON CONFLICT (interaction_id, document_id) DO NOTHING
                            """)
                    .param("tenantId", tenantId).param("documentId", s.documentId())
                    .param("interactionId", interactionId).param("modifiedAt", java.sql.Timestamp.from(s.modifiedAt()))
                    .update();
        }
    }

    /**
     * Documents that are stale now and were relied on since {@code since}, most relied-on first. A document an
     * owner has since updated drops out. Private documents are never listed.
     */
    public List<Row> report(long tenantId, Instant since, int limit) {
        return jdbc.sql("""
                        SELECT d.id, d.title, d.url, d.owner_name, d.owner_email, d.modified_at,
                               count(*) AS relied, max(f.owner_pinged_at) AS pinged
                        FROM stale_flag f JOIN document d ON d.id = f.document_id AND d.tenant_id = f.tenant_id
                        WHERE f.tenant_id = :tenantId AND f.created_at >= :since
                          AND NOT d.deleted AND NOT d.restricted AND d.modified_at < :cutoff
                        GROUP BY d.id
                        ORDER BY relied DESC, d.modified_at, d.id
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId).param("since", java.sql.Timestamp.from(since))
                .param("cutoff", java.sql.Timestamp.from(cutoff())).param("limit", limit)
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("title"), rs.getString("url"),
                        blankToNull(rs.getString("owner_name")), !isBlank(rs.getString("owner_email")),
                        rs.getLong("relied"), rs.getTimestamp("modified_at").toInstant(),
                        rs.getTimestamp("pinged") == null ? null : rs.getTimestamp("pinged").toInstant()))
                .list();
    }

    private record Target(String title, String url, String ownerEmail, Instant modifiedAt, Instant pingedAt) {}

    /** Admin-initiated: a direct message to the document's owner asking them to review it. Never automatic. */
    public PingResult askOwner(long tenantId, long documentId) {
        Optional<Target> found = jdbc.sql("""
                        SELECT d.title, d.url, d.owner_email, d.modified_at,
                               (SELECT max(f.owner_pinged_at) FROM stale_flag f
                                WHERE f.tenant_id = d.tenant_id AND f.document_id = d.id) AS pinged
                        FROM document d
                        WHERE d.tenant_id = :tenantId AND d.id = :id AND NOT d.deleted AND NOT d.restricted
                          AND d.modified_at IS NOT NULL
                          AND EXISTS (SELECT 1 FROM stale_flag f WHERE f.tenant_id = d.tenant_id AND f.document_id = d.id)
                        """)
                .param("tenantId", tenantId).param("id", documentId)
                .query((rs, n) -> new Target(rs.getString("title"), rs.getString("url"), rs.getString("owner_email"),
                        rs.getTimestamp("modified_at").toInstant(),
                        rs.getTimestamp("pinged") == null ? null : rs.getTimestamp("pinged").toInstant()))
                .optional();
        if (found.isEmpty()) {
            return PingResult.GONE;
        }
        Target doc = found.get();
        if (isBlank(doc.ownerEmail())) {
            return PingResult.NO_OWNER;
        }
        if (doc.pingedAt() != null && doc.pingedAt().isAfter(Instant.now().minus(PING_INTERVAL))) {
            return PingResult.TOO_SOON;
        }
        try {
            SlackMessenger messenger = messengers.forTenant(tenantId);
            Optional<String> slackUser = messenger.lookupByEmail(doc.ownerEmail().strip());
            if (slackUser.isEmpty()) {
                return PingResult.NOT_IN_SLACK;
            }
            String channel = messenger.openDm(slackUser.get());
            messenger.postMessage(channel, null, message(doc), null);
        } catch (RuntimeException e) {
            log.warn("Owner could not be asked to review: tenant={} document={} error={}", tenantId, documentId,
                    e instanceof SlackMessenger.SlackCallException s ? s.code() : e.getClass().getName());
            return PingResult.FAILED;
        }
        jdbc.sql("UPDATE stale_flag SET owner_pinged_at = now() WHERE tenant_id = :tenantId AND document_id = :id")
                .param("tenantId", tenantId).param("id", documentId).update();
        log.info("Owner asked to review a stale document: tenant={} document={}", tenantId, documentId);
        return PingResult.SENT;
    }

    private static String message(Target doc) {
        String title = AnswerRenderer.escape(truncate(doc.title() == null ? "" : doc.title().replaceAll("\\s+", " ").strip()));
        StringBuilder text = new StringBuilder("Hi! People keep asking docs0 questions that rely on \"").append(title)
                .append("\" (last updated ").append(DAY.format(doc.modifiedAt()))
                .append("). Could you check whether it's still right?");
        if (!isBlank(doc.url())) {
            text.append(" <").append(doc.url().strip().replace("|", "%7C").replace(">", "%3E").replace("<", "%3C")
                    .replace(" ", "%20")).append('>');
        }
        return text.toString();
    }

    private static String truncate(String s) {
        return s.length() <= MAX_TITLE_CHARS ? s : s.substring(0, MAX_TITLE_CHARS - 1).stripTrailing() + "…";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.strip();
    }
}
