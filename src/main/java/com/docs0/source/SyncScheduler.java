package com.docs0.source;

import com.docs0.config.Docs0Properties;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

    public SyncScheduler(SyncService syncService, List<SourceConnector> connectors, Docs0Properties props) {
        this.syncService = syncService;
        this.connectors = connectors;
        this.props = props;
    }

    /** Startup sync, on its own thread so a long first sync never delays the app coming up. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!props.drive().syncOnStartup() || connectors.stream().noneMatch(SourceConnector::configured)) {
            return;
        }
        Thread.ofVirtual().name("startup-sync").start(this::syncAll);
    }

    @Scheduled(
            initialDelayString = "${docs0.drive.sync-interval-minutes:15}",
            fixedDelayString = "${docs0.drive.sync-interval-minutes:15}",
            timeUnit = TimeUnit.MINUTES)
    public void syncAll() {
        for (SourceConnector connector : connectors) {
            if (!connector.configured()) {
                continue;
            }
            try {
                syncService.run(connector);
            } catch (RuntimeException e) {
                // SyncService records connector failures itself; this is the database being unreachable.
                log.warn("Could not sync {}: {}", connector.name(), e.toString());
            }
        }
    }
}
