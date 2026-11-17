package com.docs0.source.gmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.docs0.IntegrationTest;
import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.KnowledgeSearch;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SourceRepository;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.SyncService;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.web.StatusContributor.ComponentStatus;
import com.docs0.web.StatusContributor.State;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/** The Gmail connector against the real database with a fake Gmail: what is stored, who may see it, what is skipped or removed. */
class GmailSyncTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-17T09:00:00Z");
    private static final Instant T0 = Instant.parse("2026-11-03T09:00:00Z");
    private static final String ANA = "ana@example.com";
    private static final String BOB = "bob@example.com";

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

    FakeGmailClient gmail;
    GmailConnection connection;
    GmailConnector connector;

    @BeforeEach
    void setUp() {
        gmail = new FakeGmailClient();
        connection = new GmailConnection(new TokenCipher(DriveTestSetup.ENCRYPTION_KEY), sources, credentials, store);
        connector = new GmailConnector(gmail, connection, store, runs, 90,
                Clock.fixed(NOW, ZoneOffset.UTC));
        connection.connect(tenant.id(), new GmailKey(TestKeys.EMAIL, TestKeys.CLIENT_ID), FakeGmailClient.KEY);
    }

    private long sourceId() {
        return connection.source(tenant.id()).orElseThrow().id();
    }

    private SyncCounts sync() throws Exception {
        return connector.sync(tenant.id(), sourceId());
    }

    private void choose(Mailbox... mailboxes) {
        connection.setMailboxes(tenant.id(), List.of(mailboxes));
    }

    private static Mailbox box(String email, String... viewers) {
        return new Mailbox(email, List.of(viewers));
    }

    private List<String> principalsOf(String externalId) {
        return jdbc.sql("""
                        SELECT p.principal FROM document_principal p JOIN document d ON d.id = p.document_id
                        WHERE d.tenant_id = :t AND d.external_id = :e ORDER BY p.principal
                        """).param("t", tenant.id()).param("e", externalId).query(String.class).list();
    }

    private boolean restricted(String externalId) {
        return jdbc.sql("SELECT restricted FROM document WHERE tenant_id = :t AND external_id = :e")
                .param("t", tenant.id()).param("e", externalId).query(Boolean.class).single();
    }

    private Set<String> live() {
        return store.modifiedTimes(tenant.id(), sourceId()).keySet();
    }

    // ---- what is stored ----------------------------------------------------------------------

    @Test
    void nothingIsReadUntilAMailboxIsChosen() throws Exception {
        gmail.put(ANA, "t1", "10", "Hello", "hi", T0);

        assertThat(connector.configured(tenant.id())).isFalse();
        assertThat(sync()).isEqualTo(SyncCounts.NONE);

        assertThat(gmail.keysSeen).isEmpty();
        assertThat(live()).isEmpty();
    }

    @Test
    void everyThreadBecomesARestrictedDocumentOfItsMailboxOwnerAndExtraViewers() throws Exception {
        gmail.put(ANA, "t1", "10", "Budget review", "The budget is 40k.", T0);
        gmail.put("support@example.com", "s1", "20", "Refund request", "Please refund order 55.", T0.plusSeconds(60));
        choose(box(ANA), box("Support@Example.com", "Ana@example.com", " LEE@example.com ", "support@example.com"));

        assertThat(sync()).isEqualTo(new SyncCounts(2, 0, 0, 0, 0));

        assertThat(live()).containsExactlyInAnyOrder("gmail:ana@example.com:t1", "gmail:support@example.com:s1");
        assertThat(restricted("gmail:ana@example.com:t1")).isTrue();
        assertThat(restricted("gmail:support@example.com:s1")).isTrue();
        assertThat(principalsOf("gmail:ana@example.com:t1")).containsExactly("ana@example.com");
        assertThat(principalsOf("gmail:support@example.com:s1"))
                .containsExactly("ana@example.com", "lee@example.com", "support@example.com");
        String url = jdbc.sql("SELECT url FROM document WHERE external_id = 'gmail:ana@example.com:t1'")
                .query(String.class).single();
        assertThat(url).isEqualTo("https://mail.google.com/mail/u/ana@example.com/#all/t1");
        var row = jdbc.sql("SELECT title, mime_type, owner_email, modified_at, body, version FROM document WHERE external_id = 'gmail:ana@example.com:t1'")
                .query((rs, n) -> List.of(rs.getString("title"), rs.getString("mime_type"), rs.getString("owner_email"),
                        rs.getString("body"), rs.getString("version"))).single();
        assertThat(row).containsExactly("Budget review", "application/vnd.google.gmail.thread", ANA,
                row.get(3), "10");
        assertThat(row.get(3)).contains("The budget is 40k.").contains("From: Ana <ana@example.com>");
        // The source as a whole is SHARED; privacy is per document.
        assertThat(jdbc.sql("SELECT visibility FROM source WHERE kind = 'GMAIL'").query(String.class).single())
                .isEqualTo("SHARED");
    }

    @Test
    void theSearchUsesTheHistoryWindowAndLeavesOutSpamTrashDraftsAndBulkCategories() throws Exception {
        gmail.put(ANA, "t1", "10", "Hello", "hi", T0);
        choose(box(ANA));

        sync();

        long since = NOW.minusSeconds(90L * 24 * 3600).getEpochSecond();
        assertThat(gmail.queries).containsExactly("after:" + since
                + " -in:spam -in:trash -in:draft -category:promotions -category:social -category:forums");
        assertThat(connector.historyDays()).isEqualTo(90);
    }

    @Test
    void longMailboxListsArePagedThrough() throws Exception {
        gmail.pageSize = 2;
        for (int i = 1; i <= 5; i++) {
            gmail.put(ANA, "t" + i, "h" + i, "Thread " + i, "text " + i, T0.plusSeconds(i));
        }
        choose(box(ANA));

        assertThat(sync().added()).isEqualTo(5);
    }

    @Test
    void aMailboxListCutShortByThePageLimitFailsThatMailboxAndDeletesNothing() throws Exception {
        for (int i = 1; i <= 501; i++) {
            gmail.put(ANA, "t" + i, "h" + i, "Thread " + i, "text " + i, T0.plusSeconds(i));
        }
        choose(box(ANA));
        gmail.pageSize = 1000;
        assertThat(sync().added()).isEqualTo(501);

        // One thread per page: 501 pages is more than docs0 will read, so the list is incomplete.
        gmail.pageSize = 1;
        SyncCounts counts = sync();

        assertThat(counts.failed()).isEqualTo(1);
        assertThat(counts.removed()).isZero();
        assertThat(live()).hasSize(501);
        assertThat(connector.notes(tenant.id()).get(ANA)).contains("this time");
    }

    // ---- changes -----------------------------------------------------------------------------

    @Test
    void anUnchangedThreadIsNotFetchedAgainAndAChangedOneIsUpdated() throws Exception {
        gmail.put(ANA, "t1", "10", "One", "first text", T0);
        gmail.put(ANA, "t2", "20", "Two", "second text", T0);
        choose(box(ANA));
        sync();
        assertThat(gmail.threadsRead).hasSize(2);
        gmail.threadsRead.clear();

        assertThat(sync()).isEqualTo(SyncCounts.NONE);
        assertThat(gmail.threadsRead).isEmpty();

        gmail.put(ANA, "t2", "21", "Two", "second text, now with a reply", T0.plusSeconds(3600));
        assertThat(sync()).isEqualTo(new SyncCounts(0, 1, 0, 0, 0));

        assertThat(gmail.threadsRead).containsExactly("ana@example.com:t2");
        assertThat(jdbc.sql("SELECT body FROM document WHERE external_id = 'gmail:ana@example.com:t2'")
                .query(String.class).single()).contains("now with a reply");
        assertThat(jdbc.sql("SELECT version FROM document WHERE external_id = 'gmail:ana@example.com:t2'")
                .query(String.class).single()).isEqualTo("21");
        assertThat(store.versions(tenant.id(), sourceId())).containsEntry("gmail:ana@example.com:t1", "10");
    }

    @Test
    void aThreadThatLeavesTheListOrIsEmptiedIsRemoved() throws Exception {
        gmail.put(ANA, "t1", "10", "Stays", "stays", T0);
        gmail.put(ANA, "t2", "20", "Trashed", "trashed", T0);
        gmail.put(ANA, "t3", "30", "Only drafts", "draft only", T0);
        choose(box(ANA));
        sync();

        gmail.remove(ANA, "t2"); // trashed, spam-marked or older than the window: no longer listed
        gmail.mailboxes.get(ANA).put("t3", new GmailClient.MailThread("t3", "31", "Only drafts", List.of()));

        assertThat(sync()).isEqualTo(new SyncCounts(0, 0, 2, 0, 0));
        assertThat(live()).containsExactly("gmail:ana@example.com:t1");
        // A removed thread is gone from search too.
        assertThat(search.search(tenant.id(), Audience.of("U1"), "trashed", 5).hits()).isEmpty();
    }

    @Test
    void aMailboxThatIsNoLongerChosenLosesItsDocumentsAtOnceEvenIfGoogleIsDown() throws Exception {
        gmail.put(ANA, "t1", "10", "Ana mail", "ana text", T0);
        gmail.put(BOB, "b1", "11", "Bob mail", "bob text", T0);
        choose(box(ANA), box(BOB));
        sync();
        choose(box(ANA));
        gmail.keyRejected = true; // Google would refuse everything now

        assertThatThrownBy(this::sync).isInstanceOf(GmailClient.KeyRejectedException.class);

        assertThat(live()).containsExactly("gmail:ana@example.com:t1");
    }

    @Test
    void clearingEveryMailboxRemovesAllMailWithoutCallingGoogle() throws Exception {
        gmail.put(ANA, "t1", "10", "Ana mail", "ana text", T0);
        choose(box(ANA));
        sync();
        gmail.keysSeen.clear();
        choose();

        assertThat(connector.configured(tenant.id())).isTrue(); // documents remain to be cleared
        assertThat(sync()).isEqualTo(new SyncCounts(0, 0, 1, 0, 0));

        assertThat(live()).isEmpty();
        assertThat(gmail.keysSeen).isEmpty();
        assertThat(connector.configured(tenant.id())).isFalse();
    }

    @Test
    void removingAViewerTakesEffectAtTheNextSyncEvenForUnchangedThreads() throws Exception {
        gmail.put("support@example.com", "s1", "20", "Refund", "refund text", T0);
        choose(box("support@example.com", "lee@example.com"));
        sync();
        assertThat(principalsOf("gmail:support@example.com:s1")).containsExactly("lee@example.com", "support@example.com");
        gmail.threadsRead.clear();

        choose(box("support@example.com", "kim@example.com"));
        assertThat(sync()).isEqualTo(SyncCounts.NONE);

        assertThat(gmail.threadsRead).isEmpty();
        assertThat(principalsOf("gmail:support@example.com:s1")).containsExactly("kim@example.com", "support@example.com");
        assertThat(restricted("gmail:support@example.com:s1")).isTrue();
    }

    @Test
    void aViewerIsRemovedEvenWhenGoogleFailsForThatMailbox() throws Exception {
        gmail.put("support@example.com", "s1", "20", "Refund", "refund text", T0);
        choose(box("support@example.com", "lee@example.com"));
        sync();

        choose(box("support@example.com"));
        gmail.refusals.put("support@example.com", "Gmail access isn't authorised for support@example.com yet.");
        sync();

        assertThat(principalsOf("gmail:support@example.com:s1")).containsExactly("support@example.com");
    }

    // ---- failures ----------------------------------------------------------------------------

    @Test
    void aMailboxWhoseDelegationIsRejectedIsSkippedWithItsReasonAndTheOthersStillSync() throws Exception {
        gmail.put(ANA, "t1", "10", "Ana mail", "ana text", T0);
        gmail.put(BOB, "b1", "11", "Bob mail", "bob text", T0);
        choose(box(ANA), box(BOB));
        sync();
        gmail.put(ANA, "t2", "12", "More Ana", "more ana", T0);
        gmail.refusals.put(BOB, "Gmail access isn't authorised for bob@example.com yet. Check domain-wide delegation in the Workspace Admin console.");

        assertThat(sync()).isEqualTo(new SyncCounts(1, 0, 0, 0, 1));

        assertThat(live()).containsExactlyInAnyOrder("gmail:ana@example.com:t1", "gmail:ana@example.com:t2",
                "gmail:bob@example.com:b1");
        assertThat(connector.notes(tenant.id())).containsOnlyKeys(BOB);
        assertThat(connector.notes(tenant.id()).get(BOB)).contains("isn't authorised for bob@example.com");
        // The note clears once the mailbox works again.
        gmail.refusals.clear();
        assertThat(sync().failed()).isZero();
        assertThat(connector.notes(tenant.id())).isEmpty();
    }

    @Test
    void aThreadThatCannotBeReadIsCountedAndKeepsItsOldCopyWhileTheRestSync() throws Exception {
        gmail.put(ANA, "t1", "10", "Good", "good text", T0);
        gmail.put(ANA, "t2", "20", "Flaky", "old flaky text", T0);
        choose(box(ANA));
        sync();
        gmail.put(ANA, "t1", "11", "Good", "good text v2", T0);
        gmail.put(ANA, "t2", "21", "Flaky", "new flaky text", T0);
        gmail.brokenThreads.add("t2");

        assertThat(sync()).isEqualTo(new SyncCounts(0, 1, 0, 0, 1));

        assertThat(jdbc.sql("SELECT body FROM document WHERE external_id = 'gmail:ana@example.com:t2'")
                .query(String.class).single()).contains("old flaky text");
        assertThat(live()).hasSize(2);
    }

    @Test
    void aListingFailureCountsTheMailboxFailedAndDeletesNothing() throws Exception {
        gmail.put(ANA, "t1", "10", "Ana mail", "ana text", T0);
        choose(box(ANA));
        sync();
        gmail.listFailure = new IOException("Google answered HTTP 502");

        assertThat(sync()).isEqualTo(new SyncCounts(0, 0, 0, 0, 1));

        assertThat(live()).containsExactly("gmail:ana@example.com:t1");
        assertThat(connector.notes(tenant.id()).get(ANA)).contains("this time");
    }

    @Test
    void aRejectedKeyFailsTheWholeRunAndDeletesNothing() throws Exception {
        gmail.put(ANA, "t1", "10", "Ana mail", "ana text", T0);
        gmail.put(BOB, "b1", "11", "Bob mail", "bob text", T0);
        choose(box(ANA), box(BOB));
        sync();
        gmail.mailboxes.get(ANA).clear();
        gmail.keyRejected = true;

        assertThatThrownBy(this::sync).isInstanceOf(GmailClient.KeyRejectedException.class)
                .hasMessage(HttpGmailClient.KEY_REJECTED);

        assertThat(live()).containsExactlyInAnyOrder("gmail:ana@example.com:t1", "gmail:bob@example.com:b1");
    }

    @Test
    void anUnreadableStoredKeyFailsTheRunBeforeAnythingIsTouched() throws Exception {
        gmail.put(ANA, "t1", "10", "Ana mail", "ana text", T0);
        choose(box(ANA));
        sync();
        // A key stored under a different encryption key cannot be read any more.
        GmailConnection other = new GmailConnection(
                new TokenCipher(java.util.Base64.getEncoder().encodeToString("another-key-another-key-32-byte".getBytes())),
                sources, credentials, store);
        GmailConnector broken = new GmailConnector(gmail, other, store, runs, 90, Clock.fixed(NOW, ZoneOffset.UTC));
        gmail.keysSeen.clear();

        assertThatThrownBy(() -> broken.sync(tenant.id(), sourceId())).isInstanceOf(SourceAccessException.class)
                .hasMessageContaining("cannot be read");

        assertThat(live()).hasSize(1);
        assertThat(gmail.keysSeen).isEmpty();
    }

    @Test
    void throughTheSyncServiceAFailedRunIsLoggedAndTheStatusGoesDown() throws Exception {
        gmail.put(ANA, "t1", "10", "Ana mail", "ana text", T0);
        choose(box(ANA));
        gmail.keyRejected = true;

        SyncRun run = syncService.run(tenant.id(), connector).orElseThrow();

        assertThat(run.status()).isEqualTo(SyncRun.FAILED);
        assertThat(run.error()).isEqualTo(HttpGmailClient.KEY_REJECTED);
        assertThat(connector.status(tenant.id()).state()).isEqualTo(State.DOWN);
        gmail.keyRejected = false;
        SyncRun ok = syncService.run(tenant.id(), connector).orElseThrow();
        assertThat(ok.status()).isEqualTo(SyncRun.OK);
        assertThat(ok.counts()).isEqualTo(new SyncCounts(1, 0, 0, 0, 0));
    }

    // ---- status ------------------------------------------------------------------------------

    @Test
    void theStatusComponentIsCalledGmailAndReadsOnlyTheDatabase() throws Exception {
        assertThat(connector.name()).isEqualTo("Gmail");
        assertThat(connector.kind()).isEqualTo("GMAIL");
        assertThat(connector.visibility()).isEqualTo("SHARED");

        ComponentStatus noMailboxes = connector.status(tenant.id());
        assertThat(noMailboxes.name()).isEqualTo("Gmail");
        assertThat(noMailboxes.state()).isEqualTo(State.OFF);
        assertThat(noMailboxes.detail()).contains("nothing chosen");

        gmail.put(ANA, "t1", "10", "Hello", "hi", T0);
        choose(box(ANA));
        assertThat(connector.status(tenant.id()).detail()).contains("waiting for first sync");
        syncService.run(tenant.id(), connector);
        assertThat(connector.status(tenant.id()).state()).isEqualTo(State.UP);
        assertThat(connector.status(tenant.id()).detail()).contains("1 threads in 1 mailboxes");

        connection.disconnect(tenant.id());
        assertThat(connector.status(tenant.id()).state()).isEqualTo(State.OFF);
        assertThat(connector.status(tenant.id()).detail()).contains("not connected");
        assertThat(connector.configured(tenant.id())).isFalse();
        assertThat(live()).isEmpty();
    }

    // ---- secrets stay out of the logs --------------------------------------------------------

    @Test
    void neitherTheKeyNorAddressesNorMailTextAreLoggedDuringASyncOrAFailure() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level before = root.getLevel();
        root.setLevel(Level.DEBUG);
        root.addAppender(appender);
        try {
            connection.connect(tenant.id(), new GmailKey(TestKeys.EMAIL, TestKeys.CLIENT_ID), TestKeys.json());
            gmail.put(ANA, "t1", "10", "Secret merger subject", "the merger closes friday", T0);
            gmail.put(BOB, "b1", "11", "Bob subject", "bob private text", T0);
            gmail.put("carol@example.com", "c1", "12", "Carol subject", "carol text", T0);
            choose(box(ANA, "viewer@example.com"), box(BOB), box("carol@example.com"));
            gmail.refusals.put(BOB, "Gmail access isn't authorised for bob@example.com yet.");
            gmail.brokenThreads.add("c1");
            syncService.run(tenant.id(), connector);
            gmail.keyRejected = true;
            syncService.run(tenant.id(), connector);
            connection.disconnect(tenant.id());
        } finally {
            root.detachAppender(appender);
            root.setLevel(before);
        }

        String logged = appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
        assertThat(logged).contains("Sync of source"); // something was logged
        assertThat(logged).doesNotContain(TestKeys.KEY_FRAGMENT).doesNotContain("PRIVATE KEY")
                .doesNotContain(TestKeys.CLIENT_ID).doesNotContain("example.com").doesNotContain("merger")
                .doesNotContain("subject").doesNotContain("private text");
    }
}
