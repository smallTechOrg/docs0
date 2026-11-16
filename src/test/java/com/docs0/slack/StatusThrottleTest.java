package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatusThrottleTest {

    /** A hand-driven clock and timer: time only moves when the test says so. */
    private static final class ManualTime implements StatusThrottle.Timer {

        private record Scheduled(Runnable task, long dueAt) {}

        long now = 10_000;
        final List<Scheduled> scheduled = new ArrayList<>();
        int scheduleCalls;

        @Override
        public Runnable schedule(Runnable task, long delayMillis) {
            scheduleCalls++;
            Scheduled entry = new Scheduled(task, now + delayMillis);
            scheduled.add(entry);
            return () -> scheduled.remove(entry);
        }

        void advance(long millis) {
            now += millis;
            for (Scheduled entry : List.copyOf(scheduled)) {
                if (entry.dueAt() <= now && scheduled.remove(entry)) {
                    entry.task().run();
                }
            }
        }
    }

    private final ManualTime time = new ManualTime();
    private final List<String> sent = new ArrayList<>();
    private final StatusThrottle throttle = new StatusThrottle(sent::add, 1000, () -> time.now, time);

    @Test
    void holdsBackUpdatesUntilOneSecondAfterTheStatusMessageWasPosted() {
        throttle.onStatus("a");
        time.advance(999);
        assertThat(sent).isEmpty();

        time.advance(1);
        assertThat(sent).containsExactly("a");
    }

    @Test
    void sendsAtMostOneUpdatePerSecondAndTheLatestStatusWins() {
        throttle.onStatus("a");
        throttle.onStatus("b");
        throttle.onStatus("c");
        time.advance(1000);
        assertThat(sent).containsExactly("c");
        assertThat(time.scheduleCalls).isEqualTo(1);

        time.advance(400);
        throttle.onStatus("d");
        throttle.onStatus("e");
        time.advance(599);
        assertThat(sent).containsExactly("c");
        time.advance(1);
        assertThat(sent).containsExactly("c", "e");
    }

    @Test
    void sendsImmediatelyWhenTheLastUpdateIsOldEnough() {
        time.advance(1500);
        throttle.onStatus("a");
        assertThat(sent).containsExactly("a");
        assertThat(time.scheduled).isEmpty();

        time.advance(1000);
        throttle.onStatus("b");
        assertThat(sent).containsExactly("a", "b");
    }

    @Test
    void sendsNothingAfterClose() {
        throttle.onStatus("a");
        throttle.close();
        assertThat(time.scheduled).as("the pending update is cancelled").isEmpty();

        time.advance(5000);
        throttle.onStatus("b");
        time.advance(5000);
        assertThat(sent).isEmpty();
    }

    @Test
    void aTimerThatFiresAfterCloseSendsNothing() {
        List<Runnable> fired = new ArrayList<>();
        StatusThrottle uncancellable = new StatusThrottle(sent::add, 1000, () -> time.now, (task, delay) -> {
            fired.add(task);
            return () -> {};
        });
        uncancellable.onStatus("a");
        uncancellable.close();

        fired.forEach(Runnable::run);

        assertThat(sent).isEmpty();
    }

    @Test
    void ignoresBlankStatus() {
        time.advance(2000);
        throttle.onStatus(" ");
        throttle.onStatus(null);
        assertThat(sent).isEmpty();
    }
}
