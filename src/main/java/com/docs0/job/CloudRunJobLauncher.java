package com.docs0.job;

import com.docs0.config.HostedProperties;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Starts the Cloud Run Job (DOCS0_SYNC_JOB_NAME) with the Cloud Run Admin API: an access token from the metadata
 * server, then {@code POST .../jobs/{job}:run} with container args that narrow the run to one tenant and source.
 * The token is cached until shortly before it expires and is never logged.
 */
@Component
public class CloudRunJobLauncher implements JobLauncher {

    static final String DEFAULT_METADATA_TOKEN_URL =
            "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token";
    static final String DEFAULT_RUN_API = "https://run.googleapis.com";

    private static final Logger log = LoggerFactory.getLogger(CloudRunJobLauncher.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final Duration TOKEN_MARGIN = Duration.ofSeconds(60);
    private static final Gson GSON = new Gson();

    private final HostedProperties hosted;
    private final String metadataTokenUrl;
    private final String runApiBase;
    private final HttpClient http;
    private final Clock clock;
    private final long retryDelayMillis;

    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    @Autowired
    public CloudRunJobLauncher(HostedProperties hosted,
            @Value("${docs0.hosted.metadata-token-url:" + DEFAULT_METADATA_TOKEN_URL + "}") String metadataTokenUrl,
            @Value("${docs0.hosted.run-api-url:" + DEFAULT_RUN_API + "}") String runApiBase) {
        this(hosted, metadataTokenUrl, runApiBase, Clock.systemUTC(), 1000);
    }

    /** For tests: the two endpoints, a clock and the pause before the one retry. */
    public CloudRunJobLauncher(HostedProperties hosted, String metadataTokenUrl, String runApiBase, Clock clock,
            long retryDelayMillis) {
        this.hosted = hosted;
        this.metadataTokenUrl = metadataTokenUrl;
        this.runApiBase = runApiBase.endsWith("/") ? runApiBase.substring(0, runApiBase.length() - 1) : runApiBase;
        this.clock = clock;
        this.retryDelayMillis = retryDelayMillis;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public void runSync(long tenantId, long sourceId) throws LaunchFailedException {
        if (hosted == null || !hosted.syncViaJob() || !safe(hosted.gcpProject()) || !safe(hosted.gcpRegion())
                || !safe(hosted.syncJobName())) {
            throw new LaunchFailedException("the job settings are incomplete");
        }
        String url = runApiBase + "/v2/projects/" + hosted.gcpProject() + "/locations/" + hosted.gcpRegion()
                + "/jobs/" + hosted.syncJobName() + ":run";
        String body = GSON.toJson(Map.of("overrides", Map.of("containerOverrides", List.of(Map.of("args", List.of(
                "--docs0.job=sync", "--docs0.job.tenant=" + tenantId, "--docs0.job.source=" + sourceId))))));
        LaunchFailedException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                int status = post(url, body);
                if (status / 100 == 2) {
                    log.info("Sync job requested: tenant={} source={}", tenantId, sourceId);
                    return;
                }
                if (status == 401) {
                    forgetToken();
                }
                last = new LaunchFailedException("Cloud Run answered " + status);
                if (status != 429 && status / 100 != 5) {
                    break;
                }
            } catch (LaunchFailedException e) {
                last = e;
            }
            if (attempt == 1) {
                pause();
            }
        }
        log.warn("Sync job could not be requested: {}", last.getMessage());
        throw last;
    }

    private int post(String url, String body) throws LaunchFailedException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            throw new LaunchFailedException("could not reach Cloud Run");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LaunchFailedException("interrupted");
        }
    }

    private synchronized String accessToken() throws LaunchFailedException {
        if (token != null && clock.instant().isBefore(tokenExpiresAt.minus(TOKEN_MARGIN))) {
            return token;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(metadataTokenUrl)).timeout(Duration.ofSeconds(5))
                    .header("Metadata-Flavor", "Google").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new LaunchFailedException("no access token (metadata server answered " + response.statusCode() + ")");
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String access = json.get("access_token").getAsString();
            long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 300;
            token = access;
            tokenExpiresAt = clock.instant().plusSeconds(expiresIn);
            return access;
        } catch (IOException e) {
            throw new LaunchFailedException("no access token (metadata server not reachable)");
        } catch (RuntimeException e) {
            throw new LaunchFailedException("no access token (unreadable answer)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LaunchFailedException("interrupted");
        }
    }

    private synchronized void forgetToken() {
        token = null;
        tokenExpiresAt = Instant.EPOCH;
    }

    private void pause() {
        if (retryDelayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(retryDelayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean safe(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }
}
