package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.TestDatabase;
import com.docs0.action.ActionService;
import com.docs0.action.PendingAction;
import com.docs0.action.PendingActionRepository;
import com.docs0.agent.Agent;
import com.docs0.budget.BudgetService;
import com.docs0.config.Docs0Properties;
import com.docs0.conversation.ConversationRepository;
import com.docs0.conversation.InteractionRepository;
import com.docs0.crypto.TokenCipher;
import com.docs0.feedback.CorrectionRepository;
import com.docs0.feedback.FeedbackRepository;
import com.docs0.isolation.TwoTenants;
import com.docs0.isolation.TwoTenants.Side;
import com.docs0.slack.SlackFakes.FakeAgent;
import com.docs0.slack.SlackFakes.FakeKnowledge;
import com.docs0.slack.SlackFakes.FakeLlm;
import com.docs0.slack.SlackFakes.FakeMessenger;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.tenant.TenantService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Slack events for two companies whose workspaces use the same channel ids, thread timestamps, user ids and event
 * ids. Each company's events are answered in its own workspace, from its own data, and never collide.
 */
class TwoTenantSlackTest {

    private static final String CHANNEL = "C1";

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final FakeMessenger slackA = new FakeMessenger();
    private final FakeMessenger slackB = new FakeMessenger();
    private final FakeAgent agent = new FakeAgent();
    private final FakeLlm llm = new FakeLlm();
    private TwoTenants two;
    private Side a;
    private Side b;
    private TenantService tenants;
    private PendingActionRepository pending;
    private ReactionHandler reactions;
    private QuestionHandler handler;
    private final SlackMessengers messengers = tenantId -> tenantId == a.tenantId() ? slackA : slackB;

    @BeforeEach
    void setUp() {
        long tenantA = TestDatabase.resetWithTenant();
        two = TwoTenants.seed(jdbc, tenantA);
        a = two.a;
        b = two.b;
        slackA.names.put("U1", "Arjun Alpha");
        slackB.names.put("U1", "Bella Beta");
        tenants = new TenantService(jdbc);
        pending = new PendingActionRepository(jdbc);
        handler = handler(new BigDecimal("100"));
        reactions = new ReactionHandler(new InteractionRepository(jdbc), new FeedbackRepository(jdbc), messengers);
    }

    private QuestionHandler handler(BigDecimal globalCap) {
        Docs0Properties props = new Docs0Properties(
                new Docs0Properties.Tenant("smalltech", "smallTech"),
                new Docs0Properties.Anthropic("", "test-model", new BigDecimal("2"), new BigDecimal("10"), 12),
                new Docs0Properties.Budget(globalCap), new Docs0Properties.Slack("", ""),
                new Docs0Properties.Drive(15, false), null, "", "", "");
        ConversationRepository conversations = new ConversationRepository(jdbc);
        InteractionRepository interactions = new InteractionRepository(jdbc);
        CorrectionFlow flow = new CorrectionFlow(new CorrectionDetector(llm, props), conversations, interactions,
                new FeedbackRepository(jdbc), new CorrectionRepository(jdbc), messengers, tenants,
                new BudgetService(jdbc, props));
        return new QuestionHandler(tenants, agent, new FakeKnowledge(), new BudgetService(jdbc, props), conversations,
                interactions, messengers, flow, pending, new IdentityService(jdbc, messengers), Duration.ZERO);
    }

    private static IncomingEvent mention(String ts, String eventId) {
        return IncomingEvent.of(IncomingEvent.Kind.MENTION, CHANNEL, "U1", "<@UBOT> where is the okapi policy?", ts,
                null, eventId);
    }

    private static void await(CompletableFuture<Void> done) throws Exception {
        done.get(10, TimeUnit.SECONDS);
    }

    private long count(String table, long tenantId) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :t").param("t", tenantId)
                .query(Long.class).single();
    }

    // ---- events --------------------------------------------------------------------------------------

    @Test
    void identicalEventsInTwoTenantsAreEachAnsweredOnceInTheirOwnWorkspace() throws Exception {
        long conversationsBefore = count("conversation", a.tenantId()) + count("conversation", b.tenantId());

        await(handler.handle(a.tenantId(), mention("500.1", "EvSame")));
        await(handler.handle(b.tenantId(), mention("500.1", "EvSame")));

        assertThat(agent.requests).extracting(Agent.AgentRequest::tenantId)
                .containsExactlyInAnyOrder(a.tenantId(), b.tenantId());
        // A reply went to each workspace, none to the other.
        assertThat(slackA.posts).hasSize(1);
        assertThat(slackB.posts).hasSize(1);
        assertThat(slackA.updates).hasSize(1);
        assertThat(slackB.updates).hasSize(1);
        // One new conversation and one new interaction per tenant, for the same channel and thread ts.
        assertThat(count("conversation", a.tenantId()) + count("conversation", b.tenantId()))
                .isEqualTo(conversationsBefore + 2);
        for (Side side : List.of(a, b)) {
            assertThat(jdbc.sql("SELECT count(*) FROM interaction WHERE tenant_id = :t AND question LIKE '%okapi%'")
                    .param("t", side.tenantId()).query(Long.class).single()).isEqualTo(1);
        }

        // A redelivery of the same event is dropped in each tenant separately.
        await(handler.handle(a.tenantId(), mention("500.1", "EvSame")));
        await(handler.handle(b.tenantId(), mention("500.1", "EvSame")));
        assertThat(agent.requests).hasSize(2);
        assertThat(slackA.posts).hasSize(1);
        assertThat(slackB.posts).hasSize(1);
    }

    @Test
    void aFollowUpButtonClickWithTheSameIdsIsNotDroppedAsAnotherTenantsDuplicate() throws Exception {
        await(handler.followUp(a.tenantId(), CHANNEL, "100.1", "U1", "And for returns?", "U1:100.1:act1"));
        await(handler.followUp(b.tenantId(), CHANNEL, "100.1", "U1", "And for returns?", "U1:100.1:act1"));

        assertThat(agent.requests).hasSize(2);
        assertThat(slackA.posts).extracting(FakeMessenger.Posted::text).anyMatch(t -> t.contains("asked"));
        assertThat(slackB.posts).extracting(FakeMessenger.Posted::text).anyMatch(t -> t.contains("asked"));
        await(handler.followUp(b.tenantId(), CHANNEL, "100.1", "U1", "And for returns?", "U1:100.1:act1"));
        assertThat(agent.requests).hasSize(2);
    }

    @Test
    void conversationsOfDifferentTenantsAreNotQueuedBehindEachOther() throws Exception {
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        agent.behaviour = (request, progress) -> {
            if (request.tenantId() == a.tenantId()) {
                aStarted.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return SlackFakes.result(Agent.Kind.ANSWER, "Answer to: " + request.question());
        };

        CompletableFuture<Void> firstA = handler.handle(a.tenantId(), mention("600.1", "EvA1"));
        assertThat(aStarted.await(10, TimeUnit.SECONDS)).isTrue();
        // Same channel, same thread ts as A's blocked conversation, but another tenant: not queued behind it.
        CompletableFuture<Void> inB = handler.handle(b.tenantId(), mention("600.1", "EvB1"));
        await(inB);
        assertThat(firstA).isNotDone();
        assertThat(slackB.updates).hasSize(1);

        // Within one tenant the order is kept: this waits for the first.
        CompletableFuture<Void> secondA = handler.handle(a.tenantId(), IncomingEvent.of(
                IncomingEvent.Kind.THREAD_REPLY, CHANNEL, "U1", "and the okapi return window?", "600.2", "600.1", "EvA2"));
        assertThatThrownBy(() -> secondA.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        release.countDown();
        await(firstA);
        await(secondA);
        assertThat(slackA.updates).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void aSuspendedTenantsQuestionsAreRefusedPolitelyAndTheOtherTenantIsUnaffected() throws Exception {
        tenants.suspend(b.tenantId());

        await(handler.handle(b.tenantId(), mention("700.1", "EvB")));
        await(handler.handle(a.tenantId(), mention("700.1", "EvA")));

        assertThat(slackB.posts).extracting(FakeMessenger.Posted::text).containsExactly(QuestionHandler.SUSPENDED);
        assertThat(slackB.updates).isEmpty();
        assertThat(agent.requests).extracting(Agent.AgentRequest::tenantId).containsExactly(a.tenantId());
        assertThat(slackA.updates).hasSize(1);

        tenants.activate(b.tenantId());
        await(handler.handle(b.tenantId(), mention("700.2", "EvB2")));
        assertThat(agent.requests).hasSize(2);
    }

    @Test
    void aTenantOverItsOwnBudgetIsRefusedWhileTheOtherKeepsAnswering() throws Exception {
        // A has spent 11.00 this month and B 4.50. B's own cap is 4.00; A has no cap of its own (default 100).
        tenants.setMonthlyBudget(b.tenantId(), new BigDecimal("4.00"));

        await(handler.handle(b.tenantId(), mention("800.1", "EvB")));
        await(handler.handle(a.tenantId(), mention("800.1", "EvA")));

        assertThat(slackB.posts).extracting(FakeMessenger.Posted::text).containsExactly(QuestionHandler.OVER_BUDGET);
        assertThat(agent.requests).extracting(Agent.AgentRequest::tenantId).containsExactly(a.tenantId());
        assertThat(jdbc.sql("SELECT count(*) FROM interaction WHERE tenant_id = :t AND kind = 'OVER_BUDGET'")
                .param("t", b.tenantId()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM interaction WHERE tenant_id = :t AND kind = 'OVER_BUDGET'")
                .param("t", a.tenantId()).query(Long.class).single()).isZero();
    }

    @Test
    void aReplyIsAPendingCorrectionOfTheTenantItWasWrittenIn() throws Exception {
        long pendingA = count("correction", a.tenantId());
        long pendingB = count("correction", b.tenantId());
        llm.fact = "Refunds take 7 days.";

        // Both tenants have an answered thread C1/100.1 (seeded): a reply in B's thread.
        await(handler.handle(b.tenantId(), IncomingEvent.of(IncomingEvent.Kind.THREAD_REPLY, CHANNEL, "U1",
                "no, that's wrong, refunds take 7 days", "100.5", "100.1", "EvCorrect")));

        assertThat(count("correction", b.tenantId())).isEqualTo(pendingB + 1);
        assertThat(count("correction", a.tenantId())).isEqualTo(pendingA);
        assertThat(jdbc.sql("SELECT author_name FROM correction WHERE tenant_id = :t ORDER BY id DESC LIMIT 1")
                .param("t", b.tenantId()).query(String.class).single()).isEqualTo("Bella Beta");
        assertThat(slackB.posts).extracting(FakeMessenger.Posted::text).containsExactly(CorrectionFlow.THANKS);
        assertThat(slackA.posts).isEmpty();
        // The classification cost was added to B's answer, not A's.
        assertThat(jdbc.sql("SELECT cost_usd FROM interaction WHERE id = :i").param("i", a.interaction())
                .query(BigDecimal.class).single()).isEqualByComparingTo("5.000000");
    }

    @Test
    void aReactionIsAFeedbackOnTheAnswerOfTheTenantItCameFrom() throws Exception {
        // Both tenants have an answer posted at the same ts in the same channel.
        for (Side side : List.of(a, b)) {
            jdbc.sql("UPDATE interaction SET answer_ts = '900.1' WHERE id = :i").param("i", side.interaction()).update();
        }
        FeedbackRepository feedback = new FeedbackRepository(jdbc);
        long upA = feedback.count(a.tenantId(), a.interaction(), FeedbackRepository.Rating.UP);

        reactions.handle(b.tenantId(), new ReactionEvent(true, CHANNEL, "U7", "+1", "900.1")).get(10, TimeUnit.SECONDS);

        assertThat(feedback.count(b.tenantId(), b.interaction(), FeedbackRepository.Rating.UP)).isEqualTo(1);
        assertThat(feedback.count(a.tenantId(), a.interaction(), FeedbackRepository.Rating.UP)).isEqualTo(upA);

        // A thumbs down asks "what was wrong?" in B's workspace only.
        jdbc.sql("DELETE FROM feedback_prompt").update();
        reactions.handle(b.tenantId(), new ReactionEvent(true, CHANNEL, "U7", "-1", "900.1")).get(10, TimeUnit.SECONDS);
        assertThat(slackB.posts).extracting(FakeMessenger.Posted::text).containsExactly(ReactionHandler.PROMPT);
        assertThat(slackA.posts).isEmpty();
    }

    // ---- draft buttons ---------------------------------------------------------------------------------

    private ActionHandler actionHandler() {
        return new ActionHandler(tenants, new ActionService(pending, null, null, null, null, Clock.systemUTC()),
                messengers);
    }

    @Test
    void aButtonClickOnAnotherTenantsDraftIsRejectedAndChangesNothing() throws Exception {
        ActionHandler actions = actionHandler();
        String cancel = AnswerRenderer.DRAFT_CANCEL_ACTION;

        actions.handle(b.tenantId(), CHANNEL, "9000.1", "100.1", "U1", cancel, String.valueOf(a.pendingAction()))
                .get(10, TimeUnit.SECONDS);

        assertThat(slackB.ephemerals).extracting(FakeMessenger.Ephemeral::text).containsExactly(ActionHandler.GONE);
        assertThat(slackA.ephemerals).isEmpty();
        assertThat(pending.find(a.tenantId(), a.pendingAction()).orElseThrow().status())
                .isEqualTo(PendingAction.Status.PENDING);

        // The click on its own draft works, and only B's message is updated.
        actions.handle(b.tenantId(), CHANNEL, "9000.1", "100.1", "U1", cancel, String.valueOf(b.pendingAction()))
                .get(10, TimeUnit.SECONDS);
        assertThat(pending.find(b.tenantId(), b.pendingAction()).orElseThrow().status())
                .isEqualTo(PendingAction.Status.CANCELLED);
        assertThat(slackB.updates).hasSize(1);
        assertThat(slackA.updates).isEmpty();
        assertThat(pending.find(a.tenantId(), a.pendingAction()).orElseThrow().status())
                .isEqualTo(PendingAction.Status.PENDING);
    }

    @Test
    void aSuspendedTenantCannotCreateAnythingFromADraft() throws Exception {
        tenants.suspend(b.tenantId());

        actionHandler().handle(b.tenantId(), CHANNEL, "9000.1", "100.1", "U1", AnswerRenderer.DRAFT_CREATE_ACTION,
                String.valueOf(b.pendingAction())).get(10, TimeUnit.SECONDS);

        assertThat(slackB.ephemerals).extracting(FakeMessenger.Ephemeral::text).containsExactly(ActionHandler.PAUSED);
        assertThat(pending.find(b.tenantId(), b.pendingAction()).orElseThrow().status())
                .isEqualTo(PendingAction.Status.PENDING);
    }

    // ---- identities -------------------------------------------------------------------------------------

    @Test
    void identityCachesAreKeptPerTenant() {
        IdentityService identities = new IdentityService(jdbc, messengers);
        // The seeded caches say who U1 is in each company.
        assertThat(identities.person(a.tenantId(), "U1")).get().extracting(IdentityService.Person::email)
                .isEqualTo("u1@smalltech.example.com");
        assertThat(identities.person(b.tenantId(), "U1")).get().extracting(IdentityService.Person::email)
                .isEqualTo("u1@acme.example.com");

        // An unknown user is looked up in that tenant's own workspace and cached for that tenant only.
        slackA.users.put("U2", new SlackMessenger.SlackUser("U2", "u2@alpha.example.com", "Uma Alpha", false, false));
        slackB.users.put("U2", new SlackMessenger.SlackUser("U2", "u2@beta.example.com", "Umar Beta", false, false));
        assertThat(identities.displayName(a.tenantId(), "U2")).isEqualTo("Uma Alpha");
        assertThat(identities.displayName(b.tenantId(), "U2")).isEqualTo("Umar Beta");
        assertThat(jdbc.sql("SELECT email FROM identity WHERE tenant_id = :t AND slack_user_id = 'U2'")
                .param("t", a.tenantId()).query(String.class).single()).isEqualTo("u2@alpha.example.com");

        // The missing-email-scope warning is per workspace.
        slackA.scopes = java.util.Set.of("chat:write");
        slackB.scopes = java.util.Set.of("chat:write", "users:read.email");
        assertThat(identities.emailScopeMissing(a.tenantId())).isTrue();
        assertThat(identities.emailScopeMissing(b.tenantId())).isFalse();
    }

    @Test
    void aTokenFromOneWorkspaceIsNeverUsedForAnother() {
        TokenCipher cipher = new TokenCipher(DriveTestSetup.ENCRYPTION_KEY);
        SlackWorkspaces workspaces = new SlackWorkspaces(jdbc, cipher);
        assertThat(workspaces.botToken(a.tenantId())).contains(a.botToken());
        assertThat(workspaces.botToken(b.tenantId())).contains(b.botToken());
        assertThat(workspaces.botToken(b.tenantId()).orElseThrow()).isNotEqualTo(workspaces.botToken(a.tenantId())
                .orElseThrow());
    }
}
