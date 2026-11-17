package com.docs0.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The guard across processes. Two {@link SyncService} instances stand for the web service and a Cloud Run Job: they
 * share nothing but the database, so only the Postgres advisory lock can stop them syncing the same source together.
 */
class SyncLockTest extends IntegrationTest {

    @Autowired DataSource dataSource;
    @Autowired SourceRepository sources;
    @Autowired SyncRunRepository runs;

    private SyncService newProcess() {
        return new SyncService(tenants, sources, runs, new SyncLock(dataSource));
    }

    private static SourceConnector connector(String name, SyncServiceTest.StubConnector.Body body) {
        return new SyncServiceTest.StubConnector(body) {
            @Override
            public String kind() {
                return "STUB_" + name;
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    @Test
    void aSecondProcessSkipsASourceTheFirstIsSyncing() throws Exception {
        SyncService first = newProcess();
        SyncService second = newProcess();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SourceConnector slow = connector("Same", () -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return SyncCounts.NONE;
        });
        SourceConnector other = connector("Same", () -> new SyncCounts(9, 0, 0, 0, 0));

        CompletableFuture<Optional<SyncRun>> running = CompletableFuture.supplyAsync(() -> first.run(tenant.id(), slow));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(second.run(tenant.id(), other)).as("the lock is held in another process").isEmpty();
        assertThat(((SyncServiceTest.StubConnector) other).calls).hasValue(0);
        assertThat(jdbc.sql("SELECT count(*) FROM sync_run").query(Long.class).single()).isEqualTo(1);

        release.countDown();
        assertThat(running.get(10, TimeUnit.SECONDS)).get().extracting(SyncRun::status).isEqualTo(SyncRun.OK);
        assertThat(second.run(tenant.id(), other)).as("released after the run").isPresent();
    }

    @Test
    void differentSourcesSyncAtTheSameTimeAcrossProcesses() throws Exception {
        SyncService first = newProcess();
        SyncService second = newProcess();
        CountDownLatch bothInside = new CountDownLatch(2);
        SyncServiceTest.StubConnector.Body rendezvous = () -> {
            bothInside.countDown();
            assertThat(bothInside.await(10, TimeUnit.SECONDS)).as("the other source runs in parallel").isTrue();
            return SyncCounts.NONE;
        };

        CompletableFuture<Optional<SyncRun>> a = CompletableFuture.supplyAsync(
                () -> first.run(tenant.id(), connector("A", rendezvous)));
        CompletableFuture<Optional<SyncRun>> b = CompletableFuture.supplyAsync(
                () -> second.run(tenant.id(), connector("B", rendezvous)));

        assertThat(a.get(15, TimeUnit.SECONDS)).isPresent();
        assertThat(b.get(15, TimeUnit.SECONDS)).isPresent();
    }

    @Test
    void theLockIsReleasedAfterAFailedRun() {
        SyncService first = newProcess();
        SyncService second = newProcess();

        SyncRun failed = first.run(tenant.id(), connector("Boom", () -> {
            throw new IllegalStateException("source exploded");
        })).orElseThrow();
        Optional<SyncRun> again = second.run(tenant.id(), connector("Boom", () -> SyncCounts.NONE));

        assertThat(failed.status()).isEqualTo(SyncRun.FAILED);
        assertThat(again).get().extracting(SyncRun::status).isEqualTo(SyncRun.OK);
    }

    @Test
    void twoTenantsWithTheSameSourceNameDoNotBlockEachOther() throws Exception {
        long other = tenants.create("other", "Other").id();
        SyncService first = newProcess();
        SyncService second = newProcess();
        CountDownLatch bothInside = new CountDownLatch(2);
        SyncServiceTest.StubConnector.Body rendezvous = () -> {
            bothInside.countDown();
            assertThat(bothInside.await(10, TimeUnit.SECONDS)).isTrue();
            return SyncCounts.NONE;
        };

        CompletableFuture<Optional<SyncRun>> a = CompletableFuture.supplyAsync(
                () -> first.run(tenant.id(), connector("Same", rendezvous)));
        CompletableFuture<Optional<SyncRun>> b = CompletableFuture.supplyAsync(
                () -> second.run(other, connector("Same", rendezvous)));

        assertThat(a.get(15, TimeUnit.SECONDS)).isPresent();
        assertThat(b.get(15, TimeUnit.SECONDS)).isPresent();
    }

    // ---- "is a sync running" comes from the database ----

    private long sourceId() {
        return sources.findOrCreate(tenant.id(), "STUB", "Stub", "SHARED").id();
    }

    private void insertRun(long sourceId, String status, String age) {
        jdbc.sql("INSERT INTO sync_run (tenant_id, source_id, status, started_at) VALUES (:t, :s, :status, now() - CAST(:age AS interval))")
                .param("t", tenant.id()).param("s", sourceId).param("status", status).param("age", age).update();
    }

    @Test
    void aRunningRowFromAnotherProcessShowsAsRunning() {
        long source = sourceId();
        SyncService service = newProcess();
        assertThat(service.isRunning(tenant.id(), source)).isFalse();

        insertRun(source, "RUNNING", "1 hour");

        assertThat(service.isRunning(tenant.id(), source)).isTrue();
        assertThat(service.isRunning(tenant.id(), source + 1)).as("other sources are unaffected").isFalse();
    }

    @Test
    void aRunningRowOlderThanThreeHoursIsInterruptedNotRunningAndIsKept() {
        long source = sourceId();
        insertRun(source, "RUNNING", "3 hours 5 minutes");

        assertThat(newProcess().isRunning(tenant.id(), source)).isFalse();
        assertThat(runs.recent(tenant.id(), source, 5)).singleElement()
                .extracting(SyncRun::status).isEqualTo(SyncRun.RUNNING); // kept as is; pages show it as Interrupted
    }

    @Test
    void finishedRowsAreNeverRunning() {
        long source = sourceId();
        insertRun(source, "OK", "1 minute");
        insertRun(source, "FAILED", "2 minutes");

        assertThat(newProcess().isRunning(tenant.id(), source)).isFalse();
    }

    @Test
    void theLockIsTakenForTheDurationOfTheRunOnly() throws Exception {
        SyncLock lock = new SyncLock(dataSource);
        try (SyncLock.Held held = lock.tryLock(tenant.id(), 77)) {
            assertThat(held).isNotNull();
            assertThat(lock.tryLock(tenant.id(), 77)).isNull();
            SyncLock.Held other = lock.tryLock(tenant.id(), 78);
            assertThat(other).isNotNull();
            other.close();
        }
        SyncLock.Held again = lock.tryLock(tenant.id(), 77);
        assertThat(again).isNotNull();
        again.close();
    }
}
