package com.docs0.source.grain;

import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.knowledge.DocumentStore.Stored;
import com.docs0.source.Source;
import com.docs0.source.SourceConnector;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.grain.GrainClient.Recording;
import com.docs0.tenant.TenantService;
import com.docs0.web.StatusContributor;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keeps the tenant's Grain recordings (notes and transcripts) from the last
 * {@code docs0.grain.days} days in step with Grain. Details of a recording are fetched only when
 * its list-level time changed. A recording without a transcript yet is stored without a modified
 * time, so the next sync looks at it again (for {@value #RETRY_DAYS} days after it ended).
 */
@Component
public class GrainConnector implements SourceConnector, StatusContributor {

    public static final String KIND = GrainConnection.KIND;
    public static final String NAME = GrainConnection.NAME;

    static final long RETRY_DAYS = 7;

    private static final Logger log = LoggerFactory.getLogger(GrainConnector.class);
    private static final int MAX_STATUS_ERROR_CHARS = 120;

    private final GrainClient client;
    private final GrainConnection connection;
    private final DocumentStore documents;
    private final SyncRunRepository runs;
    private final TenantService tenants;
    private final int days;
    private final Clock clock;

    @Autowired
    public GrainConnector(GrainClient client, GrainConnection connection, DocumentStore documents,
                          SyncRunRepository runs, TenantService tenants,
                          @Value("${docs0.grain.days:365}") int days) {
        this(client, connection, documents, runs, tenants, days, Clock.systemUTC());
    }

    GrainConnector(GrainClient client, GrainConnection connection, DocumentStore documents,
                   SyncRunRepository runs, TenantService tenants, int days, Clock clock) {
        this.client = client;
        this.connection = connection;
        this.documents = documents;
        this.runs = runs;
        this.tenants = tenants;
        this.days = Math.max(1, days);
        this.clock = clock;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String visibility() {
        return GrainConnection.VISIBILITY;
    }

    /** How many days back recordings are indexed. */
    public int historyDays() {
        return days;
    }

    /** The start of the window. */
    public Instant windowStart() {
        return clock.instant().minus(Duration.ofDays(days));
    }

    /** True when Grain is connected, or documents remain from an earlier connection to be cleared. */
    @Override
    public boolean configured() {
        long tenantId = tenants.current().id();
        Optional<Source> source = connection.source(tenantId);
        if (source.isEmpty()) {
            return false;
        }
        return connection.credential(tenantId).isPresent() || documents.count(tenantId, source.get().id()) > 0;
    }

    @Override
    public SyncCounts sync(long tenantId, long sourceId) throws IOException {
        String token = connection.token(tenantId);
        Instant since = windowStart();
        // The whole list is read before anything is written or deleted, so a rejected token or a
        // rate limit while listing leaves the stored documents as they were.
        List<Recording> listed = client.recordings(token, since).stream()
                .filter(r -> r.id() != null && (r.start() == null || !r.start().isBefore(since)))
                .toList();

        Map<String, Stored> stored = documents.stored(tenantId, sourceId);
        if (listed.isEmpty() && !stored.isEmpty()) {
            // Safety net: an empty list when recordings were indexed before is more likely a filter or
            // API problem than every meeting vanishing. Keep what is stored and report the run as failed.
            throw new IOException("Grain returned no recordings this time, so the existing ones were kept.");
        }
        Set<String> present = new HashSet<>();
        int added = 0;
        int updated = 0;
        int failed = 0;
        for (Recording listing : listed) {
            String externalId = "recording:" + listing.id();
            present.add(externalId);
            Stored before = stored.get(externalId);
            Instant modified = GrainRender.modified(listing);
            if (before != null && before.modifiedAt() != null && Objects.equals(before.modifiedAt(), modified)) {
                continue;
            }
            try {
                Recording full = client.details(token, listing.id());
                DocumentWrite doc = GrainRender.recording(full);
                if (!GrainRender.hasTranscript(full) && isRecent(modified)) {
                    // Retry at the next sync: a null time never equals the list's time.
                    doc = new DocumentWrite(doc.externalId(), doc.title(), doc.mimeType(), doc.url(),
                            doc.ownerName(), doc.ownerEmail(), null, doc.body());
                }
                if (before != null && Objects.equals(before.modifiedAt(), doc.modifiedAt())
                        && Objects.equals(before.contentHash(), DocumentStore.contentHash(doc.body()))) {
                    continue;
                }
                documents.upsert(tenantId, sourceId, doc);
                if (before == null) {
                    added++;
                } else {
                    updated++;
                }
            } catch (com.docs0.source.SourceAccessException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                // The stored copy (if any) stays; the next sync tries again.
                failed++;
                log.warn("Grain recording {} could not be read: {}", listing.id(), e.getClass().getName());
            }
        }

        Set<String> gone = new HashSet<>(stored.keySet());
        gone.removeAll(present);
        int removed = documents.markDeleted(tenantId, sourceId, gone);
        return new SyncCounts(added, updated, removed, 0, failed);
    }

    private boolean isRecent(Instant ended) {
        return ended == null || ended.isAfter(clock.instant().minus(Duration.ofDays(RETRY_DAYS)));
    }

    /** Reads only the database; never calls Grain. */
    @Override
    public ComponentStatus status() {
        long tenantId = tenants.current().id();
        Optional<com.docs0.source.SourceCredential> credential = connection.credential(tenantId);
        if (credential.isEmpty()) {
            return ComponentStatus.off(NAME, "not connected — connect it on the Sources page");
        }
        long sourceId = credential.get().sourceId();
        Optional<SyncRun> last = runs.latest(tenantId, sourceId);
        if (last.isEmpty()) {
            return ComponentStatus.up(NAME, "connected to " + credential.get().accountName()
                    + ", waiting for first sync");
        }
        SyncRun run = last.get();
        if (SyncRun.FAILED.equals(run.status())) {
            return ComponentStatus.down(NAME, "last sync failed: " + shorten(run.error()));
        }
        long count = documents.count(tenantId, sourceId);
        if (SyncRun.RUNNING.equals(run.status())) {
            return ComponentStatus.up(NAME, count + " recordings, sync in progress");
        }
        return ComponentStatus.up(NAME, count + " recordings, last sync "
                + run.finishedAt().truncatedTo(ChronoUnit.SECONDS));
    }

    private static String shorten(String error) {
        if (error == null || error.isBlank()) {
            return "unknown error";
        }
        String oneLine = error.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_STATUS_ERROR_CHARS ? oneLine.substring(0, MAX_STATUS_ERROR_CHARS) + "..." : oneLine;
    }
}
