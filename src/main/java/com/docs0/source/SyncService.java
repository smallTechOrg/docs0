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

    /**
     * Syncs one source and returns the finished run (OK or FAILED). Returns empty, without
     * doing anything, when a sync of the same source is already in progress. Never throws
     * for a connector failure: that is recorded on the run.
     */
    public Optional<SyncRun> run(SourceConnector connector) {
        Tenant tenant = tenants.current();
        Source source = sources.findOrCreate(tenant.id(), connector.kind(), connector.name(), connector.visibility());
        if (!running.add(source.id())) {
            log.info("Sync of source {} ({}) already running, skipping", source.id(), source.name());
            return Optional.empty();
        }
        try {
            long runId = runs.start(tenant.id(), source.id());
            SyncRun finished;
            try {
                SyncCounts counts = connector.sync(tenant.id(), source.id());
                finished = runs.finish(tenant.id(), runId, SyncRun.OK, counts, null);
            } catch (Exception e) {
                String error = describe(e);
                log.warn("Sync of source {} ({}) failed: {}", source.id(), source.name(), error);
                finished = runs.finish(tenant.id(), runId, SyncRun.FAILED, SyncCounts.NONE, error);
            }
            log.info("Sync of source {} ({}) finished {}: {}", source.id(), source.name(), finished.status(),
                    finished.counts());
            return Optional.of(finished);
        } finally {
            running.remove(source.id());
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        String text = e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        text = text.replace("\u0000", "");
        return text.length() > MAX_ERROR_CHARS ? text.substring(0, MAX_ERROR_CHARS) : text;
    }
}
