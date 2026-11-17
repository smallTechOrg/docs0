package com.docs0.source.slack;

import static com.docs0.source.slack.FakeSlack.msg;
import static com.docs0.source.slack.FakeSlack.reply;
import static com.docs0.source.slack.FakeSlack.root;
import static com.docs0.source.slack.FakeSlack.ts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.IntegrationTest;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.KnowledgeSearch;
import com.docs0.slack.IdentityService;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SourceRepository;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRunRepository;
import com.docs0.web.StatusContributor.ComponentStatus;
import com.docs0.web.StatusContributor.State;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SlackHistorySyncTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    private static final Instant D16 = Instant.parse("2026-11-16T10:00:00Z");
    private static final Instant D16B = Instant.parse("2026-11-16T10:05:00Z");
    private static final Instant D17 = Instant.parse("2026-11-17T09:00:00Z");
    private static final Instant OLD = Instant.parse("2026-11-05T10:00:00Z");
    private static final int DAYS = 30;

    @Autowired
    DocumentStore store;

    @Autowired
    SourceRepository sources;

    @Autowired
    SyncRunRepository runs;

    @Autowired
    KnowledgeSearch search;

    FakeSlack slack;
    SlackHistoryConnection connection;
    SlackHistoryConnector connector;

    @BeforeEach
    void setUp() {
        slack = new FakeSlack().user("U1", "Priya", "priya@example.com").user("U2", "Marco", "marco@example.com")
                .user("U3", "Lena", "lena@example.com");
        connection = new SlackHistoryConnection(sources);
        connector = new SlackHistoryConnector(slack, new IdentityService(jdbc, slack), connection, store, runs,
                DAYS, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private long sourceId() {
        return connection.source(tenant.id()).orElseThrow().id();
    }

    private SyncCounts sync() throws Exception {
        long id = connection.source(tenant.id()).map(s -> s.id())
                .orElseGet(() -> sources.findOrCreate(tenant.id(), "SLACK", "Slack history", "SHARED").id());
        return connector.sync(tenant.id(), id);
    }

    private void choose(SlackChannel... channels) {
        connection.setChannels(tenant.id(), List.of(channels));
    }

    private static SlackChannel pub(String id, String name) {
        return new SlackChannel(id, name, false);
    }

    private static SlackChannel priv(String id, String name) {
        return new SlackChannel(id, name, true);
    }

    private void cleanRunStartedAt(Instant started) {
        jdbc.sql("""
                        INSERT INTO sync_run (tenant_id, source_id, started_at, finished_at, status)
                        VALUES (:t, :s, :at, :at, 'OK')
                        """).param("t", tenant.id()).param("s", sourceId()).param("at", java.sql.Timestamp.from(started)).update();
    }

    private Map<String, Object> row(String externalId) {
        return jdbc.sql("SELECT * FROM document WHERE tenant_id = :t AND external_id = :e")
                .param("t", tenant.id()).param("e", externalId).query().singleRow();
    }

    private List<String> principals(String externalId) {
        return jdbc.sql("""
                        SELECT p.principal FROM document_principal p JOIN document d ON d.id = p.document_id
                        WHERE d.tenant_id = :t AND d.external_id = :e ORDER BY 1
                        """).param("t", tenant.id()).param("e", externalId).query(String.class).list();
    }

    private Set<String> live() {
        return store.modifiedTimes(tenant.id(), sourceId()).keySet();
    }

    private void publicGeneral() {
        slack.channel("C1", "general", false, true).posts("C1",
                msg(ts(D16), "U1", "The launch is on <@U2>'s calendar &amp; confirmed"),
                msg(ts(D16B), "U2", "great, see <https://example.com/plan|the plan>"));
    }

    // ---- selection and basics ------------------------------------------------------------------------------

    @Test
    void nothingIsIndexedUntilAChannelIsChosen() throws Exception {
        publicGeneral();

        assertThat(connector.configured(tenant.id())).isFalse();
        assertThat(sync()).isEqualTo(SyncCounts.NONE);

        assertThat(slack.historyCalls).isEmpty();
        assertThat(store.count(tenant.id(), sourceId())).isZero();
        ComponentStatus status = connector.status(tenant.id());
        assertThat(status.name()).isEqualTo("Slack history");
        assertThat(status.state()).isEqualTo(State.OFF);
    }

    @Test
    void aPublicChannelBecomesSharedDocumentsWithNoPrincipals() throws Exception {
        publicGeneral();
        slack.posts("C1", root(ts(D16), "U1", "Who owns the brand kit?", 1, ts(D16B)));
        slack.thread("C1", root(ts(D16), "U1", "Who owns the brand kit?", 1, ts(D16B)),
                reply(ts(D16B), ts(D16), "U2", "Marco does"));
        choose(pub("C1", "general"));

        SyncCounts counts = sync();

        assertThat(counts).isEqualTo(new SyncCounts(2, 0, 0, 0, 0));
        assertThat(live()).containsExactlyInAnyOrder("day:C1:2026-11-16", "thread:C1:" + ts(D16));
        Map<String, Object> day = row("day:C1:2026-11-16");
        assertThat(day.get("restricted")).isEqualTo(false);
        assertThat(day.get("title")).isEqualTo("#general — 16 Nov 2026");
        assertThat(day.get("mime_type")).isEqualTo("application/vnd.slack.day");
        assertThat(day.get("owner_name")).isEqualTo("Priya");
        assertThat(day.get("url")).isEqualTo("https://example.slack.com/archives/C1/p" + ts(D16).replace(".", ""));
        assertThat((String) day.get("body")).isEqualTo("""
                [2026-11-16 10:00] Priya: The launch is on @Marco's calendar & confirmed
                [2026-11-16 10:05] Marco: great, see the plan (https://example.com/plan)""");
        assertThat(row("thread:C1:" + ts(D16)).get("mime_type")).isEqualTo("application/vnd.slack.thread");
        assertThat(principals("day:C1:2026-11-16")).isEmpty();
        // Anyone can find it, including an empty audience.
        assertThat(search.search(tenant.id(), Audience.sharedOnly(), "launch calendar", 5).hits()).hasSize(1);
        assertThat(search.search(tenant.id(), Audience.sharedOnly(), "launch calendar", 5).hits().get(0).sourceKind())
                .isEqualTo("SLACK");
    }

    @Test
    void onlyChosenChannelsAreEverAskedFor() throws Exception {
        publicGeneral();
        slack.channel("C2", "random", false, true).posts("C2", msg(ts(D16), "U1", "lunch?"));
        choose(pub("C1", "general"));

        sync();

        assertThat(slack.historyCalls).containsOnly("C1");
        assertThat(slack.members).isEmpty();
    }

    // ---- private channels ------------------------------------------------------------------------------------------

    @Test
    void aPrivateChannelIsRestrictedToItsCurrentMembersByIdAndEmail() throws Exception {
        slack.channel("G1", "leads", true, true).members("G1", "U1", "U2", "UBOT")
                .posts("G1", msg(ts(D16), "U1", "bonus pool is forty percent"));
        choose(priv("G1", "leads"));

        assertThat(sync()).isEqualTo(new SyncCounts(1, 0, 0, 0, 0));

        assertThat(row("day:G1:2026-11-16").get("restricted")).isEqualTo(true);
        assertThat(principals("day:G1:2026-11-16")).containsExactlyInAnyOrder("u1", "u2", "ubot", "priya@example.com",
                "marco@example.com");
        assertThat(search.search(tenant.id(), Audience.of("U1", "U2"), "bonus pool", 5).hits()).hasSize(1);
        assertThat(search.search(tenant.id(), Audience.of("U1", "U3"), "bonus pool", 5).hits()).isEmpty();
        assertThat(search.search(tenant.id(), Audience.sharedOnly(), "bonus pool", 5).hits()).isEmpty();
    }

    @Test
    void membershipChangesReachTheNextSyncEvenForUnchangedDocuments() throws Exception {
        slack.channel("G1", "leads", true, true).members("G1", "U1", "U2")
                .posts("G1", msg(ts(D16), "U1", "bonus pool is forty percent"));
        choose(priv("G1", "leads"));
        sync();
        assertThat(search.search(tenant.id(), Audience.of("U1", "U2"), "bonus", 5).hits()).hasSize(1);

        // U2 is removed from the channel, U3 is added. The messages themselves did not change.
        slack.members("G1", "U1", "U3");
        assertThat(sync()).isEqualTo(SyncCounts.NONE);

        assertThat(principals("day:G1:2026-11-16")).containsExactlyInAnyOrder("u1", "u3", "priya@example.com",
                "lena@example.com");
        assertThat(search.search(tenant.id(), Audience.of("U1", "U2"), "bonus", 5).hits()).isEmpty();
        assertThat(search.search(tenant.id(), Audience.of("U1", "U3"), "bonus", 5).hits()).hasSize(1);
    }

    @Test
    void whenMembersCannotBeListedThePrivateDocumentsAreHiddenFromEveryoneAndTheRunCarriesOn() throws Exception {
        slack.channel("G1", "leads", true, true).members("G1", "U1", "U2")
                .posts("G1", msg(ts(D16), "U1", "bonus pool is forty percent"));
        slack.channel("C1", "general", false, true).posts("C1", msg(ts(D16), "U1", "hello everyone"));
        choose(priv("G1", "leads"), pub("C1", "general"));
        sync();
        slack.failingMembers.add("G1");

        SyncCounts counts = sync();

        assertThat(counts.failed()).isEqualTo(1);
        assertThat(search.search(tenant.id(), Audience.of("U1", "U2"), "bonus", 5).hits()).isEmpty();
        assertThat(search.search(tenant.id(), Audience.of("U1", "U2"), "hello", 5).hits()).hasSize(1);
        assertThat(live()).contains("day:G1:2026-11-16"); // hidden, not deleted: it comes back when members are known

        slack.failingMembers.clear();
        sync();
        assertThat(search.search(tenant.id(), Audience.of("U1", "U2"), "bonus", 5).hits()).hasSize(1);
    }

    @Test
    void aChannelThatTurnsPrivateBecomesRestrictedAtTheNextSync() throws Exception {
        slack.channel("C1", "ops", false, true).members("C1", "U1").posts("C1", msg(ts(D16), "U1", "rollout plan"));
        choose(pub("C1", "ops"));
        sync();
        assertThat(search.search(tenant.id(), Audience.sharedOnly(), "rollout", 5).hits()).hasSize(1);

        slack.channels.clear();
        slack.channel("C1", "ops", true, true);
        choose(priv("C1", "ops"));
        sync();

        assertThat(row("day:C1:2026-11-16").get("restricted")).isEqualTo(true);
        assertThat(search.search(tenant.id(), Audience.sharedOnly(), "rollout", 5).hits()).isEmpty();
        assertThat(search.search(tenant.id(), Audience.of("U1"), "rollout", 5).hits()).hasSize(1);
    }

    // ---- joining and inviting --------------------------------------------------------------------------------------------

    @Test
    void aPublicChannelTheBotIsNotInIsJoinedFirst() throws Exception {
        slack.channel("C1", "general", false, false).posts("C1", msg(ts(D16), "U1", "hello"));
        choose(pub("C1", "general"));

        assertThat(sync().added()).isEqualTo(1);

        assertThat(slack.joins).containsExactly("C1");
    }

    @Test
    void aPublicChannelThatCannotBeJoinedIsSkippedWithAReasonAndTheRunContinues() throws Exception {
        slack.channel("C1", "general", false, false).posts("C1", msg(ts(D16), "U1", "hello"));
        slack.channel("C2", "random", false, true).posts("C2", msg(ts(D16), "U1", "lunch plans"));
        slack.failingJoins.add("C1");
        choose(pub("C1", "general"), pub("C2", "random"));

        SyncCounts counts = sync();

        assertThat(counts).isEqualTo(new SyncCounts(1, 0, 0, 1, 0));
        assertThat(connector.notes(tenant.id())).containsKey("C1");
        assertThat(connector.notes(tenant.id()).get("C1")).contains("#general", "could not join");
    }

    @Test
    void aPrivateChannelTheBotWasNotInvitedToIsSkippedWithAnInviteHintAndItsDocumentsGoAway() throws Exception {
        slack.channel("G1", "leads", true, true).members("G1", "U1").posts("G1", msg(ts(D16), "U1", "bonus pool"));
        choose(priv("G1", "leads"));
        sync();
        assertThat(live()).hasSize(1);

        slack.channels.clear(); // docs0 was removed from the channel: Slack no longer lists it
        SyncCounts counts = sync();

        assertThat(counts).isEqualTo(new SyncCounts(0, 0, 1, 1, 0));
        assertThat(connector.notes(tenant.id()).get("G1")).isEqualTo("invite @docs0 to #leads");
        assertThat(live()).isEmpty();
        assertThat(slack.joins).isEmpty();
    }

    @Test
    void aFailingChannelNeverAbortsTheRun() throws Exception {
        slack.channel("C1", "general", false, true).posts("C1", msg(ts(D16), "U1", "hello"));
        slack.channel("C2", "random", false, true).posts("C2", msg(ts(D16), "U1", "lunch plans"));
        slack.channel("C3", "eng", false, true).posts("C3", msg(ts(D17), "U2", "deploy done"));
        slack.failingHistory.add("C2");
        choose(pub("C1", "general"), pub("C2", "random"), pub("C3", "eng"));

        SyncCounts counts = sync();

        assertThat(counts).isEqualTo(new SyncCounts(2, 0, 0, 0, 1));
        assertThat(live()).containsExactlyInAnyOrder("day:C1:2026-11-16", "day:C3:2026-11-17");
    }

    @Test
    void aFailingThreadIsCountedAndKeepsItsStoredCopy() throws Exception {
        slack.channel("C1", "general", false, true);
        slack.posts("C1", root(ts(D16), "U1", "Who owns the brand kit?", 1, ts(D16B)));
        slack.thread("C1", root(ts(D16), "U1", "Who owns the brand kit?", 1, ts(D16B)),
                reply(ts(D16B), ts(D16), "U2", "Marco does"));
        choose(pub("C1", "general"));
        sync();
        String id = "thread:C1:" + ts(D16);

        // A new reply arrives, but reading the thread fails this time.
        slack.history.put("C1", List.of(root(ts(D16), "U1", "Who owns the brand kit?", 2, ts(D17))));
        slack.failingReplies.add("C1:" + ts(D16));
        SyncCounts counts = sync();

        assertThat(counts).isEqualTo(new SyncCounts(0, 0, 0, 0, 1));
        assertThat(live()).contains(id);
        assertThat((String) row(id).get("body")).contains("Marco does");
    }

    // ---- rebuilding, incremental windows, deletion -----------------------------------------------------------------

    @Test
    void theFirstRunReadsTheWholeWindowFromTheStartOfTheDay() throws Exception {
        publicGeneral();
        choose(pub("C1", "general"));

        sync();

        assertThat(slack.historyFrom).containsExactly(Instant.parse("2026-10-18T00:00:00Z")); // 30 days back, start of day
    }

    @Test
    void laterRunsOfTheSameDayReadFromTheLastCleanRunMinusADay() throws Exception {
        publicGeneral();
        choose(pub("C1", "general"));
        sync();
        slack.historyFrom.clear();
        cleanRunStartedAt(Instant.parse("2026-11-17T09:00:00Z"));

        sync();

        assertThat(slack.historyFrom).containsExactly(Instant.parse("2026-11-16T00:00:00Z"));
    }

    @Test
    void theFirstRunOfANewUtcDayReadsTheWholeWindowAgain() throws Exception {
        publicGeneral();
        choose(pub("C1", "general"));
        sync();
        slack.historyFrom.clear();
        cleanRunStartedAt(Instant.parse("2026-11-16T23:00:00Z"));

        sync();

        assertThat(slack.historyFrom).containsExactly(Instant.parse("2026-10-18T00:00:00Z"));
    }

    @Test
    void aRunWithFailuresDoesNotAdvanceTheWindow() throws Exception {
        publicGeneral();
        choose(pub("C1", "general"));
        sync();
        slack.historyFrom.clear();
        jdbc.sql("""
                        INSERT INTO sync_run (tenant_id, source_id, started_at, finished_at, status, failed)
                        VALUES (:t, :s, :at, :at, 'OK', 1)
                        """).param("t", tenant.id()).param("s", sourceId())
                .param("at", java.sql.Timestamp.from(Instant.parse("2026-11-17T09:00:00Z"))).update();

        sync();

        assertThat(slack.historyFrom).containsExactly(Instant.parse("2026-10-18T00:00:00Z"));
    }

    @Test
    void aChannelAddedLaterIsReadInFullEvenInAnIncrementalRun() throws Exception {
        publicGeneral();
        slack.channel("C2", "eng", false, true).posts("C2", msg(ts(OLD), "U1", "old news in eng"));
        choose(pub("C1", "general"));
        sync();
        cleanRunStartedAt(Instant.parse("2026-11-17T09:00:00Z"));
        choose(pub("C1", "general"), pub("C2", "eng"));
        slack.historyFrom.clear();
        slack.historyCalls.clear();

        sync();

        assertThat(slack.historyCalls).containsExactly("C1", "C2");
        assertThat(slack.historyFrom).containsExactly(Instant.parse("2026-11-16T00:00:00Z"),
                Instant.parse("2026-10-18T00:00:00Z"));
        assertThat(live()).contains("day:C2:2026-11-05");
    }

    @Test
    void unchangedDocumentsAreNotRewrittenAndNewMessagesUpdateTheDayDocument() throws Exception {
        publicGeneral();
        choose(pub("C1", "general"));
        sync();
        slack.permalinkCalls.clear();

        assertThat(sync()).isEqualTo(SyncCounts.NONE);

        slack.posts("C1", msg(ts(Instant.parse("2026-11-16T11:00:00Z")), "U1", "one more thing"));
        assertThat(sync()).isEqualTo(new SyncCounts(0, 1, 0, 0, 0));
        assertThat((String) row("day:C1:2026-11-16").get("body")).contains("one more thing");
        // The permalink is fetched for new documents only.
        assertThat(slack.permalinkCalls).isEmpty();
    }

    @Test
    void aFailedPermalinkFallsBackToTheChannelLink() throws Exception {
        publicGeneral();
        slack.permalinksFail = true;
        choose(pub("C1", "general"));

        sync();

        assertThat(row("day:C1:2026-11-16").get("url")).isEqualTo("https://slack.com/app_redirect?channel=C1");
    }

    @Test
    void aThreadIsReadAgainOnlyWhenItHasNewActivity() throws Exception {
        slack.channel("C1", "general", false, true);
        SlackMessageSet first = new SlackMessageSet();
        slack.posts("C1", first.root);
        slack.thread("C1", first.root, first.reply);
        choose(pub("C1", "general"));
        sync();
        assertThat(slack.replyCalls).hasSize(1);

        // Nothing new: the thread is not fetched again.
        sync();
        assertThat(slack.replyCalls).hasSize(1);

        // A new reply makes the root report a later latest_reply.
        Instant later = Instant.parse("2026-11-17T08:00:00Z");
        var newRoot = root(ts(D16), "U1", "Who owns the brand kit?", 2, ts(later));
        slack.history.put("C1", List.of(newRoot));
        slack.thread("C1", newRoot, first.reply, reply(ts(later), ts(D16), "U3", "Lena has it now"));
        assertThat(sync()).isEqualTo(new SyncCounts(0, 1, 0, 0, 0));

        assertThat(slack.replyCalls).hasSize(2);
        assertThat((String) row("thread:C1:" + ts(D16)).get("body")).contains("Lena has it now");
        assertThat(row("thread:C1:" + ts(D16)).get("modified_at").toString()).contains("2026-11-17");
    }

    private static final class SlackMessageSet {
        final com.docs0.slack.SlackMessenger.SlackMessage root = root(ts(D16), "U1", "Who owns the brand kit?", 1, ts(D16B));
        final com.docs0.slack.SlackMessenger.SlackMessage reply = reply(ts(D16B), ts(D16), "U2", "Marco does");
    }

    @Test
    void deletedMessagesDisappearAtTheNextSync() throws Exception {
        slack.channel("C1", "general", false, true).posts("C1", msg(ts(D16), "U1", "keep this"),
                msg(ts(D16B), "U2", "delete this"), msg(ts(D17), "U1", "lonely message"));
        choose(pub("C1", "general"));
        sync();
        assertThat((String) row("day:C1:2026-11-16").get("body")).contains("delete this");

        slack.history.put("C1", List.of(msg(ts(D16), "U1", "keep this")));
        SyncCounts counts = sync();

        assertThat((String) row("day:C1:2026-11-16").get("body")).doesNotContain("delete this");
        assertThat(counts).isEqualTo(new SyncCounts(0, 1, 1, 0, 0)); // the day updated, the 17th's document removed
        assertThat(live()).containsExactly("day:C1:2026-11-16");
    }

    @Test
    void aDeletedThreadRootRemovesTheThreadDocument() throws Exception {
        slack.channel("C1", "general", false, true);
        slack.posts("C1", root(ts(D16), "U1", "Who owns the brand kit?", 1, ts(D16B)));
        slack.thread("C1", root(ts(D16), "U1", "Who owns the brand kit?", 1, ts(D16B)),
                reply(ts(D16B), ts(D16), "U2", "Marco does"));
        choose(pub("C1", "general"));
        sync();
        assertThat(live()).hasSize(1);

        slack.history.put("C1", List.of());

        assertThat(sync().removed()).isEqualTo(1);
        assertThat(live()).isEmpty();
    }

    @Test
    void documentsOlderThanTheWindowAreDropped() throws Exception {
        slack.channel("C1", "general", false, true).posts("C1", msg(ts(OLD), "U1", "old but in window"),
                msg(ts(D16), "U1", "recent"));
        choose(pub("C1", "general"));
        sync();
        assertThat(live()).containsExactlyInAnyOrder("day:C1:2026-11-05", "day:C1:2026-11-16");

        // Thirty days later the fifth is outside the window: a full pass drops it.
        SlackHistoryConnector later = new SlackHistoryConnector(slack, new IdentityService(jdbc, slack), connection, store,
                runs, DAYS, Clock.fixed(NOW.plusSeconds(26L * 86400), ZoneOffset.UTC));
        later.sync(tenant.id(), sourceId());

        assertThat(live()).containsExactly("day:C1:2026-11-16");
    }

    @Test
    void deselectingAChannelRemovesItsDocuments() throws Exception {
        publicGeneral();
        slack.channel("C2", "eng", false, true).posts("C2", msg(ts(D16), "U1", "eng news"));
        choose(pub("C1", "general"), pub("C2", "eng"));
        sync();
        assertThat(live()).hasSize(2);

        choose(pub("C1", "general"));
        SyncCounts counts = sync();

        assertThat(counts.removed()).isEqualTo(1);
        assertThat(live()).containsExactly("day:C1:2026-11-16");
        assertThat(connector.configured(tenant.id())).isTrue();
    }

    @Test
    void clearingTheWholeSelectionRemovesEverythingAndThenTheSourceIsIdle() throws Exception {
        publicGeneral();
        choose(pub("C1", "general"));
        sync();

        choose();
        assertThat(connector.configured(tenant.id())).isTrue(); // documents remain, so a run is still needed
        assertThat(sync().removed()).isEqualTo(1);

        assertThat(live()).isEmpty();
        assertThat(connector.configured(tenant.id())).isFalse();
        assertThat(slack.historyCalls).hasSize(1);
    }

    @Test
    void anArchivedOrMissingPublicChannelLosesItsDocuments() throws Exception {
        publicGeneral();
        choose(pub("C1", "general"));
        sync();

        slack.channels.clear();
        SyncCounts counts = sync();

        assertThat(counts).isEqualTo(new SyncCounts(0, 0, 1, 1, 0));
        assertThat(connector.notes(tenant.id()).get("C1")).contains("not found or is archived");
    }

    @Test
    void ifSlackCannotListChannelsTheRunFailsAndNothingIsDeleted() throws Exception {
        publicGeneral();
        choose(pub("C1", "general"));
        sync();

        slack.listFails = true;

        assertThatThrownBy(this::sync).isInstanceOf(RuntimeException.class);
        assertThat(live()).hasSize(1);
    }

    @Test
    void docs0sOwnMessagesNeverBecomeDocuments() throws Exception {
        slack.channel("C1", "general", false, true).posts("C1", msg(ts(D16), FakeSlack.BOT, "The refund policy is 30 days."));
        choose(pub("C1", "general"));

        assertThat(sync()).isEqualTo(SyncCounts.NONE);
        assertThat(live()).isEmpty();
    }

    @Test
    void anUnknownUserStaysAnIdAndTheRunDoesNotFail() throws Exception {
        slack.channel("C1", "general", false, true).posts("C1", msg(ts(D16), "U99", "hi from a stranger"));
        choose(pub("C1", "general"));

        assertThat(sync().failed()).isZero();
        assertThat((String) row("day:C1:2026-11-16").get("body")).isEqualTo("[2026-11-16 10:00] U99: hi from a stranger");
    }

    @Test
    void withoutABotTokenTheRunFailsWithAClearMessage() {
        // A tenant with no Slack workspace: asking for its messenger fails like the real registry does.
        com.docs0.slack.SlackMessengers unconnected = t -> {
            throw new com.docs0.slack.SlackMessenger.SlackCallException("workspace", "no_workspace", null);
        };
        SlackHistoryConnector noToken = new SlackHistoryConnector(unconnected, new IdentityService(jdbc, slack), connection, store,
                runs, DAYS, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(noToken.configured(tenant.id())).isFalse();
        assertThat(noToken.status(tenant.id()).state()).isEqualTo(State.OFF);
        assertThatThrownBy(() -> noToken.sync(tenant.id(), 1L))
                .isInstanceOf(SourceAccessException.class).hasMessageContaining("DOCS0_SLACK_BOT_TOKEN");
    }

    @Test
    void statusReportsProgressAndFailures() throws Exception {
        publicGeneral();
        choose(pub("C1", "general"));
        assertThat(connector.status(tenant.id()).state()).isEqualTo(State.UP);
        assertThat(connector.status(tenant.id()).detail()).contains("waiting for first sync");

        long run = runs.start(tenant.id(), sourceId());
        runs.finish(tenant.id(), run, "OK", new SyncCounts(1, 0, 0, 0, 0), null);
        assertThat(connector.status(tenant.id()).detail()).contains("0 documents, last sync");

        long failed = runs.start(tenant.id(), sourceId());
        runs.finish(tenant.id(), failed, "FAILED", SyncCounts.NONE, "Slack said no");
        assertThat(connector.status(tenant.id()).state()).isEqualTo(State.DOWN);
        assertThat(connector.status(tenant.id()).detail()).contains("Slack said no");
    }
}
