package com.docs0.source.trello;

import com.docs0.source.SourceAccessException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link TrelloClient} on Trello's REST API; the key and token travel as query parameters. They
 * are never logged (URLs are redacted first) and never part of an exception message. Requests
 * are spaced to stay under Trello's rate limit and a 429 is waited out a few times.
 */
@Component
public class HttpTrelloClient implements TrelloClient {

    public static final String BASE = "https://api.trello.com/1";
    public static final String REJECTED = "Trello rejected the key or token. Reconnect Trello on the Sources page.";

    static final int PAGE = 1000;
    static final int MAX_RETRIES = 4;
    static final long MAX_WAIT_SECONDS = 60;
    /** Trello allows 100 requests per 10 seconds per token; stay a little under. */
    static final int DEFAULT_REQUESTS = 90;
    static final Duration DEFAULT_WINDOW = Duration.ofSeconds(10);
    static final int COMMENTS_PER_CARD = 50;
    static final int MAX_PAGES = 100;

    private static final Logger log = LoggerFactory.getLogger(HttpTrelloClient.class);
    private static final Gson GSON = new Gson();
    private static final Pattern SECRET_PARAMETER = Pattern.compile("(?i)\\b(key|token)=[^&\\s]*");

    private static final String CARD_FIELDS = "name,desc,idList,due,dueComplete,dateLastActivity,shortUrl,labels";

    private final String base;
    private final int maxRequests;
    private final long windowMillis;
    private final LongConsumer sleeperMillis;
    private final LongSupplier nowMillis;
    private final Deque<Long> sent = new ArrayDeque<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    @Autowired
    public HttpTrelloClient() {
        this(BASE, DEFAULT_REQUESTS, DEFAULT_WINDOW, HttpTrelloClient::sleep, System::currentTimeMillis);
    }

    /** For tests: another base URL, a small request budget, and a sleeper and clock to control time. */
    HttpTrelloClient(String base, int maxRequests, Duration window, LongConsumer sleeperMillis, LongSupplier nowMillis) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.maxRequests = maxRequests;
        this.windowMillis = window.toMillis();
        this.sleeperMillis = sleeperMillis;
        this.nowMillis = nowMillis;
    }

    /** The URL with the key and token values hidden; the only form that is ever logged. */
    static String redact(String url) {
        return SECRET_PARAMETER.matcher(url).replaceAll("$1=***");
    }

    @Override
    public Account me(String key, String token) throws IOException {
        JsonObject me = object(get(key, token, "/members/me", Map.of("fields", "fullName,username")));
        return new Account(text(me, "fullName"), text(me, "username"));
    }

    @Override
    public List<TrelloBoard> boards(String key, String token) throws IOException {
        JsonArray array = array(get(key, token, "/members/me/boards", Map.of("filter", "open", "fields", "name")));
        List<TrelloBoard> boards = new ArrayList<>();
        for (JsonElement e : array) {
            if (e.isJsonObject()) {
                boards.add(new TrelloBoard(text(e.getAsJsonObject(), "id"), text(e.getAsJsonObject(), "name")));
            }
        }
        return boards;
    }

    @Override
    public List<BoardList> lists(String key, String token, String boardId) throws IOException {
        JsonArray array = array(get(key, token, "/boards/" + id(boardId) + "/lists",
                Map.of("filter", "open", "fields", "name")));
        List<BoardList> lists = new ArrayList<>();
        for (JsonElement e : array) {
            if (e.isJsonObject()) {
                lists.add(new BoardList(text(e.getAsJsonObject(), "id"), text(e.getAsJsonObject(), "name")));
            }
        }
        return lists;
    }

    @Override
    public BoardContent board(String key, String token, String boardId) throws IOException {
        JsonObject board = object(get(key, token, "/boards/" + id(boardId),
                Map.of("fields", "name,desc,shortUrl,dateLastActivity")));
        List<BoardList> lists = lists(key, token, boardId);
        Map<String, List<Comment>> comments = comments(key, token, boardId);

        List<Card> cards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String before = null;
        boolean complete = false;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("filter", "open");
            query.put("fields", CARD_FIELDS);
            query.put("members", "true");
            query.put("member_fields", "fullName,username");
            query.put("checklists", "all");
            query.put("limit", String.valueOf(PAGE));
            if (before != null) {
                query.put("before", before);
            }
            JsonArray array = array(get(key, token, "/boards/" + id(boardId) + "/cards", query));
            String lastId = null;
            int fresh = 0;
            for (JsonElement e : array) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject c = e.getAsJsonObject();
                lastId = text(c, "id");
                if (seen.add(lastId)) {
                    fresh++;
                    cards.add(card(c, comments.getOrDefault(lastId, List.of())));
                }
            }
            if (array.size() < PAGE || fresh == 0) {
                complete = true;
                break;
            }
            before = lastId;
        }
        if (!complete) {
            // Cards missing from a cut-short list would be deleted by the sync: fail instead.
            throw new IOException("Trello returned more cards than docs0 will read");
        }
        return new BoardContent(text(board, "id"), text(board, "name"), text(board, "desc"), text(board, "shortUrl"),
                instant(board, "dateLastActivity"), lists, cards);
    }

    @Override
    public int countCards(String key, String token, String boardId, int cap) throws IOException {
        int count = 0;
        Set<String> seen = new HashSet<>();
        String before = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("filter", "open");
            query.put("fields", "id");
            query.put("limit", String.valueOf(PAGE));
            if (before != null) {
                query.put("before", before);
            }
            JsonArray array = array(get(key, token, "/boards/" + id(boardId) + "/cards", query));
            String lastId = null;
            int fresh = 0;
            for (JsonElement e : array) {
                if (e.isJsonObject()) {
                    lastId = text(e.getAsJsonObject(), "id");
                    if (seen.add(lastId)) {
                        fresh++;
                    }
                }
            }
            count += fresh;
            if (count >= cap) {
                return cap;
            }
            if (array.size() < PAGE || fresh == 0) {
                break;
            }
            before = lastId;
        }
        return count;
    }

    @Override
    public CreatedCard createCard(String key, String token, String listId, String name, String desc)
            throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("idList", listId);
        form.put("name", name);
        form.put("desc", desc == null ? "" : desc);
        JsonObject card = object(send(key, token, "POST", "/cards", Map.of(), form));
        String id = text(card, "id");
        String url = text(card, "shortUrl") != null ? text(card, "shortUrl") : text(card, "url");
        if (id == null || url == null) {
            throw new IOException("Trello did not create the card");
        }
        return new CreatedCard(id, url);
    }

    // ---- parsing ---------------------------------------------------------------------------

    /** Comments on the board's open cards, newest first, up to {@link #COMMENTS_PER_CARD} per card. */
    private Map<String, List<Comment>> comments(String key, String token, String boardId) throws IOException {
        Map<String, List<Comment>> byCard = new LinkedHashMap<>();
        String before = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("filter", "commentCard");
            query.put("fields", "data,date,idMemberCreator");
            query.put("memberCreator_fields", "fullName");
            query.put("limit", String.valueOf(PAGE));
            if (before != null) {
                query.put("before", before);
            }
            JsonArray array = array(get(key, token, "/boards/" + id(boardId) + "/actions", query));
            String lastId = null;
            for (JsonElement e : array) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject action = e.getAsJsonObject();
                lastId = text(action, "id");
                JsonObject data = obj(action, "data");
                String cardId = text(obj(data, "card"), "id");
                if (cardId == null) {
                    continue;
                }
                List<Comment> list = byCard.computeIfAbsent(cardId, k -> new ArrayList<>());
                if (list.size() < COMMENTS_PER_CARD) {
                    list.add(new Comment(text(obj(action, "memberCreator"), "fullName"), text(data, "text"),
                            instant(action, "date")));
                }
            }
            if (array.size() < PAGE || lastId == null) {
                break;
            }
            before = lastId;
        }
        return byCard;
    }

    private static Card card(JsonObject c, List<Comment> comments) {
        List<String> labels = new ArrayList<>();
        if (c.get("labels") instanceof JsonArray array) {
            for (JsonElement e : array) {
                if (e.isJsonObject()) {
                    String name = text(e.getAsJsonObject(), "name");
                    labels.add(name == null || name.isBlank() ? text(e.getAsJsonObject(), "color") : name);
                }
            }
        }
        labels.removeIf(l -> l == null || l.isBlank());
        List<String> members = new ArrayList<>();
        if (c.get("members") instanceof JsonArray array) {
            for (JsonElement e : array) {
                if (e.isJsonObject()) {
                    String name = text(e.getAsJsonObject(), "fullName");
                    members.add(name == null || name.isBlank() ? text(e.getAsJsonObject(), "username") : name);
                }
            }
        }
        members.removeIf(m -> m == null || m.isBlank());
        List<Checklist> checklists = new ArrayList<>();
        if (c.get("checklists") instanceof JsonArray array) {
            for (JsonElement e : array) {
                if (!e.isJsonObject()) {
                    continue;
                }
                List<ChecklistItem> items = new ArrayList<>();
                if (e.getAsJsonObject().get("checkItems") instanceof JsonArray itemArray) {
                    for (JsonElement i : itemArray) {
                        if (i.isJsonObject()) {
                            items.add(new ChecklistItem(text(i.getAsJsonObject(), "name"),
                                    "complete".equals(text(i.getAsJsonObject(), "state"))));
                        }
                    }
                }
                checklists.add(new Checklist(text(e.getAsJsonObject(), "name"), items));
            }
        }
        boolean dueComplete = c.has("dueComplete") && c.get("dueComplete").isJsonPrimitive()
                && c.get("dueComplete").getAsBoolean();
        return new Card(text(c, "id"), text(c, "name"), text(c, "desc"), text(c, "idList"), instant(c, "due"),
                dueComplete, instant(c, "dateLastActivity"), text(c, "shortUrl"), labels, members, checklists,
                comments);
    }

    // ---- HTTP ------------------------------------------------------------------------------

    private String get(String key, String token, String path, Map<String, String> query) throws IOException {
        return send(key, token, "GET", path, query, null);
    }

    /** One request with spacing and rate-limit retries. Returns the response body. */
    private String send(String key, String token, String method, String path, Map<String, String> query,
                        Map<String, String> form) throws IOException {
        StringBuilder url = new StringBuilder(base).append(path).append("?key=").append(enc(key))
                .append("&token=").append(enc(token));
        query.forEach((k, v) -> url.append('&').append(k).append('=').append(enc(v)));
        URI uri = URI.create(url.toString());

        for (int attempt = 0; ; attempt++) {
            throttle();
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60));
            if (form == null) {
                request.GET();
            } else {
                StringBuilder body = new StringBuilder();
                form.forEach((k, v) -> body.append(body.isEmpty() ? "" : "&").append(k).append('=').append(enc(v)));
                request.header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            }
            HttpResponse<String> response;
            try {
                response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Trello call interrupted");
            } catch (IOException e) {
                // Class name only: the request URL carries the key and token.
                throw new IOException("Could not reach Trello (" + e.getClass().getSimpleName() + ")");
            }
            int status = response.statusCode();
            log.debug("Trello {} {} -> {}", method, redact(uri.toString()), status);
            if (status == 401) {
                throw new SourceAccessException(REJECTED);
            }
            if (status == 429) {
                if (attempt >= MAX_RETRIES) {
                    throw new IOException("Trello is limiting how fast docs0 can read. Try again in a few minutes.");
                }
                long wait = waitSeconds(response);
                log.info("Trello rate limit, waiting {}s (retry {} of {})", wait, attempt + 1, MAX_RETRIES);
                sleeperMillis.accept(wait * 1000);
                continue;
            }
            if (status == 404) {
                throw new IOException("Trello could not find that board or card (HTTP 404)");
            }
            if (status / 100 != 2) {
                throw new IOException("Trello answered HTTP " + status);
            }
            return response.body();
        }
    }

    /** Waits until fewer than the allowed number of requests were sent in the last window. */
    private synchronized void throttle() {
        long now = nowMillis.getAsLong();
        while (true) {
            while (!sent.isEmpty() && now - sent.peekFirst() >= windowMillis) {
                sent.pollFirst();
            }
            if (sent.size() < maxRequests) {
                break;
            }
            sleeperMillis.accept(windowMillis - (now - sent.peekFirst()));
            now = nowMillis.getAsLong();
        }
        sent.addLast(now);
    }

    private static long waitSeconds(HttpResponse<String> response) {
        long seconds = 10;
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** A Trello id goes into a path: only the characters Trello ids have. */
    private static String id(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9]{1,64}")) {
            throw new IOException("That is not a Trello id");
        }
        return id;
    }

    private static JsonObject object(String body) throws IOException {
        JsonElement element = parse(body);
        if (!element.isJsonObject()) {
            throw new IOException("Trello sent a reply docs0 could not read");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray array(String body) throws IOException {
        JsonElement element = parse(body);
        if (!element.isJsonArray()) {
            throw new IOException("Trello sent a reply docs0 could not read");
        }
        return element.getAsJsonArray();
    }

    private static JsonElement parse(String body) throws IOException {
        try {
            JsonElement element = GSON.fromJson(body, JsonElement.class);
            if (element == null) {
                throw new IOException("Trello sent an empty reply");
            }
            return element;
        } catch (JsonParseException e) {
            throw new IOException("Trello sent a reply docs0 could not read");
        }
    }

    private static JsonObject obj(JsonObject parent, String name) {
        return parent != null && parent.get(name) instanceof JsonObject o ? o : null;
    }

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
}
