package com.docs0.source;

/** What one sync run did. Unchanged items are not counted. */
public record SyncCounts(int added, int updated, int removed, int skipped, int failed) {

    public static final SyncCounts NONE = new SyncCounts(0, 0, 0, 0, 0);
}
