package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.TestDatabase;
import com.docs0.agent.Agent;
import com.docs0.budget.BudgetService;
import com.docs0.config.Docs0Properties;
import com.docs0.conversation.ConversationRepository;
import com.docs0.conversation.InteractionRepository;
import com.docs0.feedback.CorrectionRepository;
import com.docs0.feedback.FeedbackRepository;
import com.docs0.slack.SlackFakes.FakeAgent;
import com.docs0.slack.SlackFakes.FakeKnowledge;
import com.docs0.slack.SlackFakes.FakeLlm;
import com.docs0.slack.SlackFakes.FakeMessenger;
import com.docs0.tenant.TenantService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A reply to an answer: correction for the admins, or an ordinary follow-up question. */
class CorrectionFlowTest {

    private static final String CHANNEL = "C100";
    private static final String DM = "D200";

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final FakeMessenger slack = new FakeMessenger();
    private final FakeAgent agent = new FakeAgent();
    private final FakeLlm llm = new FakeLlm();
    private long tenantId;
    private QuestionHandler handler;
    private ReactionHandler reactions;

    @BeforeEach
    void setUp() {
        tenantId = TestDatabase.resetWithTenant();
        Docs0Properties props = new Docs0Properties(
                new Docs0Properties.Tenant("smalltech", "smallTech"),
                new Docs0Properties.Anthropic("", "test-model", new BigDecimal("2"), new BigDecimal("10"), 12),
                new Docs0Properties.Budget(new BigDecimal("100")),
                new Docs0Properties.Slack("", ""),
                new Docs0Properties.Drive(15, false), null, "", "", "");
        TenantService tenants = new TenantService(jdbc, props);
        ConversationRepository conversations = new ConversationRepository(jdbc);
        InteractionRepository interactions = new InteractionRepository(jdbc);
        FeedbackRepository feedback = new FeedbackRepository(jdbc);
        CorrectionFlow flow = new CorrectionFlow(new CorrectionDetector(llm, props), conversations, interactions,
                feedback, new CorrectionRepository(jdbc), slack);
        handler = new QuestionHandler(tenants, agent, new FakeKnowledge(), new BudgetService(jdbc, props),
                conversations, interactions, slack, flow, new com.docs0.action.PendingActionRepository(jdbc),
                new IdentityService(jdbc, slack), Duration.ZERO);
        reactions = new ReactionHandler(tenants, interactions, feedback, slack);
        slack.names.put("U1", "Arjun");
        slack.names.put("U2", "Meera");
    }

    // An answered channel thread: question at 100.1, docs0's reply is the first post.
    private void answeredThread() throws Exception {
        await(handler.handle(IncomingEvent.of(IncomingEvent.Kind.MENTION, CHANNEL, "U2",
                "<@UBOT> what does the Team plan cost?", "100.1", null, "EvQ")));
    }

    private String answerTs() {
        return slack.posts.get(0).ts();
    }

    private void thumbsDown() throws Exception {
        reactions.handle(new ReactionEvent(true, CHANNEL, "U1", "-1", answerTs())).get(10, TimeUnit.SECONDS);
    }

    private CompletableFuture<Void> reply(String text, String ts, String eventId) {
        return handler.handle(IncomingEvent.of(IncomingEvent.Kind.THREAD_REPLY, CHANNEL, "U1", text, ts, "100.1",
                eventId));
    }

    private List<Map<String, Object>> corrections() {
        return jdbc.sql("SELECT * FROM correction WHERE tenant_id = :t ORDER BY id").param("t", tenantId)
                .query().listOfRows();
    }

    @Test
    void aReplyToTheFeedbackPromptIsStoredAsAPendingCorrection() throws Exception {
        answeredThread();
        thumbsDown();
        llm.fact = "The Team plan costs $14 per seat per month.";

        await(reply("it's 14 dollars", "100.5", "Ev1"));

        assertThat(corrections()).singleElement().satisfies(c -> {
            assertThat(c).containsEntry("status", "PENDING").containsEntry("author_slack_id", "U1")
                    .containsEntry("author_name", "Arjun").containsEntry("from_dm", false)
                    .containsEntry("question", "what does the Team plan cost?")
                    .containsEntry("original_answer", "Answer to: what does the Team plan cost?")
                    .containsEntry("correction_text", "The Team plan costs $14 per seat per month.");
            assertThat(c.get("interaction_id")).isNotNull();
            assertThat(c.get("reviewed_at")).isNull();
        });
        FakeMessenger.Posted thanks = slack.posts.get(slack.posts.size() - 1);
        assertThat(thanks.text()).isEqualTo(
                "Thanks, I've passed that to an admin to review. It won't change my answers until they approve it.");
        assertThat(thanks.threadTs()).isEqualTo("100.1");
        // Not asked as a question, and the prompt no longer waits.
        assertThat(agent.requests).hasSize(1);
        assertThat(interactionCount()).isEqualTo(1);
        assertThat(llm.asked).singleElement().satisfies(a -> assertThat(a)
                .contains("<question>what does the Team plan cost?</question>").contains("<reply>it's 14 dollars</reply>"));
        long clearedPrompts = jdbc.sql("SELECT count(*) FROM feedback_prompt WHERE cleared_at IS NOT NULL")
                .query(Long.class).single();
        assertThat(clearedPrompts).isEqualTo(1);
    }

    @Test
    void theCostOfTheClassificationIsAddedToTheAnswerItWasAbout() throws Exception {
        answeredThread();
        thumbsDown();
        await(reply("it's 14 dollars", "100.5", "Ev1"));

        // 300 input tokens at $2 and 20 output tokens at $10 per million.
        BigDecimal cost = jdbc.sql("SELECT cost_usd FROM interaction").query(BigDecimal.class).single();
        assertThat(cost).isEqualByComparingTo("0.000800");
        assertThat(jdbc.sql("SELECT input_tokens FROM interaction").query(Long.class).single()).isEqualTo(300);
    }

    @Test
    void aWrongSoundingReplyWithoutAPromptIsClassifiedToo() throws Exception {
        answeredThread();
        llm.fact = "The Team plan costs $14.";

        await(reply("That's wrong, it's actually $14", "100.5", "Ev1"));

        assertThat(corrections()).singleElement()
                .satisfies(c -> assertThat(c).containsEntry("correction_text", "The Team plan costs $14."));
        assertThat(agent.requests).hasSize(1);
    }

    @Test
    void everyHeuristicPhraseOpensTheGate() {
        for (String text : List.of("That's wrong", "that is wrong!", "this is incorrect", "not correct", "not right",
                "Actually, no", "it's actually 14", "It is actually 14", "should be 14", "WRONG")) {
            assertThat(CorrectionDetector.looksLikeCorrection(text)).as(text).isTrue();
        }
        for (String text : List.of("and for enterprise?", "thanks", "what about the annual plan", "")) {
            assertThat(CorrectionDetector.looksLikeCorrection(text)).as(text).isFalse();
        }
        assertThat(CorrectionDetector.looksLikeCorrection(null)).isFalse();
    }

    @Test
    void anOrdinaryFollowUpNeverReachesTheModelClassifier() throws Exception {
        answeredThread();

        await(reply("and for enterprise?", "100.5", "Ev1"));

        assertThat(llm.asked).isEmpty();
        assertThat(corrections()).isEmpty();
        assertThat(agent.requests).extracting(Agent.AgentRequest::question)
                .containsExactly("what does the Team plan cost?", "and for enterprise?");
    }

    @Test
    void whenTheDetectorSaysItIsAQuestionItIsAnsweredAsAFollowUp() throws Exception {
        answeredThread();
        llm.kind = "question";

        await(reply("what should be the price for enterprise?", "100.5", "Ev1"));

        assertThat(llm.asked).hasSize(1);
        assertThat(corrections()).isEmpty();
        assertThat(agent.requests).extracting(Agent.AgentRequest::question)
                .containsExactly("what does the Team plan cost?", "what should be the price for enterprise?");
        assertThat(interactionCount()).isEqualTo(2);
    }

    @Test
    void whenTheModelCallFailsTheReplyIsStillAnsweredAsAQuestion() throws Exception {
        answeredThread();
        thumbsDown();
        llm.fails = true;

        await(reply("what is the enterprise price?", "100.5", "Ev1"));

        assertThat(corrections()).isEmpty();
        assertThat(agent.requests).hasSize(2);
        assertThat(slack.updates.get(slack.updates.size() - 1).text()).startsWith("Answer to: what is the enterprise");
    }

    @Test
    void aPromptOlderThanADayNoLongerWaits() throws Exception {
        answeredThread();
        thumbsDown();
        jdbc.sql("UPDATE feedback_prompt SET created_at = now() - interval '25 hours'").update();

        await(reply("it's 14 dollars", "100.5", "Ev1"));

        assertThat(llm.asked).isEmpty();
        assertThat(corrections()).isEmpty();
        assertThat(agent.requests).hasSize(2);
    }

    @Test
    void aCorrectionInADmIsFlaggedAsFromADm() throws Exception {
        await(handler.handle(IncomingEvent.of(IncomingEvent.Kind.DIRECT_MESSAGE, DM, "U1", "what is my band?",
                "200.1", null, "Ev1")));
        llm.fact = "The band is L5.";

        await(handler.handle(IncomingEvent.of(IncomingEvent.Kind.DIRECT_MESSAGE, DM, "U1",
                "that's wrong, it's L5", "200.2", null, "Ev2")));

        assertThat(corrections()).singleElement().satisfies(c -> {
            assertThat(c).containsEntry("from_dm", true).containsEntry("author_name", "Arjun")
                    .containsEntry("question", "what is my band?");
        });
        assertThat(slack.posts.get(slack.posts.size() - 1).threadTs()).isNull();
        assertThat(agent.requests).hasSize(1);
    }

    @Test
    void oneEventIsNeverProcessedTwice() throws Exception {
        answeredThread();

        await(reply("that's wrong, it's $14", "100.5", "Ev1"));
        await(reply("that's wrong, it's $14", "100.5", "Ev1"));
        // The same message arriving again as an app_mention under another event id.
        await(handler.handle(IncomingEvent.of(IncomingEvent.Kind.MENTION, CHANNEL, "U1",
                "<@UBOT> that's wrong, it's $14", "100.5", "100.1", "Ev2")));

        assertThat(corrections()).hasSize(1);
        assertThat(llm.asked).hasSize(1);
        assertThat(agent.requests).hasSize(1);
    }

    @Test
    void theAuthorFallsBackToTheirIdWhenTheNameCannotBeLooked() throws Exception {
        answeredThread();
        slack.nameLookupFails = true;

        await(reply("that's wrong, it's $14", "100.5", "Ev1"));

        assertThat(corrections()).singleElement()
                .satisfies(c -> assertThat(c).containsEntry("author_name", "U1"));
    }

    @Test
    void aBlankFactFallsBackToTheRepliesOwnText() throws Exception {
        answeredThread();
        llm.fact = "  ";

        await(reply("that's wrong, it's $14", "100.5", "Ev1"));

        assertThat(corrections()).singleElement()
                .satisfies(c -> assertThat(c).containsEntry("correction_text", "that's wrong, it's $14"));
    }

    @Test
    void aReplyInAThreadDocs0NeverAnsweredIsStillIgnored() throws Exception {
        await(handler.handle(IncomingEvent.of(IncomingEvent.Kind.THREAD_REPLY, CHANNEL, "U1", "that's wrong",
                "300.2", "300.1", "Ev1")));

        assertThat(llm.asked).isEmpty();
        assertThat(slack.posts).isEmpty();
        assertThat(corrections()).isEmpty();
    }

    @Test
    void theCorrectionsAnAnswerUsedAreStoredOnItsInteraction() throws Exception {
        agent.behaviour = (request, progress) -> new Agent.AgentResult(Agent.Kind.ANSWER, "It is $14.",
                Agent.Confidence.HIGH, List.of(), List.of(), List.of(), 1, 0, 0, BigDecimal.ZERO, 0, null,
                List.of(4L, 9L));

        answeredThread();

        assertThat(jdbc.sql("SELECT correction_ids::text FROM interaction").query(String.class).single())
                .isEqualTo("[4, 9]");
    }

    private long interactionCount() {
        return jdbc.sql("SELECT count(*) FROM interaction").query(Long.class).single();
    }

    private static void await(CompletableFuture<Void> work) throws Exception {
        work.get(10, TimeUnit.SECONDS);
    }
}
