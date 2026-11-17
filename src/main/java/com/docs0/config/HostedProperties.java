package com.docs0.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hosted (Cloud Run) settings: the Cloud Run Job that "Sync now" starts. Unset, "Sync now" syncs inside this process.
 *
 * @param syncJobName DOCS0_SYNC_JOB_NAME, for example docs0-sync
 * @param gcpProject  DOCS0_GCP_PROJECT
 * @param gcpRegion   DOCS0_GCP_REGION
 */
@ConfigurationProperties(prefix = "docs0.hosted")
public record HostedProperties(String syncJobName, String gcpProject, String gcpRegion) {

    /** "Sync now" starts the Cloud Run Job. */
    public boolean syncViaJob() {
        return syncJobName != null && !syncJobName.isBlank();
    }
}
