package com.docs0.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All docs0 settings, bound from DOCS0_* environment variables via application.yml. */
@ConfigurationProperties(prefix = "docs0")
public record Docs0Properties(
        Tenant tenant,
        Anthropic anthropic,
        Budget budget,
        Slack slack,
        Drive drive,
        Google google,
        /** Base64 of 32 random bytes; encrypts stored Google grants. */
        String encryptionKey,
        /** Where a browser reaches this app, e.g. http://localhost:8080. */
        String publicUrl,
        /** Password for /admin pages (user "admin"). Empty: /admin is served to the local machine only. */
        String adminPassword) {

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

        /** Cost in USD of this many input and output tokens at the configured per-million prices. */
        public BigDecimal cost(long inputTokens, long outputTokens) {
            BigDecimal in = inputPricePerMtok == null ? BigDecimal.ZERO : inputPricePerMtok;
            BigDecimal out = outputPricePerMtok == null ? BigDecimal.ZERO : outputPricePerMtok;
            return BigDecimal.valueOf(inputTokens).multiply(in)
                    .add(BigDecimal.valueOf(outputTokens).multiply(out))
                    .divide(BigDecimal.valueOf(1_000_000), 6, java.math.RoundingMode.HALF_UP);
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

    public record Drive(int syncIntervalMinutes, boolean syncOnStartup) {}

    /** The OAuth client an admin signs in through to connect Google Drive. */
    public record Google(String oauthClientId, String oauthClientSecret) {

        public boolean configured() {
            return hasText(oauthClientId) && hasText(oauthClientSecret);
        }
    }

    /** The redirect URI to register on the Google OAuth client. */
    public String googleRedirectUri() {
        String base = publicUrl == null ? "" : publicUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/admin/sources/google/callback";
    }

    public boolean adminPasswordSet() {
        return hasText(adminPassword);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
