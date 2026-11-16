package com.docs0.source.drive;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.config.Docs0Properties;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.Document;
import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.KnowledgeSearch;
import com.docs0.knowledge.SearchHit;
import com.docs0.source.Source;
import com.docs0.source.SourceRepository;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.SyncService;
import com.docs0.web.StatusContributor.ComponentStatus;
import com.docs0.web.StatusContributor.State;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class DriveSyncTest extends IntegrationTest {

    private static final Audience ANYONE = Audience.sharedOnly();
    private static final Instant T0 = Instant.parse("2026-03-01T09:00:00.123Z");
    private static final Instant T1 = Instant.parse("2026-03-02T09:00:00.456Z");

    @Autowired
    SyncService syncService;

    @Autowired
    DocumentStore store;

    @Autowired
    SourceRepository sources;

    @Autowired
    SyncRunRepository runs;

    @Autowired
    KnowledgeSearch search;

    /** The application's own connector: Drive is not configured in the test profile. */
    @Autowired
    DriveConnector unconfigured;

    @TempDir
    Path tmp;

    FakeDriveClient drive;
    DriveConnector connector;

    @BeforeEach
    void setUp() throws IOException {
        drive = new FakeDriveClient();
        Path credentials = Files.writeString(tmp.resolve("service-account.json"), "{}");
        connector = connector(credentials.toString(), "");
    }

    @Test
    void firstSyncAddsEverySupportedFileWithItsMetadata() {
        drive.put("doc", "Leave policy", DriveText.GOOGLE_DOC, T0, "﻿Everyone gets 25 days of leave.");
        drive.put("sheet", "Headcount", DriveText.GOOGLE_SHEET, T0, "team,people\nplatform,7\n");
        drive.put("slides", "Kickoff deck", DriveText.GOOGLE_SLIDES, T0, "Kickoff agenda");
        drive.put("txt", "notes.txt", "text/plain", T0, "Plain notes");
        drive.put("md", "README.md", "text/markdown", T0, "# Readme");
        drive.put("csv", "export.csv", "text/csv", T0, "a,b\n1,2\n");

        SyncRun run = syncService.run(connector).orElseThrow();

        assertThat(run.status()).isEqualTo(SyncRun.OK);
        assertThat(run.counts()).isEqualTo(new SyncCounts(6, 0, 0, 0, 0));
        assertThat(run.finishedAt()).isNotNull();
        assertThat(run.error()).isNull();
        assertThat(search.count(tenant.id())).isEqualTo(6);

        Source source = sources.find(tenant.id(), "GOOGLE_DRIVE", "Google Drive").orElseThrow();
        assertThat(source.visibility()).isEqualTo("SHARED");

        SearchHit hit = search.search(tenant.id(), ANYONE, "leave", 8).hits().get(0);
        Document doc = search.findById(tenant.id(), ANYONE, hit.documentId()).orElseThrow();
        assertThat(doc.title()).isEqualTo("Leave policy");
        assertThat(doc.body()).isEqualTo("Everyone gets 25 days of leave.");
        assertThat(doc.sourceKind()).isEqualTo("GOOGLE_DRIVE");
        assertThat(doc.sourceId()).isEqualTo(source.id());
        assertThat(doc.externalId()).isEqualTo("doc");
        assertThat(doc.mimeType()).isEqualTo(DriveText.GOOGLE_DOC);
        assertThat(doc.url()).isEqualTo("https://drive.example/doc");
        assertThat(doc.ownerName()).isEqualTo("Asha Rao");
        assertThat(doc.ownerEmail()).isEqualTo("asha@example.com");
        assertThat(doc.modifiedAt()).isEqualTo(T0);
        assertThat(search.search(tenant.id(), ANYONE, "platform", 8).hits())
                .extracting(SearchHit::title).containsExactly("Headcount");
    }

    @Test
    void unchangedFilesAreNotFetchedAgainAndChangedOnesAreUpdated() {
        drive.put("a", "Alpha", "text/plain", T0, "alpha mentions giraffes");
        drive.put("b", "Beta", "text/plain", T0, "beta body");
        syncService.run(connector);
        assertThat(drive.fetched).containsExactlyInAnyOrder("a", "b");
        drive.fetched.clear();

        SyncRun unchanged = syncService.run(connector).orElseThrow();
        assertThat(unchanged.counts()).isEqualTo(SyncCounts.NONE);
        assertThat(drive.fetched).isEmpty();

        drive.put("a", "Alpha v2", "text/plain", T1, "alpha now mentions zebras");
        SyncRun changed = syncService.run(connector).orElseThrow();

        assertThat(changed.counts()).isEqualTo(new SyncCounts(0, 1, 0, 0, 0));
        assertThat(drive.fetched).containsExactly("a");
        assertThat(search.count(tenant.id())).isEqualTo(2);
        assertThat(search.search(tenant.id(), ANYONE, "giraffes", 8).hits()).isEmpty();
        assertThat(search.search(tenant.id(), ANYONE, "zebras", 8).hits())
                .extracting(SearchHit::title).containsExactly("Alpha v2");
        assertThat(store.modifiedTimes(tenant.id(), sourceId())).isEqualTo(Map.of("a", T1, "b", T0));
    }

    @Test
    void removedFileIsMarkedDeletedLeavesSearchAndComesBackIfItReappears() {
        drive.put("keep", "Keep", "text/plain", T0, "kept walrus");
        drive.put("gone", "Gone", "text/plain", T0, "removed walrus");
        syncService.run(connector);
        assertThat(search.search(tenant.id(), ANYONE, "walrus", 8).hits()).hasSize(2);

        drive.remove("gone");
        SyncRun run = syncService.run(connector).orElseThrow();

        assertThat(run.counts()).isEqualTo(new SyncCounts(0, 0, 1, 0, 0));
        assertThat(search.search(tenant.id(), ANYONE, "walrus", 8).hits())
                .extracting(SearchHit::title).containsExactly("Keep");
        assertThat(search.count(tenant.id())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT deleted FROM document WHERE external_id = 'gone'").query(Boolean.class).single())
                .isTrue();

        // Not removed twice.
        assertThat(syncService.run(connector).orElseThrow().counts()).isEqualTo(SyncCounts.NONE);

        // Restored from the Drive bin with the same modified time: indexed again.
        drive.put("gone", "Gone", "text/plain", T0, "removed walrus");
        assertThat(syncService.run(connector).orElseThrow().counts()).isEqualTo(new SyncCounts(1, 0, 0, 0, 0));
        assertThat(search.search(tenant.id(), ANYONE, "walrus", 8).hits()).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM document").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void oneFailingFileIsCountedAndTheRunStillCompletes() {
        drive.put("bad", "Bad", "text/plain", T0, "never arrives");
        drive.put("corrupt", "Corrupt.pdf", DriveText.PDF, T0, "this is not a pdf");
        drive.put("good", "Good", "text/plain", T0, "good okapi");
        drive.failingIds.add("bad");

        SyncRun run = syncService.run(connector).orElseThrow();

        assertThat(run.status()).isEqualTo(SyncRun.OK);
        assertThat(run.counts()).isEqualTo(new SyncCounts(1, 0, 0, 0, 2));
        assertThat(search.search(tenant.id(), ANYONE, "okapi", 8).hits()).hasSize(1);
        assertThat(search.count(tenant.id())).isEqualTo(1);

        // The failed file is retried on the next run and indexed once it works.
        drive.failingIds.clear();
        SyncRun retry = syncService.run(connector).orElseThrow();
        assertThat(retry.counts()).isEqualTo(new SyncCounts(1, 0, 0, 0, 1));
        assertThat(search.count(tenant.id())).isEqualTo(2);
    }

    @Test
    void failedUpdateKeepsTheStoredCopyAndIsRetried() {
        drive.put("a", "Alpha", "text/plain", T0, "original okapi");
        syncService.run(connector);

        drive.put("a", "Alpha", "text/plain", T1, "revised okapi");
        drive.failingIds.add("a");
        SyncRun failed = syncService.run(connector).orElseThrow();

        assertThat(failed.counts()).isEqualTo(new SyncCounts(0, 0, 0, 0, 1));
        assertThat(search.search(tenant.id(), ANYONE, "original", 8).hits()).hasSize(1);

        drive.failingIds.clear();
        assertThat(syncService.run(connector).orElseThrow().counts()).isEqualTo(new SyncCounts(0, 1, 0, 0, 0));
        assertThat(search.search(tenant.id(), ANYONE, "revised", 8).hits()).hasSize(1);
    }

    @Test
    void unsupportedAndOversizeFilesAreSkippedAndFoldersIgnored() {
        drive.put("img", "photo.png", "image/png", T0, new byte[] {1, 2, 3}, 3L);
        drive.put("zip", "archive.zip", "application/zip", T0, new byte[] {1}, 1L);
        drive.put("huge", "huge.txt", "text/plain", T0, "tiny in the fake".getBytes(), DriveText.MAX_BYTES + 1);
        drive.put("limit", "limit.txt", "text/plain", T0, "exactly at the limit".getBytes(), DriveText.MAX_BYTES);
        drive.put("folder", "A folder", DriveText.FOLDER, T0, "");
        drive.put("ok", "ok.txt", "text/plain", T0, "fine");

        SyncRun run = syncService.run(connector).orElseThrow();

        assertThat(run.status()).isEqualTo(SyncRun.OK);
        assertThat(run.counts()).isEqualTo(new SyncCounts(2, 0, 0, 3, 0));
        assertThat(drive.fetched).containsExactlyInAnyOrder("limit", "ok");
        assertThat(search.count(tenant.id())).isEqualTo(2);
    }

    @Test
    void syncRunRowIsWrittenWithTheCounts() {
        drive.put("a", "Alpha", "text/plain", T0, "alpha");
        drive.put("b", "Beta", "text/plain", T0, "beta");
        drive.put("c", "Gamma", "text/plain", T0, "gamma");
        syncService.run(connector);

        drive.remove("c");                                              // removed
        drive.put("a", "Alpha", "text/plain", T1, "alpha changed");     // updated
        drive.put("d", "Delta", "text/plain", T0, "delta");             // added
        drive.put("e", "Epsilon", "text/plain", T0, "epsilon");         // failed
        drive.failingIds.add("e");
        drive.put("img", "photo.png", "image/png", T0, "");             // skipped
        syncService.run(connector);

        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT r.status, r.added, r.updated, r.removed, r.skipped, r.failed, r.error,
                               r.finished_at IS NOT NULL AS finished, r.tenant_id, s.kind, s.name
                        FROM sync_run r JOIN source s ON s.id = r.source_id ORDER BY r.id
                        """).query().listOfRows();
        assertThat(rows).hasSize(2);
        Map<String, Object> second = rows.get(1);
        assertThat(second.get("status")).isEqualTo("OK");
        assertThat(second.get("added")).isEqualTo(1);
        assertThat(second.get("updated")).isEqualTo(1);
        assertThat(second.get("removed")).isEqualTo(1);
        assertThat(second.get("skipped")).isEqualTo(1);
        assertThat(second.get("failed")).isEqualTo(1);
        assertThat(second.get("error")).isNull();
        assertThat(second.get("finished")).isEqualTo(true);
        assertThat(second.get("tenant_id")).isEqualTo(tenant.id());
        assertThat(second.get("kind")).isEqualTo("GOOGLE_DRIVE");
        assertThat(second.get("name")).isEqualTo("Google Drive");
        assertThat(rows.get(0).get("added")).isEqualTo(3);
        assertThat(runs.latest(tenant.id(), sourceId()).orElseThrow().counts())
                .isEqualTo(new SyncCounts(1, 1, 1, 1, 1));
    }

    @Test
    void failedListingFailsTheRunAndDeletesNothing() {
        drive.put("a", "Alpha", "text/plain", T0, "alpha okapi");
        syncService.run(connector);

        drive.listingFails = true;
        SyncRun run = syncService.run(connector).orElseThrow();

        assertThat(run.status()).isEqualTo(SyncRun.FAILED);
        assertThat(run.error()).contains("Drive is unreachable");
        assertThat(run.finishedAt()).isNotNull();
        assertThat(search.count(tenant.id())).isEqualTo(1);

        ComponentStatus status = connector.status();
        assertThat(status.state()).isEqualTo(State.DOWN);
        assertThat(status.detail()).contains("last sync failed").contains("Drive is unreachable");
    }

    @Test
    void configuredFolderIdsArePassedToTheClient() {
        connector = connector(tmp.resolve("service-account.json").toString(), " folderA, folderB ,");
        syncService.run(connector);
        assertThat(drive.lastFolderIds).containsExactly("folderA", "folderB");

        connector = connector(tmp.resolve("service-account.json").toString(), "");
        syncService.run(connector);
        assertThat(drive.lastFolderIds).isEmpty();
    }

    @Test
    void statusIsOffWhenNotConfigured() {
        ComponentStatus status = unconfigured.status();

        assertThat(status.name()).isEqualTo("Google Drive");
        assertThat(status.state()).isEqualTo(State.OFF);
        assertThat(status.detail()).isEqualTo("no service account configured");
        assertThat(unconfigured.configured()).isFalse();
    }

    @Test
    void statusIsDownWhenTheCredentialsFileIsMissing() {
        ComponentStatus status = connector(tmp.resolve("nope.json").toString(), "").status();

        assertThat(status.name()).isEqualTo("Google Drive");
        assertThat(status.state()).isEqualTo(State.DOWN);
        assertThat(status.detail()).contains("credentials file");
    }

    @Test
    void statusIsUpBeforeAndAfterASuccessfulSyncWithoutCallingDrive() {
        assertThat(connector.status().state()).isEqualTo(State.UP);
        assertThat(connector.status().detail()).isEqualTo("waiting for first sync");

        drive.put("a", "Alpha", "text/plain", T0, "alpha");
        drive.put("b", "Beta", "text/plain", T0, "beta");
        syncService.run(connector);
        drive.fetched.clear();
        drive.lastFolderIds = null;

        ComponentStatus status = connector.status();

        assertThat(status.state()).isEqualTo(State.UP);
        assertThat(status.detail()).startsWith("2 documents, last sync ");
        assertThat(drive.fetched).isEmpty();
        assertThat(drive.lastFolderIds).isNull();
    }

    private DriveConnector connector(String credentialsFile, String folderIds) {
        Docs0Properties props = new Docs0Properties(null, null, null, null,
                new Docs0Properties.Drive(credentialsFile, folderIds, 15, false));
        return new DriveConnector(props, drive, store, sources, runs, tenants);
    }

    private long sourceId() {
        return sources.find(tenant.id(), "GOOGLE_DRIVE", "Google Drive").orElseThrow().id();
    }
}
