package com.docs0.source.grain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.source.SourceAccessException;
import com.docs0.source.grain.GrainClient.Recording;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real Grain client against a local stand-in for the REST API. Proves what is sent (method,
 * path, headers, bodies) and how replies, paging, rate limits and refusals are handled; it cannot
 * prove that Grain's real API matches the documented shapes used here.
 */
class HttpGrainClientTest {

    private static final String TOKEN = "grain_pat_SECRETTOKEN123";
    private static final Instant SINCE = Instant.parse("2026-01-01T00:00:00Z");
    private static final Gson GSON = new Gson();

    record Req(String method, String path, String authorization, String version, JsonObject body) {}

    record Resp(int status, String body, Map<String, String> headers) {
        static Resp ok(String body) {
            return new Resp(200, body, Map.of());
        }
    }

    HttpServer server;
    final List<Req> requests = new ArrayList<>();
    final Deque<Resp> script = new ArrayDeque<>();
    final List<Long> sleeps = new ArrayList<>();
    HttpGrainClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Req(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Public-Api-Version"),
                    body.isBlank() ? null : GSON.fromJson(body, JsonObject.class)));
            Resp resp = script.poll();
            if (resp == null) {
                resp = new Resp(500, "no scripted reply", Map.of());
            }
            byte[] bytes = resp.body().getBytes(StandardCharsets.UTF_8);
            resp.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(resp.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new HttpGrainClient("http://127.0.0.1:" + server.getAddress().getPort(), sleeps::add);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static final String LIST_PAGE_1 = """
            {"cursor": "next-1", "recordings": [{
               "id": "r1", "title": "Launch sync", "url": "https://grain.com/share/recording/r1/abc",
               "start_datetime": "2026-03-01T09:00:00Z", "end_datetime": "2026-03-01T09:45:00.000+00:00",
               "duration_ms": 2700000,
               "recorders": [{"id": "u1", "name": "Asha Rao", "email": "asha@example.com", "participant_id": "p1"}],
               "participants": [{"id": "p1", "name": "Asha Rao", "scope": "internal", "email": "asha@example.com"},
                                {"id": "p2", "name": "Priya Nair", "scope": "external", "email": null}]},
              {"id": "old", "title": "Too old", "start_datetime": "2025-06-01T09:00:00Z",
               "end_datetime": "2025-06-01T09:30:00Z"}]}""";
    private static final String LIST_PAGE_2 = """
            {"cursor": null, "recordings": [{"id": "r2", "title": null, "start_datetime": "2026-03-05T10:00:00Z"}]}""";

    @Test
    void sendsABearerTokenAndTheVersionHeaderAndReadsTheAccount() throws IOException {
        script.add(Resp.ok("{\"type\": \"workspace_access_token\", \"scope\": \"workspace\", \"workspace_id\": \"w1\", "
                + "\"name\": \"Acme\", \"expires_at\": null}"));

        GrainClient.Account account = client.account(TOKEN);

        assertThat(account).isEqualTo(new GrainClient.Account("Acme", "workspace"));
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).method()).isEqualTo("GET");
        assertThat(requests.get(0).path()).isEqualTo("/_/public-api/v2/token-info");
        assertThat(requests.get(0).authorization()).isEqualTo("Bearer " + TOKEN);
        assertThat(requests.get(0).version()).isEqualTo("2026-10-01");
    }

    @Test
    void listsRecordingsPageByPageWithTheDateFilterAndMapsTheFields() throws IOException {
        script.add(Resp.ok(LIST_PAGE_1));
        script.add(Resp.ok(LIST_PAGE_2));

        List<Recording> recordings = client.recordings(TOKEN, SINCE);

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).method()).isEqualTo("POST");
        assertThat(requests.get(0).path()).isEqualTo("/_/public-api/v2/recordings");
        JsonObject first = requests.get(0).body();
        assertThat(first.has("cursor")).isFalse();
        assertThat(first.getAsJsonObject("filter").get("after_datetime").getAsString()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(first.getAsJsonObject("include").get("participants").getAsBoolean()).isTrue();
        assertThat(requests.get(1).body().get("cursor").getAsString()).isEqualTo("next-1");

        // "old" is before the window and is dropped even though the stand-in returned it.
        assertThat(recordings).extracting(Recording::id).containsExactly("r1", "r2");
        Recording r1 = recordings.get(0);
        assertThat(r1.title()).isEqualTo("Launch sync");
        assertThat(r1.url()).isEqualTo("https://grain.com/share/recording/r1/abc");
        assertThat(r1.start()).isEqualTo(Instant.parse("2026-03-01T09:00:00Z"));
        assertThat(r1.end()).isEqualTo(Instant.parse("2026-03-01T09:45:00Z"));
        assertThat(r1.durationMs()).isEqualTo(2_700_000L);
        assertThat(r1.recorders()).containsExactly(new GrainClient.Person("Asha Rao", "asha@example.com"));
        assertThat(r1.participants()).containsExactly(new GrainClient.Person("Asha Rao", "asha@example.com"),
                new GrainClient.Person("Priya Nair", null));
        assertThat(recordings.get(1).title()).isNull();
        assertThat(recordings.get(1).end()).isNull();
    }

    @Test
    void countingStopsAtTheCap() throws IOException {
        script.add(Resp.ok(LIST_PAGE_1));

        assertThat(client.countRecordings(TOKEN, SINCE, 1)).isEqualTo(1);
        assertThat(requests).hasSize(1);

        script.add(Resp.ok(LIST_PAGE_1));
        script.add(Resp.ok(LIST_PAGE_2));
        assertThat(client.countRecordings(TOKEN, SINCE, 100)).isEqualTo(2);
    }

    @Test
    void detailsAskForNotesAndReadTheTranscript() throws IOException {
        script.add(Resp.ok("""
                {"id": "r1", "title": "Launch sync", "url": "https://grain.com/share/recording/r1/abc",
                 "start_datetime": "2026-03-01T09:00:00Z", "end_datetime": "2026-03-01T09:45:00Z",
                 "recorders": [{"name": "Asha Rao", "email": "asha@example.com"}],
                 "participants": [{"name": "Priya Nair", "email": "priya@example.com"}],
                 "ai_summary": {"text": "We agreed to ship on Friday."},
                 "ai_action_items": [{"status": "pending", "timestamp": 5000, "text": "Send the plan",
                                      "assignee": {"id": "a", "name": "Priya Nair", "user_id": null}},
                                     {"status": "completed", "text": "Book room", "assignee": null}],
                 "highlights": [{"id": "h1", "text": "Ship on Friday", "timestamp": 65000, "duration": 4000},
                                {"id": "h2", "text": null, "transcript": "Budget is fixed", "timestamp": 90000}]}"""));
        script.add(Resp.ok("""
                [{"participant_id": "p1", "speaker": "Asha Rao", "start": 8000, "end": 9000, "text": "Hello there."},
                 {"participant_id": null, "speaker": "Priya Nair", "start": 9500, "end": 11000, "text": "General."}]"""));

        Recording r = client.details(TOKEN, "r1");

        assertThat(requests.get(0).method()).isEqualTo("POST");
        assertThat(requests.get(0).path()).isEqualTo("/_/public-api/v2/recordings/r1");
        JsonObject include = requests.get(0).body().getAsJsonObject("include");
        assertThat(include.keySet()).containsExactlyInAnyOrder("participants", "highlights", "ai_summary", "ai_action_items");
        assertThat(requests.get(1).method()).isEqualTo("GET");
        assertThat(requests.get(1).path()).isEqualTo("/_/public-api/v2/recordings/r1/transcript");
        assertThat(requests.get(1).authorization()).isEqualTo("Bearer " + TOKEN);

        assertThat(r.summary()).isEqualTo("We agreed to ship on Friday.");
        assertThat(r.actionItems()).containsExactly("[pending] Send the plan (Priya Nair)", "[completed] Book room");
        assertThat(r.highlights()).containsExactly("[00:01:05] Ship on Friday", "[00:01:30] Budget is fixed");
        assertThat(r.transcript()).containsExactly(new GrainClient.Segment("Asha Rao", 8000, "Hello there."),
                new GrainClient.Segment("Priya Nair", 9500, "General."));
        assertThat(r.participants()).containsExactly(new GrainClient.Person("Priya Nair", "priya@example.com"));
    }

    @Test
    void aTranscriptThatIsNotThereYetGivesAnEmptyListNotAnError() throws IOException {
        script.add(Resp.ok("{\"id\": \"r1\", \"title\": \"New\", \"start_datetime\": \"2026-03-01T09:00:00Z\"}"));
        script.add(new Resp(404, "{\"error\": \"not found\"}", Map.of()));

        Recording r = client.details(TOKEN, "r1");

        assertThat(r.transcript()).isEmpty();
        assertThat(r.summary()).isNull();
        assertThat(r.actionItems()).isEmpty();
    }

    @Test
    void aRejectedTokenIsReportedWithTheReconnectText() {
        script.add(new Resp(401, "{\"error\": \"unauthorized\"}", Map.of()));

        assertThatThrownBy(() -> client.recordings(TOKEN, SINCE)).isInstanceOf(SourceAccessException.class)
                .hasMessage("Grain rejected the access token. Reconnect Grain on the Sources page.");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void rateLimitsAreWaitedOutAsRetryAfterSaysThenTheCallSucceeds() throws IOException {
        script.add(new Resp(429, "{}", Map.of("Retry-After", "3")));
        script.add(new Resp(429, "{}", Map.of("Retry-After", "7")));
        script.add(Resp.ok(LIST_PAGE_2));

        assertThat(client.recordings(TOKEN, SINCE)).hasSize(1);

        assertThat(sleeps).containsExactly(3L, 7L);
        assertThat(requests).hasSize(3);
    }

    @Test
    void keepsRateLimitedAfterTheBoundedRetriesAndSaysSo() {
        for (int i = 0; i <= HttpGrainClient.MAX_RETRIES; i++) {
            script.add(new Resp(429, "{}", Map.of()));
        }

        assertThatThrownBy(() -> client.recordings(TOKEN, SINCE)).isInstanceOf(IOException.class)
                .hasMessageContaining("Grain is limiting how fast");
        assertThat(sleeps).hasSize(HttpGrainClient.MAX_RETRIES).allMatch(s -> s <= HttpGrainClient.MAX_WAIT_SECONDS);
    }

    @Test
    void otherFailuresNeverMentionTheTokenOrTheBody() {
        script.add(new Resp(500, "boom " + TOKEN, Map.of()));
        script.add(Resp.ok("not json " + TOKEN));

        assertThatThrownBy(() -> client.account(TOKEN)).isInstanceOf(IOException.class)
                .hasMessage("Grain answered HTTP 500");
        assertThatThrownBy(() -> client.account(TOKEN)).isInstanceOf(IOException.class)
                .hasMessage("Grain sent a reply docs0 could not read");
    }

    @Test
    void anUnreachableGrainGivesAMessageWithNoUrlOrToken() {
        server.stop(0);

        assertThatThrownBy(() -> client.account(TOKEN)).isInstanceOf(IOException.class)
                .hasMessageStartingWith("Could not reach Grain").hasMessageNotContaining(TOKEN)
                .hasMessageNotContaining("127.0.0.1");
    }
}
