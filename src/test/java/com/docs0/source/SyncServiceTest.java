package com.docs0.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SyncServiceTest extends IntegrationTest {

    @Autowired
    SyncService syncService;

    @Autowired
    SyncScheduler scheduler;

    @Autowired
    SourceRepository sources;

    @Autowired
    SyncRunRepository runs;

    /** A connector whose sync does whatever the test says. */
    static class StubConnector implements SourceConnector {

        interface Body {
            SyncCounts run() throws Exception;
        }

        final Body body;
        final AtomicInteger calls = new AtomicInteger();

        StubConnector(Body body) {
            this.body = body;
        }

        @Override
        public String kind() {
            return "STUB";
        }

        @Override
        public String name() {
            return "Stub";
        }

        @Override
        public String visibility() {
            return "SHARED";
        }

        @Override
        public boolean configured(long tenantId) {
            return true;
        }

        @Override
        public SyncCounts sync(long tenantId, long sourceId) throws Exception {
            calls.incrementAndGet();
            return body.run();
        }
    }

    @Test
    void recordsAnOkRunAndReusesTheSourceRow() {
        StubConnector connector = new StubConnector(() -> new SyncCounts(1, 2, 3, 4, 5));

        SyncRun first = syncService.run(tenant.id(), connector).orElseThrow();
        SyncRun second = syncService.run(tenant.id(), connector).orElseThrow();

        assertThat(first.status()).isEqualTo(SyncRun.OK);
        assertThat(first.counts()).isEqualTo(new SyncCounts(1, 2, 3, 4, 5));
        assertThat(first.tenantId()).isEqualTo(tenant.id());
        assertThat(first.startedAt()).isNotNull();
        assertThat(first.finishedAt()).isAfterOrEqualTo(first.startedAt());
        assertThat(second.sourceId()).isEqualTo(first.sourceId());
        assertThat(jdbc.sql("SELECT count(*) FROM source").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM sync_run").query(Long.class).single()).isEqualTo(2);
        assertThat(runs.latest(tenant.id(), first.sourceId()).orElseThrow().id()).isEqualTo(second.id());
    }

    @Test
    void connectorFailureIsRecordedNotThrownAndTheNextRunWorks() {
        AtomicInteger attempt = new AtomicInteger();
        StubConnector connector = new StubConnector(() -> {
            if (attempt.getAndIncrement() == 0) {
                throw new IllegalStateException("x".repeat(2000));
            }
            return SyncCounts.NONE;
        });

        SyncRun failed = syncService.run(tenant.id(), connector).orElseThrow();

        assertThat(failed.status()).isEqualTo(SyncRun.FAILED);
        assertThat(failed.error()).startsWith("IllegalStateException: xxx").hasSize(500);
        assertThat(failed.finishedAt()).isNotNull();
        assertThat(syncService.run(tenant.id(), connector).orElseThrow().status()).isEqualTo(SyncRun.OK);
    }

    @Test
    void secondSyncOfTheSameSourceIsRefusedWhileOneIsRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StubConnector connector = new StubConnector(() -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return SyncCounts.NONE;
        });

        CompletableFuture<Optional<SyncRun>> first = CompletableFuture.supplyAsync(() -> syncService.run(tenant.id(), connector));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(syncService.run(tenant.id(), connector)).isEmpty();

        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).orElseThrow().status()).isEqualTo(SyncRun.OK);
        assertThat(connector.calls.get()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM sync_run").query(Long.class).single()).isEqualTo(1);

        // The guard is released afterwards.
        assertThat(syncService.run(tenant.id(), connector)).isPresent();
    }

    @Test
    void backgroundSyncIsInTheLogAsRunningBeforeItReturnsAndRefusesASecondStart() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        StubConnector connector = new StubConnector(() -> {
            release.await(10, TimeUnit.SECONDS);
            return new SyncCounts(1, 0, 0, 0, 0);
        });

        assertThat(syncService.startInBackground(tenant.id(), connector)).isTrue();

        long sourceId = sources.find(tenant.id(), "STUB", "Stub").orElseThrow().id();
        assertThat(runs.latest(tenant.id(), sourceId).orElseThrow().status()).isEqualTo(SyncRun.RUNNING);
        assertThat(syncService.isRunning(tenant.id(), sourceId)).isTrue();
        assertThat(syncService.startInBackground(tenant.id(), connector)).isFalse();

        release.countDown();
        org.awaitility.Awaitility.await().atMost(10, TimeUnit.SECONDS)
                .until(() -> !syncService.isRunning(tenant.id(), sourceId));
        SyncRun finished = runs.latest(tenant.id(), sourceId).orElseThrow();
        assertThat(finished.status()).isEqualTo(SyncRun.OK);
        assertThat(finished.counts()).isEqualTo(new SyncCounts(1, 0, 0, 0, 0));
        assertThat(runs.recent(tenant.id(), sourceId, 5)).hasSize(1);
    }

    @Test
    void sourceAccessProblemsAreRecordedInTheAdminsWords() {
        StubConnector connector = new StubConnector(() -> {
            throw new SourceAccessException("Reconnect it.");
        });

        assertThat(syncService.run(tenant.id(), connector).orElseThrow().error()).isEqualTo("Reconnect it.");
    }

    @Test
    void schedulingDoesNothingWhenDriveIsNotConfigured() {
        scheduler.onReady();
        scheduler.syncAll();

        assertThat(jdbc.sql("SELECT count(*) FROM sync_run").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM source").query(Long.class).single()).isZero();
    }
}
