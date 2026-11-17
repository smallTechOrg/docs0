package com.docs0.source;

import com.docs0.config.Docs0Properties;
import com.docs0.tenant.Tenant;
import com.docs0.tenant.TenantService;
import java.util.List;
import java.util.function.Predicate;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * When sources are synced: once after startup, then on a fixed interval. Connectors that
 * have nothing to sync are left alone, so until Drive is connected this does nothing.
 */
@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final SyncService syncService;
    private final List<SourceConnector> connectors;
    private final Docs0Properties props;
    private final TenantService tenants;

    public SyncScheduler(SyncService syncService, List<SourceConnector> connectors, Docs0Properties props,
                         TenantService tenants) {
        this.syncService = syncService;
        this.connectors = connectors;
        this.props = props;
        this.tenants = tenants;
    }

    /**
     * Startup sync, on its own thread so a long first sync never delays the app coming up. Called by
     * {@code SchedulingConfig}, which exists only while the internal scheduler is on.
     */
    public void onReady() {
        if (!props.drive().syncOnStartup()) {
            return;
        }
        Thread.ofVirtual().name("startup-sync").start(this::syncAll);
    }

    @Scheduled(
            initialDelayString = "${docs0.drive.sync-interval-minutes:15}",
            fixedDelayString = "${docs0.drive.sync-interval-minutes:15}",
            timeUnit = TimeUnit.MINUTES)
    public void syncAll() {
        List<Tenant> active;
        try {
            active = tenants.active();
        } catch (RuntimeException e) {
            log.warn("Could not list tenants to sync: {}", e.getClass().getName());
            return;
        }
        syncAll(active, connector -> true);
    }

    /**
     * Syncs these tenants side by side, each with every configured connector accepted by {@code only}. Also the
     * entry point of the sync job (which lists the tenants itself, so an unreachable database fails the job).
     */
    public void syncAll(List<Tenant> active, Predicate<SourceConnector> only) {
        // Tenants run side by side, so a slow or stuck one never holds up the others; this returns when all are done.
        List<Thread> running = new java.util.ArrayList<>();
        for (Tenant tenant : active) {
            running.add(Thread.ofVirtual().name("sync-tenant-" + tenant.id()).start(() -> syncTenant(tenant.id(), only)));
        }
        for (Thread thread : running) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Every configured connector of one tenant. A failing source never stops the next source or the next tenant:
     * each is tried on its own.
     */
    void syncTenant(long tenantId) {
        syncTenant(tenantId, connector -> true);
    }

    public void syncTenant(long tenantId, Predicate<SourceConnector> only) {
        for (SourceConnector connector : connectors) {
            try {
                if (!only.test(connector) || !connector.configured(tenantId)) {
                    continue;
                }
                syncService.run(tenantId, connector);
            } catch (RuntimeException e) {
                // SyncService records connector failures itself; this is the database being unreachable.
                log.warn("Could not sync {} for tenant {}: {}", connector.name(), tenantId, e.toString());
            }
        }
    }
}
