package com.docs0.source.linear;

import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.source.Source;
import com.docs0.source.SourceConnector;
import com.docs0.source.StatusText;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.linear.LinearClient.Doc;
import com.docs0.source.linear.LinearClient.Issue;
import com.docs0.source.linear.LinearClient.Project;
import com.docs0.web.StatusContributor;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Keeps the tenant's Linear issues, projects and documents in step with the teams the admin chose. */
@Component
public class LinearConnector implements SourceConnector, StatusContributor {

    public static final String KIND = LinearConnection.KIND;
    public static final String NAME = LinearConnection.NAME;

    private static final Logger log = LoggerFactory.getLogger(LinearConnector.class);

    private final LinearClient client;
    private final LinearConnection connection;
    private final DocumentStore documents;
    private final SyncRunRepository runs;

    public LinearConnector(LinearClient client, LinearConnection connection, DocumentStore documents,
                           SyncRunRepository runs) {
        this.client = client;
        this.connection = connection;
        this.documents = documents;
        this.runs = runs;
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
        return LinearConnection.VISIBILITY;
    }

    /** True when Linear is connected and either teams are chosen or documents remain from an earlier choice. */
    @Override
    public boolean configured(long tenantId) {
        Optional<Source> source = connection.source(tenantId);
        if (source.isEmpty() || connection.credential(tenantId).isEmpty()) {
            return false;
        }
        return !connection.teams(tenantId).isEmpty() || documents.count(tenantId, source.get().id()) > 0;
    }

    @Override
    public SyncCounts sync(long tenantId, long sourceId) throws IOException {
        List<LinearTeam> teams = connection.teams(tenantId);
        Map<String, DocumentWrite> wanted = new LinkedHashMap<>();
        // Items that could not be rendered keep their stored copy until the next run.
        Set<String> unreadable = new HashSet<>();
        int failed = 0;

        if (!teams.isEmpty()) {
            // Everything is read before anything is written or deleted, so a rejected key or a
            // rate limit part-way leaves the stored documents as they were.
            String apiKey = connection.apiKey(tenantId);
            List<Issue> issues = new ArrayList<>();
            for (LinearTeam team : teams) {
                issues.addAll(client.issues(apiKey, team.id()));
            }
            List<Project> projects = client.projects(apiKey, teams.stream().map(LinearTeam::id).toList());
            Set<String> projectIds = new HashSet<>();
            for (Project project : projects) {
                projectIds.add(project.id());
            }
            List<Doc> docs = client.documents(apiKey).stream()
                    .filter(d -> d.projectId() != null && projectIds.contains(d.projectId()))
                    .toList();

            for (Issue issue : issues) {
                failed += render(wanted, unreadable, "issue:" + issue.id(), () -> LinearRender.issue(issue));
            }
            for (Project project : projects) {
                failed += render(wanted, unreadable, "project:" + project.id(), () -> LinearRender.project(project));
            }
            for (Doc doc : docs) {
                failed += render(wanted, unreadable, "doc:" + doc.id(), () -> LinearRender.document(doc));
            }
        }

        Map<String, Instant> stored = documents.modifiedTimes(tenantId, sourceId);
        int added = 0;
        int updated = 0;
        for (DocumentWrite doc : wanted.values()) {
            boolean known = stored.containsKey(doc.externalId());
            if (known && Objects.equals(stored.get(doc.externalId()), doc.modifiedAt())) {
                continue;
            }
            try {
                documents.upsert(tenantId, sourceId, doc);
                if (known) {
                    updated++;
                } else {
                    added++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Linear item {} could not be stored: {}", doc.externalId(), e.getClass().getName());
            }
        }

        Set<String> gone = new HashSet<>(stored.keySet());
        gone.removeAll(wanted.keySet());
        gone.removeAll(unreadable);
        int removed = documents.markDeleted(tenantId, sourceId, gone);
        return new SyncCounts(added, updated, removed, 0, failed);
    }

    /** Renders one item into {@code wanted}; returns 1 when it could not be rendered. */
    private int render(Map<String, DocumentWrite> wanted, Set<String> unreadable, String externalId,
                       java.util.function.Supplier<DocumentWrite> render) {
        try {
            DocumentWrite doc = render.get();
            wanted.putIfAbsent(doc.externalId(), doc);
            return 0;
        } catch (RuntimeException e) {
            unreadable.add(externalId);
            log.warn("Linear item {} could not be read: {}", externalId, e.getClass().getName());
            return 1;
        }
    }

    /** Reads only the database; never calls Linear. */
    @Override
    public ComponentStatus status(long tenantId) {
        Optional<com.docs0.source.SourceCredential> credential = connection.credential(tenantId);
        if (credential.isEmpty()) {
            return ComponentStatus.off(NAME, "not connected — connect it on the Sources page");
        }
        if (connection.teams(tenantId).isEmpty()) {
            return ComponentStatus.off(NAME, "connected, nothing chosen yet");
        }
        long sourceId = credential.get().sourceId();
        Optional<SyncRun> last = runs.latest(tenantId, sourceId);
        if (last.isEmpty()) {
            return ComponentStatus.up(NAME, "connected to " + credential.get().accountName()
                    + ", waiting for first sync");
        }
        SyncRun run = last.get();
        if (SyncRun.FAILED.equals(run.status())) {
            return ComponentStatus.down(NAME, "last sync failed: " + StatusText.shorten(run.error()));
        }
        long count = documents.count(tenantId, sourceId);
        if (SyncRun.RUNNING.equals(run.status())) {
            return ComponentStatus.up(NAME, count + " documents, sync in progress");
        }
        return ComponentStatus.up(NAME, count + " documents, last sync "
                + run.finishedAt().truncatedTo(ChronoUnit.SECONDS));
    }
}
