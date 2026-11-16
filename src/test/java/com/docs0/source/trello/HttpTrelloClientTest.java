package com.docs0.source.trello;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.docs0.source.SourceAccessException;
import com.docs0.source.trello.TrelloClient.BoardContent;
import com.docs0.source.trello.TrelloClient.Card;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The real Trello client against a local stand-in for Trello's REST API. Proves what is
 * requested, how replies, pagination, throttling, rate limits and refusals are handled, and that
 * the key and token never reach a log line or an exception. It cannot prove that Trello's real
 * endpoints accept these parameters.
 */
class HttpTrelloClientTest {

    private static final String KEY = "KEYSECRET1234";
    private static final String TOKEN = "TOKENSECRET5678";

    record Req(String method, String path, Map<String, String> query, String contentType, String body) {}

    record Resp(int status, String body, Map<String, String> headers) {
        static Resp ok(String body) {
            return new Resp(200, body, Map.of());
        }
    }

    HttpServer server;
    final List<Req> requests = new ArrayList<>();
    /** Replies by "METHOD path"; the last one repeats. */
    final Map<String, Deque<Resp>> routes = new HashMap<>();
    final List<Long> sleeps = new ArrayList<>();
    final long[] now = {1_000_000L};
    HttpTrelloClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Req(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    form(exchange.getRequestURI().getRawQuery()), exchange.getRequestHeaders().getFirst("Content-Type"),
                    body));
            Deque<Resp> queue = routes.get(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            Resp resp = queue == null ? new Resp(404, "not found", Map.of())
                    : queue.size() > 1 ? queue.poll() : queue.peek();
            byte[] bytes = resp.body().getBytes(StandardCharsets.UTF_8);
            resp.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(resp.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = newClient(90);
    }

    private HttpTrelloClient newClient(int perWindow) {
        return new HttpTrelloClient("http://127.0.0.1:" + server.getAddress().getPort() + "/1", perWindow,
                Duration.ofSeconds(10), millis -> {
                    sleeps.add(millis);
                    now[0] += millis;
                }, () -> now[0]);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void route(String methodAndPath, Resp... replies) {
        routes.put(methodAndPath, new ArrayDeque<>(List.of(replies)));
    }

    private static Map<String, String> form(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return map;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return map;
    }

    @Test
    void sendsTheKeyAndTokenAsQueryParametersAndReadsTheMember() throws IOException {
        route("GET /1/members/me", Resp.ok("{\"id\": \"m1\", \"fullName\": \"Asha Rao\", \"username\": \"asharao\"}"));

        TrelloClient.Account account = client.me(KEY, TOKEN);

        assertThat(account).isEqualTo(new TrelloClient.Account("Asha Rao", "asharao"));
        assertThat(requests.get(0).query()).containsEntry("key", KEY).containsEntry("token", TOKEN);
    }

    @Test
    void listsOnlyOpenBoards() throws IOException {
        route("GET /1/members/me/boards", Resp.ok("[{\"id\": \"b1\", \"name\": \"Roadmap\"}, {\"id\": \"b2\", \"name\": \"Hiring\"}]"));

        assertThat(client.boards(KEY, TOKEN)).containsExactly(new TrelloBoard("b1", "Roadmap"),
                new TrelloBoard("b2", "Hiring"));
        assertThat(requests.get(0).query()).containsEntry("filter", "open");
    }

    @Test
    void readsABoardWithItsListsCardsLabelsMembersChecklistsAndComments() throws IOException {
        route("GET /1/boards/b1", Resp.ok("""
                {"id": "b1", "name": "Roadmap", "desc": "What we build", "shortUrl": "https://trello.com/b/b1",
                 "dateLastActivity": "2026-03-05T08:00:00.000Z"}"""));
        route("GET /1/boards/b1/lists", Resp.ok("[{\"id\": \"l1\", \"name\": \"To do\"}, {\"id\": \"l2\", \"name\": \"Doing\"}]"));
        route("GET /1/boards/b1/actions", Resp.ok("""
                [{"id": "a2", "date": "2026-03-04T09:00:00.000Z", "memberCreator": {"fullName": "Marco Diaz"},
                  "data": {"text": "Waiting on legal", "card": {"id": "c1"}}},
                 {"id": "a1", "date": "2026-03-03T09:00:00.000Z", "memberCreator": {"fullName": "Priya Nair"},
                  "data": {"text": "Draft is ready", "card": {"id": "c1"}}},
                 {"id": "a0", "date": "2026-03-02T09:00:00.000Z", "memberCreator": {"fullName": "Priya Nair"},
                  "data": {"text": "Other card", "card": {"id": "c2"}}}]"""));
        route("GET /1/boards/b1/cards", Resp.ok("""
                [{"id": "c1", "name": "Write launch post", "desc": "A post", "idList": "l2",
                  "due": "2026-04-10T12:00:00.000Z", "dueComplete": true, "dateLastActivity": "2026-03-04T09:00:00.000Z",
                  "shortUrl": "https://trello.com/c/c1",
                  "labels": [{"name": "urgent", "color": "red"}, {"name": "", "color": "green"}],
                  "members": [{"fullName": "Priya Nair", "username": "priya"}, {"fullName": "", "username": "marco"}],
                  "checklists": [{"name": "Steps", "checkItems": [{"name": "Write", "state": "complete"},
                                                                    {"name": "Ship", "state": "incomplete"}]}]},
                 {"id": "c2", "name": "Bare card", "desc": "", "idList": "l1", "due": null, "dueComplete": false,
                  "dateLastActivity": "2026-03-02T09:00:00.000Z", "shortUrl": "https://trello.com/c/c2",
                  "labels": [], "members": [], "checklists": []}]"""));

        BoardContent board = client.board(KEY, TOKEN, "b1");

        assertThat(board.name()).isEqualTo("Roadmap");
        assertThat(board.desc()).isEqualTo("What we build");
        assertThat(board.shortUrl()).isEqualTo("https://trello.com/b/b1");
        assertThat(board.lastActivity()).isEqualTo(Instant.parse("2026-03-05T08:00:00Z"));
        assertThat(board.lists()).extracting(TrelloClient.BoardList::name).containsExactly("To do", "Doing");
        assertThat(board.cards()).hasSize(2);
        Card c1 = board.cards().get(0);
        assertThat(c1.name()).isEqualTo("Write launch post");
        assertThat(c1.listId()).isEqualTo("l2");
        assertThat(c1.due()).isEqualTo(Instant.parse("2026-04-10T12:00:00Z"));
        assertThat(c1.dueComplete()).isTrue();
        assertThat(c1.lastActivity()).isEqualTo(Instant.parse("2026-03-04T09:00:00Z"));
        assertThat(c1.labels()).containsExactly("urgent", "green");
        assertThat(c1.members()).containsExactly("Priya Nair", "marco");
        assertThat(c1.checklists()).hasSize(1);
        assertThat(c1.checklists().get(0).items()).containsExactly(new TrelloClient.ChecklistItem("Write", true),
                new TrelloClient.ChecklistItem("Ship", false));
        assertThat(c1.comments()).extracting(TrelloClient.Comment::text).containsExactly("Waiting on legal", "Draft is ready");
        assertThat(c1.comments().get(0).authorName()).isEqualTo("Marco Diaz");
        assertThat(board.cards().get(1).comments()).extracting(TrelloClient.Comment::text).containsExactly("Other card");
        assertThat(board.cards().get(1).due()).isNull();

        Req cards = requests.stream().filter(r -> r.path().equals("/1/boards/b1/cards")).findFirst().orElseThrow();
        assertThat(cards.query()).containsEntry("filter", "open").containsEntry("limit", "1000")
                .containsEntry("checklists", "all").containsEntry("members", "true");
        assertThat(cards.query().get("fields")).contains("dateLastActivity", "desc", "idList", "due", "labels");
        Req actions = requests.stream().filter(r -> r.path().equals("/1/boards/b1/actions")).findFirst().orElseThrow();
        assertThat(actions.query()).containsEntry("filter", "commentCard").containsEntry("limit", "1000");
    }

    @Test
    void pagesThroughMoreThanAThousandCardsWithBefore() throws IOException {
        route("GET /1/boards/b1", Resp.ok("{\"id\": \"b1\", \"name\": \"Big\"}"));
        route("GET /1/boards/b1/lists", Resp.ok("[]"));
        route("GET /1/boards/b1/actions", Resp.ok("[]"));
        JsonArray first = new JsonArray();
        for (int i = 1; i <= 1000; i++) {
            JsonObject c = new JsonObject();
            c.addProperty("id", "c%04d".formatted(i));
            c.addProperty("name", "Card " + i);
            first.add(c);
        }
        JsonArray second = new JsonArray();
        for (int i = 1001; i <= 1003; i++) {
            JsonObject c = new JsonObject();
            c.addProperty("id", "c%04d".formatted(i));
            c.addProperty("name", "Card " + i);
            second.add(c);
        }
        route("GET /1/boards/b1/cards", Resp.ok(first.toString()), Resp.ok(second.toString()));

        BoardContent board = client.board(KEY, TOKEN, "b1");

        assertThat(board.cards()).hasSize(1003);
        List<Req> cardCalls = requests.stream().filter(r -> r.path().endsWith("/cards")).toList();
        assertThat(cardCalls).hasSize(2);
        assertThat(cardCalls.get(0).query()).doesNotContainKey("before");
        assertThat(cardCalls.get(1).query()).containsEntry("before", "c1000");
    }

    @Test
    void capsCommentsPerCard() throws IOException {
        route("GET /1/boards/b1", Resp.ok("{\"id\": \"b1\", \"name\": \"B\"}"));
        route("GET /1/boards/b1/lists", Resp.ok("[]"));
        JsonArray actions = new JsonArray();
        for (int i = 0; i < 60; i++) {
            actions.add(com.google.gson.JsonParser.parseString("{\"id\": \"a" + i + "\", \"date\": \"2026-03-04T09:00:00.000Z\", "
                    + "\"data\": {\"text\": \"note " + i + "\", \"card\": {\"id\": \"c1\"}}}"));
        }
        route("GET /1/boards/b1/actions", Resp.ok(actions.toString()));
        route("GET /1/boards/b1/cards", Resp.ok("[{\"id\": \"c1\", \"name\": \"Chatty\"}]"));

        assertThat(client.board(KEY, TOKEN, "b1").cards().get(0).comments()).hasSize(HttpTrelloClient.COMMENTS_PER_CARD);
    }

    @Test
    void anUnauthorisedReplyIsTheReconnectMessageWithoutTheSecrets() {
        route("GET /1/members/me", new Resp(401, "invalid token", Map.of()));

        assertThatThrownBy(() -> client.me(KEY, TOKEN)).isInstanceOf(SourceAccessException.class)
                .hasMessage("Trello rejected the key or token. Reconnect Trello on the Sources page.");
    }

    @Test
    void aRateLimitIsWaitedOutThenTheCallSucceeds() throws IOException {
        route("GET /1/members/me", new Resp(429, "slow down", Map.of("Retry-After", "2")),
                Resp.ok("{\"fullName\": \"A\", \"username\": \"a\"}"));

        assertThat(client.me(KEY, TOKEN).username()).isEqualTo("a");

        assertThat(sleeps).containsExactly(2000L);
        assertThat(requests).hasSize(2);
    }

    @Test
    void aRateLimitThatDoesNotLiftEndsInAnErrorAfterABoundedNumberOfWaits() {
        route("GET /1/members/me", new Resp(429, "slow down", Map.of()));

        assertThatThrownBy(() -> client.me(KEY, TOKEN)).isInstanceOf(IOException.class)
                .isNotInstanceOf(SourceAccessException.class).hasMessageContaining("limiting how fast")
                .hasMessageNotContaining(KEY).hasMessageNotContaining(TOKEN);

        assertThat(sleeps).hasSize(HttpTrelloClient.MAX_RETRIES);
        assertThat(requests).hasSize(HttpTrelloClient.MAX_RETRIES + 1);
    }

    @Test
    void requestsAreSpacedToStayUnderTheLimit() throws IOException {
        route("GET /1/members/me", Resp.ok("{\"fullName\": \"A\", \"username\": \"a\"}"));
        HttpTrelloClient slow = newClient(3);

        for (int i = 0; i < 3; i++) {
            slow.me(KEY, TOKEN);
        }
        assertThat(sleeps).isEmpty();
        slow.me(KEY, TOKEN);

        assertThat(sleeps).containsExactly(10_000L);
        assertThat(requests).hasSize(4);
    }

    @Test
    void secretsNeverReachTheLogOrAnExceptionMessage() throws IOException {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HttpTrelloClient.class);
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            route("GET /1/members/me", new Resp(429, "x", Map.of("Retry-After", "1")), Resp.ok("{\"fullName\": \"A\"}"));
            route("GET /1/boards/gone", new Resp(404, "not found", Map.of()));
            route("GET /1/members/me/boards", new Resp(500, "boom " + KEY, Map.of()));
            client.me(KEY, TOKEN);
            List<String> messages = new ArrayList<>();
            for (Callable call : List.<Callable>of(() -> client.board(KEY, TOKEN, "gone"),
                    () -> client.boards(KEY, TOKEN))) {
                try {
                    call.run();
                } catch (IOException e) {
                    messages.add(e.getMessage());
                }
            }
            server.stop(0);
            try {
                client.me(KEY, TOKEN);
            } catch (IOException e) {
                messages.add(e.getMessage());
            }

            assertThat(messages).hasSize(3).allSatisfy(m -> assertThat(m).doesNotContain(KEY, TOKEN, "127.0.0.1"));
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain(KEY, TOKEN));
            assertThat(appender.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                    .contains("/1/members/me").contains("key=***&token=***"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
    }

    interface Callable {
        void run() throws IOException;
    }

    @Test
    void redactHidesTheValuesOfKeyAndToken() {
        assertThat(HttpTrelloClient.redact("https://api.trello.com/1/members/me?key=abc&token=def&fields=x"))
                .isEqualTo("https://api.trello.com/1/members/me?key=***&token=***&fields=x");
        assertThat(HttpTrelloClient.redact("https://h/p?fields=x&TOKEN=zzz")).isEqualTo("https://h/p?fields=x&TOKEN=***");
    }

    @Test
    void createsACardWithAPostAndAnExactFormBody() throws IOException {
        route("POST /1/cards", Resp.ok("{\"id\": \"c9\", \"shortUrl\": \"https://trello.com/c/abc123\"}"));

        TrelloClient.CreatedCard created = client.createCard(KEY, TOKEN, "l1", "Fix the \"login\" page", "Steps & notes\nline 2");

        assertThat(created).isEqualTo(new TrelloClient.CreatedCard("c9", "https://trello.com/c/abc123"));
        Req request = requests.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.contentType()).isEqualTo("application/x-www-form-urlencoded");
        assertThat(request.query()).containsOnlyKeys("key", "token");
        assertThat(form(request.body())).containsExactly(
                Map.entry("idList", "l1"),
                Map.entry("name", "Fix the \"login\" page"),
                Map.entry("desc", "Steps & notes\nline 2"));
    }

    @Test
    void aFailedCreateIsAnErrorAndAnUnauthorisedOneIsTheReconnectMessage() {
        route("POST /1/cards", Resp.ok("{\"nope\": true}"), new Resp(401, "unauthorized", Map.of()));

        assertThatThrownBy(() -> client.createCard(KEY, TOKEN, "l1", "T", "D")).isInstanceOf(IOException.class)
                .hasMessage("Trello did not create the card");
        assertThatThrownBy(() -> client.createCard(KEY, TOKEN, "l1", "T", "D")).isInstanceOf(SourceAccessException.class)
                .hasMessage(HttpTrelloClient.REJECTED);
    }

    @Test
    void countsCardsAndStopsAtTheCap() throws IOException {
        route("GET /1/boards/b1/cards", Resp.ok("[{\"id\": \"c1\"}, {\"id\": \"c2\"}, {\"id\": \"c3\"}]"));

        assertThat(client.countCards(KEY, TOKEN, "b1", 100)).isEqualTo(3);
        assertThat(client.countCards(KEY, TOKEN, "b1", 2)).isEqualTo(2);
    }

    @Test
    void anIdThatIsNotATrelloIdIsRefusedBeforeAnyRequest() {
        assertThatThrownBy(() -> client.board(KEY, TOKEN, "../members/me")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> client.lists(KEY, TOKEN, "b1/../x")).isInstanceOf(IOException.class);

        assertThat(requests).isEmpty();
    }
}
