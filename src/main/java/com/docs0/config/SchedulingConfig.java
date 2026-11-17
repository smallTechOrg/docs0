package com.docs0.config;

import com.docs0.source.SyncScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The one switch for everything that runs on a timer inside the process: with DOCS0_INTERNAL_SCHEDULER=false this
 * class does not exist, so no {@code @Scheduled} method (source sync, weekly digest) ever runs and the startup sync
 * does not start. Add any new scheduled job under this switch by simply using {@code @Scheduled}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "docs0.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    private final SyncScheduler syncScheduler;

    public SchedulingConfig(SyncScheduler syncScheduler) {
        this.syncScheduler = syncScheduler;
    }

    /** Syncs once after startup, when DOCS0_DRIVE_SYNC_ON_STARTUP is true. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        syncScheduler.onReady();
    }
}
