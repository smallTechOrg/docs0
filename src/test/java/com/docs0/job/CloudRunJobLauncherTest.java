package com.docs0.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.docs0.config.HostedProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The Cloud Run Jobs call against a local fake of the metadata server and the Cloud Run Admin API. */
class CloudRunJobLauncherTest {

    record Call(String method, String path, String authorization, String metadataFlavor, String contentType,
            String body) {}

    private HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger runStatus = new AtomicInteger(200);
    private final AtomicInteger runFailuresLeft = new AtomicInteger(0);
    private final AtomicInteger metadataStatus = new AtomicInteger(200);
    private volatile long expiresIn = 3600;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-17T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            record(exchange);
            tokenCalls.incrementAndGet();
            byte[] body = ("{\"access_token\":\"ya29.secret-token-" + tokenCalls.get() + "\",\"expires_in\":" + expiresIn
                    + ",\"token_type\":\"Bearer\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(metadataStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v2", exchange -> {
            record(exchange);
            int status = runFailuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? 503 : runStatus.get();
            byte[] body = "{\"name\":\"operations/x\",\"error\":\"detail-that-must-not-reach-the-page\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger("com.docs0")).addAppender(logs);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        ((Logger) LoggerFactory.getLogger("com.docs0")).detachAppender(logs);
    }

    private void record(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Metadata-Flavor"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private CloudRunJobLauncher launcher() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new CloudRunJobLauncher(new HostedProperties("docs0-sync", "my-project", "us-central1"),
                base + "/token", base, clock, 0);
    }

    private List<Call> runCalls() {
        return calls.stream().filter(c -> c.path().startsWith("/v2")).toList();
    }

    @Test
    void asksTheMetadataServerForATokenAndRunsTheJobWithOverridesNarrowedToTheSource() throws Exception {
        launcher().runSync(7, 42);

        Call token = calls.get(0);
        assertThat(token.method()).isEqualTo("GET");
        assertThat(token.path()).isEqualTo("/token");
        assertThat(token.metadataFlavor()).isEqualTo("Google");
        Call run = runCalls().get(0);
        assertThat(run.method()).isEqualTo("POST");
        assertThat(run.path()).isEqualTo("/v2/projects/my-project/locations/us-central1/jobs/docs0-sync:run");
        assertThat(run.authorization()).isEqualTo("Bearer ya29.secret-token-1");
        assertThat(run.contentType()).startsWith("application/json");
        assertThat(com.google.gson.JsonParser.parseString(run.body())).isEqualTo(com.google.gson.JsonParser.parseString(
                "{\"overrides\":{\"containerOverrides\":[{\"args\":[\"--docs0.job=sync\",\"--docs0.job.tenant=7\","
                        + "\"--docs0.job.source=42\"]}]}}"));
    }

    @Test
    void theTokenIsCachedUntilJustBeforeItExpires() throws Exception {
        CloudRunJobLauncher launcher = launcher();
        expiresIn = 3600;

        launcher.runSync(1, 1);
        now.set(now.get().plusSeconds(3000));
        launcher.runSync(1, 2);
        assertThat(tokenCalls).as("still valid").hasValue(1);

        now.set(now.get().plusSeconds(560)); // 3560 s in: within the 60 s safety margin of the 3600 s expiry
        launcher.runSync(1, 3);

        assertThat(tokenCalls).hasValue(2);
        assertThat(runCalls().get(2).authorization()).isEqualTo("Bearer ya29.secret-token-2");
    }

    @Test
    void aTransientErrorIsRetriedOnceAndThenSucceeds() throws Exception {
        runFailuresLeft.set(1);

        launcher().runSync(1, 1);

        assertThat(runCalls()).hasSize(2);
    }

    @Test
    void tooManyRequestsIsRetriedOnceAndThenFailsPlainly() {
        runStatus.set(429);

        assertThatThrownBy(() -> launcher().runSync(1, 1)).isInstanceOf(JobLauncher.LaunchFailedException.class)
                .hasMessage("Cloud Run answered 429");
        assertThat(runCalls()).hasSize(2);
    }

    @Test
    void aServerErrorIsRetriedOnceAndThenFailsPlainly() {
        runStatus.set(500);

        assertThatThrownBy(() -> launcher().runSync(1, 1)).hasMessage("Cloud Run answered 500");
        assertThat(runCalls()).hasSize(2);
    }

    @Test
    void aClientErrorIsNotRetried() {
        runStatus.set(403);

        assertThatThrownBy(() -> launcher().runSync(1, 1)).hasMessage("Cloud Run answered 403");
        assertThat(runCalls()).hasSize(1);
    }

    @Test
    void anUnauthorizedAnswerForgetsTheCachedToken() throws Exception {
        CloudRunJobLauncher launcher = launcher();
        launcher.runSync(1, 1);
        runStatus.set(401);
        assertThatThrownBy(() -> launcher.runSync(1, 1)).hasMessage("Cloud Run answered 401");
        runStatus.set(200);

        launcher.runSync(1, 1);

        assertThat(tokenCalls.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void noTokenMeansAPlainFailureAndNoCallToCloudRun() {
        metadataStatus.set(404);

        assertThatThrownBy(() -> launcher().runSync(1, 1)).isInstanceOf(JobLauncher.LaunchFailedException.class)
                .hasMessageContaining("no access token");
        assertThat(runCalls()).isEmpty();
    }

    @Test
    void anUnreachableCloudRunIsAPlainFailure() {
        String base = "http://127.0.0.1:1";
        CloudRunJobLauncher launcher = new CloudRunJobLauncher(new HostedProperties("docs0-sync", "p", "r"),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/token", base, clock, 0);

        assertThatThrownBy(() -> launcher.runSync(1, 1)).hasMessage("could not reach Cloud Run");
    }

    @Test
    void incompleteOrUnsafeSettingsFailWithoutAnyCall() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        for (HostedProperties bad : List.of(new HostedProperties("", "p", "r"), new HostedProperties("j", "", "r"),
                new HostedProperties("j", "p", ""), new HostedProperties("j/../x", "p", "r"),
                new HostedProperties("j", "p?x=1", "r"))) {
            assertThatThrownBy(() -> new CloudRunJobLauncher(bad, base + "/token", base, clock, 0).runSync(1, 1))
                    .hasMessage("the job settings are incomplete");
        }
        assertThat(calls).isEmpty();
    }

    @Test
    void neitherTheTokenNorTheResponseBodyIsEverLoggedOrShown() {
        runStatus.set(500);
        List<String> shown = new ArrayList<>();

        try {
            launcher().runSync(1, 1);
        } catch (JobLauncher.LaunchFailedException e) {
            shown.add(e.getMessage());
        }
        launcherSucceeds();

        String everything = String.join("\n", shown) + logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(everything).doesNotContain("ya29").doesNotContain("secret-token").doesNotContain("detail-that-must");
    }

    private void launcherSucceeds() {
        runStatus.set(200);
        try {
            launcher().runSync(1, 1);
        } catch (JobLauncher.LaunchFailedException e) {
            throw new AssertionError(e);
        }
    }
}
