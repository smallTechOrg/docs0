package com.docs0.source.grain;

import com.docs0.source.SourceAccessException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link GrainClient} on Grain's public REST API (https://developers.grain.com). A personal or
 * workspace access token goes in the Authorization header as a bearer token, next to the
 * required Public-Api-Version header. The token is never logged and never part of an exception
 * message. Rate limits (429 with Retry-After) and short server errors are waited out a few
 * times, then the call fails.
 *
 * <p>All HTTP paths, request bodies and JSON field names of Grain live in this one class. They
 * follow the published reference; see the "mapping" comments if a real run shows a difference.
 */
@Component
public class HttpGrainClient implements GrainClient {

    public static final String BASE_URL = "https://api.grain.com";
    public static final String API_VERSION = "2026-10-01";
    public static final String REJECTED = "Grain rejected the access token. Reconnect Grain on the Sources page.";

    static final int MAX_RETRIES = 4;
    static final long MAX_WAIT_SECONDS = 60;
    private static final long DEFAULT_WAIT_SECONDS = 5;
    private static final int MAX_PAGES = 2000;
    private static final String API = "/_/public-api/v2";

    private static final Logger log = LoggerFactory.getLogger(HttpGrainClient.class);
    private static final Gson GSON = new Gson();

    private final String baseUrl;
    private final LongConsumer sleeper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    @Autowired
    public HttpGrainClient() {
        this(BASE_URL, HttpGrainClient::sleepSeconds);
    }

    /** For tests: another base URL, and a sleeper that is told how many seconds to wait. */
    HttpGrainClient(String baseUrl, LongConsumer sleeper) {
        this.baseUrl = baseUrl;
        this.sleeper = sleeper;
    }

    /** mapping: GET /token-info -> {type, scope: personal|workspace, name, user_id | workspace_id, expires_at}. */
    @Override
    public Account account(String token) throws IOException {
        JsonObject info = asObject(call(token, "GET", API + "/token-info", null));
        if (info == null) {
            throw new IOException("Grain sent a reply docs0 could not read");
        }
        return new Account(text(info, "name"), text(info, "scope"));
    }

    @Override
    public List<Recording> recordings(String token, Instant since) throws IOException {
        List<Recording> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonObject reply = listPage(token, since, cursor);
            for (JsonObject r : objects(reply, "recordings")) {
                Recording recording = summary(r);
                // The date filter is also applied here, in case Grain treats its bounds differently.
                if (recording.id() != null && (recording.start() == null || !recording.start().isBefore(since))) {
                    all.add(recording);
                }
            }
            cursor = text(reply, "cursor");
            if (cursor == null || cursor.isBlank()) {
                return all;
            }
        }
        throw new IOException("Grain returned more pages than docs0 will read");
    }

    @Override
    public int countRecordings(String token, Instant since, int cap) throws IOException {
        int count = 0;
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonObject reply = listPage(token, since, cursor);
            for (JsonObject r : objects(reply, "recordings")) {
                Instant start = instant(r, "start_datetime");
                if (start == null || !start.isBefore(since)) {
                    count++;
                }
            }
            if (count >= cap) {
                return cap;
            }
            cursor = text(reply, "cursor");
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        return count;
    }

    /**
     * mapping: POST /recordings with {cursor, filter: {after_datetime}, include: {participants}} ->
     * {cursor (null at the end), recordings: [...]}. Grain's page text describes the two datetime
     * filters the wrong way round; after_datetime is used here for "started after".
     */
    private JsonObject listPage(String token, Instant since, String cursor) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        if (cursor != null) {
            body.put("cursor", cursor);
        }
        body.put("filter", Map.of("after_datetime", since.toString()));
        body.put("include", Map.of("participants", true));
        JsonObject reply = asObject(call(token, "POST", API + "/recordings", GSON.toJson(body)));
        if (reply == null) {
            throw new IOException("Grain sent a reply docs0 could not read");
        }
        return reply;
    }

    /**
     * mapping: POST /recordings/{id} with {include: {participants, highlights, ai_summary,
     * ai_action_items}} -> one recording; GET /recordings/{id}/transcript -> [{participant_id,
     * speaker, start (ms), end (ms), text}]. A 404 on the transcript means it is not ready yet.
     */
    @Override
    public Recording details(String token, String recordingId) throws IOException {
        Map<String, Object> include = new LinkedHashMap<>();
        include.put("participants", true);
        include.put("highlights", true);
        include.put("ai_summary", true);
        include.put("ai_action_items", true);
        String id = java.net.URLEncoder.encode(recordingId, StandardCharsets.UTF_8);
        JsonObject r = asObject(call(token, "POST", API + "/recordings/" + id, GSON.toJson(Map.of("include", include))));
        if (r == null) {
            throw new IOException("Grain sent a reply docs0 could not read");
        }
        Recording base = summary(r);

        List<String> actions = new ArrayList<>();
        for (JsonObject a : objects(r, "ai_action_items")) {
            String text = text(a, "text");
            if (text == null) {
                continue;
            }
            String assignee = text(asObject(a.get("assignee")), "name");
            String status = text(a, "status");
            actions.add((status == null ? "" : "[" + status + "] ") + text + (assignee == null ? "" : " (" + assignee + ")"));
        }
        List<String> highlights = new ArrayList<>();
        for (JsonObject h : objects(r, "highlights")) {
            String text = text(h, "text");
            if (text == null) {
                text = text(h, "transcript");
            }
            if (text != null) {
                String at = h.has("timestamp") && h.get("timestamp").isJsonPrimitive()
                        ? GrainRender.clock(h.get("timestamp").getAsLong()) + " " : "";
                highlights.add(at + text);
            }
        }

        List<Segment> transcript = new ArrayList<>();
        JsonElement raw = call(token, "GET", API + "/recordings/" + id + "/transcript", null, true);
        JsonArray segments = raw != null && raw.isJsonArray() ? raw.getAsJsonArray() : null;
        if (segments == null && raw != null && raw.isJsonObject() && raw.getAsJsonObject().get("transcript") instanceof JsonArray a) {
            segments = a;
        }
        if (segments != null) {
            for (JsonElement e : segments) {
                if (e.isJsonObject()) {
                    JsonObject s = e.getAsJsonObject();
                    transcript.add(new Segment(text(s, "speaker"), number(s, "start"), text(s, "text")));
                }
            }
        }

        JsonObject summary = asObject(r.get("ai_summary"));
        return new Recording(base.id(), base.title(), base.url(), base.start(), base.end(), base.durationMs(),
                base.recorders(), base.participants(), text(summary, "text"), actions, highlights, transcript);
    }

    /** mapping of one recording object: id, title, url, start_datetime, end_datetime, duration_ms, recorders[], participants[]. */
    private static Recording summary(JsonObject r) {
        return new Recording(text(r, "id"), text(r, "title"), text(r, "url"), instant(r, "start_datetime"),
                instant(r, "end_datetime"), r.has("duration_ms") && r.get("duration_ms").isJsonPrimitive()
                        ? Long.valueOf(number(r, "duration_ms")) : null,
                people(r, "recorders"), people(r, "participants"), null, List.of(), List.of(), List.of());
    }

    private static List<Person> people(JsonObject r, String field) {
        List<Person> people = new ArrayList<>();
        for (JsonObject p : objects(r, field)) {
            people.add(new Person(text(p, "name"), text(p, "email")));
        }
        return people;
    }

    // ---- HTTP plumbing ---------------------------------------------------------------------

    private JsonElement call(String token, String method, String path, String json) throws IOException {
        return call(token, method, path, json, false);
    }

    /** One call with retries. Returns the parsed JSON body; null on a 404 when {@code missingIsNull}. */
    private JsonElement call(String token, String method, String path, String json, boolean missingIsNull)
            throws IOException {
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response = send(token, method, path, json);
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw new SourceAccessException(REJECTED);
            }
            if (status == 404 && missingIsNull) {
                return null;
            }
            if (status == 429 || status == 502 || status == 503 || status == 504) {
                if (attempt >= MAX_RETRIES) {
                    throw new IOException(status == 429
                            ? "Grain is limiting how fast docs0 can read. Try again in a few minutes."
                            : "Grain answered HTTP " + status);
                }
                long wait = waitSeconds(response, attempt);
                log.info("Grain answered {}, waiting {}s (retry {} of {})", status, wait, attempt + 1, MAX_RETRIES);
                sleeper.accept(wait);
                continue;
            }
            if (status / 100 != 2) {
                throw new IOException("Grain answered HTTP " + status);
            }
            try {
                JsonElement parsed = GSON.fromJson(response.body(), JsonElement.class);
                if (parsed == null || parsed instanceof JsonNull) {
                    throw new IOException("Grain sent a reply docs0 could not read");
                }
                return parsed;
            } catch (JsonParseException e) {
                throw new IOException("Grain sent a reply docs0 could not read");
            }
        }
    }

    private HttpResponse<String> send(String token, String method, String path, String json) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("Public-Api-Version", API_VERSION)
                .header("Accept", "application/json");
        if (json != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Grain call interrupted");
        } catch (IOException e) {
            // Class name only: nothing from the request belongs in a message.
            throw new IOException("Could not reach Grain (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static long waitSeconds(HttpResponse<String> response, int attempt) {
        long seconds = DEFAULT_WAIT_SECONDS << attempt;
        String header = response.headers().firstValue("Retry-After").orElse(null);
        if (header != null) {
            try {
                seconds = Long.parseLong(header.strip());
            } catch (NumberFormatException e) {
                // keep the default
            }
        }
        return Math.max(0, Math.min(MAX_WAIT_SECONDS, seconds));
    }

    private static void sleepSeconds(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- JSON helpers ----------------------------------------------------------------------

    private static JsonObject asObject(JsonElement e) {
        return e instanceof JsonObject o ? o : null;
    }

    private static List<JsonObject> objects(JsonObject parent, String name) {
        List<JsonObject> list = new ArrayList<>();
        if (parent != null && parent.get(name) instanceof JsonArray array) {
            for (JsonElement e : array) {
                if (e.isJsonObject()) {
                    list.add(e.getAsJsonObject());
                }
            }
        }
        return list;
    }

    /** A string field; numbers are returned as text. Null when missing or JSON null. */
    private static String text(JsonObject parent, String name) {
        if (parent == null) {
            return null;
        }
        JsonElement e = parent.get(name);
        if (e == null || e instanceof JsonNull || !e.isJsonPrimitive()) {
            return null;
        }
        return e.getAsString();
    }

    private static long number(JsonObject parent, String name) {
        try {
            String value = text(parent, name);
            return value == null ? 0 : (long) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Instant instant(JsonObject parent, String name) {
        String value = text(parent, name);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
