package com.docs0.config;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All docs0 settings, bound from DOCS0_* environment variables via application.yml. */
@ConfigurationProperties(prefix = "docs0")
public record Docs0Properties(Tenant tenant, Anthropic anthropic, Budget budget, Slack slack, Drive drive) {

    public record Tenant(String slug, String name) {}

    public record Anthropic(
            String apiKey,
            String model,
            BigDecimal inputPricePerMtok,
            BigDecimal outputPricePerMtok,
            int maxSteps) {

        public boolean configured() {
            return hasText(apiKey);
        }
    }

    public record Budget(BigDecimal monthlyUsd) {

        /** True when a positive monthly cap is set. */
        public boolean capped() {
            return monthlyUsd != null && monthlyUsd.signum() > 0;
        }
    }

    public record Slack(String botToken, String appToken) {

        public boolean configured() {
            return hasText(botToken) && hasText(appToken);
        }
    }

    public record Drive(String credentialsFile, String folderIds, int syncIntervalMinutes, boolean syncOnStartup) {

        public boolean configured() {
            return hasText(credentialsFile);
        }

        /** Folder ids to restrict the sync to; empty means everything the service account can see. */
        public List<String> folderIdList() {
            if (!hasText(folderIds)) {
                return List.of();
            }
            return Arrays.stream(folderIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
