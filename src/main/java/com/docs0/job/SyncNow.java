package com.docs0.job;

import com.docs0.config.Docs0Properties;
import com.docs0.config.HostedProperties;
import com.docs0.source.Source;
import com.docs0.source.SourceConnector;
import com.docs0.source.SourceRepository;
import com.docs0.source.SyncService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * What the "Sync now" button of every Sources section does. Which tenant and source is decided by the caller from the
 * signed-in admin: a click can only ever sync that admin's own source.
 *
 * <ul>
 *   <li>Hosted ({@code DOCS0_SYNC_JOB_NAME} set): asks the Cloud Run Job to sync this one source.
 *   <li>HTTP transport without a job: syncs inside the request, because work left running after the response is
 *       frozen on Cloud Run's request-based CPU.
 *   <li>Otherwise (the laptop pilot): syncs on a background thread, as always.
 * </ul>
 */
@Service
public class SyncNow {

    public enum Outcome { STARTED, ALREADY_RUNNING, REQUESTED, FAILED }

    /** {@code reason} is only set for FAILED. */
    public record Result(Outcome outcome, String reason) {

        /**
         * Tells the admin on the page what happened. Nothing is said for STARTED: the page shows the run itself.
         *
         * @param alreadyRunning the section's own "already running" sentence
         */
        public void flash(RedirectAttributes flash, String alreadyRunning) {
            switch (outcome) {
                case ALREADY_RUNNING -> flash.addFlashAttribute("notice", alreadyRunning);
                case REQUESTED -> flash.addFlashAttribute("notice", REQUESTED_NOTICE);
                case FAILED -> flash.addFlashAttribute("error", "Could not start the sync job (" + reason
                        + "). It will run at the next scheduled time.");
                case STARTED -> { }
            }
        }
    }

    static final String REQUESTED_NOTICE =
            "A sync was requested. It starts in a few seconds; this page refreshes while it runs.";

    private final Docs0Properties props;
    private final HostedProperties hosted;
    private final SyncService syncService;
    private final SourceRepository sources;
    private final JobLauncher launcher;

    public SyncNow(Docs0Properties props, HostedProperties hosted, SyncService syncService, SourceRepository sources,
            JobLauncher launcher) {
        this.props = props;
        this.hosted = hosted;
        this.syncService = syncService;
        this.sources = sources;
        this.launcher = launcher;
    }

    public Result request(long tenantId, SourceConnector connector) {
        if (hosted.syncViaJob()) {
            Source source = sources.findOrCreate(tenantId, connector.kind(), connector.name(), connector.visibility());
            if (syncService.isRunning(tenantId, source.id())) {
                return new Result(Outcome.ALREADY_RUNNING, null);
            }
            try {
                launcher.runSync(tenantId, source.id());
                return new Result(Outcome.REQUESTED, null);
            } catch (JobLauncher.LaunchFailedException e) {
                return new Result(Outcome.FAILED, e.getMessage());
            }
        }
        if (props.slack() != null && props.slack().httpTransport()) {
            return syncService.run(tenantId, connector).isPresent() ? new Result(Outcome.STARTED, null)
                    : new Result(Outcome.ALREADY_RUNNING, null);
        }
        return syncService.startInBackground(tenantId, connector) ? new Result(Outcome.STARTED, null)
                : new Result(Outcome.ALREADY_RUNNING, null);
    }
}
