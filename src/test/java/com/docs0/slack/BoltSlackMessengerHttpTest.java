package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.slack.SlackMessenger.ChannelInfo;
import com.docs0.slack.SlackMessenger.SlackCallException;
import com.docs0.slack.SlackMessenger.SlackMessage;
import com.docs0.slack.SlackMessenger.SlackUser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real Slack client code against a local stand-in for the Web API. Proves the method names,
 * parameters sent, paging, rate-limit waiting and how replies are read; it cannot prove that Slack
 * itself accepts these calls or sends exactly these fields.
 */
class BoltSlackMessengerHttpTest {

    private static final String TOKEN = "xoxb-SECRET-TOKEN";

    record Req(String method, Map<String, String> params, String authorization) {}

    record Resp(int status, String body, Map<String, String> headers) {
        static Resp ok(String body) {
            return new Resp(200, body, Map.of());
        }
    }

    HttpServer server;
    final List<Req> requests = new ArrayList<>();
    final Deque<Resp> script = new ArrayDeque<>();
    final List<Long> sleeps = new ArrayList<>();
    volatile Resp authResponse = Resp.ok("{\"ok\":true,\"team_id\":\"T1\",\"user_id\":\"UBOT\"}");
    volatile int authCalls;
    BoltSlackMessenger messenger;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = new LinkedHashMap<>();
            String query = body.isEmpty() ? exchange.getRequestURI().getRawQuery() : body;
            if (query != null) {
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                                URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                    }
                }
            }
            String method = exchange.getRequestURI().getPath().substring("/api/".length());
            // The Slack client asks auth.test by itself once per token to learn the team; keep it out of the script.
            boolean auth = method.equals("auth.test");
            if (!auth) {
                requests.add(new Req(method, params, exchange.getRequestHeaders().getFirst("Authorization")));
            } else {
                authCalls++;
            }
            Resp resp = auth ? authResponse : script.poll();
            if (resp == null) {
                resp = new Resp(500, "no scripted reply", Map.of());
            }
            byte[] bytes = resp.body().getBytes(StandardCharsets.UTF_8);
            resp.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(resp.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        messenger = new BoltSlackMessenger(TOKEN, "http://127.0.0.1:" + server.getAddress().getPort() + "/api/",
                sleeps::add);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void reply(String json) {
        script.add(Resp.ok(json));
    }

    @Test
    void channelsAreListedPageByPageWithTypesAndTheTokenSent() {
        reply("""
                {"ok":true,"channels":[
                  {"id":"C1","name":"general","is_private":false,"is_member":true,"is_archived":false,"num_members":40},
                  {"id":"C2","name":"old","is_private":false,"is_member":false,"is_archived":true,"num_members":2}],
                 "response_metadata":{"next_cursor":"abc"}}""");
        reply("""
                {"ok":true,"channels":[
                  {"id":"G1","name":"leads","is_private":true,"is_member":true,"is_archived":false,"num_members":5}],
                 "response_metadata":{"next_cursor":""}}""");

        List<ChannelInfo> channels = messenger.listChannels();

        assertThat(channels).containsExactly(new ChannelInfo("C1", "general", false, true, 40),
                new ChannelInfo("G1", "leads", true, true, 5));
        assertThat(requests).extracting(Req::method).containsExactly("conversations.list", "conversations.list");
        assertThat(requests.get(0).params()).containsEntry("types", "public_channel,private_channel")
                .containsEntry("exclude_archived", "1");
        assertThat(requests.get(1).params()).containsEntry("cursor", "abc");
        assertThat(requests.get(0).authorization()).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void historyAsksFromTheGivenInstantAndFollowsPaging() {
        reply("""
                {"ok":true,"has_more":true,"messages":[
                  {"type":"message","ts":"1793614800.000200","user":"U2","text":"second"},
                  {"type":"message","ts":"1793614500.000100","user":"U1","text":"root","thread_ts":"1793614500.000100",
                   "reply_count":3,"latest_reply":"1793614900.000300","edited":{"user":"U1","ts":"1793614600.000000"},
                   "files":[{"title":"Q4 plan.pdf","name":"q4.pdf"},{"name":"only-name.png"}]}],
                 "response_metadata":{"next_cursor":"next1"}}""");
        reply("""
                {"ok":true,"has_more":false,"messages":[
                  {"type":"message","subtype":"channel_join","ts":"1793614000.000100","user":"U3","text":"<@U3> has joined"}]}""");

        List<SlackMessage> messages = messenger.history("C1", Instant.parse("2026-11-02T00:00:00Z"));

        assertThat(requests).extracting(Req::method).containsExactly("conversations.history", "conversations.history");
        assertThat(requests.get(0).params()).containsEntry("channel", "C1").containsEntry("oldest", "1793577600.000000")
                .containsEntry("limit", "200");
        assertThat(requests.get(1).params()).containsEntry("cursor", "next1");
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).isEqualTo(new SlackMessage("1793614800.000200", null, "U2", null, null, null, "second",
                0, null, null, List.of()));
        assertThat(messages.get(1).threadTs()).isEqualTo("1793614500.000100");
        assertThat(messages.get(1).replyCount()).isEqualTo(3);
        assertThat(messages.get(1).latestReply()).isEqualTo("1793614900.000300");
        assertThat(messages.get(1).editedTs()).isEqualTo("1793614600.000000");
        assertThat(messages.get(1).fileTitles()).containsExactly("Q4 plan.pdf", "only-name.png");
        assertThat(messages.get(2).subtype()).isEqualTo("channel_join");
    }

    @Test
    void repliesAreReadForOneThreadWithPaging() {
        reply("""
                {"ok":true,"has_more":true,"messages":[{"ts":"1.1","thread_ts":"1.1","user":"U1","text":"root"}],
                 "response_metadata":{"next_cursor":"c2"}}""");
        reply("""
                {"ok":true,"has_more":false,"messages":[{"ts":"1.2","thread_ts":"1.1","user":"U2","text":"reply"}]}""");

        List<SlackMessage> thread = messenger.replies("C1", "1.1");

        assertThat(thread).extracting(SlackMessage::text).containsExactly("root", "reply");
        assertThat(requests.get(0).method()).isEqualTo("conversations.replies");
        assertThat(requests.get(0).params()).containsEntry("channel", "C1").containsEntry("ts", "1.1");
        assertThat(requests.get(1).params()).containsEntry("cursor", "c2");
    }

    @Test
    void aRateLimitIsWaitedOutAsSlackAsksAndTheCallIsRepeated() {
        script.add(new Resp(429, "{\"ok\":false,\"error\":\"ratelimited\"}", Map.of("Retry-After", "7")));
        script.add(new Resp(429, "{\"ok\":false,\"error\":\"ratelimited\"}", Map.of("Retry-After", "2")));
        reply("{\"ok\":true,\"has_more\":false,\"messages\":[{\"ts\":\"1.1\",\"user\":\"U1\",\"text\":\"hi\"}]}");

        List<SlackMessage> messages = messenger.history("C1", Instant.parse("2026-11-02T00:00:00Z"));

        assertThat(messages).hasSize(1);
        assertThat(sleeps).containsExactly(7L, 2L);
        assertThat(requests).hasSize(3);
    }

    @Test
    void aRateLimitThatNeverEndsGivesUpAfterABoundedNumberOfRetries() {
        for (int i = 0; i < 20; i++) {
            script.add(new Resp(429, "{\"ok\":false,\"error\":\"ratelimited\"}", Map.of("Retry-After", "1")));
        }

        assertThatThrownBy(() -> messenger.history("C1", Instant.EPOCH)).isInstanceOf(SlackCallException.class);

        assertThat(requests).hasSize(BoltSlackMessenger.MAX_RATE_LIMIT_RETRIES + 1);
        assertThat(sleeps).hasSize(BoltSlackMessenger.MAX_RATE_LIMIT_RETRIES);
    }

    @Test
    void aHugeOrMissingRetryAfterIsBounded() {
        script.add(new Resp(429, "{\"ok\":false,\"error\":\"ratelimited\"}", Map.of("Retry-After", "100000")));
        script.add(new Resp(429, "{\"ok\":false,\"error\":\"ratelimited\"}", Map.of()));
        reply("{\"ok\":true,\"messages\":[]}");

        messenger.replies("C1", "1.1");

        assertThat(sleeps).containsExactly(BoltSlackMessenger.MAX_RETRY_AFTER_SECONDS, 5L);
    }

    @Test
    void otherSlackErrorsKeepTheirCodeAndAreNotRetried() {
        reply("{\"ok\":false,\"error\":\"not_in_channel\"}");

        assertThatThrownBy(() -> messenger.history("C1", Instant.EPOCH)).isInstanceOfSatisfying(SlackCallException.class,
                e -> assertThat(e.code()).isEqualTo("not_in_channel"));
        assertThat(requests).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void joiningAChannelAndItsMissingScope() {
        reply("{\"ok\":true,\"channel\":{\"id\":\"C1\"}}");
        reply("{\"ok\":false,\"error\":\"missing_scope\",\"needed\":\"channels:join\"}");

        messenger.joinChannel("C1");
        assertThatThrownBy(() -> messenger.joinChannel("C2")).isInstanceOfSatisfying(SlackCallException.class,
                e -> assertThat(e.code()).isEqualTo("missing_scope"));

        assertThat(requests.get(0).method()).isEqualTo("conversations.join");
        assertThat(requests.get(0).params()).containsEntry("channel", "C1");
    }

    @Test
    void permalinkIsFetchedForOneMessage() {
        reply("{\"ok\":true,\"permalink\":\"https://acme.slack.com/archives/C1/p1793614500000100\"}");

        assertThat(messenger.permalink("C1", "1793614500.000100"))
                .isEqualTo("https://acme.slack.com/archives/C1/p1793614500000100");
        assertThat(requests.get(0).method()).isEqualTo("chat.getPermalink");
        assertThat(requests.get(0).params()).containsEntry("channel", "C1").containsEntry("message_ts", "1793614500.000100");
    }

    @Test
    void userInfoReadsEmailNameBotAndDeactivatedFlags() {
        reply("""
                {"ok":true,"user":{"id":"U1","name":"priya","real_name":"Priya Shah","deleted":false,"is_bot":false,
                 "profile":{"display_name":"Priya","email":"Priya@Example.com"}}}""");
        reply("""
                {"ok":true,"user":{"id":"U2","name":"deployer","real_name":"Deploy Bot","deleted":true,"is_bot":true,
                 "profile":{"display_name":""}}}""");

        SlackUser person = messenger.userInfo("U1");
        SlackUser bot = messenger.userInfo("U2");

        assertThat(person).isEqualTo(new SlackUser("U1", "Priya@Example.com", "Priya", false, false));
        assertThat(bot).isEqualTo(new SlackUser("U2", null, "Deploy Bot", true, true));
        assertThat(requests.get(0).method()).isEqualTo("users.info");
        assertThat(requests.get(0).params()).containsEntry("user", "U1");
    }

    @Test
    void grantedScopesComeFromTheAuthTestResponseHeader() {
        authResponse = new Resp(200, "{\"ok\":true,\"team\":\"Acme\",\"user\":\"docs0\",\"user_id\":\"UBOT\"}",
                Map.of("x-oauth-scopes", "chat:write,users:read,users:read.email"));

        assertThat(messenger.botScopes()).containsExactlyInAnyOrder("chat:write", "users:read", "users:read.email");
        assertThat(messenger.botUserId()).isEqualTo("UBOT");
    }

    @Test
    void unknownScopesAreAnEmptySetNotAFailure() {
        assertThat(messenger.botScopes()).isEmpty();
        authResponse = Resp.ok("{\"ok\":false,\"error\":\"invalid_auth\"}");
        assertThat(new BoltSlackMessenger(TOKEN, "http://127.0.0.1:" + server.getAddress().getPort() + "/api/", sleeps::add)
                .botScopes()).isEmpty();
    }
}
