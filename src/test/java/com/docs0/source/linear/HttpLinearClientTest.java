package com.docs0.source.linear;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.source.SourceAccessException;
import com.docs0.source.linear.LinearClient.Issue;
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
 * The real Linear client against a local stand-in for the GraphQL endpoint. Proves what is sent
 * (headers, queries, variables) and how replies, rate limits and refusals are handled; it cannot
 * prove that Linear's real schema accepts these field names.
 */
class HttpLinearClientTest {

    private static final String KEY = "lin_api_SECRETKEY123";
    private static final Gson GSON = new Gson();

    record Req(String authorization, String contentType, JsonObject body) {}

    record Resp(int status, String body, Map<String, String> headers) {
        static Resp ok(String body) {
            return new Resp(200, body, Map.of());
        }
    }

    HttpServer server;
    final List<Req> requests = new ArrayList<>();
    final Deque<Resp> script = new ArrayDeque<>();
    final List<Long> sleeps = new ArrayList<>();
    HttpLinearClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/graphql", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Req(exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), GSON.fromJson(body, JsonObject.class)));
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
        client = new HttpLinearClient("http://127.0.0.1:" + server.getAddress().getPort() + "/graphql", sleeps::add);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private JsonObject variables(int request) {
        return requests.get(request).body().getAsJsonObject("variables");
    }

    @Test
    void sendsThePersonalKeyAsIsWithoutABearerPrefixAndReadsTheViewer() throws IOException {
        script.add(Resp.ok("{\"data\": {\"viewer\": {\"name\": \"Asha Rao\", \"email\": \"a@x.com\"}, "
                + "\"organization\": {\"name\": \"Acme\"}}}"));

        LinearClient.Viewer viewer = client.viewer(KEY);

        assertThat(viewer).isEqualTo(new LinearClient.Viewer("Asha Rao", "Acme"));
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).authorization()).isEqualTo(KEY);
        assertThat(requests.get(0).contentType()).isEqualTo("application/json");
        assertThat(requests.get(0).body().get("query").getAsString()).contains("viewer", "organization");
    }

    @Test
    void readsEveryPageOfTeams() throws IOException {
        script.add(Resp.ok("{\"data\": {\"teams\": {\"nodes\": [{\"id\": \"t1\", \"key\": \"ENG\", \"name\": \"Engineering\"}], "
                + "\"pageInfo\": {\"hasNextPage\": true, \"endCursor\": \"c1\"}}}}"));
        script.add(Resp.ok("{\"data\": {\"teams\": {\"nodes\": [{\"id\": \"t2\", \"key\": \"OPS\", \"name\": \"Operations\"}], "
                + "\"pageInfo\": {\"hasNextPage\": false, \"endCursor\": null}}}}"));

        List<LinearTeam> teams = client.teams(KEY);

        assertThat(teams).containsExactly(new LinearTeam("t1", "ENG", "Engineering"),
                new LinearTeam("t2", "OPS", "Operations"));
        assertThat(variables(0).has("after")).isFalse();
        assertThat(variables(1).get("after").getAsString()).isEqualTo("c1");
    }

    @Test
    void readsIssuesPageByPageWithTheTeamFilterSmallPagesAndMapsEveryField() throws IOException {
        script.add(Resp.ok("""
                {"data": {"issues": {"nodes": [{
                   "id": "i1", "identifier": "ENG-12", "title": "Fix login", "description": "Users cannot log in",
                   "priorityLabel": "High", "url": "https://linear.app/acme/issue/ENG-12",
                   "createdAt": "2026-03-01T09:00:00.000Z", "updatedAt": "2026-03-04T10:30:00.000Z",
                   "dueDate": "2026-04-01", "state": {"name": "In Progress"},
                   "assignee": {"name": "Priya Nair", "email": "priya@example.com"},
                   "creator": {"name": "Marco Diaz", "email": "marco@example.com"},
                   "labels": {"nodes": [{"name": "bug"}, {"name": "backend"}]},
                   "project": {"name": "Launch"}, "cycle": {"name": null, "number": 4},
                   "parent": {"identifier": "ENG-3"},
                   "comments": {"nodes": [{"body": "On it", "createdAt": "2026-03-02T08:00:00.000Z",
                                           "user": {"name": "Priya Nair"}}]}}],
                  "pageInfo": {"hasNextPage": true, "endCursor": "cur-1"}}}}
                """));
        script.add(Resp.ok("""
                {"data": {"issues": {"nodes": [{
                   "id": "i2", "identifier": "ENG-13", "title": "Bare issue", "description": null,
                   "priorityLabel": "No priority", "url": "https://linear.app/acme/issue/ENG-13",
                   "createdAt": "2026-03-01T09:00:00.000Z", "updatedAt": "2026-03-05T10:30:00.000Z",
                   "dueDate": null, "state": {"name": "Todo"}, "assignee": null,
                   "creator": null, "labels": {"nodes": []}, "project": null, "cycle": null, "parent": null,
                   "comments": {"nodes": []}}],
                  "pageInfo": {"hasNextPage": false, "endCursor": "cur-2"}}}}
                """));

        List<Issue> issues = client.issues(KEY, "team-eng");

        assertThat(issues).hasSize(2);
        Issue first = issues.get(0);
        assertThat(first.id()).isEqualTo("i1");
        assertThat(first.identifier()).isEqualTo("ENG-12");
        assertThat(first.title()).isEqualTo("Fix login");
        assertThat(first.description()).isEqualTo("Users cannot log in");
        assertThat(first.state()).isEqualTo("In Progress");
        assertThat(first.priority()).isEqualTo("High");
        assertThat(first.assigneeName()).isEqualTo("Priya Nair");
        assertThat(first.assigneeEmail()).isEqualTo("priya@example.com");
        assertThat(first.creatorName()).isEqualTo("Marco Diaz");
        assertThat(first.labels()).containsExactly("bug", "backend");
        assertThat(first.project()).isEqualTo("Launch");
        assertThat(first.cycle()).isEqualTo("Cycle 4");
        assertThat(first.dueDate()).isEqualTo("2026-04-01");
        assertThat(first.createdAt()).isEqualTo(Instant.parse("2026-03-01T09:00:00Z"));
        assertThat(first.updatedAt()).isEqualTo(Instant.parse("2026-03-04T10:30:00Z"));
        assertThat(first.url()).isEqualTo("https://linear.app/acme/issue/ENG-12");
        assertThat(first.parentIdentifier()).isEqualTo("ENG-3");
        assertThat(first.comments()).hasSize(1);
        assertThat(first.comments().get(0).authorName()).isEqualTo("Priya Nair");
        assertThat(first.comments().get(0).body()).isEqualTo("On it");
        assertThat(first.comments().get(0).createdAt()).isEqualTo(Instant.parse("2026-03-02T08:00:00Z"));
        Issue second = issues.get(1);
        assertThat(second.description()).isNull();
        assertThat(second.assigneeName()).isNull();
        assertThat(second.project()).isNull();
        assertThat(second.cycle()).isNull();
        assertThat(second.labels()).isEmpty();
        assertThat(second.comments()).isEmpty();

        String query = requests.get(0).body().get("query").getAsString();
        assertThat(query).contains("issues(first: 50", "comments(first: 50)", "team: { id: { eq: $team } }");
        assertThat(variables(0).get("team").getAsString()).isEqualTo("team-eng");
        assertThat(variables(1).get("after").getAsString()).isEqualTo("cur-1");
        assertThat(requests).hasSize(2);
    }

    @Test
    void readsProjectsAndDocuments() throws IOException {
        script.add(Resp.ok("""
                {"data": {"projects": {"nodes": [{"id": "p1", "name": "Launch", "description": "Big launch",
                   "state": "started", "url": "https://linear.app/acme/project/p1", "targetDate": "2026-06-30",
                   "updatedAt": "2026-03-04T10:30:00.000Z", "lead": {"name": "Priya Nair", "email": "priya@example.com"}}],
                  "pageInfo": {"hasNextPage": false, "endCursor": null}}}}
                """));
        script.add(Resp.ok("""
                {"data": {"documents": {"nodes": [{"id": "d1", "title": "Launch plan", "content": "Steps",
                   "url": "https://linear.app/acme/document/d1", "updatedAt": "2026-03-05T10:30:00.000Z",
                   "project": {"id": "p1", "name": "Launch"}, "creator": {"name": "Asha Rao"}},
                  {"id": "d2", "title": "Loose note", "content": "x", "url": null, "updatedAt": null,
                   "project": null, "creator": null}],
                  "pageInfo": {"hasNextPage": false, "endCursor": null}}}}
                """));

        List<LinearClient.Project> projects = client.projects(KEY, List.of("t1", "t2"));
        List<LinearClient.Doc> docs = client.documents(KEY);

        assertThat(projects).hasSize(1);
        assertThat(projects.get(0).name()).isEqualTo("Launch");
        assertThat(projects.get(0).leadEmail()).isEqualTo("priya@example.com");
        assertThat(projects.get(0).targetDate()).isEqualTo("2026-06-30");
        assertThat(variables(0).getAsJsonArray("teams")).hasSize(2);
        assertThat(docs).hasSize(2);
        assertThat(docs.get(0).projectId()).isEqualTo("p1");
        assertThat(docs.get(0).creatorName()).isEqualTo("Asha Rao");
        assertThat(docs.get(1).projectId()).isNull();
        assertThat(client.projects(KEY, List.of())).isEmpty();
    }

    @Test
    void anUnauthorisedReplyIsTheReconnectMessage() {
        script.add(new Resp(401, "{\"errors\": [{\"message\": \"Authentication required\"}]}", Map.of()));

        assertThatThrownBy(() -> client.viewer(KEY)).isInstanceOf(SourceAccessException.class)
                .hasMessage("Linear rejected the API key. Reconnect Linear on the Sources page.");
    }

    @Test
    void aGraphqlAuthenticationErrorIsTheSameMessageWhateverTheHttpStatus() {
        script.add(Resp.ok("{\"errors\": [{\"message\": \"nope\", \"extensions\": {\"code\": \"AUTHENTICATION_ERROR\"}}]}"));
        script.add(new Resp(400, "{\"errors\": [{\"message\": \"nope\", \"extensions\": {\"code\": \"AUTHENTICATION_ERROR\"}}]}",
                Map.of()));

        assertThatThrownBy(() -> client.teams(KEY)).isInstanceOf(SourceAccessException.class)
                .hasMessage(HttpLinearClient.REJECTED);
        assertThatThrownBy(() -> client.teams(KEY)).isInstanceOf(SourceAccessException.class)
                .hasMessage(HttpLinearClient.REJECTED);
    }

    @Test
    void aRateLimitIsWaitedOutAsTheServerAsksAndThenTheCallSucceeds() throws IOException {
        script.add(new Resp(429, "{}", Map.of("Retry-After", "3")));
        script.add(new Resp(400, "{\"errors\": [{\"message\": \"slow down\", \"extensions\": {\"code\": \"RATELIMITED\"}}]}",
                Map.of("Retry-After", "7")));
        script.add(Resp.ok("{\"data\": {\"viewer\": {\"name\": \"A\"}, \"organization\": {\"name\": \"Acme\"}}}"));

        assertThat(client.viewer(KEY).workspaceName()).isEqualTo("Acme");

        assertThat(sleeps).containsExactly(3L, 7L);
        assertThat(requests).hasSize(3);
    }

    @Test
    void aRateLimitThatDoesNotLiftEndsInAnErrorAfterABoundedNumberOfWaits() {
        for (int i = 0; i < 10; i++) {
            script.add(new Resp(429, "{}", Map.of()));
        }

        assertThatThrownBy(() -> client.viewer(KEY)).isInstanceOf(IOException.class)
                .isNotInstanceOf(SourceAccessException.class)
                .hasMessageContaining("limiting how fast");

        assertThat(sleeps).hasSize(HttpLinearClient.MAX_RETRIES);
        assertThat(sleeps).allMatch(s -> s <= HttpLinearClient.MAX_WAIT_SECONDS);
        assertThat(requests).hasSize(HttpLinearClient.MAX_RETRIES + 1);
    }

    @Test
    void otherGraphqlErrorsAndHttpFailuresBecomePlainErrorsWithoutTheKey() {
        script.add(new Resp(400, "{\"errors\": [{\"message\": \"Cannot query field \\\"foo\\\"\"}]}", Map.of()));
        script.add(new Resp(502, "bad gateway", Map.of()));

        assertThatThrownBy(() -> client.viewer(KEY)).isInstanceOf(IOException.class)
                .hasMessageContaining("Linear returned an error: Cannot query field")
                .hasMessageNotContaining(KEY);
        assertThatThrownBy(() -> client.viewer(KEY)).isInstanceOf(IOException.class)
                .hasMessage("Linear answered HTTP 502");
    }

    @Test
    void aConnectionFailureNamesNoUrlAndNoKey() {
        server.stop(0);

        assertThatThrownBy(() -> client.viewer(KEY)).isInstanceOf(IOException.class)
                .hasMessageStartingWith("Could not reach Linear")
                .hasMessageNotContaining(KEY)
                .hasMessageNotContaining("127.0.0.1");
    }

    @Test
    void createsAnIssueWithTheIssueCreateMutationAndExactVariables() throws IOException {
        script.add(Resp.ok("{\"data\": {\"issueCreate\": {\"success\": true, "
                + "\"issue\": {\"identifier\": \"ENG-99\", \"url\": \"https://linear.app/acme/issue/ENG-99\"}}}}"));

        LinearClient.Created created = client.createIssue(KEY, "team-eng", "Fix \"login\"", "Steps:\n1. Try it", 2);

        assertThat(created).isEqualTo(new LinearClient.Created("ENG-99", "https://linear.app/acme/issue/ENG-99"));
        Req request = requests.get(0);
        assertThat(request.authorization()).isEqualTo(KEY);
        assertThat(request.body().get("query").getAsString()).startsWith("mutation").contains("issueCreate(input: $input)");
        assertThat(request.body().getAsJsonObject("variables")).isEqualTo(GSON.fromJson("""
                {"input": {"teamId": "team-eng", "title": "Fix \\"login\\"", "description": "Steps:\\n1. Try it", "priority": 2}}
                """, JsonObject.class));
        assertThat(request.body().keySet()).containsExactlyInAnyOrder("query", "variables");
    }

    @Test
    void anIssueWithoutAPriorityLeavesTheFieldOutAndAFailedCreateIsAnError() throws IOException {
        script.add(Resp.ok("{\"data\": {\"issueCreate\": {\"success\": true, \"issue\": {\"identifier\": \"ENG-1\", \"url\": \"u\"}}}}"));
        script.add(Resp.ok("{\"data\": {\"issueCreate\": {\"success\": false, \"issue\": null}}}"));

        client.createIssue(KEY, "team-eng", "T", "D", null);
        assertThat(variables(0).getAsJsonObject("input").has("priority")).isFalse();

        assertThatThrownBy(() -> client.createIssue(KEY, "team-eng", "T", "D", null)).isInstanceOf(IOException.class)
                .hasMessage("Linear did not create the issue");
    }

    @Test
    void aRateLimitedCreateGivesUpAfterTheBoundedWaits() {
        for (int i = 0; i < 10; i++) {
            script.add(new Resp(429, "{}", Map.of()));
        }

        assertThatThrownBy(() -> client.createIssue(KEY, "t", "T", "D", null)).isInstanceOf(IOException.class);
        assertThat(requests).hasSize(HttpLinearClient.MAX_RETRIES + 1);
    }

    @Test
    void countsIssuesAndStopsAtTheCap() throws IOException {
        script.add(Resp.ok("{\"data\": {\"issues\": {\"nodes\": [{\"id\": \"1\"}, {\"id\": \"2\"}, {\"id\": \"3\"}], "
                + "\"pageInfo\": {\"hasNextPage\": true, \"endCursor\": \"c\"}}}}"));
        script.add(Resp.ok("{\"data\": {\"issues\": {\"nodes\": [{\"id\": \"4\"}, {\"id\": \"5\"}], "
                + "\"pageInfo\": {\"hasNextPage\": false, \"endCursor\": null}}}}"));

        assertThat(client.countIssues(KEY, "t", 100)).isEqualTo(5);

        script.add(Resp.ok("{\"data\": {\"issues\": {\"nodes\": [{\"id\": \"1\"}, {\"id\": \"2\"}, {\"id\": \"3\"}], "
                + "\"pageInfo\": {\"hasNextPage\": true, \"endCursor\": \"c\"}}}}"));
        assertThat(client.countIssues(KEY, "t", 3)).isEqualTo(3);
        assertThat(requests).hasSize(3);
    }
}
