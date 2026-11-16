package com.docs0.source.gmail;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link GmailClient} on the Gmail and Admin SDK REST APIs with plain {@code java.net.http} and Gson.
 * Tokens come from a {@link TokenSource} (one per mailbox, kept until shortly before they expire).
 * Rate limits and short server errors are waited out a few times (honouring Retry-After), then the
 * call fails. Messages never contain the key, a token or a URL. Read-only: only GET requests.
 *
 * <p>Paths and JSON fields follow Google's published reference (users.threads.list / get,
 * users.getProfile, directory users.list); see the "mapping" comments if a real run differs.
 */
@Component
public class HttpGmailClient implements GmailClient {

    public static final String GMAIL_BASE = "https://gmail.googleapis.com";
    public static final String ADMIN_BASE = "https://admin.googleapis.com";
    public static final String GMAIL_SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    public static final String DIRECTORY_SCOPE = "https://www.googleapis.com/auth/admin.directory.user.readonly";

    public static final String KEY_REJECTED =
            "Google did not accept the service account key. Paste a new key on the Sources page.";
    public static final String API_NOT_ENABLED = "Gmail API is not enabled for this Google Cloud project.";
    public static final String DIRECTORY_API_NOT_ENABLED =
            "Admin SDK API is not enabled for this Google Cloud project.";
    public static final String DIRECTORY_DENIED = "Google Workspace did not allow the directory lookup. Check that "
            + "the admin email is an administrator and that the admin.directory.user.readonly scope is added to the "
            + "domain-wide delegation.";

    static final int MAX_RETRIES = 4;
    static final long MAX_WAIT_SECONDS = 60;
    private static final long DEFAULT_WAIT_SECONDS = 2;
    private static final Duration REFRESH_BEFORE_EXPIRY = Duration.ofMinutes(2);
    private static final int PAGE_SIZE = 100;
    private static final int MAX_DIRECTORY_PAGES = 100;
    private static final Set<String> RATE_REASONS = Set.of("ratelimitexceeded", "userratelimitexceeded",
            "quotaexceeded", "backenderror");

    private static final Logger log = LoggerFactory.getLogger(HttpGmailClient.class);
    private static final Gson GSON = new Gson();

    private final String gmailBase;
    private final String adminBase;
    private final TokenSource tokens;
    private final LongConsumer sleeper;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Map<String, TokenSource.Token> tokenCache = new ConcurrentHashMap<>();

    @Autowired
    public HttpGmailClient(TokenSource tokens) {
        this(GMAIL_BASE, ADMIN_BASE, tokens, HttpGmailClient::sleepSeconds, Clock.systemUTC());
    }

    /** For tests: other base URLs, a sleeper told how many seconds to wait, and a clock. */
    HttpGmailClient(String gmailBase, String adminBase, TokenSource tokens, LongConsumer sleeper, Clock clock) {
        this.gmailBase = gmailBase;
        this.adminBase = adminBase;
        this.tokens = tokens;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    static String delegation(String email) {
        return "Gmail access isn't authorised for " + email
                + " yet. Check domain-wide delegation in the Workspace Admin console.";
    }

    static String cannotImpersonate(String email) {
        return email + " can't be accessed. The person may not exist or may be suspended.";
    }

    // ---- the calls -------------------------------------------------------------------------

    /** mapping: GET /gmail/v1/users/{email}/profile -> {emailAddress, messagesTotal, threadsTotal, historyId}. */
    @Override
    public Profile profile(String keyJson, String mailbox) throws IOException {
        JsonObject r = object(call(keyJson, mailbox, GMAIL_SCOPE, gmailBase, userPath(mailbox) + "/profile", mailbox, false));
        return new Profile(text(r, "emailAddress"), number(r, "messagesTotal"), number(r, "threadsTotal"));
    }

    /** mapping: GET /gmail/v1/users/{email}/threads?q&maxResults&pageToken -> {threads: [{id, historyId, snippet}], nextPageToken}. */
    @Override
    public ThreadPage threads(String keyJson, String mailbox, String query, String pageToken) throws IOException {
        StringBuilder path = new StringBuilder(userPath(mailbox)).append("/threads?maxResults=").append(PAGE_SIZE);
        if (query != null && !query.isBlank()) {
            path.append("&q=").append(enc(query));
        }
        if (pageToken != null && !pageToken.isBlank()) {
            path.append("&pageToken=").append(enc(pageToken));
        }
        JsonObject r = object(call(keyJson, mailbox, GMAIL_SCOPE, gmailBase, path.toString(), mailbox, false));
        List<ThreadRef> refs = new ArrayList<>();
        for (JsonObject t : objects(r, "threads")) {
            if (text(t, "id") != null) {
                refs.add(new ThreadRef(text(t, "id"), text(t, "historyId")));
            }
        }
        String next = text(r, "nextPageToken");
        return new ThreadPage(refs, next == null || next.isBlank() ? null : next);
    }

    /**
     * mapping: GET /gmail/v1/users/{email}/threads/{id}?format=full -> {id, historyId, messages: [{id,
     * labelIds, internalDate (ms, as text), payload: {mimeType, filename, headers: [{name, value}], body:
     * {size, data (base64url), attachmentId}, parts: [...]}}]}. A 404 means the thread is gone.
     */
    @Override
    public MailThread thread(String keyJson, String mailbox, String threadId) throws IOException {
        JsonElement raw = call(keyJson, mailbox, GMAIL_SCOPE, gmailBase,
                userPath(mailbox) + "/threads/" + enc(threadId) + "?format=full", mailbox, true);
        if (raw == null) {
            return null;
        }
        JsonObject r = object(raw);
        List<Message> messages = new ArrayList<>();
        String subject = null;
        for (JsonObject m : objects(r, "messages")) {
            Set<String> labels = new java.util.HashSet<>();
            if (m.get("labelIds") instanceof JsonArray a) {
                a.forEach(l -> labels.add(l.getAsString()));
            }
            if (labels.contains("SPAM") || labels.contains("TRASH") || labels.contains("DRAFT")) {
                continue;
            }
            JsonObject payload = m.get("payload") instanceof JsonObject p ? p : new JsonObject();
            String messageSubject = header(payload, "Subject");
            if (subject == null && messageSubject != null && !messageSubject.isBlank()) {
                subject = messageSubject;
            }
            List<String> attachments = new ArrayList<>();
            String body = body(payload, attachments);
            Instant date = null;
            try {
                date = Instant.ofEpochMilli(Long.parseLong(text(m, "internalDate")));
            } catch (RuntimeException e) {
                // no usable date
            }
            messages.add(new Message(text(m, "id"), date, header(payload, "From"), header(payload, "To"),
                    header(payload, "Cc"), header(payload, "Date"), body, attachments));
        }
        return new MailThread(text(r, "id"), text(r, "historyId"), subject, messages);
    }

    /**
     * mapping: GET /admin/directory/v1/users?customer=my_customer&query=isSuspended=false&maxResults=200&pageToken
     * -> {users: [{primaryEmail, name: {fullName}, suspended, archived}], nextPageToken}.
     */
    @Override
    public List<DirectoryUser> directory(String keyJson, String adminEmail) throws IOException {
        List<DirectoryUser> users = new ArrayList<>();
        String pageToken = null;
        for (int page = 0; page < MAX_DIRECTORY_PAGES; page++) {
            String path = "/admin/directory/v1/users?customer=my_customer&maxResults=200&orderBy=email&query="
                    + enc("isSuspended=false") + (pageToken == null ? "" : "&pageToken=" + enc(pageToken));
            JsonObject r = object(call(keyJson, adminEmail, DIRECTORY_SCOPE, adminBase, path, null, false));
            for (JsonObject u : objects(r, "users")) {
                String email = text(u, "primaryEmail");
                boolean gone = u.get("suspended") instanceof com.google.gson.JsonPrimitive s && s.isBoolean() && s.getAsBoolean()
                        || u.get("archived") instanceof com.google.gson.JsonPrimitive a && a.isBoolean() && a.getAsBoolean();
                if (email != null && !gone) {
                    users.add(new DirectoryUser(email.toLowerCase(Locale.ROOT),
                            text(u.get("name") instanceof JsonObject n ? n : null, "fullName")));
                }
            }
            pageToken = text(r, "nextPageToken");
            if (pageToken == null || pageToken.isBlank()) {
                return users;
            }
        }
        return users;
    }

    // ---- MIME ------------------------------------------------------------------------------

    private static String body(JsonObject payload, List<String> attachments) {
        return GmailText.trimQuotes(extract(payload, attachments));
    }

    /** The text of a part: plain text preferred over HTML in an alternative, attachments only named. */
    private static String extract(JsonObject part, List<String> attachments) {
        String mime = GmailText.lower(text(part, "mimeType"));
        String filename = text(part, "filename");
        if (filename != null && !filename.isBlank()) {
            attachments.add(filename.strip());
            return "";
        }
        List<JsonObject> children = objects(part, "parts");
        if (mime.startsWith("multipart/") || !children.isEmpty()) {
            if (mime.equals("multipart/alternative")) {
                String plain = null;
                String other = null;
                for (JsonObject child : children) {
                    String t = extract(child, attachments);
                    if (t.isBlank()) {
                        continue;
                    }
                    if (GmailText.lower(text(child, "mimeType")).equals("text/plain") && plain == null) {
                        plain = t;
                    } else {
                        other = t;
                    }
                }
                return plain != null ? plain : other == null ? "" : other;
            }
            StringBuilder joined = new StringBuilder();
            for (JsonObject child : children) {
                String t = extract(child, attachments);
                if (!t.isBlank()) {
                    joined.append(joined.length() == 0 ? "" : "\n\n").append(t.strip());
                }
            }
            return joined.toString();
        }
        JsonObject body = part.get("body") instanceof JsonObject b ? b : null;
        String data = text(body, "data");
        if (mime.equals("text/plain") || mime.isEmpty()) {
            return GmailText.decode(data, header(part, "Content-Type"));
        }
        if (mime.equals("text/html")) {
            return GmailText.htmlToText(GmailText.decode(data, header(part, "Content-Type")));
        }
        return ""; // an inline image or another kind of part: nothing to read
    }

    private static String header(JsonObject part, String name) {
        for (JsonObject h : objects(part, "headers")) {
            String n = text(h, "name");
            if (n != null && n.equalsIgnoreCase(name)) {
                String v = text(h, "value");
                return v == null ? null : v.replaceAll("[\\r\\n]+", " ").strip();
            }
        }
        return null;
    }

    // ---- HTTP plumbing ---------------------------------------------------------------------

    private static String userPath(String mailbox) {
        return "/gmail/v1/users/" + enc(mailbox);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * One GET with a token for {@code subject}. Returns the parsed body; null on a 404 when
     * {@code missingIsNull}. {@code mailbox} is who a 404 or 403 is about (null for the directory).
     */
    private JsonElement call(String keyJson, String subject, String scope, String base, String path, String mailbox,
                             boolean missingIsNull) throws IOException {
        boolean refreshed = false;
        for (int attempt = 0; ; ) {
            TokenSource.Token token = token(keyJson, subject, scope, refreshed);
            HttpResponse<String> response = send(base + path, token.value());
            int status = response.statusCode();
            if (status / 100 == 2) {
                try {
                    JsonElement parsed = GSON.fromJson(response.body(), JsonElement.class);
                    if (parsed == null || parsed instanceof JsonNull) {
                        throw new IOException("Google sent a reply docs0 could not read");
                    }
                    return parsed;
                } catch (JsonParseException e) {
                    throw new IOException("Google sent a reply docs0 could not read");
                }
            }
            if (status == 401 && !refreshed) {
                refreshed = true; // the cached token may have been revoked: ask for a new one once
                continue;
            }
            String reason = reason(response.body());
            boolean rateLimited = status == 429 || status == 500 || status == 502 || status == 503 || status == 504
                    || status == 403 && RATE_REASONS.contains(reason);
            if (rateLimited) {
                if (attempt >= MAX_RETRIES) {
                    throw new IOException(status == 429 || status == 403
                            ? "Google is limiting how fast docs0 can read. Try again in a few minutes."
                            : "Google answered HTTP " + status);
                }
                long wait = waitSeconds(response, attempt);
                log.info("Google answered {}, waiting {}s (retry {} of {})", status, wait, ++attempt, MAX_RETRIES);
                sleeper.accept(wait);
                continue;
            }
            if (status == 404 && missingIsNull) {
                return null;
            }
            throw refusal(status, reason, response.body(), subject, mailbox);
        }
    }

    private IOException refusal(int status, String reason, String body, String subject, String mailbox) {
        boolean directory = mailbox == null;
        String message = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (reason.equals("accessnotconfigured") || message.contains("has not been used in project")
                || message.contains("api has not been used") || message.contains("it is disabled")) {
            return new SourceAccessException(directory ? DIRECTORY_API_NOT_ENABLED : API_NOT_ENABLED);
        }
        if (status == 404 && !directory) {
            return new SourceAccessException(mailbox + " was not found.");
        }
        if (status == 400 && message.contains("failedprecondition")) {
            return new SourceAccessException(mailbox + " does not have Gmail turned on.");
        }
        if (status == 401 || status == 403) {
            return new SourceAccessException(directory ? DIRECTORY_DENIED : delegation(subject));
        }
        return new IOException("Google answered HTTP " + status);
    }

    /** The first error reason of a Google error body, lowercase; empty when there is none. */
    private static String reason(String body) {
        try {
            JsonObject o = GSON.fromJson(body, JsonObject.class);
            JsonObject error = o == null ? null : o.get("error") instanceof JsonObject e ? e : null;
            if (error != null && error.get("errors") instanceof JsonArray a && !a.isEmpty() && a.get(0) instanceof JsonObject first) {
                String r = text(first, "reason");
                if (r != null) {
                    return r.toLowerCase(Locale.ROOT);
                }
            }
            String status = text(error, "status");
            return status == null ? "" : status.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private TokenSource.Token token(String keyJson, String subject, String scope, boolean forceNew) throws IOException {
        String cacheKey = fingerprint(keyJson) + "|" + subject + "|" + scope;
        TokenSource.Token cached = forceNew ? null : tokenCache.get(cacheKey);
        if (cached != null && cached.expiresAt().minus(REFRESH_BEFORE_EXPIRY).isAfter(clock.instant())) {
            return cached;
        }
        tokenCache.remove(cacheKey);
        TokenSource.Token fresh = tokens.token(keyJson, subject, scope);
        if (fresh.expiresAt() == null) {
            fresh = new TokenSource.Token(fresh.value(), clock.instant().plus(Duration.ofMinutes(50)));
        }
        tokenCache.put(cacheKey, fresh);
        return fresh;
    }

    private static String fingerprint(String keyJson) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(keyJson.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> send(String url, String token) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET().build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Google call interrupted");
        } catch (IOException e) {
            // Class name only: the URL and the token do not belong in a message.
            throw new IOException("Could not reach Google (" + e.getClass().getSimpleName() + ")");
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

    private static JsonObject object(JsonElement e) throws IOException {
        if (e instanceof JsonObject o) {
            return o;
        }
        throw new IOException("Google sent a reply docs0 could not read");
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
}
