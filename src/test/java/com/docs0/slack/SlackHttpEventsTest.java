package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docs0.TestDatabase;
import com.docs0.config.Docs0Properties;
import com.docs0.crypto.TokenCipher;
import com.docs0.isolation.TwoTenants;
import com.docs0.isolation.TwoTenants.Side;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.web.slack.SlackEventsController;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Slack's HTTP requests end to end through the controller, the parser and the gateway, with the answering handlers
 * replaced by mocks so the test sees exactly what each tenant's handler is given.
 */
class SlackHttpEventsTest {

    private static final String SECRET = "signing-secret-for-tests";
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private TwoTenants two;
    private Side a;
    private Side b;
    private SlackWorkspaces workspaces;
    private QuestionHandler questions;
    private ReactionHandler reactions;
    private ActionHandler actions;
    private SlackGateway gateway;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        long tenantA = TestDatabase.resetWithTenant();
        two = TwoTenants.seed(jdbc, tenantA);
        a = two.a;
        b = two.b;
        workspaces = new SlackWorkspaces(jdbc, new TokenCipher(DriveTestSetup.ENCRYPTION_KEY));
        questions = mock(QuestionHandler.class);
        reactions = mock(ReactionHandler.class);
        actions = mock(ActionHandler.class);
        when(questions.handle(anyLong(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(questions.followUp(anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(reactions.handle(anyLong(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(actions.handle(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        Docs0Properties props = new Docs0Properties(new Docs0Properties.Tenant("smalltech", "smallTech"), null, null,
                new Docs0Properties.Slack("", "", "client", "client-secret", SECRET), null, null, "",
                "https://docs0.example", "");
        gateway = new SlackGateway(props, workspaces, null, questions, reactions, actions);
        SlackHttpEvents events = new SlackHttpEvents(gateway, workspaces);
        mvc = MockMvcBuilders.standaloneSetup(new SlackEventsController(props, events, CLOCK)).build();
    }

    // ---- request helpers ----

    private static String ts() {
        return Long.toString(NOW.getEpochSecond());
    }

    private static MockHttpServletRequestBuilder signed(String url, String contentType, String body) {
        return post(url).contentType(contentType).content(body)
                .header(SlackSignature.TIMESTAMP_HEADER, ts())
                .header(SlackSignature.SIGNATURE_HEADER,
                        SlackSignature.sign(SECRET, ts(), body.getBytes(StandardCharsets.UTF_8)));
    }

    private void event(String team, String eventId, String eventJson) throws Exception {
        mvc.perform(signed("/slack/events", "application/json", envelope(team, eventId, eventJson)))
                .andExpect(status().isOk()).andExpect(content().string(""));
    }

    private static String envelope(String team, String eventId, String eventJson) {
        return "{\"token\":\"x\",\"team_id\":\"" + team + "\",\"api_app_id\":\"A1\",\"type\":\"event_callback\","
                + "\"event_id\":\"" + eventId + "\",\"event_time\":1763373600,\"event\":" + eventJson + "}";
    }

    private void click(String team, String json) throws Exception {
        String body = "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
        mvc.perform(signed("/slack/interactions", "application/x-www-form-urlencoded", body))
                .andExpect(status().isOk()).andExpect(content().string(""));
    }

    private static final String MENTION = "{\"type\":\"app_mention\",\"user\":\"U1\",\"text\":\"<@UBOT> refund?\","
            + "\"ts\":\"100.2\",\"thread_ts\":\"100.1\",\"channel\":\"C1\",\"event_ts\":\"100.2\"}";

    // ---- verification ----

    @Test
    void answersAUrlVerificationChallengeWhenSignedCorrectly() throws Exception {
        String body = "{\"token\":\"x\",\"challenge\":\"3eZbrw1aBm2rZgRNFdxV2595E9CY3gmdALWMmHkvFXO7tYXAYM8P\","
                + "\"type\":\"url_verification\"}";

        mvc.perform(signed("/slack/events", "application/json", body)).andExpect(status().isOk())
                .andExpect(content().string("3eZbrw1aBm2rZgRNFdxV2595E9CY3gmdALWMmHkvFXO7tYXAYM8P"));
    }

    @Test
    void rejectsRequestsThatAreNotCorrectlySignedWithABareUnauthorized() throws Exception {
        String body = envelope("T_ALPHA", "Ev1", MENTION);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String good = SlackSignature.sign(SECRET, ts(), bytes);
        String old = Long.toString(NOW.minusSeconds(400).getEpochSecond());
        String future = Long.toString(NOW.plusSeconds(400).getEpochSecond());

        MockHttpServletRequestBuilder[] bad = {
                post("/slack/events").contentType("application/json").content(body),
                post("/slack/events").contentType("application/json").content(body)
                        .header(SlackSignature.TIMESTAMP_HEADER, ts()),
                post("/slack/events").contentType("application/json").content(body)
                        .header(SlackSignature.SIGNATURE_HEADER, good),
                post("/slack/events").contentType("application/json").content(body)
                        .header(SlackSignature.TIMESTAMP_HEADER, ts())
                        .header(SlackSignature.SIGNATURE_HEADER, SlackSignature.sign("wrong", ts(), bytes)),
                post("/slack/events").contentType("application/json").content(body.replace("refund", "refunds"))
                        .header(SlackSignature.TIMESTAMP_HEADER, ts()).header(SlackSignature.SIGNATURE_HEADER, good),
                post("/slack/events").contentType("application/json").content(body)
                        .header(SlackSignature.TIMESTAMP_HEADER, old)
                        .header(SlackSignature.SIGNATURE_HEADER, SlackSignature.sign(SECRET, old, bytes)),
                post("/slack/events").contentType("application/json").content(body)
                        .header(SlackSignature.TIMESTAMP_HEADER, future)
                        .header(SlackSignature.SIGNATURE_HEADER, SlackSignature.sign(SECRET, future, bytes)),
                post("/slack/interactions").contentType("application/x-www-form-urlencoded").content("payload={}")};
        for (MockHttpServletRequestBuilder request : bad) {
            mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(content().string(""));
        }
        verify(questions, after(200).never()).handle(anyLong(), any());
    }

    @Test
    void anUnverifiedUrlVerificationGetsNoChallengeBack() throws Exception {
        mvc.perform(post("/slack/events").contentType("application/json")
                        .content("{\"type\":\"url_verification\",\"challenge\":\"abc\"}"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    }

    // ---- routing ----

    @Test
    void routesEachCompanysMentionToItsOwnTenantEvenWithIdenticalChannelAndEventIds() throws Exception {
        event("T_ALPHA", "Ev1", MENTION);
        event("T_BETA", "Ev1", MENTION);

        ArgumentCaptor<IncomingEvent> alpha = ArgumentCaptor.forClass(IncomingEvent.class);
        ArgumentCaptor<IncomingEvent> beta = ArgumentCaptor.forClass(IncomingEvent.class);
        verify(questions, timeout(2000)).handle(eq(a.tenantId()), alpha.capture());
        verify(questions, timeout(2000)).handle(eq(b.tenantId()), beta.capture());
        assertThat(alpha.getValue().channel()).isEqualTo("C1");
        assertThat(alpha.getValue().teamId()).isEqualTo("T_ALPHA");
        assertThat(beta.getValue().channel()).isEqualTo("C1");
        assertThat(beta.getValue().teamId()).isEqualTo("T_BETA");
    }

    @Test
    void ignoresAnUnknownWorkspaceWithOkAndHandsNothingToAnyTenant() throws Exception {
        event("T_NOBODY", "Ev1", MENTION);

        verify(questions, after(300).never()).handle(anyLong(), any());
    }

    @Test
    void ignoresEventsFromAWorkspaceThatRemovedDocs0() throws Exception {
        event("T_BETA", "Ev0", "{\"type\":\"app_uninstalled\"}");
        awaitRevoked("T_BETA");

        event("T_BETA", "Ev1", MENTION);
        event("T_ALPHA", "Ev2", MENTION);

        verify(questions, timeout(2000)).handle(eq(a.tenantId()), any());
        verify(questions, after(300).never()).handle(eq(b.tenantId()), any());
        // The tenant and its data survive.
        assertThat(jdbc.sql("SELECT count(*) FROM document WHERE tenant_id = :t").param("t", b.tenantId())
                .query(Long.class).single()).isGreaterThan(0);
    }

    @Test
    void aBotTokenRevocationRemovesTheWorkspaceButAPersonsDoesNot() throws Exception {
        event("T_ALPHA", "Ev1", "{\"type\":\"tokens_revoked\",\"tokens\":{\"oauth\":[\"U1\"]}}");
        event("T_BETA", "Ev2", "{\"type\":\"tokens_revoked\",\"tokens\":{\"bot\":[\"UBOTBETA\"]}}");

        awaitRevoked("T_BETA");
        assertThat(workspaces.byTeamId("T_ALPHA")).isPresent();
        assertThat(workspaces.byTeamId("T_BETA")).isEmpty();
        assertThat(workspaces.botToken(b.tenantId())).isEmpty();
    }

    private void awaitRevoked(String team) throws InterruptedException {
        for (int i = 0; i < 100 && workspaces.byTeamId(team).isPresent(); i++) {
            Thread.sleep(20);
        }
        assertThat(workspaces.byTeamId(team)).isEmpty();
    }

    // ---- the three-second acknowledgement ----

    @Test
    void acknowledgesAtOnceWhileASlowHandlerIsStillRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(questions.handle(anyLong(), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return CompletableFuture.completedFuture(null);
        });
        try {
            long before = System.nanoTime();
            event("T_ALPHA", "Ev1", MENTION);
            long millis = (System.nanoTime() - before) / 1_000_000;

            assertThat(started.await(2, TimeUnit.SECONDS)).as("the handler was started").isTrue();
            assertThat(millis).as("the response did not wait for the handler").isLessThan(1500);
        } finally {
            release.countDown();
        }
    }

    // ---- Slack's retries ----

    @Test
    void aRetryOfAnEventAlreadySeenIsDroppedButANewEventWithTheRetryHeaderIsNot() throws Exception {
        String body = envelope("T_ALPHA", "EvRetry", MENTION);
        mvc.perform(signed("/slack/events", "application/json", body)).andExpect(status().isOk());
        verify(questions, timeout(2000)).handle(eq(a.tenantId()), any());

        mvc.perform(signed("/slack/events", "application/json", body).header("X-Slack-Retry-Num", "1")
                        .header("X-Slack-Retry-Reason", "http_timeout"))
                .andExpect(status().isOk()).andExpect(content().string(""));
        verify(questions, after(300).times(1)).handle(eq(a.tenantId()), any());

        mvc.perform(signed("/slack/events", "application/json", envelope("T_ALPHA", "EvOther", MENTION))
                .header("X-Slack-Retry-Num", "1")).andExpect(status().isOk());
        verify(questions, timeout(2000).times(2)).handle(eq(a.tenantId()), any());
    }

    // ---- every event type maps like Socket Mode ----

    @Test
    void messageEventsMapExactlyAsTheSocketModePathMapsThem() throws Exception {
        String dm = "{\"type\":\"message\",\"channel\":\"D1\",\"channel_type\":\"im\",\"user\":\"U1\","
                + "\"text\":\"hi\",\"ts\":\"200.1\",\"event_ts\":\"200.1\"}";
        String thread = "{\"type\":\"message\",\"channel\":\"C1\",\"channel_type\":\"channel\",\"user\":\"U1\","
                + "\"text\":\"and?\",\"ts\":\"100.3\",\"thread_ts\":\"100.1\"}";
        String top = "{\"type\":\"message\",\"channel\":\"C1\",\"channel_type\":\"channel\",\"user\":\"U1\","
                + "\"text\":\"how do refunds work?\",\"ts\":\"300.1\"}";
        event("T_ALPHA", "EvDm", dm);
        event("T_ALPHA", "EvThread", thread);
        event("T_ALPHA", "EvTop", top);
        event("T_ALPHA", "EvMention", MENTION);

        ArgumentCaptor<IncomingEvent> seen = ArgumentCaptor.forClass(IncomingEvent.class);
        verify(questions, timeout(2000).times(4)).handle(eq(a.tenantId()), seen.capture());
        // What Socket Mode hands over: the shared mapping applied to the SDK model objects.
        var gson = com.slack.api.util.json.GsonFactory.createSnakeCase();
        assertThat(seen.getAllValues()).containsExactlyInAnyOrder(
                SlackGateway.fromMessage(gson.fromJson(dm, com.slack.api.model.event.MessageEvent.class), "EvDm")
                        .withTeam("T_ALPHA"),
                SlackGateway.fromMessage(gson.fromJson(thread, com.slack.api.model.event.MessageEvent.class), "EvThread")
                        .withTeam("T_ALPHA"),
                SlackGateway.fromMessage(gson.fromJson(top, com.slack.api.model.event.MessageEvent.class), "EvTop")
                        .withTeam("T_ALPHA"),
                SlackGateway.fromMention(gson.fromJson(MENTION, com.slack.api.model.event.AppMentionEvent.class),
                        "EvMention").withTeam("T_ALPHA"));
        assertThat(seen.getAllValues()).extracting(IncomingEvent::kind).containsExactlyInAnyOrder(
                IncomingEvent.Kind.DIRECT_MESSAGE, IncomingEvent.Kind.THREAD_REPLY, IncomingEvent.Kind.CHANNEL_MESSAGE,
                IncomingEvent.Kind.MENTION);
    }

    @Test
    void messagesWithASubtypeAreNotQuestions() throws Exception {
        event("T_ALPHA", "Ev1", "{\"type\":\"message\",\"subtype\":\"bot_message\",\"channel\":\"C1\","
                + "\"channel_type\":\"channel\",\"bot_id\":\"B1\",\"text\":\"hello?\",\"ts\":\"100.1\"}");
        event("T_ALPHA", "Ev2", "{\"type\":\"message\",\"subtype\":\"message_changed\",\"channel\":\"C1\","
                + "\"ts\":\"100.2\"}");
        event("T_ALPHA", "Ev3", "{\"type\":\"something_else\"}");

        verify(questions, after(300).never()).handle(anyLong(), any());
        verify(reactions, never()).handle(anyLong(), any());
    }

    @Test
    void reactionsAreRoutedToTheTenantOfTheWorkspace() throws Exception {
        String added = "{\"type\":\"reaction_added\",\"user\":\"U1\",\"reaction\":\"+1\","
                + "\"item\":{\"type\":\"message\",\"channel\":\"C1\",\"ts\":\"100.2\"},\"event_ts\":\"100.9\"}";
        String removed = added.replace("reaction_added", "reaction_removed").replace("+1", "thumbsdown");
        event("T_ALPHA", "EvA", added);
        event("T_BETA", "EvB", removed);

        ArgumentCaptor<ReactionEvent> alpha = ArgumentCaptor.forClass(ReactionEvent.class);
        ArgumentCaptor<ReactionEvent> beta = ArgumentCaptor.forClass(ReactionEvent.class);
        verify(reactions, timeout(2000)).handle(eq(a.tenantId()), alpha.capture());
        verify(reactions, timeout(2000)).handle(eq(b.tenantId()), beta.capture());
        assertThat(alpha.getValue()).isEqualTo(new ReactionEvent(true, "C1", "U1", "+1", "100.2", "T_ALPHA"));
        assertThat(beta.getValue()).isEqualTo(new ReactionEvent(false, "C1", "U1", "thumbsdown", "100.2", "T_BETA"));
    }

    private static String blockAction(String team, String actionId, String value) {
        return "{\"type\":\"block_actions\",\"user\":{\"id\":\"U1\",\"team_id\":\"" + team + "\"},"
                + "\"team\":{\"id\":\"" + team + "\"},\"api_app_id\":\"A1\",\"token\":\"x\","
                + "\"container\":{\"type\":\"message\",\"message_ts\":\"9000.2\",\"channel_id\":\"C1\","
                + "\"thread_ts\":\"100.1\"},\"channel\":{\"id\":\"C1\",\"name\":\"general\"},"
                + "\"message\":{\"ts\":\"9000.2\",\"thread_ts\":\"100.1\"},"
                + "\"actions\":[{\"action_id\":\"" + actionId + "\",\"block_id\":\"b\",\"type\":\"button\","
                + "\"value\":\"" + value + "\",\"action_ts\":\"1763373601.1\","
                + "\"text\":{\"type\":\"plain_text\",\"text\":\"Go\"}}]}";
    }

    @Test
    void followUpButtonsAndDraftButtonsAreRoutedWithTheirTenant() throws Exception {
        click("T_ALPHA", blockAction("T_ALPHA", "follow_up_0", "What about refunds?"));
        click("T_BETA", blockAction("T_BETA", "draft_create", "42"));
        click("T_BETA", blockAction("T_BETA", "draft_cancel", "43"));
        click("T_BETA", blockAction("T_BETA", "something_else", "x"));
        click("T_NOBODY", blockAction("T_NOBODY", "draft_create", "44"));

        verify(questions, timeout(2000)).followUp(eq(a.tenantId()), eq("C1"), eq("100.1"), eq("U1"),
                eq("What about refunds?"), anyString());
        verify(actions, timeout(2000)).handle(b.tenantId(), "C1", "9000.2", "100.1", "U1", "draft_create", "42");
        verify(actions, timeout(2000)).handle(b.tenantId(), "C1", "9000.2", "100.1", "U1", "draft_cancel", "43");
        verify(actions, after(300).times(2)).handle(anyLong(), any(), any(), any(), any(), any(), any());
        verify(questions, times(1)).followUp(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void theSocketModeEntryPointsGiveTheHandlersTheSameThing() {
        var gson = com.slack.api.util.json.GsonFactory.createSnakeCase();
        gateway.appMention("T_BETA", gson.fromJson(MENTION, com.slack.api.model.event.AppMentionEvent.class), "EvS");

        ArgumentCaptor<IncomingEvent> seen = ArgumentCaptor.forClass(IncomingEvent.class);
        verify(questions, timeout(2000)).handle(eq(b.tenantId()), seen.capture());
        assertThat(seen.getValue().teamId()).isEqualTo("T_BETA");
        assertThat(seen.getValue().eventId()).isEqualTo("EvS");
    }

    @Test
    void theBodyIsReadOnceAndAnOversizedBodyIsRefused() throws Exception {
        byte[] huge = new byte[1_000_001];
        java.util.Arrays.fill(huge, (byte) 'a');
        mvc.perform(post("/slack/events").contentType("application/json").content(huge)
                        .header(SlackSignature.TIMESTAMP_HEADER, ts())
                        .header(SlackSignature.SIGNATURE_HEADER, SlackSignature.sign(SECRET, ts(), huge)))
                .andExpect(status().isUnauthorized());
    }
}
