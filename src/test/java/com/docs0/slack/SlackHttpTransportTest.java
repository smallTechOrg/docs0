package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docs0.TestDatabase;
import com.docs0.config.Docs0Properties;
import com.docs0.crypto.TokenCipher;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.tenant.local.LocalSlackPilot;
import com.docs0.web.StatusContributor;
import com.docs0.web.slack.SlackEventsController;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * DOCS0_SLACK_TRANSPORT=http in single-company mode (no client id or secret): the signing secret alone protects the
 * endpoints, the first event registers the pilot workspace on demand, and a request is held open until its work is done.
 */
class SlackHttpTransportTest {

    private static final String SECRET = "signing-secret-for-tests";
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String MENTION = "{\"type\":\"app_mention\",\"user\":\"U1\",\"text\":\"<@UBOT> refund?\","
            + "\"ts\":\"100.2\",\"thread_ts\":\"100.1\",\"channel\":\"C1\",\"event_ts\":\"100.2\"}";

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private long tenantId;
    private SlackWorkspaces workspaces;
    private QuestionHandler questions;
    private ReactionHandler reactions;
    private ActionHandler actions;
    private LocalSlackPilot pilot;
    private final AtomicInteger registerCalls = new AtomicInteger();
    private final AtomicInteger registerFailures = new AtomicInteger();
    private volatile long registerDelayMillis;
    private SlackGateway gateway;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        tenantId = TestDatabase.resetWithTenant();
        jdbc.sql("DELETE FROM slack_installation").update();
        workspaces = new SlackWorkspaces(jdbc, new TokenCipher(DriveTestSetup.ENCRYPTION_KEY));
        questions = mock(QuestionHandler.class);
        reactions = mock(ReactionHandler.class);
        actions = mock(ActionHandler.class);
        when(questions.handle(anyLong(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(questions.followUp(anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(reactions.handle(anyLong(), any())).thenReturn(CompletableFuture.completedFuture(null));
        pilot = mock(LocalSlackPilot.class);
        when(pilot.tenantId()).thenReturn(tenantId);
        when(pilot.register()).thenAnswer(call -> {
            registerCalls.incrementAndGet();
            if (registerDelayMillis > 0) {
                Thread.sleep(registerDelayMillis);
            }
            if (registerFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new SlackMessenger.SlackCallException("auth.test", "network_error", null);
            }
            workspaces.register(tenantId, "T_PILOT", "Pilot", "xoxb-secret", "UBOT", "test", null);
            return new BoltSlackMessenger.Identity("T_PILOT", "Pilot", "docs0", "UBOT");
        });
        build("xoxb-pilot-token", SECRET, 5);
    }

    @AfterEach
    void tearDown() {
        clients.shutdownNow();
    }

    private void build(String botToken, String signingSecret, int holdOpenSeconds) {
        Docs0Properties props = new Docs0Properties(new Docs0Properties.Tenant("smalltech", "smallTech"), null, null,
                new Docs0Properties.Slack(botToken, "", "", "", signingSecret, "http", holdOpenSeconds), null, null,
                "", "https://docs0.example", "");
        gateway = new SlackGateway(props, workspaces, pilot, questions, reactions, actions);
        SlackHttpEvents events = new SlackHttpEvents(gateway, workspaces);
        mvc = MockMvcBuilders.standaloneSetup(new SlackEventsController(props, events, CLOCK)).build();
    }

    // ---- helpers ----

    private static String ts() {
        return Long.toString(NOW.getEpochSecond());
    }

    private static MockHttpServletRequestBuilder signed(String url, String contentType, String body) {
        return post(url).contentType(contentType).content(body).header(SlackSignature.TIMESTAMP_HEADER, ts())
                .header(SlackSignature.SIGNATURE_HEADER,
                        SlackSignature.sign(SECRET, ts(), body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String envelope(String team, String eventId, String eventJson) {
        return "{\"token\":\"x\",\"team_id\":\"" + team + "\",\"api_app_id\":\"A1\",\"type\":\"event_callback\","
                + "\"event_id\":\"" + eventId + "\",\"event_time\":1763373600,\"event\":" + eventJson + "}";
    }

    private MockHttpServletRequestBuilder eventRequest(String team, String eventId) {
        return signed("/slack/events", "application/json", envelope(team, eventId, MENTION));
    }

    /** Runs the request on another thread, so the test can look at the moment the controller is still holding it. */
    private Future<Integer> async(MockHttpServletRequestBuilder request) {
        return clients.submit(() -> mvc.perform(request).andReturn().getResponse().getStatus());
    }

    private void registerPilotWorkspace() {
        workspaces.register(tenantId, "T_PILOT", "Pilot", "xoxb-secret", "UBOT", "test", null);
    }

    // ---- signature ----

    @Test
    void aSignedUrlVerificationGetsItsChallengeWithoutAnyMultiTenantSettings() throws Exception {
        mvc.perform(signed("/slack/events", "application/json", "{\"type\":\"url_verification\",\"challenge\":\"abc123\"}"))
                .andExpect(status().isOk()).andExpect(content().string("abc123"));
    }

    @Test
    void aBlankSigningSecretRejectsEverythingEvenAFreshlySignedRequest() throws Exception {
        build("xoxb-pilot-token", "", 5);
        String body = "{\"type\":\"url_verification\",\"challenge\":\"abc\"}";

        // Even a request signed with the key an attacker would guess for a blank secret.
        mvc.perform(post("/slack/events").contentType("application/json").content(body)
                        .header(SlackSignature.TIMESTAMP_HEADER, ts())
                        .header(SlackSignature.SIGNATURE_HEADER, "v0=" + "0".repeat(64)))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mvc.perform(post("/slack/interactions").contentType("application/x-www-form-urlencoded").content("payload={}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsignedWronglySignedAndStaleRequestsGetABare401AndRunNothing() throws Exception {
        String body = envelope("T_PILOT", "Ev1", MENTION);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String old = Long.toString(NOW.minusSeconds(400).getEpochSecond());

        mvc.perform(post("/slack/events").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mvc.perform(post("/slack/events").contentType("application/json").content(body)
                        .header(SlackSignature.TIMESTAMP_HEADER, ts())
                        .header(SlackSignature.SIGNATURE_HEADER, SlackSignature.sign("wrong", ts(), bytes)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/slack/events").contentType("application/json").content(body)
                        .header(SlackSignature.TIMESTAMP_HEADER, old)
                        .header(SlackSignature.SIGNATURE_HEADER, SlackSignature.sign(SECRET, old, bytes)))
                .andExpect(status().isUnauthorized());

        verify(questions, after(200).never()).handle(anyLong(), any());
        assertThat(registerCalls).hasValue(0);
    }

    // ---- lazy registration ----

    @Test
    void anEventFromAnUnregisteredWorkspaceRegistersThePilotOnDemandAndIsHandled() throws Exception {
        mvc.perform(eventRequest("T_PILOT", "Ev1")).andExpect(status().isOk()).andExpect(content().string(""));

        assertThat(registerCalls).hasValue(1);
        verify(questions).handle(eq(tenantId), any());
        assertThat(workspaces.byTeamId("T_PILOT")).isPresent();
    }

    @Test
    void withoutAPilotBotTokenAnUnknownWorkspaceIsStillIgnored() throws Exception {
        build("", SECRET, 5);

        mvc.perform(eventRequest("T_PILOT", "Ev1")).andExpect(status().isOk());

        assertThat(registerCalls).hasValue(0);
        verify(questions, never()).handle(anyLong(), any());
    }

    @Test
    void aRegisteredWorkspaceIsNotRegisteredAgain() throws Exception {
        registerPilotWorkspace();

        mvc.perform(eventRequest("T_PILOT", "Ev1")).andExpect(status().isOk());

        assertThat(registerCalls).hasValue(0);
        verify(questions).handle(eq(tenantId), any());
    }

    @Test
    void concurrentFirstEventsRegisterTheWorkspaceOnceAndAreAllHandled() throws Exception {
        registerDelayMillis = 300;
        List<Future<Integer>> responses = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            responses.add(async(eventRequest("T_PILOT", "Ev" + i)));
        }
        for (Future<Integer> response : responses) {
            assertThat(response.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }

        assertThat(registerCalls).hasValue(1);
        verify(questions, times(6)).handle(eq(tenantId), any());
    }

    @Test
    void aFailedRegistrationIsRetriedAtMostEveryThirtySecondsAndThenSucceeds() throws Exception {
        AtomicLong nanos = new AtomicLong(1_000_000_000_000L);
        gateway.useNanoClock(nanos::get);
        registerFailures.set(1);

        mvc.perform(eventRequest("T_PILOT", "Ev1")).andExpect(status().isOk());
        assertThat(registerCalls).hasValue(1);
        assertThat(gateway.status(tenantId).state()).isEqualTo(StatusContributor.State.DOWN);
        verify(questions, never()).handle(anyLong(), any());

        nanos.addAndGet(TimeUnit.SECONDS.toNanos(29));
        mvc.perform(eventRequest("T_PILOT", "Ev2")).andExpect(status().isOk());
        assertThat(registerCalls).as("still inside the 30 s window").hasValue(1);

        nanos.addAndGet(TimeUnit.SECONDS.toNanos(2));
        mvc.perform(eventRequest("T_PILOT", "Ev3")).andExpect(status().isOk());
        assertThat(registerCalls).hasValue(2);
        verify(questions, times(1)).handle(eq(tenantId), any());
        assertThat(gateway.status(tenantId)).isEqualTo(StatusContributor.ComponentStatus.up("Slack",
                "events over HTTP (signing secret set)"));
    }

    // ---- status ----

    @Test
    void theStatusSaysEventsOverHttpOrOffWhenTheSecretOrTokenIsMissing() {
        assertThat(gateway.status(tenantId)).isEqualTo(StatusContributor.ComponentStatus.up("Slack",
                "events over HTTP (signing secret set)"));

        build("", SECRET, 5);
        assertThat(gateway.status(tenantId).state()).isEqualTo(StatusContributor.State.OFF);
        build("xoxb-pilot-token", "", 5);
        assertThat(gateway.status(tenantId).state()).isEqualTo(StatusContributor.State.OFF);
    }

    @Test
    void startRegistersInTheBackgroundAndNeverStartsSocketMode() throws Exception {
        gateway.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (registerCalls.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(registerCalls).hasValue(1);
        gateway.stop();
    }

    // ---- hold-open ----

    @Test
    void theRequestIsNotAnsweredUntilTheHandlerHasFinished() throws Exception {
        registerPilotWorkspace();
        CompletableFuture<Void> work = new CompletableFuture<>();
        when(questions.handle(anyLong(), any())).thenReturn(work);

        Future<Integer> response = async(eventRequest("T_PILOT", "Ev1"));
        verify(questions, org.mockito.Mockito.timeout(3000)).handle(eq(tenantId), any());
        Thread.sleep(300);
        assertThat(response.isDone()).as("held open while the handler works").isFalse();

        work.complete(null);

        assertThat(response.get(5, TimeUnit.SECONDS)).isEqualTo(200);
    }

    @Test
    void aHandlerThatNeverFinishesIsAnsweredWith200AtTheHoldOpenLimit() throws Exception {
        registerPilotWorkspace();
        build("xoxb-pilot-token", SECRET, 1);
        when(questions.handle(anyLong(), any())).thenReturn(new CompletableFuture<>());

        long started = System.nanoTime();
        mvc.perform(eventRequest("T_PILOT", "Ev1")).andExpect(status().isOk()).andExpect(content().string(""));
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(millis).isBetween(900L, 4000L);
    }

    @Test
    void aSlackRetryAndAnInFlightDuplicateAreAnsweredAtOnceWithoutRunningTheEventAgain() throws Exception {
        registerPilotWorkspace();
        CompletableFuture<Void> work = new CompletableFuture<>();
        when(questions.handle(anyLong(), any())).thenReturn(work);

        Future<Integer> first = async(eventRequest("T_PILOT", "Ev1"));
        verify(questions, org.mockito.Mockito.timeout(3000)).handle(eq(tenantId), any());

        // The same event while the first is being worked on: with and without Slack's retry header.
        mvc.perform(eventRequest("T_PILOT", "Ev1").header("X-Slack-Retry-Num", "1")).andExpect(status().isOk())
                .andExpect(content().string(""));
        mvc.perform(eventRequest("T_PILOT", "Ev1")).andExpect(status().isOk()).andExpect(content().string(""));
        // A retry of an event this instance already finished.
        mvc.perform(eventRequest("T_PILOT", "Ev0").header("X-Slack-Retry-Num", "2")).andExpect(status().isOk());

        assertThat(first.isDone()).isFalse();
        verify(questions, times(1)).handle(eq(tenantId), any());
        work.complete(null);
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(200);
    }

    @Test
    void aSlowHandlerDoesNotStopOtherRequests() throws Exception {
        registerPilotWorkspace();
        CompletableFuture<Void> slow = new CompletableFuture<>();
        when(questions.handle(anyLong(), any())).thenReturn(slow).thenReturn(CompletableFuture.completedFuture(null));

        Future<Integer> held = async(eventRequest("T_PILOT", "Ev1"));
        verify(questions, org.mockito.Mockito.timeout(3000)).handle(eq(tenantId), any());

        long started = System.nanoTime();
        mvc.perform(eventRequest("T_PILOT", "Ev2")).andExpect(status().isOk());
        mvc.perform(signed("/slack/events", "application/json", "{\"type\":\"url_verification\",\"challenge\":\"c\"}"))
                .andExpect(content().string("c"));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2000);
        assertThat(held.isDone()).isFalse();
        slow.complete(null);
        held.get(5, TimeUnit.SECONDS);
    }

    @Test
    void aReactionIsHeldOpenToo() throws Exception {
        registerPilotWorkspace();
        CompletableFuture<Void> work = new CompletableFuture<>();
        when(reactions.handle(anyLong(), any())).thenReturn(work);
        String reaction = "{\"type\":\"reaction_added\",\"user\":\"U1\",\"reaction\":\"-1\",\"item\":{\"type\":\"message\","
                + "\"channel\":\"C1\",\"ts\":\"100.2\"},\"event_ts\":\"100.3\"}";

        Future<Integer> response = async(signed("/slack/events", "application/json",
                envelope("T_PILOT", "EvR", reaction)));
        verify(reactions, org.mockito.Mockito.timeout(3000)).handle(eq(tenantId), any());
        Thread.sleep(200);
        assertThat(response.isDone()).isFalse();
        work.complete(null);
        assertThat(response.get(5, TimeUnit.SECONDS)).isEqualTo(200);
    }

    // ---- interactions ----

    @Test
    void aFollowUpClickIsRoutedWithItsTenantAndHeldOpenUntilItIsDone() throws Exception {
        registerPilotWorkspace();
        CompletableFuture<Void> work = new CompletableFuture<>();
        when(questions.followUp(anyLong(), any(), any(), any(), any(), any())).thenReturn(work);
        String payload = "{\"type\":\"block_actions\",\"user\":{\"id\":\"U1\",\"team_id\":\"T_PILOT\"},"
                + "\"team\":{\"id\":\"T_PILOT\"},\"channel\":{\"id\":\"C1\"},"
                + "\"message\":{\"ts\":\"100.5\",\"thread_ts\":\"100.1\"},"
                + "\"actions\":[{\"action_id\":\"follow_up_0\",\"value\":\"And for enterprise?\",\"action_ts\":\"1.1\"}]}";

        Future<Integer> response = async(signed("/slack/interactions", "application/x-www-form-urlencoded",
                "payload=" + URLEncoder.encode(payload, StandardCharsets.UTF_8)));
        verify(questions, org.mockito.Mockito.timeout(3000)).followUp(eq(tenantId), eq("C1"), eq("100.1"), eq("U1"),
                eq("And for enterprise?"), any());
        Thread.sleep(200);
        assertThat(response.isDone()).isFalse();
        work.complete(null);
        assertThat(response.get(5, TimeUnit.SECONDS)).isEqualTo(200);
    }

    @Test
    void anInteractionFromAnUnknownWorkspaceWithNoPilotTokenIsIgnored() throws Exception {
        build("", SECRET, 5);
        String payload = "{\"type\":\"block_actions\",\"user\":{\"id\":\"U1\",\"team_id\":\"T_X\"},\"team\":{\"id\":\"T_X\"},"
                + "\"channel\":{\"id\":\"C1\"},\"message\":{\"ts\":\"1.1\"},"
                + "\"actions\":[{\"action_id\":\"follow_up_0\",\"value\":\"q\",\"action_ts\":\"1.1\"}]}";

        mvc.perform(signed("/slack/interactions", "application/x-www-form-urlencoded",
                "payload=" + URLEncoder.encode(payload, StandardCharsets.UTF_8))).andExpect(status().isOk());

        verify(questions, never()).followUp(anyLong(), any(), any(), any(), any(), any());
    }
}
