package com.docs0.source;

/** One kind of knowledge source. {@link SyncService} owns the source row, the sync log and locking. */
public interface SourceConnector {

    /** Stored in {@code source.kind}, e.g. GOOGLE_DRIVE. */
    String kind();

    /** Stored in {@code source.name} and shown on the status page. */
    String name();

    /** SHARED or RESTRICTED. */
    String visibility();

    /** False when the credentials for this source are not set; it is then never synced on a schedule. */
    boolean configured();

    /**
     * Brings the tenant's documents of this source up to date. One bad item must be counted
     * as failed, not thrown; throw only when the run as a whole cannot proceed.
     */
    SyncCounts sync(long tenantId, long sourceId) throws Exception;
}
