package com.docs0.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.docs0.Phase6IntegrationTest;
import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.KnowledgeSearch;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SourceConnector;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SourceRepository;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.SyncScheduler;
import com.docs0.source.SyncService;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.source.linear.FakeLinearClient;
import com.docs0.source.linear.LinearConnection;
import com.docs0.source.linear.LinearConnector;
import com.docs0.source.linear.LinearTeam;
import com.docs0.tenant.TenantService;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** One scheduler pass serves every tenant: each with its own credentials, one failing never stops the other. */
class SchedulerIsolationTest extends Phase6IntegrationTest {

    static final String KEY_A = "lin_api_ALPHA_key";
    static final String KEY_B = "lin_api_BETA_key";
    static final LinearTeam ENG = new LinearTeam("team-eng", "ENG", "Engineering");

    /** A Linear whose issues depend on the API key, and which can fail for one key. */
    static final class KeyedLinear extends FakeLinearClient {

        final Map<String, List<Issue>> byKey = new ConcurrentHashMap<>();
        final List<String> issueCalls = new CopyOnWriteArrayList<>();
        volatile String failingKey;

        @Override
        public List<Issue> issues(String apiKey, String teamId) throws IOException {
            issueCalls.add(apiKey);
            if (apiKey.equals(failingKey)) {
                throw new SourceAccessException("Linear rejected the key");
            }
            return byKey.getOrDefault(apiKey, List.of());
        }
    }

    @Autowired SyncService syncService;
    @Autowired SourceRepository sources;
    @Autowired SourceCredentialRepository credentials;
    @Autowired DocumentStore store;
    @Autowired SyncRunRepository runs;
    @Autowired KnowledgeSearch search;
    @Autowired TenantService tenantService;

    KeyedLinear linear = new KeyedLinear();
    LinearConnection connection;
    LinearConnector connector;
    TwoTenants two;

    @BeforeEach
    void setUp() {
        two = TwoTenants.seed(jdbc, tenant.id());
        connection = new LinearConnection(new TokenCipher(DriveTestSetup.ENCRYPTION_KEY), sources, credentials, store);
        connector = new LinearConnector(linear, connection, store, runs);
        Instant t = Instant.parse("2026-03-01T09:00:00Z");
        linear.byKey.put(KEY_A, List.of(FakeLinearClient.issue("a1", "ENG-1", "Alpha login bug", t)));
        linear.byKey.put(KEY_B, List.of(FakeLinearClient.issue("b1", "ENG-1", "Beta login bug", t),
                FakeLinearClient.issue("b2", "ENG-2", "Beta billing bug", t)));
    }

    private void connect(long tenantId, String key) {
        connection.connect(tenantId, "Workspace", key);
        connection.setTeams(tenantId, List.of(ENG));
    }

    private SyncScheduler scheduler() {
        return new SyncScheduler(syncService, List.of(connector), DriveTestSetup.props(), tenantService);
    }

    private long documents(long tenantId) {
        return search.count(tenantId);
    }

    @Test
    void oneSchedulerPassSyncsEveryConfiguredTenantWithItsOwnKey() {
        connect(two.a.tenantId(), KEY_A);
        connect(two.b.tenantId(), KEY_B);
        jdbc.sql("DELETE FROM document").update(); // start from the connectors' own documents only

        scheduler().syncAll();

        assertThat(documents(two.a.tenantId())).isEqualTo(1);
        assertThat(documents(two.b.tenantId())).isEqualTo(2);
        assertThat(search.search(two.a.tenantId(), Audience.sharedOnly(), "login", 10).hits())
                .extracting(h -> h.title()).containsExactly("ENG-1: Alpha login bug");
        assertThat(search.search(two.b.tenantId(), Audience.sharedOnly(), "login", 10).hits())
                .extracting(h -> h.title()).containsExactly("ENG-1: Beta login bug");
        // Each key was used, and only for its own tenant's team.
        assertThat(linear.issueCalls).containsExactlyInAnyOrder(KEY_A, KEY_B);
        assertThat(runs.latest(two.a.tenantId(), connection.source(two.a.tenantId()).orElseThrow().id()).orElseThrow()
                .status()).isEqualTo(SyncRun.OK);
        assertThat(runs.latest(two.b.tenantId(), connection.source(two.b.tenantId()).orElseThrow().id()).orElseThrow()
                .status()).isEqualTo(SyncRun.OK);
    }

    @Test
    void aFailureInOneTenantDoesNotStopTheOther() {
        connect(two.a.tenantId(), KEY_A);
        connect(two.b.tenantId(), KEY_B);
        jdbc.sql("DELETE FROM document").update();
        linear.failingKey = KEY_A;

        scheduler().syncAll();

        SyncRun failed = runs.latest(two.a.tenantId(), connection.source(two.a.tenantId()).orElseThrow().id())
                .orElseThrow();
        assertThat(failed.status()).isEqualTo(SyncRun.FAILED);
        assertThat(failed.error()).doesNotContain(KEY_B);
        assertThat(runs.latest(two.b.tenantId(), connection.source(two.b.tenantId()).orElseThrow().id()).orElseThrow()
                .status()).isEqualTo(SyncRun.OK);
        assertThat(documents(two.a.tenantId())).isZero();
        assertThat(documents(two.b.tenantId())).isEqualTo(2);
    }

    @Test
    void aTenantThatHasNotConnectedTheSourceIsLeftAlone() {
        connect(two.a.tenantId(), KEY_A);
        jdbc.sql("DELETE FROM document").update();

        assertThat(connector.configured(two.a.tenantId())).isTrue();
        assertThat(connector.configured(two.b.tenantId())).isFalse();
        scheduler().syncAll();

        assertThat(linear.issueCalls).containsExactly(KEY_A);
        assertThat(jdbc.sql("SELECT count(*) FROM sync_run WHERE tenant_id = :t AND source_id IN "
                + "(SELECT id FROM source WHERE kind = 'LINEAR')").param("t", two.b.tenantId()).query(Long.class)
                .single()).isZero();
    }

    @Test
    void aSuspendedTenantIsSkippedByTheScheduleAndByAManualSync() {
        connect(two.a.tenantId(), KEY_A);
        connect(two.b.tenantId(), KEY_B);
        jdbc.sql("DELETE FROM document").update();
        tenantService.suspend(two.b.tenantId());

        scheduler().syncAll();

        assertThat(linear.issueCalls).containsExactly(KEY_A);
        assertThat(documents(two.b.tenantId())).isZero();
        assertThat(syncService.run(two.b.tenantId(), connector)).isEmpty();
        assertThat(syncService.startInBackground(two.b.tenantId(), connector)).isFalse();

        tenantService.activate(two.b.tenantId());
        scheduler().syncAll();
        assertThat(documents(two.b.tenantId())).isEqualTo(2);
    }

    /** A connector that holds its sync open until released. */
    static final class Blocking implements SourceConnector {

        final CountDownLatch started = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);

        @Override public String kind() { return "STUB"; }
        @Override public String name() { return "Stub"; }
        @Override public String visibility() { return "SHARED"; }
        @Override public boolean configured(long tenantId) { return true; }

        @Override
        public SyncCounts sync(long tenantId, long sourceId) throws Exception {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return SyncCounts.NONE;
        }
    }

    @Test
    void theInProcessGuardIsPerTenantAndSource() throws Exception {
        Blocking blocking = new Blocking();
        long a = two.a.tenantId();
        long b = two.b.tenantId();

        assertThat(syncService.startInBackground(a, blocking)).isTrue();
        long sourceA = sources.find(a, "STUB", "Stub").orElseThrow().id();
        // The same connector for another tenant is a different source and runs alongside.
        assertThat(syncService.startInBackground(b, blocking)).isTrue();
        long sourceB = sources.find(b, "STUB", "Stub").orElseThrow().id();
        assertThat(blocking.started.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(syncService.isRunning(a, sourceA)).isTrue();
        assertThat(syncService.isRunning(b, sourceB)).isTrue();
        // A tenant is never told about, or blocked by, another tenant's run.
        assertThat(syncService.isRunning(b, sourceA)).isFalse();
        assertThat(syncService.isRunning(a, sourceB)).isFalse();
        assertThat(syncService.startInBackground(a, blocking)).isFalse();

        blocking.release.countDown();
        await().atMost(10, TimeUnit.SECONDS).until(() -> !syncService.isRunning(a, sourceA)
                && !syncService.isRunning(b, sourceB));
    }
}
