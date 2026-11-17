package com.docs0.source;

import com.docs0.tenant.TenantService;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Runs a connector for a tenant and records the run in the sync log. */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private static final int MAX_ERROR_CHARS = 500;

    private final TenantService tenants;
    private final SourceRepository sources;
    private final SyncRunRepository runs;
    /** Across processes; null only in tests that never need it. */
    private final SyncLock lock;
    /** A source being synced. Keyed by tenant as well as source, so no two tenants can ever share a guard. */
    private record RunKey(long tenantId, long sourceId) {}

    /** Sources being synced right now. One app instance, so an in-process guard is enough. */
    private final Set<RunKey> running = ConcurrentHashMap.newKeySet();

    @Autowired
    public SyncService(TenantService tenants, SourceRepository sources, SyncRunRepository runs, SyncLock lock) {
        this.tenants = tenants;
        this.sources = sources;
        this.runs = runs;
        this.lock = lock;
    }

    /** Without the cross-process lock: the in-process guard only. */
    public SyncService(TenantService tenants, SourceRepository sources, SyncRunRepository runs) {
        this(tenants, sources, runs, null);
    }

    /** A run that has been recorded as started and holds the guard and the lock of its source. */
    private record Started(SourceConnector connector, long tenantId, Source source, long runId,
            SyncLock.Held held) {}

    /**
     * Syncs one source of the tenant and returns the finished run (OK or FAILED). Returns empty, without
     * doing anything, when a sync of the same source is already in progress or the tenant is suspended.
     * Never throws for a connector failure: that is recorded on the run.
     */
    public Optional<SyncRun> run(long tenantId, SourceConnector connector) {
        return begin(tenantId, connector).map(this::complete);
    }

    /**
     * Starts a sync on its own thread and returns at once. The run is already in the sync
     * log as RUNNING when this returns. False when a sync of the source is in progress or the tenant is suspended.
     */
    public boolean startInBackground(long tenantId, SourceConnector connector) {
        Optional<Started> started = begin(tenantId, connector);
        started.ifPresent(s -> Thread.ofVirtual().name("sync-now").start(() -> {
            try {
                complete(s);
            } catch (RuntimeException e) {
                log.warn("Sync of source {} could not be recorded: {}", s.source().id(), e.toString());
            }
        }));
        return started.isPresent();
    }

    /**
     * True while a sync of this tenant's source is in progress: in this process, or (a Cloud Run Job syncs in
     * another process) a run row says RUNNING and began less than {@link SyncRunRepository#STALE_AFTER} ago. A RUNNING
     * row older than that was left by a process that died; it shows as Interrupted and is kept.
     */
    public boolean isRunning(long tenantId, long sourceId) {
        return running.contains(new RunKey(tenantId, sourceId)) || runs.hasRunning(tenantId, sourceId);
    }

    private Optional<Started> begin(long tenantId, SourceConnector connector) {
        if (!tenants.isActive(tenantId)) {
            log.info("Sync of {} skipped: tenant {} is not active", connector.name(), tenantId);
            return Optional.empty();
        }
        Source source = sources.findOrCreate(tenantId, connector.kind(), connector.name(), connector.visibility());
        RunKey key = new RunKey(tenantId, source.id());
        if (!running.add(key)) {
            log.info("Sync of source {} ({}) already running, skipping", source.id(), source.name());
            return Optional.empty();
        }
        SyncLock.Held held = null;
        try {
            if (lock != null) {
                held = lock.tryLock(tenantId, source.id());
                if (held == null) {
                    running.remove(key);
                    log.info("Sync of source {} ({}) skipped: another process is syncing it", source.id(),
                            source.name());
                    return Optional.empty();
                }
            }
            return Optional.of(new Started(connector, tenantId, source, runs.start(tenantId, source.id()), held));
        } catch (RuntimeException e) {
            if (held != null) {
                held.close();
            }
            running.remove(key);
            throw e;
        }
    }

    private SyncRun complete(Started started) {
        long tenantId = started.tenantId();
        Source source = started.source();
        try {
            SyncRun finished;
            try {
                SyncCounts counts = started.connector().sync(tenantId, source.id());
                finished = runs.finish(tenantId, started.runId(), SyncRun.OK, counts, null);
            } catch (Exception e) {
                String error = describe(e);
                log.warn("Sync of source {} ({}) failed: {}", source.id(), source.name(), error);
                finished = runs.finish(tenantId, started.runId(), SyncRun.FAILED, SyncCounts.NONE, error);
            }
            log.info("Sync of source {} ({}) finished {}: {}", source.id(), source.name(), finished.status(),
                    finished.counts());
            return finished;
        } finally {
            running.remove(new RunKey(tenantId, source.id()));
            if (started.held() != null) {
                started.held().close();
            }
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
