package com.docs0.source.drive;

import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.source.Folder;
import com.docs0.source.Source;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SourceCredential;
import com.docs0.source.SourceConnector;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.tenant.TenantService;
import com.docs0.web.StatusContributor;
import java.io.IOException;
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

/** Keeps the tenant's Google Drive documents in step with the folders the admin chose. */
@Component
public class DriveConnector implements SourceConnector, StatusContributor {

    public static final String KIND = DriveConnection.KIND;
    public static final String NAME = DriveConnection.NAME;

    private static final Logger log = LoggerFactory.getLogger(DriveConnector.class);
    private static final int MAX_STATUS_ERROR_CHARS = 120;

    private final DriveClient client;
    private final DriveConnection connection;
    private final DocumentStore documents;
    private final SyncRunRepository runs;
    private final TenantService tenants;

    public DriveConnector(DriveClient client, DriveConnection connection, DocumentStore documents,
                          SyncRunRepository runs, TenantService tenants) {
        this.client = client;
        this.connection = connection;
        this.documents = documents;
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
        return DriveConnection.VISIBILITY;
    }

    /**
     * True when Drive is connected and there is work for a scheduled sync: folders are
     * chosen, or documents remain from a selection that has since been cleared. So a
     * connected Drive with nothing chosen and nothing indexed adds no rows to the sync log.
     */
    @Override
    public boolean configured() {
        long tenantId = tenants.current().id();
        Optional<Source> source = connection.source(tenantId);
        if (source.isEmpty() || connection.credential(tenantId).isEmpty()) {
            return false;
        }
        return !connection.folders(tenantId).isEmpty() || documents.count(tenantId, source.get().id()) > 0;
    }

    @Override
    public SyncCounts sync(long tenantId, long sourceId) throws IOException {
        // Only the chosen folders are listed. With none chosen the list is empty, so documents
        // left from an earlier selection are marked deleted below and nothing is indexed.
        // If listing fails the run fails here, before anything is marked deleted.
        List<String> folderIds = connection.folders(tenantId).stream().map(Folder::id).toList();
        List<DriveFile> files = client.listFiles(tenantId, folderIds);
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
            if (!DriveText.indexable(file.mimeType(), file.size())) {
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
                String text = DriveText.extract(file.mimeType(), client.fetchContent(tenantId, file));
                documents.upsert(tenantId, sourceId, new DocumentWrite(file.id(), file.name(), file.mimeType(),
                        file.webViewLink(), file.ownerName(), file.ownerEmail(), file.modifiedTime(), text));
                if (known) {
                    updated++;
                } else {
                    added++;
                }
            } catch (SourceAccessException e) {
                // Google has stopped accepting the grant: fail the run, delete nothing.
                throw e;
            } catch (Exception e) {
                // The stored copy, if any, stays; the file is retried on the next run.
                failed++;
                log.warn("Drive file {} ({}) could not be indexed: {}", file.id(), file.mimeType(), e.toString());
            }
        }

        // Stored documents that are no longer inside a chosen folder, or can no longer be indexed,
        // drop out of search.
        Set<String> gone = new HashSet<>(stored.keySet());
        gone.removeAll(indexable);
        int removed = documents.markDeleted(tenantId, sourceId, gone);
        return new SyncCounts(added, updated, removed, skipped, failed);
    }

    /** Reads only the database; never calls Google. */
    @Override
    public ComponentStatus status() {
        long tenantId = tenants.current().id();
        Optional<SourceCredential> credential = connection.credential(tenantId);
        if (credential.isEmpty()) {
            return ComponentStatus.off(NAME, "not connected — connect it on the Sources page");
        }
        if (!connection.setupComplete()) {
            return ComponentStatus.down(NAME, "setup incomplete: " + String.join(", ", connection.missingSettings())
                    + " not set");
        }
        if (connection.folders(tenantId).isEmpty()) {
            return ComponentStatus.off(NAME, "connected as " + credential.get().accountEmail()
                    + ", no folders chosen yet");
        }
        long sourceId = credential.get().sourceId();
        Optional<SyncRun> last = runs.latest(tenantId, sourceId);
        if (last.isEmpty()) {
            return ComponentStatus.up(NAME, "connected as " + credential.get().accountEmail()
                    + ", waiting for first sync");
        }
        SyncRun run = last.get();
        if (SyncRun.FAILED.equals(run.status())) {
            return ComponentStatus.down(NAME, "last sync failed: " + shorten(run.error()));
        }
        long count = documents.count(tenantId, sourceId);
        if (SyncRun.RUNNING.equals(run.status())) {
            return ComponentStatus.up(NAME, count + " documents, sync in progress");
        }
        return ComponentStatus.up(NAME, count + " documents, last sync "
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
