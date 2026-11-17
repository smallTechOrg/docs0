package com.docs0.job;

/** Starts the sync job outside this process (hosted mode). The network edge: tests use a fake. */
public interface JobLauncher {

    /** The job could not be started. {@link #getMessage()} is a short reason safe to show on a page. */
    class LaunchFailedException extends Exception {
        public LaunchFailedException(String reason) {
            super(reason);
        }
    }

    /** Asks for a sync of one source of one tenant. Returns once the request is accepted, not when the sync ends. */
    void runSync(long tenantId, long sourceId) throws LaunchFailedException;
}
