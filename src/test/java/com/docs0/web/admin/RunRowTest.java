package com.docs0.web.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRun;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** How a row of the sync log reads on the Sources pages. */
class RunRowTest {

    private static SyncRun run(String status, Duration age) {
        return new SyncRun(1, 1, 1, status, Instant.now().minus(age), null, SyncCounts.NONE, null);
    }

    @Test
    void aRecentRunningRowShowsAsRunningWhileASyncIsRunning() {
        assertThat(SourcesController.runRow(run(SyncRun.RUNNING, Duration.ofHours(1)), true).label()).isEqualTo("Running");
    }

    @Test
    void aRunningRowOverThreeHoursOldIsInterruptedEvenIfAnotherSyncIsRunning() {
        assertThat(SourcesController.runRow(run(SyncRun.RUNNING, Duration.ofHours(4)), true).label())
                .isEqualTo("Interrupted");
    }

    @Test
    void aRunningRowWithNoSyncRunningIsInterrupted() {
        assertThat(SourcesController.runRow(run(SyncRun.RUNNING, Duration.ofMinutes(5)), false).label())
                .isEqualTo("Interrupted");
    }
}
