package com.docs0.source.trello;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.IntegrationTest;
import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.KnowledgeSearch;
import com.docs0.knowledge.SearchHit;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SourceRepository;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.SyncService;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.source.trello.TrelloClient.Card;
import com.docs0.web.StatusContributor.ComponentStatus;
import com.docs0.web.StatusContributor.State;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TrelloSyncTest extends IntegrationTest {

    private static final Audience ANYONE = Audience.sharedOnly();
    private static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");
    private static final Instant T1 = Instant.parse("2026-03-02T09:00:00Z");
    private static final TrelloBoard ROADMAP = new TrelloBoard("b1", "Roadmap");

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

    FakeTrelloClient trello;
    TrelloConnection connection;
    TrelloConnector connector;

    @BeforeEach
    void setUp() {
        trello = new FakeTrelloClient();
        connection = new TrelloConnection(new TokenCipher(DriveTestSetup.ENCRYPTION_KEY), sources, credentials, store);
        connector = new TrelloConnector(trello, connection, store, runs);
        connection.connect(tenant.id(), "Asha Rao (@asharao)", FakeTrelloClient.GOOD_KEY, FakeTrelloClient.GOOD_TOKEN);
        connection.setBoards(tenant.id(), List.of(ROADMAP));
    }

    private long sourceId() {
        return connection.source(tenant.id()).orElseThrow().id();
    }

    private SyncCounts sync() throws Exception {
        return connector.sync(tenant.id(), sourceId());
    }

    private void cards(Card... cards) {
        trello.content.put("b1", FakeTrelloClient.board("b1", "Roadmap", T0, List.of(cards)));
    }

    @Test
    void firstSyncIndexesTheBoardAndEachOfItsCards() throws Exception {
        cards(FakeTrelloClient.card("c1", "Write launch post", "l-doing", T0),
                FakeTrelloClient.card("c2", "Book venue", "l-todo", T0));

        assertThat(sync()).isEqualTo(new SyncCounts(3, 0, 0, 0, 0));

        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsOnlyKeys("board:b1", "card:c1", "card:c2");
        List<SearchHit> hits = search.search(tenant.id(), ANYONE, "venue", 5).hits();
        assertThat(hits).extracting(SearchHit::title).containsExactly("Book venue");
        assertThat(hits.get(0).sourceKind()).isEqualTo("TRELLO");
        String body = search.findById(tenant.id(), ANYONE, hits.get(0).documentId()).orElseThrow().body();
        assertThat(body).contains("Board: Roadmap", "List: To do", "☐ Ship it");
        assertThat(search.search(tenant.id(), ANYONE, "roadmap board", 5).hits())
                .extracting(SearchHit::title).contains("Trello board: Roadmap");
    }

    @Test
    void changedCardsAreUpdatedUnchangedOnesLeftAloneAndClosedOnesRemoved() throws Exception {
        cards(FakeTrelloClient.card("c1", "First", "l-todo", T0), FakeTrelloClient.card("c2", "Second", "l-todo", T0));
        sync();

        assertThat(sync()).isEqualTo(SyncCounts.NONE);

        cards(FakeTrelloClient.card("c1", "First, renamed", "l-todo", T1));
        // The board's own last activity is unchanged, so only the card moves.
        assertThat(sync()).isEqualTo(new SyncCounts(0, 1, 1, 0, 0));
        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsOnlyKeys("board:b1", "card:c1");
    }

    @Test
    void aRejectedTokenFailsTheRunWithTheReconnectMessageAndDeletesNothing() throws Exception {
        cards(FakeTrelloClient.card("c1", "First", "l-todo", T0));
        sync();
        trello.readFailure = new SourceAccessException(HttpTrelloClient.REJECTED);

        SyncRun run = syncService.run(tenant.id(), connector).orElseThrow();

        assertThat(run.status()).isEqualTo(SyncRun.FAILED);
        assertThat(run.error()).isEqualTo("Trello rejected the key or token. Reconnect Trello on the Sources page.");
        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsKeys("card:c1", "board:b1");
    }

    @Test
    void aBoardThatCannotBeReadFailsTheRunNamingItAndDeletesNothing() throws Exception {
        TrelloBoard hiring = new TrelloBoard("b2", "Hiring");
        connection.setBoards(tenant.id(), List.of(ROADMAP, hiring));
        cards(FakeTrelloClient.card("c1", "First", "l-todo", T0));
        trello.content.put("b2", FakeTrelloClient.board("b2", "Hiring", T0, List.of()));
        sync();
        trello.brokenBoards.add("b2");

        SyncRun run = syncService.run(tenant.id(), connector).orElseThrow();

        assertThat(run.status()).isEqualTo(SyncRun.FAILED);
        assertThat(run.error()).contains("\"Hiring\"", "untick it on the Sources page");
        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsKeys("card:c1", "board:b1", "board:b2");
    }

    @Test
    void oneCardThatCannotBeReadIsCountedAndTheRestStillIndex() throws Exception {
        Card good = FakeTrelloClient.card("c1", "Good", "l-todo", T0);
        Card base = FakeTrelloClient.card("c2", "Bad", "l-todo", T0);
        Card bad = new Card(base.id(), base.name(), "d", base.listId(), null, false, T0, base.shortUrl(), List.of(),
                List.of(), List.of(), java.util.Arrays.asList((TrelloClient.Comment) null));
        cards(good, bad);

        assertThat(sync()).isEqualTo(new SyncCounts(2, 0, 0, 0, 1)); // board, good card; bad card failed
        assertThat(store.modifiedTimes(tenant.id(), sourceId())).containsOnlyKeys("board:b1", "card:c1");
    }

    @Test
    void choosingNoBoardClearsWhatWasIndexedAndMakesNoTrelloCall() throws Exception {
        cards(FakeTrelloClient.card("c1", "First", "l-todo", T0));
        sync();
        int reads = trello.boardsRead.size();
        connection.setBoards(tenant.id(), List.of());

        assertThat(connector.configured(tenant.id())).isTrue();
        assertThat(sync()).isEqualTo(new SyncCounts(0, 0, 2, 0, 0));

        assertThat(trello.boardsRead).hasSize(reads);
        assertThat(store.count(tenant.id(), sourceId())).isZero();
        assertThat(connector.configured(tenant.id())).isFalse();
    }

    @Test
    void syncingWithoutAConnectionIsRefused() {
        connection.disconnect(tenant.id());

        assertThatThrownBy(() -> sync()).isInstanceOf(SourceAccessException.class)
                .hasMessageContaining("Trello is not connected");
    }

    @Test
    void disconnectingRemovesTheSecretAndTheDocumentsButKeepsTheBoards() throws Exception {
        cards(FakeTrelloClient.card("c1", "First", "l-todo", T0));
        sync();

        connection.disconnect(tenant.id());

        assertThat(connection.credential(tenant.id())).isEmpty();
        assertThat(store.count(tenant.id(), sourceId())).isZero();
        assertThat(connection.boards(tenant.id())).containsExactly(ROADMAP);
        assertThat(search.search(tenant.id(), ANYONE, "first", 5).hits()).isEmpty();
    }

    @Test
    void theSourceIsSharedAndKnownAsTrello() {
        assertThat(connection.source(tenant.id()).orElseThrow().kind()).isEqualTo("TRELLO");
        assertThat(connector.visibility()).isEqualTo("SHARED");
        assertThat(connector.name()).isEqualTo("Trello");
    }

    @Test
    void statusSaysWhatIsMissingWhatFailedAndHowMuchIsIndexed() throws Exception {
        connection.disconnect(tenant.id());
        assertThat(connector.status(tenant.id())).isEqualTo(
                ComponentStatus.off("Trello", "not connected — connect it on the Sources page"));

        connection.connect(tenant.id(), "Asha", FakeTrelloClient.GOOD_KEY, FakeTrelloClient.GOOD_TOKEN);
        connection.setBoards(tenant.id(), List.of());
        assertThat(connector.status(tenant.id())).isEqualTo(ComponentStatus.off("Trello", "connected, nothing chosen yet"));

        connection.setBoards(tenant.id(), List.of(ROADMAP));
        assertThat(connector.status(tenant.id()).detail()).contains("waiting for first sync");

        cards(FakeTrelloClient.card("c1", "First", "l-todo", T0));
        syncService.run(tenant.id(), connector);
        assertThat(connector.status(tenant.id()).state()).isEqualTo(State.UP);
        assertThat(connector.status(tenant.id()).detail()).startsWith("2 documents, last sync ");

        trello.readFailure = new SourceAccessException(HttpTrelloClient.REJECTED);
        syncService.run(tenant.id(), connector);
        assertThat(connector.status(tenant.id()).state()).isEqualTo(State.DOWN);
        assertThat(connector.status(tenant.id()).detail()).startsWith("last sync failed: Trello rejected the key or token");
    }
}
