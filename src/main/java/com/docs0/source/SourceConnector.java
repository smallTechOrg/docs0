package com.docs0.source;

/** One kind of knowledge source. {@link SyncService} owns the source row, the sync log and locking. */
public interface SourceConnector {

    /** Stored in {@code source.kind}, e.g. GOOGLE_DRIVE. */
    String kind();

    /** Stored in {@code source.name} and shown on the status page. */
    String name();

    /** SHARED or RESTRICTED. */
    String visibility();

    /** False when there is nothing to sync (not connected, nothing chosen); it is then skipped on the schedule. */
    boolean configured();

    /**
     * Brings the tenant's documents of this source up to date. One bad item must be counted
     * as failed, not thrown; throw only when the run as a whole cannot proceed. Throw
     * {@link SourceAccessException} when an admin has to act; its message is shown as is.
     */
    SyncCounts sync(long tenantId, long sourceId) throws Exception;
}
