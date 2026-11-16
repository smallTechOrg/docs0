package com.docs0.source.slack;

import static com.docs0.source.slack.FakeSlack.msg;
import static com.docs0.source.slack.FakeSlack.ts;
import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.knowledge.DocumentStore;
import com.docs0.slack.IdentityService;
import com.docs0.slack.SlackMessengers;
import com.docs0.source.SourceRepository;
import com.docs0.source.SyncRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Slack history for two workspaces with the same channel and user ids: names, notes and documents stay apart. */
class SlackHistoryTwoTenantsTest extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    private static final Instant D16 = Instant.parse("2026-11-16T10:00:00Z");

    @Autowired DocumentStore store;
    @Autowired SourceRepository sources;
    @Autowired SyncRunRepository runs;

    FakeSlack slackA;
    FakeSlack slackB;
    long a;
    long b;
    SlackHistoryConnection connection;
    SlackHistoryConnector connector;

    @BeforeEach
    void setUp() {
        a = tenant.id();
        b = tenants.ensure("acme", "Acme").id();
        slackA = new FakeSlack().user("U1", "Priya Alpha", "priya@alpha.example.com");
        slackB = new FakeSlack().user("U1", "Prince Beta", "prince@beta.example.com");
        slackA.channel("C1", "general", false, true).channel("G1", "leads", true, false)
                .posts("C1", msg(ts(D16), "U1", "Alpha launch is next week"));
        slackB.channel("C1", "general", false, true).posts("C1", msg(ts(D16), "U1", "Beta launch is next month"));
        SlackMessengers messengers = tenantId -> tenantId == a ? slackA : slackB;
        connection = new SlackHistoryConnection(sources);
        connector = new SlackHistoryConnector(messengers, new IdentityService(jdbc, messengers), connection, store, runs,
                30, Clock.fixed(NOW, ZoneOffset.UTC));
        connection.setChannels(a, List.of(new SlackChannel("C1", "general", false), new SlackChannel("G1", "leads", true)));
        connection.setChannels(b, List.of(new SlackChannel("C1", "general", false)));
    }

    private void sync(long tenantId) throws Exception {
        connector.sync(tenantId, connection.source(tenantId).orElseThrow().id());
    }

    private String bodies(long tenantId) {
        return String.join("\n", jdbc.sql("SELECT body FROM document WHERE tenant_id = :t")
                .param("t", tenantId).query(String.class).list());
    }

    @Test
    void namesDocumentsAndSkipNotesStayWithTheirTenantEvenWhenSyncedAtTheSameTime() throws Exception {
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> run(a));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> run(b));
        first.get();
        second.get();

        assertThat(bodies(a)).contains("Priya Alpha").contains("Alpha launch").doesNotContain("Beta")
                .doesNotContain("Prince");
        assertThat(bodies(b)).contains("Prince Beta").contains("Beta launch").doesNotContain("Alpha")
                .doesNotContain("Priya");
        // A's private channel the bot is not in is noted for A; B has no such note.
        assertThat(connector.notes(a)).containsKey("G1");
        assertThat(connector.notes(b)).isEmpty();
        // Each tenant's Slack was asked only about its own channels.
        assertThat(slackA.historyCalls).containsOnly("C1");
        assertThat(slackB.historyCalls).containsOnly("C1");
    }

    private void run(long tenantId) {
        try {
            sync(tenantId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aTenantWithoutAWorkspaceIsNotConfiguredAndItsSyncFailsCleanly() {
        long c = tenants.ensure("globex", "Globex").id();
        SlackMessengers none = tenantId -> {
            throw new com.docs0.slack.SlackMessenger.SlackCallException("workspace", "no_workspace", null);
        };
        SlackHistoryConnector lonely = new SlackHistoryConnector(none, new IdentityService(jdbc, none), connection,
                store, runs, 30, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(lonely.configured(c)).isFalse();
        assertThat(lonely.status(c).state()).isEqualTo(com.docs0.web.StatusContributor.State.OFF);
    }
}
