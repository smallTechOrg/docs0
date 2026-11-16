package com.docs0.source;

import java.time.Instant;

/** One row of the sync log. Status is RUNNING, OK or FAILED. */
public record SyncRun(
        long id,
        long tenantId,
        long sourceId,
        String status,
        Instant startedAt,
        Instant finishedAt,
        SyncCounts counts,
        String error) {

    public static final String RUNNING = "RUNNING";
    public static final String OK = "OK";
    public static final String FAILED = "FAILED";
}
