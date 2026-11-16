package com.docs0.source.drive;

import com.docs0.config.Docs0Properties;
import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.source.Source;
import com.docs0.source.SourceConnector;
import com.docs0.source.SourceRepository;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.tenant.TenantService;
import com.docs0.web.StatusContributor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
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
import org.springframework.stereotype.Component;

/** Keeps the tenant's Google Drive documents in step with what the service account can see. */
@Component
public class DriveConnector implements SourceConnector, StatusContributor {

    public static final String KIND = "GOOGLE_DRIVE";
    public static final String NAME = "Google Drive";

    private static final Logger log = LoggerFactory.getLogger(DriveConnector.class);
    private static final int MAX_STATUS_ERROR_CHARS = 120;

    private final Docs0Properties.Drive props;
    private final DriveClient client;
    private final DocumentStore documents;
    private final SourceRepository sources;
    private final SyncRunRepository runs;
    private final TenantService tenants;

    public DriveConnector(Docs0Properties props, DriveClient client, DocumentStore documents,
                          SourceRepository sources, SyncRunRepository runs, TenantService tenants) {
        this.props = props.drive();
        this.client = client;
        this.documents = documents;
        this.sources = sources;
        this.runs = runs;
        this.tenants = tenants;
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
        return "SHARED";
    }

    @Override
    public boolean configured() {
        return props.configured();
    }

    @Override
    public SyncCounts sync(long tenantId, long sourceId) throws IOException {
        // If listing fails the run fails here, before anything is marked deleted.
        List<DriveFile> files = client.listFiles(props.folderIdList());
        Map<String, Instant> stored = documents.modifiedTimes(tenantId, sourceId);

        Set<String> indexable = new HashSet<>();
        int added = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        for (DriveFile file : files) {
            if (DriveText.FOLDER.equals(file.mimeType())) {
                continue;
            }
            if (!DriveText.supported(file.mimeType())
                    || (file.size() != null && file.size() > DriveText.MAX_BYTES)) {
                skipped++;
                continue;
            }
            if (!indexable.add(file.id())) {
                continue;
            }
            boolean known = stored.containsKey(file.id());
            if (known && Objects.equals(stored.get(file.id()), file.modifiedTime())) {
                continue;
            }
            try {
                String text = DriveText.extract(file.mimeType(), client.fetchContent(file));
                documents.upsert(tenantId, sourceId, new DocumentWrite(file.id(), file.name(), file.mimeType(),
                        file.webViewLink(), file.ownerName(), file.ownerEmail(), file.modifiedTime(), text));
                if (known) {
                    updated++;
                } else {
                    added++;
                }
            } catch (Exception e) {
                // The stored copy, if any, stays; the file is retried on the next run.
                failed++;
                log.warn("Drive file {} ({}) could not be indexed: {}", file.id(), file.mimeType(), e.toString());
            }
        }

        // Stored documents that are gone from Drive, or can no longer be indexed, drop out of search.
        Set<String> gone = new HashSet<>(stored.keySet());
        gone.removeAll(indexable);
        int removed = documents.markDeleted(tenantId, sourceId, gone);
        return new SyncCounts(added, updated, removed, skipped, failed);
    }

    /** Reads only local state (the credentials file and the database); never calls Google. */
    @Override
    public ComponentStatus status() {
        if (!configured()) {
            return ComponentStatus.off(NAME, "no service account configured");
        }
        if (!credentialsReadable()) {
            return ComponentStatus.down(NAME, "credentials file missing or unreadable");
        }
        long tenantId = tenants.current().id();
        Optional<Source> source = sources.find(tenantId, KIND, NAME);
        Optional<SyncRun> last = source.flatMap(s -> runs.latest(tenantId, s.id()));
        if (last.isEmpty()) {
            return ComponentStatus.up(NAME, "waiting for first sync");
        }
        SyncRun run = last.get();
        if (SyncRun.FAILED.equals(run.status())) {
            return ComponentStatus.down(NAME, "last sync failed: " + shorten(run.error()));
        }
        long count = documents.count(tenantId, source.get().id());
        if (SyncRun.RUNNING.equals(run.status())) {
            return ComponentStatus.up(NAME, count + " documents, sync in progress");
        }
        return ComponentStatus.up(NAME, count + " documents, last sync "
                + run.finishedAt().truncatedTo(ChronoUnit.SECONDS));
    }

    private boolean credentialsReadable() {
        try {
            Path path = Path.of(props.credentialsFile().trim());
            return Files.isRegularFile(path) && Files.isReadable(path);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static String shorten(String error) {
        if (error == null || error.isBlank()) {
            return "unknown error";
        }
        String oneLine = error.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_STATUS_ERROR_CHARS ? oneLine.substring(0, MAX_STATUS_ERROR_CHARS) + "..." : oneLine;
    }
}
