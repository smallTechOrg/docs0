package com.docs0.slack;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Limits how often the status line is rewritten: at most one update per interval, counted
 * from when the status message was posted. A status that arrives too early is held and
 * sent when the interval is over; if several arrive, only the latest is sent. After
 * {@link #close()} nothing more is sent, so a late status can never overwrite the answer.
 */
final class StatusThrottle {

    /** Runs a task later. Returns a handle that cancels it. */
    interface Timer {
        Runnable schedule(Runnable task, long delayMillis);
    }

    private final Consumer<String> sink;
    private final long intervalMillis;
    private final LongSupplier clockMillis;
    private final Timer timer;

    private long lastSentAt;
    private String pending;
    private Runnable cancelScheduled;
    private boolean closed;

    StatusThrottle(Consumer<String> sink, long intervalMillis, LongSupplier clockMillis, Timer timer) {
        this.sink = sink;
        this.intervalMillis = intervalMillis;
        this.clockMillis = clockMillis;
        this.timer = timer;
        this.lastSentAt = clockMillis.getAsLong();
    }

    /** Offers a new status line. Safe to call from any thread. */
    synchronized void onStatus(String text) {
        if (closed || text == null || text.isBlank()) {
            return;
        }
        long wait = lastSentAt + intervalMillis - clockMillis.getAsLong();
        if (wait <= 0 && cancelScheduled == null) {
            send(text);
            return;
        }
        pending = text;
        if (cancelScheduled == null) {
            cancelScheduled = timer.schedule(this::flush, Math.max(wait, 0));
        }
    }

    /** Stops all updates. Returns only after any update in flight has finished. */
    synchronized void close() {
        closed = true;
        pending = null;
        if (cancelScheduled != null) {
            cancelScheduled.run();
            cancelScheduled = null;
        }
    }

    private synchronized void flush() {
        cancelScheduled = null;
        if (closed || pending == null) {
            return;
        }
        String text = pending;
        pending = null;
        send(text);
    }

    // Called with the lock held, so updates never overlap and close() waits for one in flight.
    private void send(String text) {
        lastSentAt = clockMillis.getAsLong();
        sink.accept(text);
    }
}
