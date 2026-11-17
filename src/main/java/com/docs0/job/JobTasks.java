package com.docs0.job;

import com.docs0.config.Docs0Properties;
import com.docs0.insights.DigestService;
import com.docs0.source.Source;
import com.docs0.source.SourceConnector;
import com.docs0.source.SourceRepository;
import com.docs0.source.SyncScheduler;
import com.docs0.tenant.Tenant;
import com.docs0.tenant.TenantService;
import com.docs0.tenant.local.LocalSlackPilot;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The work of the one-shot jobs (see {@link JobRunner}). Ids and counts only in the log. */
@Component
public class JobTasks {

    private static final Logger log = LoggerFactory.getLogger(JobTasks.class);

    private final Docs0Properties props;
    private final TenantService tenants;
    private final SourceRepository sources;
    private final SyncScheduler scheduler;
    private final DigestService digest;
    private final LocalSlackPilot pilot;
    private final List<SourceConnector> connectors;

    public JobTasks(Docs0Properties props, TenantService tenants, SourceRepository sources, SyncScheduler scheduler,
            DigestService digest, LocalSlackPilot pilot, List<SourceConnector> connectors) {
        this.props = props;
        this.tenants = tenants;
        this.sources = sources;
        this.scheduler = scheduler;
        this.digest = digest;
        this.pilot = pilot;
        this.connectors = connectors;
    }

    /** Runs the job. Throws only when the job itself cannot run (for example the database is unreachable). */
    public void run(JobRunner.Job job) {
        log.info("Job {} starting", job.name());
        registerPilotWorkspace();
        if ("sync".equals(job.name())) {
            sync(job);
        } else {
            digest.runOnce();
        }
        log.info("Job {} finished", job.name());
    }

    /**
     * The Slack history sync and the digest need the workspace registry; the job starts no listener, so the pilot
     * workspace is registered here when a bot token is configured. A Slack outage is not a job failure.
     */
    private void registerPilotWorkspace() {
        if (props.slack() == null || props.slack().botToken() == null || props.slack().botToken().isBlank()) {
            return;
        }
        try {
            pilot.register();
        } catch (Exception e) {
            log.warn("Slack workspace could not be registered by the job: {}", e.getClass().getSimpleName());
        }
    }

    private void sync(JobRunner.Job job) {
        List<Tenant> active = tenants.active(); // throws when the database is unreachable: the job fails
        if (job.tenantId().isPresent()) {
            active = active.stream().filter(t -> t.id() == job.tenantId().getAsLong()).toList();
        }
        if (job.sourceId().isPresent()) {
            Optional<Source> source = sources.findById(job.sourceId().getAsLong());
            Optional<SourceConnector> connector = source.flatMap(s -> connectors.stream()
                    .filter(c -> c.kind().equals(s.kind()) && c.name().equals(s.name())).findFirst());
            if (source.isEmpty() || connector.isEmpty()
                    || active.stream().noneMatch(t -> t.id() == source.get().tenantId())) {
                // Unknown source, or one that belongs to another tenant than the one asked for: nothing is synced.
                log.warn("Sync job: source {} not found for the requested tenant, nothing to sync",
                        job.sourceId().getAsLong());
                return;
            }
            active = active.stream().filter(t -> t.id() == source.get().tenantId()).toList();
            SourceConnector only = connector.get();
            scheduler.syncAll(active, c -> c == only);
        } else {
            scheduler.syncAll(active, c -> true);
        }
        log.info("Sync job: {} tenant(s) processed", active.size());
    }
}
