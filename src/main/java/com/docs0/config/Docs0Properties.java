package com.docs0.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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

    /**
     * @param botToken      pilot only: the Socket Mode app's bot token
     * @param appToken      pilot only: the Socket Mode app-level token
     * @param clientId      distributed app: OAuth client id (Add to Slack and Sign in with Slack)
     * @param clientSecret  distributed app: OAuth client secret
     * @param signingSecret verifies requests Slack sends to /slack/events and /slack/interactions (distributed app,
     *                      and the single-company pilot when {@code transport} is http)
     * @param transport     how Slack reaches the single-company pilot: socket (default) or http. Anything else
     *                      (job mode sets "none") starts no Slack listener at all
     * @param holdOpenSeconds http transport: how long a Slack request is held open while its work finishes
     */
    public record Slack(String botToken, String appToken, String clientId, String clientSecret,
            String signingSecret, String transport, int holdOpenSeconds) {

        public static final int DEFAULT_HOLD_OPEN_SECONDS = 270;

        /** The settings as bound from the environment. */
        @ConstructorBinding
        public Slack {
        }

        /** Socket Mode (the default transport), without the HTTP settings. */
        public Slack(String botToken, String appToken, String clientId, String clientSecret, String signingSecret) {
            this(botToken, appToken, clientId, clientSecret, signingSecret, "socket", DEFAULT_HOLD_OPEN_SECONDS);
        }

        /** The pilot's two tokens only: no distributed app. */
        public Slack(String botToken, String appToken) {
            this(botToken, appToken, "", "", "");
        }

        public boolean configured() {
            return hasText(botToken) && hasText(appToken);
        }

        /** True when all three distributed-app settings are present. */
        public boolean multiTenant() {
            return hasText(clientId) && hasText(clientSecret) && hasText(signingSecret);
        }

        /** Slack sends events over HTTP (DOCS0_SLACK_TRANSPORT=http): no Socket Mode, work is held inside the request. */
        public boolean httpTransport() {
            return transport != null && transport.strip().equalsIgnoreCase("http");
        }

        /** Socket Mode is the transport: unset, blank or "socket". */
        public boolean socketTransport() {
            return transport == null || transport.isBlank() || transport.strip().equalsIgnoreCase("socket");
        }

        /** HTTP transport with the pilot's bot token and the signing secret both set. */
        public boolean httpPilotConfigured() {
            return httpTransport() && hasText(botToken) && hasText(signingSecret);
        }

        public java.time.Duration holdOpen() {
            return java.time.Duration.ofSeconds(holdOpenSeconds > 0 ? holdOpenSeconds : DEFAULT_HOLD_OPEN_SECONDS);
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

    /**
     * Multi-tenant mode: Slack client id, client secret and signing secret are all set. Companies then install docs0
     * themselves and their admins sign in with Slack. Off, docs0 serves the one configured tenant as before.
     */
    public boolean multiTenant() {
        return slack != null && slack.multiTenant();
    }

    /** {@link #publicUrl()} without trailing slashes. */
    public String publicBaseUrl() {
        String base = publicUrl == null ? "" : publicUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /** True when the public URL is https. */
    public boolean publicUrlIsHttps() {
        return publicBaseUrl().regionMatches(true, 0, "https://", 0, 8);
    }

    public boolean adminPasswordSet() {
        return hasText(adminPassword);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
