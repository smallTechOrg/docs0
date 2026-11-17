package com.docs0.source.linear;

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
 * {@link LinearClient} on Linear's GraphQL API. A personal API key goes in the Authorization
 * header as is, without a Bearer prefix. The key is never logged and never part of an exception
 * message. Rate limits are waited out a few times, then the call fails.
 */
@Component
public class HttpLinearClient implements LinearClient {

    public static final String ENDPOINT = "https://api.linear.app/graphql";
    public static final String REJECTED = "Linear rejected the API key. Reconnect Linear on the Sources page.";

    static final int ISSUE_PAGE = 25;
    static final int COMMENTS_PER_ISSUE = 50;
    static final int LIST_PAGE = 100;
    static final int MAX_RETRIES = 4;
    static final long MAX_WAIT_SECONDS = 60;
    private static final long DEFAULT_WAIT_SECONDS = 5;
    private static final int MAX_PAGES = 1000;

    private static final Logger log = LoggerFactory.getLogger(HttpLinearClient.class);
    private static final Gson GSON = new Gson();

    private static final String PAGE_INFO = "pageInfo { hasNextPage endCursor }";

    private final URI endpoint;
    private final LongConsumer sleeper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    @Autowired
    public HttpLinearClient() {
        this(ENDPOINT, HttpLinearClient::sleepSeconds);
    }

    /** For tests: another endpoint, and a sleeper that is told how many seconds to wait. */
    HttpLinearClient(String endpoint, LongConsumer sleeper) {
        this.endpoint = URI.create(endpoint);
        this.sleeper = sleeper;
    }

    @Override
    public Viewer viewer(String apiKey) throws IOException {
        JsonObject data = query(apiKey, "query { viewer { name email } organization { name } }", Map.of());
        JsonObject viewer = obj(data, "viewer");
        JsonObject organization = obj(data, "organization");
        return new Viewer(text(viewer, "name"), text(organization, "name"));
    }

    @Override
    public List<LinearTeam> teams(String apiKey) throws IOException {
        List<LinearTeam> teams = new ArrayList<>();
        for (JsonObject node : pages(apiKey, "teams",
                "query($after: String) { teams(first: " + LIST_PAGE + ", after: $after) { nodes { id key name } "
                        + PAGE_INFO + " } }", Map.of())) {
            teams.add(new LinearTeam(text(node, "id"), text(node, "key"), text(node, "name")));
        }
        return teams;
    }

    @Override
    public List<Issue> issues(String apiKey, String teamId) throws IOException {
        String query = "query($team: ID!, $after: String) { issues(first: " + ISSUE_PAGE
                + ", after: $after, filter: { team: { id: { eq: $team } } }) { nodes { "
                + "id identifier title description priorityLabel url createdAt updatedAt dueDate "
                + "state { name } assignee { name email } creator { name email } "
                + "labels(first: 20) { nodes { name } } project { name } cycle { name number } "
                + "parent { identifier } "
                + "comments(first: " + COMMENTS_PER_ISSUE + ") { nodes { body createdAt user { name } } } "
                + "} " + PAGE_INFO + " } }";
        List<Issue> issues = new ArrayList<>();
        for (JsonObject n : pages(apiKey, "issues", query, Map.of("team", teamId))) {
            List<String> labels = new ArrayList<>();
            for (JsonObject label : nodes(obj(n, "labels"))) {
                labels.add(text(label, "name"));
            }
            List<Comment> comments = new ArrayList<>();
            for (JsonObject c : nodes(obj(n, "comments"))) {
                comments.add(new Comment(text(obj(c, "user"), "name"), text(c, "body"), instant(c, "createdAt")));
            }
            JsonObject cycle = obj(n, "cycle");
            String cycleName = text(cycle, "name");
            if (cycleName == null && text(cycle, "number") != null) {
                cycleName = "Cycle " + text(cycle, "number");
            }
            issues.add(new Issue(text(n, "id"), text(n, "identifier"), text(n, "title"), text(n, "description"),
                    text(obj(n, "state"), "name"), text(n, "priorityLabel"), text(obj(n, "assignee"), "name"),
                    text(obj(n, "assignee"), "email"), text(obj(n, "creator"), "name"),
                    text(obj(n, "creator"), "email"), labels, text(obj(n, "project"), "name"), cycleName,
                    text(n, "dueDate"), instant(n, "createdAt"), instant(n, "updatedAt"), text(n, "url"),
                    text(obj(n, "parent"), "identifier"), comments));
        }
        return issues;
    }

    @Override
    public List<Project> projects(String apiKey, List<String> teamIds) throws IOException {
        if (teamIds.isEmpty()) {
            return List.of();
        }
        String query = "query($teams: [ID!], $after: String) { projects(first: " + ISSUE_PAGE
                + ", after: $after, filter: { accessibleTeams: { some: { id: { in: $teams } } } }) { nodes { "
                + "id name description state url targetDate updatedAt lead { name email } } " + PAGE_INFO + " } }";
        List<Project> projects = new ArrayList<>();
        for (JsonObject n : pages(apiKey, "projects", query, Map.of("teams", teamIds))) {
            projects.add(new Project(text(n, "id"), text(n, "name"), text(n, "description"), text(n, "state"),
                    text(obj(n, "lead"), "name"), text(obj(n, "lead"), "email"), text(n, "targetDate"),
                    text(n, "url"), instant(n, "updatedAt")));
        }
        return projects;
    }

    @Override
    public List<Doc> documents(String apiKey) throws IOException {
        String query = "query($after: String) { documents(first: " + ISSUE_PAGE + ", after: $after) { nodes { "
                + "id title content url updatedAt project { id name } creator { name } } " + PAGE_INFO + " } }";
        List<Doc> docs = new ArrayList<>();
        for (JsonObject n : pages(apiKey, "documents", query, Map.of())) {
            docs.add(new Doc(text(n, "id"), text(n, "title"), text(n, "content"), text(obj(n, "project"), "id"),
                    text(obj(n, "project"), "name"), text(obj(n, "creator"), "name"), text(n, "url"),
                    instant(n, "updatedAt")));
        }
        return docs;
    }

    @Override
    public int countIssues(String apiKey, String teamId, int cap) throws IOException {
        String query = "query($team: ID!, $after: String) { issues(first: " + LIST_PAGE
                + ", after: $after, filter: { team: { id: { eq: $team } } }) { nodes { id } " + PAGE_INFO + " } }";
        int count = 0;
        String after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("team", teamId);
            variables.put("after", after);
            JsonObject connection = obj(query(apiKey, query, variables), "issues");
            count += nodes(connection).size();
            if (count >= cap) {
                return cap;
            }
            after = nextCursor(connection);
            if (after == null) {
                break;
            }
        }
        return count;
    }

    @Override
    public Created createIssue(String apiKey, String teamId, String title, String description, Integer priority)
            throws IOException {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("teamId", teamId);
        input.put("title", title);
        input.put("description", description);
        if (priority != null) {
            input.put("priority", priority);
        }
        JsonObject data = query(apiKey, "mutation($input: IssueCreateInput!) { issueCreate(input: $input) "
                + "{ success issue { identifier url } } }", Map.of("input", input));
        JsonObject created = obj(data, "issueCreate");
        JsonObject issue = obj(created, "issue");
        if (created == null || !created.has("success") || !created.get("success").getAsBoolean() || issue == null) {
            throw new IOException("Linear did not create the issue");
        }
        return new Created(text(issue, "identifier"), text(issue, "url"));
    }

    // ---- GraphQL plumbing ------------------------------------------------------------------

    /** Runs a query that takes {@code $after} and walks every page of the connection named {@code field}. */
    private List<JsonObject> pages(String apiKey, String field, String query, Map<String, Object> variables)
            throws IOException {
        List<JsonObject> all = new ArrayList<>();
        String after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> withCursor = new LinkedHashMap<>(variables);
            withCursor.put("after", after);
            JsonObject connection = obj(query(apiKey, query, withCursor), field);
            all.addAll(nodes(connection));
            after = nextCursor(connection);
            if (after == null) {
                return all;
            }
        }
        throw new IOException("Linear returned more pages than docs0 will read");
    }

    private static String nextCursor(JsonObject connection) {
        JsonObject info = obj(connection, "pageInfo");
        if (info == null || !info.has("hasNextPage") || !info.get("hasNextPage").getAsBoolean()) {
            return null;
        }
        return text(info, "endCursor");
    }

    private static List<JsonObject> nodes(JsonObject connection) {
        List<JsonObject> nodes = new ArrayList<>();
        if (connection != null && connection.get("nodes") instanceof JsonArray array) {
            for (JsonElement e : array) {
                if (e.isJsonObject()) {
                    nodes.add(e.getAsJsonObject());
                }
            }
        }
        return nodes;
    }

    /** One GraphQL call with rate-limit retries. Returns the {@code data} object. */
    private JsonObject query(String apiKey, String query, Map<String, Object> variables) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("variables", variables);
        String json = GSON.toJson(body);

        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response = send(apiKey, json);
            int status = response.statusCode();
            if (status == 401) {
                throw new SourceAccessException(REJECTED);
            }
            JsonObject parsed = parse(response.body());
            String errorCode = null;
            String errorMessage = null;
            if (parsed != null && parsed.get("errors") instanceof JsonArray errors && !errors.isEmpty()) {
                JsonObject first = errors.get(0).isJsonObject() ? errors.get(0).getAsJsonObject() : null;
                errorCode = text(obj(first, "extensions"), "code");
                errorMessage = text(first, "message");
            }
            if ("AUTHENTICATION_ERROR".equals(errorCode)) {
                throw new SourceAccessException(REJECTED);
            }
            if (status == 429 || "RATELIMITED".equals(errorCode)) {
                if (attempt >= MAX_RETRIES) {
                    throw new IOException("Linear is limiting how fast docs0 can read. Try again in a few minutes.");
                }
                long wait = waitSeconds(response, attempt);
                log.info("Linear rate limit, waiting {}s (retry {} of {})", wait, attempt + 1, MAX_RETRIES);
                sleeper.accept(wait);
                continue;
            }
            if (errorCode != null || errorMessage != null) {
                throw new IOException("Linear returned an error: " + shorten(errorMessage));
            }
            if (status / 100 != 2) {
                throw new IOException("Linear answered HTTP " + status);
            }
            JsonObject data = parsed == null ? null : obj(parsed, "data");
            if (data == null) {
                throw new IOException("Linear sent a reply docs0 could not read");
            }
            return data;
        }
    }

    private HttpResponse<String> send(String apiKey, String json) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Linear call interrupted");
        } catch (IOException e) {
            // Class name only: nothing from the request belongs in a message.
            throw new IOException("Could not reach Linear (" + e.getClass().getSimpleName() + ")");
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

    private static JsonObject parse(String body) {
        try {
            JsonElement element = GSON.fromJson(body, JsonElement.class);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (JsonParseException e) {
            return null;
        }
    }

    private static JsonObject obj(JsonObject parent, String name) {
        return parent != null && parent.get(name) instanceof JsonObject o ? o : null;
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

    private static Instant instant(JsonObject parent, String name) {
        String value = text(parent, name);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String shorten(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        String oneLine = message.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "..." : oneLine;
    }
}
