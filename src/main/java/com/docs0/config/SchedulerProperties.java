package com.docs0.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DOCS0_INTERNAL_SCHEDULER. False in hosted mode and in job mode: nothing is scheduled inside the process, and
 * {@code SchedulingConfig} (which turns Spring scheduling on) is not created.
 */
@ConfigurationProperties(prefix = "docs0.scheduler")
public record SchedulerProperties(Boolean enabled) {

    public boolean isEnabled() {
        return enabled == null || enabled;
    }
}
