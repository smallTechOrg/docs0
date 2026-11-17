package com.docs0.source.drive;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.Document;
import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.KnowledgeSearch;
import com.docs0.knowledge.SearchHit;
import com.docs0.source.Folder;
import com.docs0.source.Source;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SourceRepository;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.SyncScheduler;
import com.docs0.source.SyncService;
import com.docs0.web.StatusContributor.ComponentStatus;
import com.docs0.web.StatusContributor.State;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    @Autowired
    SourceCredentialRepository credentials;

    /** The application's own connector: nothing is connected in the test profile. */
    @Autowired
    DriveConnector unconnected;

    FakeDriveClient drive;
    DriveConnection connection;
    DriveConnector connector;

    /** Drive connected as asha@example.com with the fake's "main" folder chosen. */
    @BeforeEach
    void setUp() {
        drive = new FakeDriveClient();
        connection = new DriveConnection(DriveTestSetup.props(), new TokenCipher(DriveTestSetup.ENCRYPTION_KEY),
                sources, credentials, store);
        connector = new DriveConnector(drive, connection, store, runs);
        connection.connect(tenant.id(), "asha@example.com", "refresh-token");
        connection.addFolder(tenant.id(), new Folder(FakeDriveClient.MAIN, "Main"));
    }

    @Test
    void firstSyncAddsEverySupportedFileWithItsMetadata() {
        drive.put("doc", "Leave policy", DriveText.GOOGLE_DOC, T0, "﻿Everyone gets 25 days of leave.");
        drive.put("sheet", "Headcount", DriveText.GOOGLE_SHEET, T0, "team,people\nplatform,7\n");
        drive.put("slides", "Kickoff deck", DriveText.GOOGLE_SLIDES, T0, "Kickoff agenda");
        drive.put("txt", "notes.txt", "text/plain", T0, "Plain notes");
        drive.put("md", "README.md", "text/markdown", T0, "# Readme");
        drive.put("csv", "export.csv", "text/csv", T0, "a,b\n1,2\n");

        SyncRun run = syncService.run(tenant.id(), connector).orElseThrow();

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
        syncService.run(tenant.id(), connector);
        assertThat(drive.fetched).containsExactlyInAnyOrder("a", "b");
        drive.fetched.clear();

        SyncRun unchanged = syncService.run(tenant.id(), connector).orElseThrow();
        assertThat(unchanged.counts()).isEqualTo(SyncCounts.NONE);
        assertThat(drive.fetched).isEmpty();

        drive.put("a", "Alpha v2", "text/plain", T1, "alpha now mentions zebras");
        SyncRun changed = syncService.run(tenant.id(), connector).orElseThrow();

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
        syncService.run(tenant.id(), connector);
        assertThat(search.search(tenant.id(), ANYONE, "walrus", 8).hits()).hasSize(2);

        drive.remove("gone");
        SyncRun run = syncService.run(tenant.id(), connector).orElseThrow();

        assertThat(run.counts()).isEqualTo(new SyncCounts(0, 0, 1, 0, 0));
        assertThat(search.search(tenant.id(), ANYONE, "walrus", 8).hits())
                .extracting(SearchHit::title).containsExactly("Keep");
        assertThat(search.count(tenant.id())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT deleted FROM document WHERE external_id = 'gone'").query(Boolean.class).single())
                .isTrue();

        // Not removed twice.
        assertThat(syncService.run(tenant.id(), connector).orElseThrow().counts()).isEqualTo(SyncCounts.NONE);

        // Restored from the Drive bin with the same modified time: indexed again.
        drive.put("gone", "Gone", "text/plain", T0, "removed walrus");
        assertThat(syncService.run(tenant.id(), connector).orElseThrow().counts()).isEqualTo(new SyncCounts(1, 0, 0, 0, 0));
        assertThat(search.search(tenant.id(), ANYONE, "walrus", 8).hits()).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM document").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void oneFailingFileIsCountedAndTheRunStillCompletes() {
        drive.put("bad", "Bad", "text/plain", T0, "never arrives");
        drive.put("corrupt", "Corrupt.pdf", DriveText.PDF, T0, "this is not a pdf");
        drive.put("good", "Good", "text/plain", T0, "good okapi");
        drive.failingIds.add("bad");

        SyncRun run = syncService.run(tenant.id(), connector).orElseThrow();

        assertThat(run.status()).isEqualTo(SyncRun.OK);
        assertThat(run.counts()).isEqualTo(new SyncCounts(1, 0, 0, 0, 2));
        assertThat(search.search(tenant.id(), ANYONE, "okapi", 8).hits()).hasSize(1);
        assertThat(search.count(tenant.id())).isEqualTo(1);

        // The failed file is retried on the next run and indexed once it works.
        drive.failingIds.clear();
        SyncRun retry = syncService.run(tenant.id(), connector).orElseThrow();
        assertThat(retry.counts()).isEqualTo(new SyncCounts(1, 0, 0, 0, 1));
        assertThat(search.count(tenant.id())).isEqualTo(2);
    }

    @Test
    void failedUpdateKeepsTheStoredCopyAndIsRetried() {
        drive.put("a", "Alpha", "text/plain", T0, "original okapi");
        syncService.run(tenant.id(), connector);

        drive.put("a", "Alpha", "text/plain", T1, "revised okapi");
        drive.failingIds.add("a");
        SyncRun failed = syncService.run(tenant.id(), connector).orElseThrow();

        assertThat(failed.counts()).isEqualTo(new SyncCounts(0, 0, 0, 0, 1));
        assertThat(search.search(tenant.id(), ANYONE, "original", 8).hits()).hasSize(1);

        drive.failingIds.clear();
        assertThat(syncService.run(tenant.id(), connector).orElseThrow().counts()).isEqualTo(new SyncCounts(0, 1, 0, 0, 0));
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

        SyncRun run = syncService.run(tenant.id(), connector).orElseThrow();

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
        syncService.run(tenant.id(), connector);

        drive.remove("c");                                              // removed
        drive.put("a", "Alpha", "text/plain", T1, "alpha changed");     // updated
        drive.put("d", "Delta", "text/plain", T0, "delta");             // added
        drive.put("e", "Epsilon", "text/plain", T0, "epsilon");         // failed
        drive.failingIds.add("e");
        drive.put("img", "photo.png", "image/png", T0, "");             // skipped
        syncService.run(tenant.id(), connector);

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
        syncService.run(tenant.id(), connector);

        drive.listingFails = true;
        SyncRun run = syncService.run(tenant.id(), connector).orElseThrow();

        assertThat(run.status()).isEqualTo(SyncRun.FAILED);
        assertThat(run.error()).contains("Drive is unreachable");
        assertThat(run.finishedAt()).isNotNull();
        assertThat(search.count(tenant.id())).isEqualTo(1);

        ComponentStatus status = connector.status(tenant.id());
        assertThat(status.state()).isEqualTo(State.DOWN);
        assertThat(status.detail()).contains("last sync failed").contains("Drive is unreachable");
    }

    @Test
    void onlyTheChosenFoldersAreSynced() {
        drive.folder("sub", "Sub", FakeDriveClient.MAIN);
        drive.folder("other", "Other", DriveClient.MY_DRIVE);
        drive.put("a", "In main", "text/plain", T0, "main okapi");
        drive.putIn("sub", "b", "In sub", "text/plain", T0, "nested okapi");
        drive.putIn("other", "c", "In other", "text/plain", T0, "unchosen okapi");

        SyncRun run = syncService.run(tenant.id(), connector).orElseThrow();

        assertThat(drive.lastFolderIds).containsExactly(FakeDriveClient.MAIN);
        assertThat(run.counts()).isEqualTo(new SyncCounts(2, 0, 0, 0, 0));
        assertThat(search.search(tenant.id(), ANYONE, "okapi", 8).hits())
                .extracting(SearchHit::title).containsExactlyInAnyOrder("In main", "In sub");

        connection.addFolder(tenant.id(), new Folder("other", "Other"));
        assertThat(syncService.run(tenant.id(), connector).orElseThrow().counts()).isEqualTo(new SyncCounts(1, 0, 0, 0, 0));
        assertThat(drive.lastFolderIds).containsExactly(FakeDriveClient.MAIN, "other");
        assertThat(search.search(tenant.id(), ANYONE, "okapi", 8).hits()).hasSize(3);
    }

    @Test
    void removingAFolderDropsItsDocumentsFromSearchAtTheNextSync() {
        drive.folder("other", "Other", DriveClient.MY_DRIVE);
        connection.addFolder(tenant.id(), new Folder("other", "Other"));
        drive.put("a", "In main", "text/plain", T0, "main okapi");
        drive.putIn("other", "c", "In other", "text/plain", T0, "other okapi");
        syncService.run(tenant.id(), connector);
        assertThat(search.search(tenant.id(), ANYONE, "okapi", 8).hits()).hasSize(2);

        connection.removeFolder(tenant.id(), "other");
        // Not before the next sync.
        assertThat(search.search(tenant.id(), ANYONE, "okapi", 8).hits()).hasSize(2);
        SyncRun run = syncService.run(tenant.id(), connector).orElseThrow();

        assertThat(run.counts()).isEqualTo(new SyncCounts(0, 0, 1, 0, 0));
        assertThat(search.search(tenant.id(), ANYONE, "okapi", 8).hits())
                .extracting(SearchHit::title).containsExactly("In main");
    }

    @Test
    void chosenFoldersThatComeBackEmptyKeepTheirDocumentsUntilTheAdminRemovesThemFromTheSelection() {
        drive.put("a", "Alpha", "text/plain", T0, "alpha okapi");
        drive.put("b", "Beta", "text/plain", T0, "beta okapi");
        syncService.run(tenant.id(), connector);

        // Drive suddenly lists nothing for the chosen folder (lost permission, moved folder, an outage).
        drive.remove("a");
        drive.remove("b");
        SyncRun run = syncService.run(tenant.id(), connector).orElseThrow();

        assertThat(run.status()).isEqualTo(SyncRun.FAILED);
        assertThat(run.error()).contains("returned no files").contains("were kept").contains("remove them from the selection");
        assertThat(search.count(tenant.id())).isEqualTo(2);
        assertThat(connector.status(tenant.id()).state()).isEqualTo(State.DOWN);

        // Taking the folder off the selection is how an admin says they really are gone.
        connection.removeFolder(tenant.id(), FakeDriveClient.MAIN);
        scheduler(connector).syncAll();
        assertThat(search.count(tenant.id())).isZero();
    }

    @Test
    void anEmptyFolderWithNothingStoredIsNotAnError() {
        SyncRun run = syncService.run(tenant.id(), connector).orElseThrow();

        assertThat(run.status()).isEqualTo(SyncRun.OK);
    }

    @Test
    void clearingTheSelectionDropsEveryDocumentAtTheNextSyncWithoutCallingDrive() {
        drive.put("a", "Alpha", "text/plain", T0, "alpha okapi");
        drive.put("b", "Beta", "text/plain", T0, "beta okapi");
        syncService.run(tenant.id(), connector);
        int callsBefore = drive.calls;

        connection.removeFolder(tenant.id(), FakeDriveClient.MAIN);
        // Leftover documents are work for the scheduler, once.
        assertThat(connector.configured(tenant.id())).isTrue();
        scheduler(connector).syncAll();

        SyncRun run = runs.latest(tenant.id(), sourceId()).orElseThrow();
        assertThat(run.status()).isEqualTo(SyncRun.OK);
        assertThat(run.counts()).isEqualTo(new SyncCounts(0, 0, 2, 0, 0));
        assertThat(search.count(tenant.id())).isZero();
        assertThat(search.search(tenant.id(), ANYONE, "okapi", 8).hits()).isEmpty();
        assertThat(drive.calls).isEqualTo(callsBefore);
        assertThat(connector.configured(tenant.id())).isFalse();
    }

    @Test
    void connectedWithNoFoldersChosenIndexesNothingAndStaysOutOfTheSyncLog() {
        connection.removeFolder(tenant.id(), FakeDriveClient.MAIN);
        drive.put("a", "Alpha", "text/plain", T0, "alpha okapi");

        assertThat(connector.configured(tenant.id())).isFalse();
        scheduler(connector).syncAll();
        assertThat(jdbc.sql("SELECT count(*) FROM sync_run").query(Long.class).single()).isZero();

        // Even when asked directly, nothing is indexed and Drive is not called.
        SyncRun run = syncService.run(tenant.id(), connector).orElseThrow();
        assertThat(run.status()).isEqualTo(SyncRun.OK);
        assertThat(run.counts()).isEqualTo(SyncCounts.NONE);
        assertThat(search.count(tenant.id())).isZero();
        assertThat(drive.calls).isZero();
    }

    @Test
    void scheduledSyncRunsOnceDriveIsConnectedWithAFolder() {
        drive.put("a", "Alpha", "text/plain", T0, "alpha okapi");
        assertThat(connector.configured(tenant.id())).isTrue();

        scheduler(connector).syncAll();

        assertThat(search.count(tenant.id())).isEqualTo(1);
    }

    @Test
    void revokedGrantFailsTheRunWithTheReconnectMessageAndDeletesNothing() {
        drive.put("a", "Alpha", "text/plain", T0, "alpha okapi");
        drive.put("b", "Beta", "text/plain", T0, "beta okapi");
        syncService.run(tenant.id(), connector);

        drive.revoked = true;
        SyncRun run = syncService.run(tenant.id(), connector).orElseThrow();

        assertThat(run.status()).isEqualTo(SyncRun.FAILED);
        assertThat(run.error())
                .isEqualTo("Google access was revoked or expired. Reconnect Google Drive on the Sources page.");
        assertThat(run.counts()).isEqualTo(SyncCounts.NONE);
        assertThat(search.count(tenant.id())).isEqualTo(2);
        assertThat(search.search(tenant.id(), ANYONE, "okapi", 8).hits()).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM document WHERE deleted").query(Long.class).single()).isZero();

        ComponentStatus status = connector.status(tenant.id());
        assertThat(status.state()).isEqualTo(State.DOWN);
        assertThat(status.detail()).contains("last sync failed").contains("Reconnect Google Drive");
    }

    @Test
    void grantRevokedWhileFetchingFilesFailsTheRunInsteadOfCountingEveryFileAsFailed() {
        drive.put("a", "Alpha", "text/plain", T0, "alpha okapi");
        syncService.run(tenant.id(), connector);
        // Listing still works; the first download is refused.
        FakeDriveClient refusing = new FakeDriveClient() {
            @Override
            public byte[] fetchContent(long tenantId, DriveFile file) throws java.io.IOException {
                throw new com.docs0.source.SourceAccessException(GoogleDriveClient.RECONNECT_MESSAGE);
            }
        };
        refusing.put("b", "Beta", "text/plain", T0, "beta okapi");

        SyncRun run = syncService.run(tenant.id(), new DriveConnector(refusing, connection, store, runs)).orElseThrow();

        assertThat(run.status()).isEqualTo(SyncRun.FAILED);
        assertThat(run.error()).isEqualTo(GoogleDriveClient.RECONNECT_MESSAGE);
        // "a" is missing from this listing but is not deleted, because the run failed.
        assertThat(search.search(tenant.id(), ANYONE, "okapi", 8).hits())
                .extracting(SearchHit::title).containsExactly("Alpha");
    }

    @Test
    void disconnectDeletesTheGrantAndHidesTheDocuments() {
        drive.put("a", "Alpha", "text/plain", T0, "alpha okapi");
        syncService.run(tenant.id(), connector);
        assertThat(search.count(tenant.id())).isEqualTo(1);

        connection.disconnect(tenant.id());

        assertThat(jdbc.sql("SELECT count(*) FROM source_credential").query(Long.class).single()).isZero();
        assertThat(connection.credential(tenant.id())).isEmpty();
        assertThat(search.count(tenant.id())).isZero();
        assertThat(search.search(tenant.id(), ANYONE, "okapi", 8).hits()).isEmpty();
        assertThat(connector.configured(tenant.id())).isFalse();
        assertThat(connector.status(tenant.id()).state()).isEqualTo(State.OFF);

        // Connecting again keeps the folder selection; the next sync brings the documents back.
        connection.connect(tenant.id(), "asha@example.com", "refresh-token-2");
        assertThat(connection.folders(tenant.id())).containsExactly(new Folder(FakeDriveClient.MAIN, "Main"));
        assertThat(syncService.run(tenant.id(), connector).orElseThrow().counts()).isEqualTo(new SyncCounts(1, 0, 0, 0, 0));
        assertThat(search.count(tenant.id())).isEqualTo(1);
    }

    @Test
    void theGrantIsStoredEncryptedAndReconnectingReplacesIt() {
        String first = jdbc.sql("SELECT encrypted_refresh_token FROM source_credential").query(String.class).single();
        assertThat(first).doesNotContain("refresh-token");
        assertThat(new TokenCipher(DriveTestSetup.ENCRYPTION_KEY).decrypt(first)).isEqualTo("refresh-token");

        connection.connect(tenant.id(), "ben@example.com", "another-token");

        Map<String, Object> row = jdbc.sql("SELECT * FROM source_credential").query().singleRow();
        assertThat(row.get("account_email")).isEqualTo("ben@example.com");
        assertThat(row.get("tenant_id")).isEqualTo(tenant.id());
        assertThat(row.get("source_id")).isEqualTo(sourceId());
        assertThat((String) row.get("encrypted_refresh_token")).isNotEqualTo(first).doesNotContain("another-token");
        assertThat(new TokenCipher(DriveTestSetup.ENCRYPTION_KEY)
                .decrypt((String) row.get("encrypted_refresh_token"))).isEqualTo("another-token");
        assertThat(connection.credential(tenant.id()).orElseThrow().toString()).doesNotContain(
                (String) row.get("encrypted_refresh_token"));
    }

    @Test
    void chosenFoldersAreKeptInSourceConfigPerTenant() {
        long other = tenants.ensure("other", "Other Co").id();
        connection.addFolder(other, new Folder("theirs", "Theirs"));
        connection.addFolder(tenant.id(), new Folder("f2", "Second \"quoted\" <b>"));
        connection.addFolder(tenant.id(), new Folder(FakeDriveClient.MAIN, "Main renamed"));

        assertThat(connection.folders(tenant.id()))
                .containsExactly(new Folder("f2", "Second \"quoted\" <b>"), new Folder(FakeDriveClient.MAIN, "Main renamed"));
        assertThat(connection.folders(other)).containsExactly(new Folder("theirs", "Theirs"));
        assertThat(jdbc.sql("SELECT config #>> '{folders,0,id}' FROM source WHERE tenant_id = :t")
                .param("t", tenant.id()).query(String.class).single()).isEqualTo("f2");
        assertThat(jdbc.sql("SELECT jsonb_array_length(config -> 'folders') FROM source WHERE tenant_id = :t")
                .param("t", tenant.id()).query(Integer.class).single()).isEqualTo(2);

        connection.removeFolder(tenant.id(), "f2");
        connection.removeFolder(tenant.id(), "never-added");
        assertThat(connection.folders(tenant.id())).containsExactly(new Folder(FakeDriveClient.MAIN, "Main renamed"));
        assertThat(connection.folders(other)).hasSize(1);
        assertThat(jdbc.sql("SELECT config::text FROM source WHERE tenant_id = :t")
                .param("t", tenant.id()).query(String.class).single())
                .isEqualTo("{\"folders\": [{\"id\": \"main\", \"name\": \"Main renamed\"}]}");
    }

    @Test
    void statusIsOffWhenNotConnected() {
        connection.disconnect(tenant.id());

        assertThat(connector.status(tenant.id())).isEqualTo(unconnected.status(tenant.id()));
        assertThat(connector.status(tenant.id()).name()).isEqualTo("Google Drive");
        assertThat(connector.status(tenant.id()).state()).isEqualTo(State.OFF);
        assertThat(connector.status(tenant.id()).detail()).isEqualTo("not connected — connect it on the Sources page");
        assertThat(connector.configured(tenant.id())).isFalse();
        assertThat(unconnected.configured(tenant.id())).isFalse();
    }

    @Test
    void statusIsOffWhenConnectedButNoFoldersAreChosen() {
        connection.removeFolder(tenant.id(), FakeDriveClient.MAIN);

        ComponentStatus status = connector.status(tenant.id());

        assertThat(status.name()).isEqualTo("Google Drive");
        assertThat(status.state()).isEqualTo(State.OFF);
        assertThat(status.detail()).isEqualTo("connected as asha@example.com, no folders chosen yet");
    }

    @Test
    void statusIsUpBeforeAndAfterASuccessfulSyncWithoutCallingDrive() {
        assertThat(connector.status(tenant.id()).state()).isEqualTo(State.UP);
        assertThat(connector.status(tenant.id()).detail()).isEqualTo("connected as asha@example.com, waiting for first sync");

        drive.put("a", "Alpha", "text/plain", T0, "alpha");
        drive.put("b", "Beta", "text/plain", T0, "beta");
        syncService.run(tenant.id(), connector);
        int callsBefore = drive.calls;

        ComponentStatus status = connector.status(tenant.id());

        assertThat(status.state()).isEqualTo(State.UP);
        assertThat(status.detail()).startsWith("2 documents, last sync ");
        assertThat(drive.calls).isEqualTo(callsBefore);
    }

    @Test
    void statusIsDownWhenTheEncryptionKeyHasGoneMissing() {
        DriveConnection keyless = new DriveConnection(DriveTestSetup.props(), new TokenCipher(""), sources,
                credentials, store);

        ComponentStatus status = new DriveConnector(drive, keyless, store, runs).status(tenant.id());

        assertThat(status.state()).isEqualTo(State.DOWN);
        assertThat(status.detail()).contains("DOCS0_ENCRYPTION_KEY");
    }

    private SyncScheduler scheduler(DriveConnector driveConnector) {
        return new SyncScheduler(syncService, List.<com.docs0.source.SourceConnector>of(driveConnector), DriveTestSetup.props(), tenants);
    }

    private long sourceId() {
        return sources.find(tenant.id(), "GOOGLE_DRIVE", "Google Drive").orElseThrow().id();
    }
}
