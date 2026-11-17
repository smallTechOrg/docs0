package com.docs0.source.gmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.source.SourceAccessException;
import com.docs0.source.gmail.GmailClient.DirectoryUser;
import com.docs0.source.gmail.GmailClient.MailThread;
import com.docs0.source.gmail.GmailClient.Message;
import com.docs0.source.gmail.GmailClient.ThreadPage;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real Gmail client against a local stand-in for the Gmail and Admin SDK REST APIs. Proves what is
 * sent (method, path, query, bearer token), how MIME is read, and how errors, paging and rate limits are
 * handled. It cannot prove that Google's real replies match the documented shapes used here.
 */
class HttpGmailClientTest {

    private static final String KEY = "{\"fake\":\"key-material-PRIVATE-123\"}";
    private static final String ANA = "ana@example.com";
    private static final Instant T0 = Instant.parse("2026-11-17T09:00:00Z");

    record Req(String method, String path, String query, String authorization) {}

    record Resp(int status, String body, Map<String, String> headers) {
        static Resp ok(String body) {
            return new Resp(200, body, Map.of());
        }

        static Resp error(int status, String body) {
            return new Resp(status, body, Map.of());
        }
    }

    /** Hands out tokens "tok-N" and remembers who they were for. */
    static final class FakeTokens implements TokenSource {
        final List<String> issued = new ArrayList<>();
        Duration lifetime = Duration.ofHours(1);
        volatile Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

        @Override
        public Token token(String keyJson, String subject, String scope) {
            issued.add(subject + "|" + scope);
            return new Token("tok-" + issued.size(), clock.instant().plus(lifetime));
        }
    }

    HttpServer server;
    final List<Req> requests = new ArrayList<>();
    final Deque<Resp> script = new ArrayDeque<>();
    final List<Long> sleeps = new ArrayList<>();
    final FakeTokens tokens = new FakeTokens();
    final AtomicReference<Instant> now = new AtomicReference<>(T0);
    HttpGmailClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Req(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("Authorization")));
            Resp resp = script.poll();
            if (resp == null) {
                resp = Resp.error(500, "no scripted reply");
            }
            byte[] bytes = resp.body().getBytes(StandardCharsets.UTF_8);
            resp.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(resp.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
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
        tokens.clock = clock;
        client = new HttpGmailClient(base, base, tokens, sleeps::add, clock);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    // ---- JSON builders ---------------------------------------------------------------------

    static String b64(String text, Charset charset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(charset));
    }

    static String b64(String text) {
        return b64(text, StandardCharsets.UTF_8);
    }

    static String part(String mime, String data) {
        return "{\"mimeType\":\"" + mime + "\",\"filename\":\"\",\"body\":{\"size\":1,\"data\":\"" + data + "\"}}";
    }

    static String multipart(String mime, String... parts) {
        return "{\"mimeType\":\"" + mime + "\",\"filename\":\"\",\"body\":{\"size\":0},\"parts\":["
                + String.join(",", parts) + "]}";
    }

    static String attachment(String name) {
        return "{\"mimeType\":\"application/pdf\",\"filename\":\"" + name
                + "\",\"body\":{\"attachmentId\":\"ANGjdJ_secret\",\"size\":99999}}";
    }

    static String message(String id, String labels, String internalDate, String subject, String payload) {
        String headers = "[{\"name\":\"From\",\"value\":\"Ana <ana@example.com>\"},{\"name\":\"To\",\"value\":\"me@example.com\"},"
                + "{\"name\":\"Cc\",\"value\":\"boss@example.com\"},{\"name\":\"Date\",\"value\":\"Mon, 3 Nov 2026 09:00:00 +0000\"},"
                + "{\"name\":\"Subject\",\"value\":\"" + subject + "\"}]";
        String withHeaders = payload.replaceFirst("\\{", "{\"headers\":" + headers.replace("\\", "\\\\") + ",");
        return "{\"id\":\"" + id + "\",\"threadId\":\"t1\",\"labelIds\":" + labels + ",\"internalDate\":\"" + internalDate
                + "\",\"payload\":" + withHeaders + "}";
    }

    static String thread(String historyId, String... messages) {
        return "{\"id\":\"t1\",\"historyId\":\"" + historyId + "\",\"messages\":[" + String.join(",", messages) + "]}";
    }

    private MailThread read(String... messages) throws IOException {
        script.add(Resp.ok(thread("777", messages)));
        return client.thread(KEY, ANA, "t1");
    }

    private static String textOf(MailThread t, int index) {
        return t.messages().get(index).text();
    }

    // ---- requests and tokens ---------------------------------------------------------------

    @Test
    void profileCallsTheMailboxWithItsOwnBearerTokenAndReadsTheCounts() throws Exception {
        script.add(Resp.ok("{\"emailAddress\":\"ana@example.com\",\"messagesTotal\":1234,\"threadsTotal\":400,\"historyId\":\"9\"}"));

        var profile = client.profile(KEY, ANA);

        assertThat(profile.messagesTotal()).isEqualTo(1234);
        assertThat(profile.threadsTotal()).isEqualTo(400);
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).method()).isEqualTo("GET");
        assertThat(requests.get(0).path()).isEqualTo("/gmail/v1/users/ana%40example.com/profile");
        assertThat(requests.get(0).authorization()).isEqualTo("Bearer tok-1");
        assertThat(tokens.issued).containsExactly(ANA + "|" + HttpGmailClient.GMAIL_SCOPE);
    }

    @Test
    void everyMailboxGetsItsOwnTokenAndATokenIsReusedUntilShortlyBeforeItExpires() throws Exception {
        for (int i = 0; i < 6; i++) {
            script.add(Resp.ok("{\"messagesTotal\":1}"));
        }

        client.profile(KEY, ANA);
        client.profile(KEY, ANA);
        client.profile(KEY, "bob@example.com");
        assertThat(tokens.issued).containsExactly(ANA + "|" + HttpGmailClient.GMAIL_SCOPE,
                "bob@example.com|" + HttpGmailClient.GMAIL_SCOPE);
        assertThat(requests.get(2).authorization()).isEqualTo("Bearer tok-2");

        now.set(T0.plus(Duration.ofMinutes(59))); // inside the two minutes before expiry: a new token
        client.profile(KEY, ANA);
        assertThat(tokens.issued).hasSize(3);
        assertThat(requests.get(3).authorization()).isEqualTo("Bearer tok-3");

        now.set(T0.plus(Duration.ofMinutes(60 + 10))); // far past the first token's life
        client.profile(KEY, "bob@example.com");
        assertThat(tokens.issued).hasSize(4);
    }

    @Test
    void aRefusedCachedTokenIsReplacedOnceBeforeGivingUp() throws Exception {
        script.add(Resp.ok("{\"messagesTotal\":1}"));
        script.add(Resp.error(401, "{\"error\":{\"code\":401,\"message\":\"Invalid Credentials\"}}"));
        script.add(Resp.ok("{\"messagesTotal\":2}"));
        client.profile(KEY, ANA);

        assertThat(client.profile(KEY, ANA).messagesTotal()).isEqualTo(2);
        assertThat(tokens.issued).hasSize(2);
        assertThat(requests.get(2).authorization()).isEqualTo("Bearer tok-2");

        script.add(Resp.error(401, "{}"));
        script.add(Resp.error(401, "{}"));
        assertThatThrownBy(() -> client.profile(KEY, ANA)).isInstanceOf(SourceAccessException.class);
    }

    @Test
    void onlyGetRequestsAreEverSent() throws Exception {
        script.add(Resp.ok("{\"threads\":[]}"));
        script.add(Resp.ok(thread("1", message("m1", "[\"INBOX\"]", "1762160400000", "Hi", part("text/plain", b64("hello"))))));
        script.add(Resp.ok("{\"users\":[]}"));

        client.threads(KEY, ANA, "x", null);
        client.thread(KEY, ANA, "t1");
        client.directory(KEY, "admin@example.com");

        assertThat(requests).extracting(Req::method).containsOnly("GET");
    }

    // ---- listing ---------------------------------------------------------------------------

    @Test
    void threadListsArePagedAndCarryTheQueryAndTheHistoryIds() throws Exception {
        script.add(Resp.ok("{\"threads\":[{\"id\":\"a1\",\"historyId\":\"11\",\"snippet\":\"x\"},{\"id\":\"a2\",\"historyId\":\"12\"}],\"nextPageToken\":\"PAGE/2==\"}"));
        script.add(Resp.ok("{\"threads\":[{\"id\":\"a3\",\"historyId\":\"13\"}],\"resultSizeEstimate\":3}"));
        String query = "after:1 -in:spam -category:promotions";

        ThreadPage first = client.threads(KEY, ANA, query, null);
        ThreadPage second = client.threads(KEY, ANA, query, first.nextPageToken());

        assertThat(first.threads()).extracting(GmailClient.ThreadRef::id).containsExactly("a1", "a2");
        assertThat(first.threads().get(1).historyId()).isEqualTo("12");
        assertThat(first.nextPageToken()).isEqualTo("PAGE/2==");
        assertThat(second.threads()).extracting(GmailClient.ThreadRef::id).containsExactly("a3");
        assertThat(second.nextPageToken()).isNull();
        assertThat(requests.get(0).path()).isEqualTo("/gmail/v1/users/ana%40example.com/threads");
        assertThat(requests.get(0).query()).contains("maxResults=100")
                .contains("q=after%3A1+-in%3Aspam+-category%3Apromotions").doesNotContain("pageToken");
        assertThat(requests.get(1).query()).contains("pageToken=PAGE%2F2%3D%3D");
    }

    @Test
    void anEmptyMailboxListsNoThreads() throws Exception {
        script.add(Resp.ok("{\"resultSizeEstimate\":0}"));

        assertThat(client.threads(KEY, ANA, "q", null).threads()).isEmpty();
    }

    // ---- MIME ------------------------------------------------------------------------------

    @Test
    void aPlainAndHtmlAlternativeGivesThePlainTextAndTheHeaders() throws Exception {
        String payload = multipart("multipart/alternative", part("text/plain", b64("Plain version")),
                part("text/html", b64("<p>HTML <b>version</b></p>")));

        MailThread t = read(message("m1", "[\"INBOX\"]", "1762160400000", "Launch plan", payload));

        assertThat(requests.get(0).path()).isEqualTo("/gmail/v1/users/ana%40example.com/threads/t1");
        assertThat(requests.get(0).query()).isEqualTo("format=full");
        assertThat(t.historyId()).isEqualTo("777");
        assertThat(t.subject()).isEqualTo("Launch plan");
        Message m = t.messages().get(0);
        assertThat(m.text()).isEqualTo("Plain version");
        assertThat(m.from()).isEqualTo("Ana <ana@example.com>");
        assertThat(m.to()).isEqualTo("me@example.com");
        assertThat(m.cc()).isEqualTo("boss@example.com");
        assertThat(m.dateHeader()).isEqualTo("Mon, 3 Nov 2026 09:00:00 +0000");
        assertThat(m.date()).isEqualTo(Instant.ofEpochMilli(1762160400000L));
    }

    @Test
    void nestedMultipartPartsAreWalkedInOrderAndAttachmentsAreOnlyNamed() throws Exception {
        String body = multipart("multipart/alternative", part("text/plain", b64("Body first line\n\nSecond paragraph")),
                multipart("multipart/related", part("text/html", b64("<p>html copy</p>")),
                        "{\"mimeType\":\"image/png\",\"filename\":\"\",\"body\":{\"attachmentId\":\"inline\",\"size\":5}}"));
        String payload = multipart("multipart/mixed", body, attachment("Budget 2026.pdf"), attachment("notes.txt"));

        MailThread t = read(message("m1", "[\"INBOX\"]", "1762160400000", "Budget", payload));

        Message m = t.messages().get(0);
        assertThat(m.text()).isEqualTo("Body first line\n\nSecond paragraph");
        assertThat(m.attachments()).containsExactly("Budget 2026.pdf", "notes.txt");
        // Only the thread itself was fetched: attachments are never downloaded.
        assertThat(requests).hasSize(1);
        assertThat(m.text()).doesNotContain("ANGjdJ_secret");
    }

    @Test
    void base64UrlCharactersAndNonAsciiTextSurvive() throws Exception {
        // "???" and ">>>" encode with '_' and '-' in base64url; the text also has accents and an emoji.
        String text = "Prix: 5 € ??? >>> naïve café 😀";
        assertThat(b64(text)).contains("_").contains("-");

        MailThread t = read(message("m1", "[]", "1762160400000", "Prix", part("text/plain", b64(text))));

        assertThat(textOf(t, 0)).isEqualTo(text);
    }

    @Test
    void theCharsetOfAPartIsHonoured() throws Exception {
        String latinPart = "{\"mimeType\":\"text/plain\",\"headers\":[{\"name\":\"Content-Type\",\"value\":\"text/plain; charset=\\\"iso-8859-1\\\"\"}],"
                + "\"filename\":\"\",\"body\":{\"data\":\"" + b64("Un café crème", Charset.forName("ISO-8859-1")) + "\"}}";
        String unknownPart = "{\"mimeType\":\"text/plain\",\"headers\":[{\"name\":\"Content-Type\",\"value\":\"text/plain; charset=made-up-9\"}],"
                + "\"filename\":\"\",\"body\":{\"data\":\"" + b64("plain ascii") + "\"}}";

        MailThread t = read(message("m1", "[]", "1762160400000", "Latin", multipart("multipart/mixed", latinPart)),
                message("m2", "[]", "1762160500000", "Unknown", multipart("multipart/mixed", unknownPart)));

        assertThat(textOf(t, 0)).isEqualTo("Un café crème");
        assertThat(textOf(t, 1)).isEqualTo("plain ascii");
    }

    @Test
    void htmlOnlyMailBecomesReadableText() throws Exception {
        String html = "<html><head><style>p{color:red}</style><title>t</title></head><body>"
                + "<p>Hello &amp; welcome,</p><div>Line two<br>Line three &nbsp;&#8364;5 &lt;ok&gt;</div>"
                + "<ul><li>one</li><li>two</li></ul><script>alert(1)</script></body></html>";

        MailThread t = read(message("m1", "[]", "1762160400000", "Html", part("text/html", b64(html))));

        assertThat(textOf(t, 0)).isEqualTo("Hello & welcome,\nLine two\nLine three €5 <ok>\n- one\n- two");
    }

    @Test
    void quotedRepliesAndShortSignaturesAreTrimmedButInlineRepliesAreKept() throws Exception {
        String reply = "Sounds good, let's go ahead.\n\nThanks,\nBob\n\nOn Mon, 3 Nov 2026 at 09:00, Ana <ana@example.com> wrote:\n"
                + "> Can we ship on Friday?\n> Let me know.\n";
        String wrapped = "Yes.\n\nOn Mon, Nov 3, 2026 at 9:00 AM Ana Example\n<ana@example.com> wrote:\n\n> older text\n";
        String inline = "Point one:\n> Is it ready?\nYes, ready.\n> Is it tested?\nNot yet.\n";
        String signed = "See you then.\n-- \nBob Builder\nCEO\n";
        String plainOn = "On the other hand, we should wait.\nOn Friday we decide.";

        MailThread t = read(message("m1", "[]", "1", "R", part("text/plain", b64(reply))),
                message("m2", "[]", "2", "R", part("text/plain", b64(wrapped))),
                message("m3", "[]", "3", "R", part("text/plain", b64(inline))),
                message("m4", "[]", "4", "R", part("text/plain", b64(signed))),
                message("m5", "[]", "5", "R", part("text/plain", b64(plainOn))));

        assertThat(textOf(t, 0)).isEqualTo("Sounds good, let's go ahead.\n\nThanks,\nBob");
        assertThat(textOf(t, 1)).isEqualTo("Yes.");
        assertThat(textOf(t, 2)).isEqualTo("Point one:\nYes, ready.\nNot yet.");
        assertThat(textOf(t, 3)).isEqualTo("See you then.");
        assertThat(textOf(t, 4)).isEqualTo("On the other hand, we should wait.\nOn Friday we decide.");
    }

    @Test
    void htmlQuotedBlocksAreLeftOut() throws Exception {
        String html = "<div>Thanks, agreed.</div><div class=\"gmail_quote\"><div>On Mon, Ana wrote:</div>"
                + "<blockquote class=\"gmail_quote\">Can we ship?<blockquote>earlier</blockquote></blockquote></div>";

        MailThread t = read(message("m1", "[]", "1", "H", part("text/html", b64(html))));

        assertThat(textOf(t, 0)).isEqualTo("Thanks, agreed.");
    }

    @Test
    void spamTrashAndDraftMessagesAreLeftOutOfTheThread() throws Exception {
        MailThread t = read(
                message("m1", "[\"INBOX\"]", "1", "Keep", part("text/plain", b64("kept"))),
                message("m2", "[\"TRASH\"]", "2", "Keep", part("text/plain", b64("trashed"))),
                message("m3", "[\"DRAFT\"]", "3", "Keep", part("text/plain", b64("draft"))),
                message("m4", "[\"SPAM\"]", "4", "Keep", part("text/plain", b64("spam"))));

        assertThat(t.messages()).extracting(Message::text).containsExactly("kept");
    }

    @Test
    void aThreadThatIsGoneIsNullAndAMailboxThatIsNotFoundIsRefused() throws Exception {
        script.add(Resp.error(404, "{\"error\":{\"code\":404,\"message\":\"Requested entity was not found.\",\"errors\":[{\"reason\":\"notFound\"}]}}"));
        assertThat(client.thread(KEY, ANA, "gone")).isNull();

        script.add(Resp.error(404, "{\"error\":{\"code\":404,\"message\":\"Requested entity was not found.\",\"errors\":[{\"reason\":\"notFound\"}]}}"));
        assertThatThrownBy(() -> client.profile(KEY, ANA)).isInstanceOf(SourceAccessException.class)
                .hasMessage("ana@example.com was not found.");
    }

    // ---- errors ----------------------------------------------------------------------------

    @Test
    void delegationAndApiProblemsAreExplainedInPlainWords() throws Exception {
        script.add(Resp.error(403, "{\"error\":{\"code\":403,\"message\":\"Delegation denied for ana@example.com\",\"errors\":[{\"reason\":\"forbidden\"}]}}"));
        assertThatThrownBy(() -> client.profile(KEY, ANA)).isInstanceOf(SourceAccessException.class)
                .hasMessage("Gmail access isn't authorised for ana@example.com yet. Check domain-wide delegation in the Workspace Admin console.");

        script.add(Resp.error(403, "{\"error\":{\"code\":403,\"message\":\"Gmail API has not been used in project 123 before or it is disabled.\",\"errors\":[{\"reason\":\"accessNotConfigured\"}]}}"));
        assertThatThrownBy(() -> client.profile(KEY, ANA)).isInstanceOf(SourceAccessException.class)
                .hasMessage("Gmail API is not enabled for this Google Cloud project.");

        script.add(Resp.error(400, "{\"error\":{\"code\":400,\"message\":\"Mail service not enabled\",\"status\":\"FAILED_PRECONDITION\",\"errors\":[{\"reason\":\"failedPrecondition\"}]}}"));
        assertThatThrownBy(() -> client.profile(KEY, ANA)).isInstanceOf(SourceAccessException.class)
                .hasMessage("ana@example.com does not have Gmail turned on.");

        script.add(Resp.error(502, "<html>bad gateway</html>"));
        script.add(Resp.error(502, "<html>bad gateway</html>"));
        script.add(Resp.error(502, "<html>bad gateway</html>"));
        script.add(Resp.error(502, "<html>bad gateway</html>"));
        script.add(Resp.error(502, "<html>bad gateway</html>"));
        assertThatThrownBy(() -> client.profile(KEY, ANA)).isNotInstanceOf(SourceAccessException.class)
                .hasMessage("Google answered HTTP 502");
    }

    @Test
    void messagesNeverContainTheKeyATokenOrAUrl() throws Exception {
        List<String> messages = new ArrayList<>();
        script.add(Resp.error(403, "{\"error\":{\"message\":\"Bearer tok-1 PRIVATE KEY https://x\",\"errors\":[{\"reason\":\"forbidden\"}]}}"));
        script.add(Resp.error(404, "{\"error\":{\"message\":\"secret details\"}}"));
        script.add(Resp.error(418, "tok-1 teapot"));
        for (int i = 0; i < 3; i++) {
            try {
                client.threads(KEY, ANA, "q", "PAGE-TOKEN-SECRET");
            } catch (IOException e) {
                messages.add(e.getMessage());
            }
        }
        // The server is gone: the connection error names only the class.
        server.stop(0);
        try {
            client.profile(KEY, "carol@example.com");
        } catch (IOException e) {
            messages.add(e.getMessage());
        }

        assertThat(messages).hasSize(4);
        for (String message : messages) {
            assertThat(message).doesNotContain("tok-").doesNotContain("PAGE-TOKEN").doesNotContain("PRIVATE")
                    .doesNotContain("http://").doesNotContain("https://").doesNotContain("127.0.0.1")
                    .doesNotContain("secret").doesNotContain("Bearer");
        }
        assertThat(messages.get(3)).startsWith("Could not reach Google (");
    }

    // ---- backoff ---------------------------------------------------------------------------

    @Test
    void aRateLimitIsWaitedOutHonouringRetryAfter() throws Exception {
        script.add(new Resp(429, "{}", Map.of("Retry-After", "7")));
        script.add(new Resp(503, "{}", Map.of()));
        script.add(Resp.error(403, "{\"error\":{\"errors\":[{\"reason\":\"rateLimitExceeded\"}]}}"));
        script.add(Resp.ok("{\"messagesTotal\":5}"));

        assertThat(client.profile(KEY, ANA).messagesTotal()).isEqualTo(5);

        assertThat(sleeps).containsExactly(7L, 4L, 8L);
        assertThat(requests).hasSize(4);
        assertThat(tokens.issued).hasSize(1);
    }

    @Test
    void retriesAreBoundedAndTheWaitIsCapped() throws Exception {
        for (int i = 0; i < HttpGmailClient.MAX_RETRIES + 1; i++) {
            script.add(new Resp(429, "{}", Map.of("Retry-After", "3600")));
        }

        assertThatThrownBy(() -> client.profile(KEY, ANA)).hasMessageContaining("limiting how fast");

        assertThat(requests).hasSize(HttpGmailClient.MAX_RETRIES + 1);
        assertThat(sleeps).hasSize(HttpGmailClient.MAX_RETRIES).containsOnly(HttpGmailClient.MAX_WAIT_SECONDS);
    }

    // ---- directory -------------------------------------------------------------------------

    @Test
    void theDirectoryIsReadPagedAsTheAdminWithoutSuspendedPeople() throws Exception {
        script.add(Resp.ok("{\"users\":[{\"primaryEmail\":\"Ana@Example.com\",\"name\":{\"fullName\":\"Ana Example\"},\"suspended\":false},"
                + "{\"primaryEmail\":\"old@example.com\",\"name\":{\"fullName\":\"Old\"},\"suspended\":true}],\"nextPageToken\":\"N2\"}"));
        script.add(Resp.ok("{\"users\":[{\"primaryEmail\":\"bob@example.com\",\"name\":{\"fullName\":\"Bob\"}},"
                + "{\"primaryEmail\":\"arch@example.com\",\"archived\":true}]}"));

        List<DirectoryUser> users = client.directory(KEY, "admin@example.com");

        assertThat(users).containsExactly(new DirectoryUser("ana@example.com", "Ana Example"),
                new DirectoryUser("bob@example.com", "Bob"));
        assertThat(requests.get(0).path()).isEqualTo("/admin/directory/v1/users");
        assertThat(requests.get(0).query()).contains("customer=my_customer").contains("query=isSuspended%3Dfalse");
        assertThat(requests.get(1).query()).contains("pageToken=N2");
        assertThat(tokens.issued).containsExactly("admin@example.com|" + HttpGmailClient.DIRECTORY_SCOPE);
    }

    @Test
    void directoryRefusalsAreExplained() {
        script.add(Resp.error(403, "{\"error\":{\"code\":403,\"message\":\"Not Authorized to access this resource/api\",\"errors\":[{\"reason\":\"forbidden\"}]}}"));
        assertThatThrownBy(() -> client.directory(KEY, "admin@example.com")).isInstanceOf(SourceAccessException.class)
                .hasMessageContaining("did not allow the directory lookup").hasMessageContaining("admin.directory.user.readonly");

        script.add(Resp.error(403, "{\"error\":{\"code\":403,\"message\":\"Admin SDK API has not been used in project 1 before or it is disabled.\",\"errors\":[{\"reason\":\"accessNotConfigured\"}]}}"));
        assertThatThrownBy(() -> client.directory(KEY, "admin@example.com")).isInstanceOf(SourceAccessException.class)
                .hasMessage("Admin SDK API is not enabled for this Google Cloud project.");
    }
}
