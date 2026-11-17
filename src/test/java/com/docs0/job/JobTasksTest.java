package com.docs0.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.docs0.Phase6IntegrationTest;
import com.docs0.TestData;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Citation;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Kind;
import com.docs0.conversation.Conversation;
import com.docs0.conversation.InteractionRepository;
import com.docs0.insights.DigestService;
import com.docs0.source.SourceConnector;
import com.docs0.source.SourceRepository;
import com.docs0.source.SyncCounts;
import com.docs0.source.slack.FakeSlack;
import com.docs0.tenant.local.LocalSlackPilot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The sync and digest jobs, run against the real database with fake connectors and a fake Slack. */
@Import(JobTasksTest.Connectors.class)
@TestPropertySource(properties = "docs0.slack.bot-token=xoxb-test-token")
class JobTasksTest extends Phase6IntegrationTest {

    /** A connector that is configured for the tenants the test says, and records every sync it is asked for. */
    static class FakeConnector implements SourceConnector {
        final String name;
        final Set<Long> configuredFor = ConcurrentHashMap.newKeySet();
        final List<Long> synced = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        FakeConnector(String name) {
            this.name = name;
        }

        @Override public String kind() { return "JOBTEST_" + name; }
        @Override public String name() { return name; }
        @Override public String visibility() { return "SHARED"; }
        @Override public boolean configured(long tenantId) { return configuredFor.contains(tenantId); }

        @Override
        public SyncCounts sync(long tenantId, long sourceId) {
            synced.add(tenantId);
            if (failing) {
                throw new IllegalStateException("source " + name + " is down");
            }
            return new SyncCounts(3, 0, 0, 0, 0);
        }

        void reset() {
            configuredFor.clear();
            synced.clear();
            failing = false;
        }
    }

    @TestConfiguration
    static class Connectors {
        @Bean FakeConnector jobA() { return new FakeConnector("A"); }
        @Bean FakeConnector jobB() { return new FakeConnector("B"); }
        @Bean FakeConnector jobC() { return new FakeConnector("C"); }
        @Bean FakeConnector jobD() { return new FakeConnector("D"); }
    }

    @MockitoBean LocalSlackPilot pilot;

    @Autowired JobTasks jobs;
    @Autowired SourceRepository sources;
    @Autowired DigestService digest;
    @Autowired InteractionRepository interactions;
    @Autowired FakeSlack slack;
    @Autowired @Qualifier("jobA") FakeConnector a;
    @Autowired @Qualifier("jobB") FakeConnector b;
    @Autowired @Qualifier("jobC") FakeConnector c;
    @Autowired @Qualifier("jobD") FakeConnector d;

    long second;

    @BeforeEach
    void setUp() {
        for (FakeConnector connector : List.of(a, b, c, d)) {
            connector.reset();
        }
        second = tenants.create("second", "Second").id();
        slack.reset();
    }

    private static JobRunner.Job sync(OptionalLong tenant, OptionalLong source) {
        return new JobRunner.Job("sync", tenant, source);
    }

    private Map<String, String> runs() {
        Map<String, String> result = new java.util.TreeMap<>();
        jdbc.sql("""
                SELECT r.tenant_id, s.name, r.status FROM sync_run r JOIN source s ON s.id = r.source_id
                WHERE s.kind LIKE 'JOBTEST_%' ORDER BY r.tenant_id, s.name
                """).query((rs, n) -> {
            result.put(rs.getLong(1) + ":" + rs.getString(2), rs.getString(3));
            return null;
        }).list();
        return result;
    }

    @Test
    void syncRunsEveryConfiguredConnectorOfEveryActiveTenantAndSkipsTheRest() {
        a.configuredFor.addAll(List.of(tenant.id(), second));
        b.configuredFor.add(tenant.id());
        // c is configured for nobody

        jobs.run(sync(OptionalLong.empty(), OptionalLong.empty()));

        assertThat(runs()).containsExactlyInAnyOrderEntriesOf(Map.of(
                tenant.id() + ":A", "OK", second + ":A", "OK", tenant.id() + ":B", "OK"));
        assertThat(c.synced).isEmpty();
    }

    @Test
    void aSuspendedTenantIsNotSynced() {
        a.configuredFor.addAll(List.of(tenant.id(), second));
        tenants.suspend(second);

        jobs.run(sync(OptionalLong.empty(), OptionalLong.empty()));

        assertThat(runs()).containsOnlyKeys(tenant.id() + ":A");
    }

    @Test
    void aFailingSourceIsRecordedAndTheOthersCarryOnWithoutFailingTheJob() {
        a.configuredFor.addAll(List.of(tenant.id(), second));
        d.configuredFor.addAll(List.of(tenant.id(), second));
        d.failing = true;

        jobs.run(sync(OptionalLong.empty(), OptionalLong.empty())); // does not throw

        assertThat(runs()).containsEntry(tenant.id() + ":A", "OK").containsEntry(second + ":A", "OK")
                .containsEntry(tenant.id() + ":D", "FAILED").containsEntry(second + ":D", "FAILED");
        assertThat(jdbc.sql("SELECT error FROM sync_run WHERE status = 'FAILED' LIMIT 1").query(String.class).single())
                .contains("source D is down");
    }

    @Test
    void theTenantArgumentNarrowsTheRunToOneTenant() {
        a.configuredFor.addAll(List.of(tenant.id(), second));
        b.configuredFor.addAll(List.of(tenant.id(), second));

        jobs.run(sync(OptionalLong.of(second), OptionalLong.empty()));

        assertThat(runs()).containsOnlyKeys(second + ":A", second + ":B");
    }

    @Test
    void theSourceArgumentNarrowsTheRunToOneSource() {
        a.configuredFor.addAll(List.of(tenant.id(), second));
        b.configuredFor.addAll(List.of(tenant.id(), second));
        long sourceB = sources.findOrCreate(second, b.kind(), b.name(), b.visibility()).id();

        jobs.run(sync(OptionalLong.of(second), OptionalLong.of(sourceB)));

        assertThat(runs()).containsOnlyKeys(second + ":B");
    }

    @Test
    void aSourceOfAnotherTenantIsRejectedAndNothingIsSynced() {
        a.configuredFor.addAll(List.of(tenant.id(), second));
        long othersSource = sources.findOrCreate(second, a.kind(), a.name(), a.visibility()).id();

        jobs.run(sync(OptionalLong.of(tenant.id()), OptionalLong.of(othersSource)));

        assertThat(runs()).isEmpty();
        assertThat(a.synced).isEmpty();
    }

    @Test
    void anUnknownSourceSyncsNothing() {
        a.configuredFor.add(tenant.id());

        jobs.run(sync(OptionalLong.of(tenant.id()), OptionalLong.of(987654)));

        assertThat(runs()).isEmpty();
    }

    @Test
    void thePilotWorkspaceIsRegisteredBeforeSyncingAndASlackOutageDoesNotFailTheJob() {
        a.configuredFor.add(tenant.id());
        org.mockito.Mockito.when(pilot.register()).thenThrow(
                new com.docs0.slack.SlackMessenger.SlackCallException("auth.test", "network_error", null));

        jobs.run(sync(OptionalLong.empty(), OptionalLong.empty()));

        verify(pilot, atLeastOnce()).register();
        assertThat(runs()).containsEntry(tenant.id() + ":A", "OK");
    }

    // ---- digest ----

    private void logAnswer(long tenantId, String channel) {
        Conversation conversation = TestData.conversation(jdbc, tenantId, channel, "1.1", false, false);
        AgentResult result = new AgentResult(Kind.ANSWER, "An answer.", Confidence.HIGH,
                List.of(new Citation(1, 1, "t", "u", "q")), List.of(), List.of(), 1, 100, 10, new BigDecimal("0.1"), 5,
                null);
        interactions.insert(tenantId, conversation.id(), "U1", "How do refunds work?", result,
                "9." + System.nanoTime() % 1000000);
    }

    @Test
    void theDigestJobPostsOncePerActiveTenantAndIsIdempotentPerWeek() {
        slack.channel("C2", "digest", false, true);
        for (long id : new long[] {tenant.id(), second}) {
            digest.setChannel(id, "C2", "digest");
            logAnswer(id, "C1");
        }

        jobs.run(new JobRunner.Job("digest", OptionalLong.empty(), OptionalLong.empty()));
        assertThat(slack.sent).hasSize(2);
        jobs.run(new JobRunner.Job("digest", OptionalLong.empty(), OptionalLong.empty()));

        assertThat(slack.sent).as("a second run in the same week posts nothing").hasSize(2);
        assertThat(digest.lastPostedAt(tenant.id())).isPresent();
        assertThat(digest.lastPostedAt(second)).isPresent();
    }

    @Test
    void theDigestJobSkipsASuspendedTenant() {
        slack.channel("C2", "digest", false, true);
        digest.setChannel(second, "C2", "digest");
        logAnswer(second, "C1");
        tenants.suspend(second);

        jobs.run(new JobRunner.Job("digest", OptionalLong.empty(), OptionalLong.empty()));

        assertThat(slack.sent).isEmpty();
    }
}
