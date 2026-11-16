package com.docs0.web.slack;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can move, to expire sign-in links and request timestamps. */
public class MutableClock extends Clock {

    private volatile Instant now = Instant.now();

    public void reset() {
        now = Instant.now();
    }

    public void advance(Duration by) {
        now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
