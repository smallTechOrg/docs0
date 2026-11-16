package com.docs0.source;

import com.docs0.tenant.Tenant;
import com.docs0.tenant.TenantService;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Runs a connector for the current tenant and records the run in the sync log. */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private static final int MAX_ERROR_CHARS = 500;

    private final TenantService tenants;
    private final SourceRepository sources;
    private final SyncRunRepository runs;
    /** Ids of sources being synced right now. One app instance, so an in-process guard is enough. */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public SyncService(TenantService tenants, SourceRepository sources, SyncRunRepository runs) {
        this.tenants = tenants;
        this.sources = sources;
        this.runs = runs;
    }

    /** A run that has been recorded as started and holds the guard of its source. */
    private record Started(SourceConnector connector, Tenant tenant, Source source, long runId) {}

    /**
     * Syncs one source and returns the finished run (OK or FAILED). Returns empty, without
     * doing anything, when a sync of the same source is already in progress. Never throws
     * for a connector failure: that is recorded on the run.
     */
    public Optional<SyncRun> run(SourceConnector connector) {
        return begin(connector).map(this::complete);
    }

    /**
     * Starts a sync on its own thread and returns at once. The run is already in the sync
     * log as RUNNING when this returns. False when a sync of the source is in progress.
     */
    public boolean startInBackground(SourceConnector connector) {
        Optional<Started> started = begin(connector);
        started.ifPresent(s -> Thread.ofVirtual().name("sync-now").start(() -> {
            try {
                complete(s);
            } catch (RuntimeException e) {
                log.warn("Sync of source {} could not be recorded: {}", s.source().id(), e.toString());
            }
        }));
        return started.isPresent();
    }

    /** True while a sync of this source is in progress in this app instance. */
    public boolean isRunning(long sourceId) {
        return running.contains(sourceId);
    }

    private Optional<Started> begin(SourceConnector connector) {
        Tenant tenant = tenants.current();
        Source source = sources.findOrCreate(tenant.id(), connector.kind(), connector.name(), connector.visibility());
        if (!running.add(source.id())) {
            log.info("Sync of source {} ({}) already running, skipping", source.id(), source.name());
            return Optional.empty();
        }
        try {
            return Optional.of(new Started(connector, tenant, source, runs.start(tenant.id(), source.id())));
        } catch (RuntimeException e) {
            running.remove(source.id());
            throw e;
        }
    }

    private SyncRun complete(Started started) {
        Tenant tenant = started.tenant();
        Source source = started.source();
        try {
            SyncRun finished;
            try {
                SyncCounts counts = started.connector().sync(tenant.id(), source.id());
                finished = runs.finish(tenant.id(), started.runId(), SyncRun.OK, counts, null);
            } catch (Exception e) {
                String error = describe(e);
                log.warn("Sync of source {} ({}) failed: {}", source.id(), source.name(), error);
                finished = runs.finish(tenant.id(), started.runId(), SyncRun.FAILED, SyncCounts.NONE, error);
            }
            log.info("Sync of source {} ({}) finished {}: {}", source.id(), source.name(), finished.status(),
                    finished.counts());
            return finished;
        } finally {
            running.remove(source.id());
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        if (e instanceof SourceAccessException && message != null) {
            return message;
        }
        String text = e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        text = text.replace("\u0000", "");
        return text.length() > MAX_ERROR_CHARS ? text.substring(0, MAX_ERROR_CHARS) : text;
    }
}
