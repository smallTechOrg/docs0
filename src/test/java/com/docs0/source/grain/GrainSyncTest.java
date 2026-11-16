package com.docs0.source.grain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.IntegrationTest;
import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.Document;
import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.KnowledgeSearch;
import com.docs0.knowledge.SearchHit;
import com.docs0.source.Source;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SourceRepository;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.SyncService;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.source.grain.GrainClient.Recording;
import com.docs0.web.StatusContributor.ComponentStatus;
import com.docs0.web.StatusContributor.State;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GrainSyncTest extends IntegrationTest {

    private static final Audience ANYONE = Audience.sharedOnly();
    private static final Instant NOW = Instant.parse("2026-03-20T00:00:00Z");
    private static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");
    private static final Instant T1 = Instant.parse("2026-03-02T09:00:00Z");

    @Autowired
    SyncService syncService;

    @Autowired
    DocumentStore store;

    @Autowired
    SourceRepository sources;

    @Autowired
    SourceCredentialRepository credentials;

    @Autowired
    SyncRunRepository runs;

    @Autowired
    KnowledgeSearch search;

    FakeGrainClient grain;
    GrainConnection connection;
    GrainConnector connector;
    Clock clock;

    @BeforeEach
    void setUp() {
        grain = new FakeGrainClient();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        connection = new GrainConnection(new TokenCipher(DriveTestSetup.ENCRYPTION_KEY), sources, credentials, store);
        connector = new GrainConnector(grain, connection, store, runs, tenants, 30, clock);
        connection.connect(tenant.id(), "Acme Workspace", FakeGrainClient.GOOD_TOKEN);
    }

    private long sourceId() {
        return connection.source(tenant.id()).orElseThrow().id();
    }

    private SyncCounts sync() throws Exception {
        return connector.sync(tenant.id(), sourceId());
    }

    private Recording add(String id, String title, Instant start) {
        Recording r = FakeGrainClient.recording(id, title, start, start.plusSeconds(2700));
        grain.recordings.put(id, r);
        return r;
    }

    @Test
    void firstSyncIndexesRecordingsAsSharedDocumentsWithTheStoredToken() throws Exception {
        add("r1", "Launch sync", T0);

        assertThat(sync()).isEqualTo(new SyncCounts(1, 0, 0, 0, 0));

        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsOnlyKeys("recording:r1");
        assertThat(grain.tokensSeen).containsOnly(FakeGrainClient.GOOD_TOKEN);
        List<SearchHit> hits = search.search(tenant.id(), ANYONE, "launch", 5).hits();
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).title()).isEqualTo("Launch sync");
        assertThat(hits.get(0).sourceKind()).isEqualTo("GRAIN");
        assertThat(hits.get(0).url()).isEqualTo("https://grain.com/share/r1");
        Document read = search.findById(tenant.id(), ANYONE, hits.get(0).documentId()).orElseThrow();
        assertThat(read.body()).contains("Participants: Priya Nair", "Friday works for me.");
    }

    @Test
    void changedRecordingsAreUpdatedUnchangedOnesAreNotFetchedAgainAndGoneOnesRemoved() throws Exception {
        add("r1", "First", T0);
        add("r2", "Second", T0);
        sync();
        grain.detailsRead.clear();

        assertThat(sync()).isEqualTo(SyncCounts.NONE);
        assertThat(grain.detailsRead).isEmpty();

        grain.recordings.remove("r2");
        add("r1", "First, renamed", T1);
        assertThat(sync()).isEqualTo(new SyncCounts(0, 1, 1, 0, 0));

        assertThat(grain.detailsRead).containsExactly("r1");
        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsOnlyKeys("recording:r1");
        assertThat(search.search(tenant.id(), ANYONE, "second", 5).hits()).isEmpty();
        assertThat(search.search(tenant.id(), ANYONE, "renamed", 5).hits()).hasSize(1);
    }

    @Test
    void aRecordingThatFallsOutOfTheWindowIsMarkedDeleted() throws Exception {
        add("r1", "Fresh", T0);
        add("r2", "Aging", Instant.parse("2026-02-20T09:00:00Z"));
        assertThat(sync()).isEqualTo(new SyncCounts(2, 0, 0, 0, 0));

        // Ten days later r2 (started 2026-02-20) is older than 30 days; r1 still is not.
        GrainConnector later = new GrainConnector(grain, connection, store, runs, tenants, 30,
                Clock.fixed(NOW.plusSeconds(10 * 86400), ZoneOffset.UTC));
        assertThat(later.sync(tenant.id(), sourceId())).isEqualTo(new SyncCounts(0, 0, 1, 0, 0));

        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsOnlyKeys("recording:r1");
    }

    @Test
    void anEmptyListWhenMeetingsAreIndexedKeepsThemAndFailsTheRun() throws Exception {
        add("r1", "Keep me", T0);
        sync();

        grain.recordings.clear();

        assertThatThrownBy(this::sync).isInstanceOf(java.io.IOException.class).hasMessageContaining("kept");
        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsOnlyKeys("recording:r1");
        assertThat(search.search(tenant.id(), ANYONE, "keep", 5).hits()).hasSize(1);
    }

    @Test
    void recordingsOlderThanTheWindowAreNeverIndexed() throws Exception {
        add("old", "Ancient", Instant.parse("2025-01-01T09:00:00Z"));

        assertThat(sync()).isEqualTo(SyncCounts.NONE);
        assertThat(store.count(tenant.id(), sourceId())).isZero();
    }

    @Test
    void aRecordingWithoutATranscriptIsIndexedAndLookedAtAgainUntilItHasOne() throws Exception {
        Instant recent = NOW.minusSeconds(3600);
        Recording full = FakeGrainClient.recording("r1", "Fresh call", recent, recent.plusSeconds(1800));
        grain.recordings.put("r1", FakeGrainClient.withoutTranscript(full));

        assertThat(sync()).isEqualTo(new SyncCounts(1, 0, 0, 0, 0));
        Document first = search.findById(tenant.id(), ANYONE,
                search.search(tenant.id(), ANYONE, "fresh", 5).hits().get(0).documentId()).orElseThrow();
        assertThat(first.body()).contains("Not available yet.");

        // Nothing new: fetched again (the transcript may be ready) but nothing is rewritten.
        grain.detailsRead.clear();
        assertThat(sync()).isEqualTo(SyncCounts.NONE);
        assertThat(grain.detailsRead).containsExactly("r1");

        grain.recordings.put("r1", full);
        assertThat(sync()).isEqualTo(new SyncCounts(0, 1, 0, 0, 0));
        grain.detailsRead.clear();
        assertThat(sync()).isEqualTo(SyncCounts.NONE);
        assertThat(grain.detailsRead).isEmpty();
        assertThat(search.search(tenant.id(), ANYONE, "friday", 5).hits()).hasSize(1);
    }

    @Test
    void aTranscriptThatNeverComesStopsBeingRetriedAfterAWeek() throws Exception {
        Recording old = FakeGrainClient.recording("r1", "Silent call", T0, T0.plusSeconds(1800));
        grain.recordings.put("r1", FakeGrainClient.withoutTranscript(old));

        assertThat(sync()).isEqualTo(new SyncCounts(1, 0, 0, 0, 0));
        grain.detailsRead.clear();

        assertThat(sync()).isEqualTo(SyncCounts.NONE);
        assertThat(grain.detailsRead).isEmpty();
    }

    @Test
    void oneBadRecordingIsCountedAndTheRestStillIndexAndAnOldCopyIsKept() throws Exception {
        add("r1", "Good one", T0);
        add("r2", "Broken one", T0);
        grain.brokenDetails.add("r2");

        assertThat(sync()).isEqualTo(new SyncCounts(1, 0, 0, 0, 1));
        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsOnlyKeys("recording:r1");

        grain.brokenDetails.clear();
        sync();
        add("r2", "Broken one", T1);
        grain.brokenDetails.add("r2");
        assertThat(sync()).isEqualTo(new SyncCounts(0, 0, 0, 0, 1));
        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsOnlyKeys("recording:r1", "recording:r2");
    }

    @Test
    void aRejectedTokenFailsTheRunWithTheReconnectMessageAndDeletesNothing() throws Exception {
        add("r1", "First", T0);
        sync();
        grain.readFailure = new SourceAccessException(HttpGrainClient.REJECTED);

        SyncRun run = syncService.run(connector).orElseThrow();

        assertThat(run.status()).isEqualTo(SyncRun.FAILED);
        assertThat(run.error()).isEqualTo("Grain rejected the access token. Reconnect Grain on the Sources page.");
        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsOnlyKeys("recording:r1");
    }

    @Test
    void anyOtherFailureWhileListingAlsoLeavesTheStoredDocumentsAlone() throws Exception {
        add("r1", "First", T0);
        sync();
        grain.readFailure = new IOException("Grain is limiting how fast docs0 can read. Try again in a few minutes.");

        SyncRun run = syncService.run(connector).orElseThrow();

        assertThat(run.status()).isEqualTo(SyncRun.FAILED);
        assertThat(run.error()).contains("limiting how fast");
        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsOnlyKeys("recording:r1");
    }

    @Test
    void syncingWithoutAConnectionIsRefusedWithAClearMessage() {
        connection.disconnect(tenant.id());

        assertThatThrownBy(() -> sync()).isInstanceOf(SourceAccessException.class)
                .hasMessageContaining("Grain is not connected");
        assertThat(connector.configured()).isFalse();
    }

    @Test
    void disconnectingRemovesTheTokenAndTheDocuments() throws Exception {
        add("r1", "First", T0);
        sync();
        assertThat(connector.configured()).isTrue();

        connection.disconnect(tenant.id());

        assertThat(connection.credential(tenant.id())).isEmpty();
        assertThat(store.count(tenant.id(), sourceId())).isZero();
        assertThat(connector.configured()).isFalse();
        assertThat(search.search(tenant.id(), ANYONE, "first", 5).hits()).isEmpty();
    }

    @Test
    void theSourceIsSharedAndKnownAsGrain() {
        Source source = connection.source(tenant.id()).orElseThrow();

        assertThat(source.kind()).isEqualTo("GRAIN");
        assertThat(source.name()).isEqualTo("Grain");
        assertThat(source.visibility()).isEqualTo("SHARED");
        assertThat(connector.visibility()).isEqualTo("SHARED");
        assertThat(connector.name()).isEqualTo("Grain");
    }

    @Test
    void statusSaysWhatIsMissingWhatFailedAndHowMuchIsIndexed() throws Exception {
        connection.disconnect(tenant.id());
        assertThat(connector.status()).isEqualTo(
                ComponentStatus.off("Grain", "not connected — connect it on the Sources page"));

        connection.connect(tenant.id(), "Acme Workspace", FakeGrainClient.GOOD_TOKEN);
        assertThat(connector.status().state()).isEqualTo(State.UP);
        assertThat(connector.status().detail()).contains("connected to Acme Workspace", "waiting for first sync");

        add("r1", "First", T0);
        syncService.run(connector);
        assertThat(connector.status().state()).isEqualTo(State.UP);
        assertThat(connector.status().detail()).startsWith("1 recordings, last sync ");

        grain.readFailure = new SourceAccessException(HttpGrainClient.REJECTED);
        syncService.run(connector);
        ComponentStatus down = connector.status();
        assertThat(down.state()).isEqualTo(State.DOWN);
        assertThat(down.detail()).startsWith("last sync failed: Grain rejected the access token");
    }
}
