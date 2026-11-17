package com.docs0.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A small in-memory limit on anonymous requests that start a flow (install, sign-in and their callbacks):
 * {@value #LIMIT} per minute per client address, across all of them, so one address cannot make the server create
 * sessions or call Slack without end. The table is bounded; the oldest addresses are forgotten first. The address is the
 * connection's, or the last {@code X-Forwarded-For} entry when the connection comes from this machine (a proxy
 * there is trusted to have added it).
 */
@Component
public class IpRateLimiter {

    public static final int LIMIT = 30;
    static final int MAX_ADDRESSES = 10_000;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private record Window(Instant start, int count) {}

    private final Clock clock;
    private final Map<String, Window> windows = new LinkedHashMap<>();

    @Autowired
    public IpRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** True when this request may go ahead; false when its address has used up this minute. */
    public synchronized boolean allow(HttpServletRequest request) {
        String address = address(request);
        Instant now = clock.instant();
        Window window = windows.remove(address); // re-inserted below, so the map stays in order of last use
        if (window == null || window.start().plus(WINDOW).isBefore(now)) {
            window = new Window(now, 0);
        }
        boolean allowed = window.count() < LIMIT;
        windows.put(address, new Window(window.start(), window.count() + (allowed ? 1 : 0)));
        while (windows.size() > MAX_ADDRESSES) {
            windows.remove(windows.keySet().iterator().next());
        }
        return allowed;
    }

    /** Forgets every address. For tests that share one application context. */
    public synchronized void reset() {
        windows.clear();
    }

    static String address(HttpServletRequest request) {
        String remote = request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank() && loopback(remote)) {
            String[] parts = forwarded.split(",");
            String last = parts[parts.length - 1].strip();
            if (!last.isEmpty() && last.length() <= 64) {
                return last;
            }
        }
        return remote;
    }

    private static boolean loopback(String address) {
        try {
            // An IP literal: no DNS lookup.
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (java.net.UnknownHostException | RuntimeException e) {
            return false;
        }
    }
}
