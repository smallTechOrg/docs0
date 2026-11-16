package com.docs0.source.linear;

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
import com.docs0.source.linear.LinearClient.Comment;
import com.docs0.source.linear.LinearClient.Issue;
import com.docs0.web.StatusContributor.ComponentStatus;
import com.docs0.web.StatusContributor.State;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LinearSyncTest extends IntegrationTest {

    private static final Audience ANYONE = Audience.sharedOnly();
    private static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");
    private static final Instant T1 = Instant.parse("2026-03-02T09:00:00Z");
    private static final LinearTeam ENG = new LinearTeam("team-eng", "ENG", "Engineering");

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

    FakeLinearClient linear;
    LinearConnection connection;
    LinearConnector connector;

    @BeforeEach
    void setUp() {
        linear = new FakeLinearClient();
        connection = new LinearConnection(new TokenCipher(DriveTestSetup.ENCRYPTION_KEY), sources, credentials, store);
        connector = new LinearConnector(linear, connection, store, runs, tenants);
        connection.connect(tenant.id(), "Acme", FakeLinearClient.GOOD_KEY);
        connection.setTeams(tenant.id(), List.of(ENG));
    }

    private long sourceId() {
        return connection.source(tenant.id()).orElseThrow().id();
    }

    private SyncCounts sync() throws Exception {
        return connector.sync(tenant.id(), sourceId());
    }

    @Test
    void firstSyncIndexesIssuesProjectsAndTheirDocumentsWithTheStoredKey() throws Exception {
        linear.issuesByTeam.put("team-eng", List.of(FakeLinearClient.issue("i1", "ENG-12", "Fix login", T0)));
        linear.projects.add(FakeLinearClient.project("p1", "Launch", T0));
        linear.docs.add(FakeLinearClient.doc("d1", "Launch plan", "p1", T0));
        linear.docs.add(FakeLinearClient.doc("d2", "Other team's plan", "p-elsewhere", T0));

        assertThat(sync()).isEqualTo(new SyncCounts(3, 0, 0, 0, 0));

        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsOnlyKeys("issue:i1", "project:p1", "doc:d1");
        assertThat(linear.keysSeen).containsOnly(FakeLinearClient.GOOD_KEY);
        List<SearchHit> hits = search.search(tenant.id(), ANYONE, "login", 5).hits();
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).title()).isEqualTo("ENG-12: Fix login");
        assertThat(hits.get(0).sourceKind()).isEqualTo("LINEAR");
        assertThat(hits.get(0).url()).isEqualTo("https://linear.app/acme/issue/ENG-12");
        Document read = search.findById(tenant.id(), ANYONE, hits.get(0).documentId()).orElseThrow();
        assertThat(read.body()).contains("Status: In Progress", "Comments (1):");
    }

    @Test
    void changedItemsAreUpdatedUnchangedOnesLeftAloneAndGoneOnesRemoved() throws Exception {
        linear.issuesByTeam.put("team-eng", List.of(
                FakeLinearClient.issue("i1", "ENG-1", "First", T0),
                FakeLinearClient.issue("i2", "ENG-2", "Second", T0)));
        sync();

        assertThat(sync()).isEqualTo(SyncCounts.NONE);

        linear.issuesByTeam.put("team-eng", List.of(FakeLinearClient.issue("i1", "ENG-1", "First, renamed", T1)));
        assertThat(sync()).isEqualTo(new SyncCounts(0, 1, 1, 0, 0));

        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsOnlyKeys("issue:i1");
        assertThat(search.search(tenant.id(), ANYONE, "second", 5).hits()).isEmpty();
        assertThat(search.search(tenant.id(), ANYONE, "renamed", 5).hits()).hasSize(1);
    }

    @Test
    void oneIssueThatCannotBeReadIsCountedAndTheRestStillIndex() throws Exception {
        Issue good = FakeLinearClient.issue("i1", "ENG-1", "Good one", T0);
        Issue base = FakeLinearClient.issue("i2", "ENG-2", "Broken one", T0);
        Issue broken = new Issue(base.id(), base.identifier(), base.title(), "d", "Todo", "Low", null, null, null,
                null, List.of(), null, null, null, null, T0, base.url(), null,
                Arrays.asList((Comment) null));
        linear.issuesByTeam.put("team-eng", List.of(good, broken));

        assertThat(sync()).isEqualTo(new SyncCounts(1, 0, 0, 0, 1));

        // Once stored, an issue that fails to render later keeps its old copy.
        Issue fixed = FakeLinearClient.issue("i2", "ENG-2", "Broken one", T0);
        linear.issuesByTeam.put("team-eng", List.of(good, fixed));
        sync();
        linear.issuesByTeam.put("team-eng", List.of(good, broken));
        assertThat(sync()).isEqualTo(new SyncCounts(0, 0, 0, 0, 1));
        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsOnlyKeys("issue:i1", "issue:i2");
    }

    @Test
    void aRejectedKeyFailsTheRunWithTheReconnectMessageAndDeletesNothing() throws Exception {
        linear.issuesByTeam.put("team-eng", List.of(FakeLinearClient.issue("i1", "ENG-1", "First", T0)));
        sync();
        linear.readFailure = new SourceAccessException(HttpLinearClient.REJECTED);

        SyncRun run = syncService.run(connector).orElseThrow();

        assertThat(run.status()).isEqualTo(SyncRun.FAILED);
        assertThat(run.error()).isEqualTo("Linear rejected the API key. Reconnect Linear on the Sources page.");
        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsOnlyKeys("issue:i1");
    }

    @Test
    void anyOtherFailureAlsoLeavesTheStoredDocumentsAlone() throws Exception {
        linear.issuesByTeam.put("team-eng", List.of(FakeLinearClient.issue("i1", "ENG-1", "First", T0)));
        sync();
        linear.readFailure = new IOException("Linear is limiting how fast docs0 can read. Try again in a few minutes.");

        SyncRun run = syncService.run(connector).orElseThrow();

        assertThat(run.status()).isEqualTo(SyncRun.FAILED);
        assertThat(run.error()).contains("limiting how fast");
        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsOnlyKeys("issue:i1");
    }

    @Test
    void choosingNoTeamIndexesNothingAndClearsWhatWasIndexedBefore() throws Exception {
        linear.issuesByTeam.put("team-eng", List.of(FakeLinearClient.issue("i1", "ENG-1", "First", T0)));
        linear.projects.add(FakeLinearClient.project("p1", "Launch", T0));
        sync();
        int calls = linear.keysSeen.size();
        connection.setTeams(tenant.id(), List.of());

        assertThat(connector.configured()).isTrue(); // documents remain to be cleared
        assertThat(sync()).isEqualTo(new SyncCounts(0, 0, 2, 0, 0));

        assertThat(linear.keysSeen).hasSize(calls);
        assertThat(store.count(tenant.id(), sourceId())).isZero();
        assertThat(connector.configured()).isFalse();
        assertThat(sync()).isEqualTo(SyncCounts.NONE);
    }

    @Test
    void syncingWithoutAConnectionIsRefusedWithAClearMessage() {
        connection.disconnect(tenant.id());

        assertThatThrownBy(() -> sync()).isInstanceOf(SourceAccessException.class)
                .hasMessageContaining("Linear is not connected");
    }

    @Test
    void disconnectingRemovesTheKeyAndTheDocumentsButKeepsTheTeams() throws Exception {
        linear.issuesByTeam.put("team-eng", List.of(FakeLinearClient.issue("i1", "ENG-1", "First", T0)));
        sync();

        connection.disconnect(tenant.id());

        assertThat(connection.credential(tenant.id())).isEmpty();
        assertThat(store.count(tenant.id(), sourceId())).isZero();
        assertThat(connection.teams(tenant.id())).containsExactly(ENG);
        assertThat(connector.configured()).isFalse();
        assertThat(search.search(tenant.id(), ANYONE, "first", 5).hits()).isEmpty();
    }

    @Test
    void theSourceIsSharedAndKnownAsLinear() {
        Source source = connection.source(tenant.id()).orElseThrow();

        assertThat(source.kind()).isEqualTo("LINEAR");
        assertThat(source.name()).isEqualTo("Linear");
        assertThat(source.visibility()).isEqualTo("SHARED");
        assertThat(connector.visibility()).isEqualTo("SHARED");
    }

    @Test
    void statusSaysWhatIsMissingWhatFailedAndHowMuchIsIndexed() throws Exception {
        LinearConnector fresh = new LinearConnector(linear, connection, store, runs, tenants);
        connection.disconnect(tenant.id());
        assertThat(fresh.status()).isEqualTo(
                ComponentStatus.off("Linear", "not connected — connect it on the Sources page"));

        connection.connect(tenant.id(), "Acme", FakeLinearClient.GOOD_KEY);
        connection.setTeams(tenant.id(), List.of());
        assertThat(fresh.status()).isEqualTo(ComponentStatus.off("Linear", "connected, nothing chosen yet"));

        connection.setTeams(tenant.id(), List.of(ENG));
        assertThat(fresh.status().state()).isEqualTo(State.UP);
        assertThat(fresh.status().detail()).contains("waiting for first sync");

        linear.issuesByTeam.put("team-eng", List.of(FakeLinearClient.issue("i1", "ENG-1", "First", T0)));
        syncService.run(connector);
        assertThat(fresh.status().state()).isEqualTo(State.UP);
        assertThat(fresh.status().detail()).startsWith("1 documents, last sync ");

        linear.readFailure = new SourceAccessException(HttpLinearClient.REJECTED);
        syncService.run(connector);
        ComponentStatus down = fresh.status();
        assertThat(down.state()).isEqualTo(State.DOWN);
        assertThat(down.detail()).startsWith("last sync failed: Linear rejected the API key");
    }

    @Test
    void theSearchCanBeLimitedToOneSourceKind() throws Exception {
        linear.issuesByTeam.put("team-eng", List.of(FakeLinearClient.issue("i1", "ENG-1", "Pricing page", T0)));
        sync();
        Source drive = sources.findOrCreate(tenant.id(), "GOOGLE_DRIVE", "Google Drive", "SHARED");
        store.upsert(tenant.id(), drive.id(), new DocumentStore.DocumentWrite("f1", "Pricing policy", "text/plain",
                "https://docs.example/f1", "Marco", null, T0, "Our pricing policy."));

        assertThat(search.search(tenant.id(), ANYONE, "pricing", 10).hits()).hasSize(2);
        assertThat(search.search(tenant.id(), ANYONE, "pricing", 10, java.util.Set.of("LINEAR")).hits())
                .extracting(SearchHit::sourceKind).containsExactly("LINEAR");
        assertThat(search.search(tenant.id(), ANYONE, "pricing", 10, java.util.Set.of("GOOGLE_DRIVE", "TRELLO")).hits())
                .extracting(SearchHit::sourceKind).containsExactly("GOOGLE_DRIVE");
        assertThat(search.search(tenant.id(), ANYONE, "pricing", 10, java.util.Set.of()).hits()).hasSize(2);
    }
}
