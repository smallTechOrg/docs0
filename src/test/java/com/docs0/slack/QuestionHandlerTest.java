package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.TestDatabase;
import com.docs0.agent.Agent;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Citation;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Exchange;
import com.docs0.agent.Agent.Kind;
import com.docs0.budget.BudgetService;
import com.docs0.config.Docs0Properties;
import com.docs0.conversation.ConversationRepository;
import com.docs0.conversation.InteractionRepository;
import com.docs0.knowledge.Audience;
import com.docs0.slack.SlackFakes.FakeAgent;
import com.docs0.slack.SlackFakes.FakeKnowledge;
import com.docs0.slack.SlackFakes.FakeMessenger;
import com.docs0.tenant.TenantService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class QuestionHandlerTest {

    private static final String CHANNEL = "C100";
    private static final String DM = "D200";

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final FakeMessenger slack = new FakeMessenger();
    private final FakeAgent agent = new FakeAgent();
    private final FakeKnowledge knowledge = new FakeKnowledge();
    private long tenantId;
    private QuestionHandler handler;

    @BeforeEach
    void setUp() {
        tenantId = TestDatabase.resetWithTenant();
        handler = newHandler(Duration.ZERO, new BigDecimal("100"));
    }

    private QuestionHandler newHandler(Duration statusInterval, BigDecimal monthlyBudget) {
        Docs0Properties props = new Docs0Properties(
                new Docs0Properties.Tenant("smalltech", "smallTech"),
                new Docs0Properties.Anthropic("", "test-model", new BigDecimal("2"), new BigDecimal("10"), 12),
                new Docs0Properties.Budget(monthlyBudget),
                new Docs0Properties.Slack("", ""),
                new Docs0Properties.Drive(15, false), null, "", "", "");
        return new QuestionHandler(new TenantService(jdbc, props), agent, knowledge, new BudgetService(jdbc, props),
                new ConversationRepository(jdbc), new InteractionRepository(jdbc), slack, statusInterval);
    }

    // ---- where the answer goes ---------------------------------------------------------------

    @Test
    void mentionInChannelIsAnsweredInTheThreadOfThatMessage() throws Exception {
        await(handler.handle(mention("<@UBOT> what is our pricing?", "100.1", null, "Ev1")));

        FakeMessenger.Posted status = slack.onlyPost();
        assertThat(status.channel()).isEqualTo(CHANNEL);
        assertThat(status.threadTs()).isEqualTo("100.1");
        assertThat(status.text()).isEqualTo(QuestionHandler.STATUS_SEARCHING);
        assertThat(agent.requests).singleElement()
                .satisfies(r -> assertThat(r.question()).isEqualTo("what is our pricing?"));
        assertThat(slack.lastUpdate().text()).isEqualTo("Answer to: what is our pricing?");
        assertThat(conversationRows()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("channel_id", CHANNEL).containsEntry("thread_ts", "100.1")
                    .containsEntry("is_dm", false);
        });
    }

    @Test
    void mentionInsideAnExistingThreadIsAnsweredInThatThread() throws Exception {
        await(handler.handle(mention("<@UBOT> and for enterprise?", "100.7", "100.1", "Ev1")));

        assertThat(slack.onlyPost().threadTs()).isEqualTo("100.1");
        assertThat(conversationRows()).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("thread_ts", "100.1"));
    }

    @Test
    void directMessageIsAnsweredUnthreadedAndTheWholeDmIsOneConversationWithHistory() throws Exception {
        await(handler.handle(dm("what is our pricing?", "200.1", null, "Ev1")));
        await(handler.handle(dm("and for enterprise?", "200.2", null, "Ev2")));

        assertThat(slack.posts).hasSize(2).allSatisfy(p -> {
            assertThat(p.channel()).isEqualTo(DM);
            assertThat(p.threadTs()).isNull();
        });
        assertThat(conversationRows()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("channel_id", DM).containsEntry("thread_ts", "").containsEntry("is_dm", true);
        });
        assertThat(agent.requests.get(0).history()).isEmpty();
        assertThat(agent.requests.get(1).history())
                .containsExactly(new Exchange("what is our pricing?", "Answer to: what is our pricing?"));
        // In a DM only the asker sees the answer; no channel lookup is needed.
        assertThat(agent.requests.get(1).audience()).isEqualTo(Audience.of("U1"));
        assertThat(slack.memberLookups).hasValue(0);
    }

    @Test
    void directMessageWrittenInAThreadIsAnsweredInThatThread() throws Exception {
        await(handler.handle(dm("what is our pricing?", "200.5", "200.1", "Ev1")));

        assertThat(slack.onlyPost().threadTs()).isEqualTo("200.1");
        assertThat(conversationRows()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("thread_ts", "200.1").containsEntry("is_dm", true);
        });
    }

    // ---- triggers and ignore rules -------------------------------------------------------------

    @Test
    void threadReplyWithoutMentionIsAnsweredOnlyAfterDocs0HasAnsweredInThatThread() throws Exception {
        await(handler.handle(threadReply("anyone know the pricing?", "100.2", "100.1", "Ev1")));
        assertThat(slack.posts).isEmpty();
        assertThat(agent.requests).isEmpty();

        await(handler.handle(mention("<@UBOT> what is our pricing?", "100.3", "100.1", "Ev2")));
        await(handler.handle(threadReply("and for enterprise?", "100.4", "100.1", "Ev3")));

        assertThat(agent.requests).extracting(Agent.AgentRequest::question)
                .containsExactly("what is our pricing?", "and for enterprise?");
        assertThat(slack.posts).hasSize(2).allSatisfy(p -> assertThat(p.threadTs()).isEqualTo("100.1"));
        assertThat(agent.requests.get(1).history()).hasSize(1);

        // A reply in some other thread is still ignored.
        await(handler.handle(threadReply("unrelated chatter", "300.2", "300.1", "Ev4")));
        assertThat(agent.requests).hasSize(2);
    }

    @Test
    void botMessagesSubtypesAndDocs0sOwnMessagesAreIgnored() throws Exception {
        await(handler.handle(new IncomingEvent(IncomingEvent.Kind.DIRECT_MESSAGE, DM, "U9", "hello", "200.1", null,
                "Ev1", null, true)));
        await(handler.handle(new IncomingEvent(IncomingEvent.Kind.DIRECT_MESSAGE, DM, "U1", "edited", "200.2", null,
                "Ev2", "message_changed", false)));
        await(handler.handle(new IncomingEvent(IncomingEvent.Kind.MENTION, CHANNEL, "U1", "<@UBOT> joined", "100.1",
                null, "Ev3", "channel_join", false)));
        await(handler.handle(new IncomingEvent(IncomingEvent.Kind.DIRECT_MESSAGE, DM, "UBOT", "my own answer",
                "200.3", null, "Ev4", null, false)));
        await(handler.handle(null));

        assertThat(slack.posts).isEmpty();
        assertThat(agent.requests).isEmpty();
        assertThat(interactionRows()).isEmpty();
    }

    @Test
    void duplicateEventIdIsAnsweredOnce() throws Exception {
        await(handler.handle(mention("<@UBOT> what is our pricing?", "100.1", null, "Ev1")));
        await(handler.handle(mention("<@UBOT> what is our pricing?", "100.1", null, "Ev1")));

        assertThat(agent.requests).hasSize(1);
        assertThat(slack.posts).hasSize(1);
        assertThat(interactionRows()).hasSize(1);
    }

    @Test
    void mentionAndMessageEventForTheSameMessageGiveOneAnswer() throws Exception {
        // First question in a thread: the message event arrives before the app_mention.
        await(handler.handle(threadReply("<@UBOT> what is our pricing?", "100.2", "100.1", "Ev1")));
        await(handler.handle(mention("<@UBOT> what is our pricing?", "100.2", "100.1", "Ev2")));
        assertThat(agent.requests).hasSize(1);

        // Thread already answered: both events qualify on their own, in either order.
        await(handler.handle(mention("<@UBOT> and enterprise?", "100.3", "100.1", "Ev3")));
        await(handler.handle(threadReply("<@UBOT> and enterprise?", "100.3", "100.1", "Ev4")));
        await(handler.handle(threadReply("<@UBOT> and startups?", "100.4", "100.1", "Ev5")));
        await(handler.handle(mention("<@UBOT> and startups?", "100.4", "100.1", "Ev6")));

        assertThat(agent.requests).extracting(Agent.AgentRequest::question)
                .containsExactly("what is our pricing?", "and enterprise?", "and startups?");
        assertThat(slack.posts).hasSize(3);
        assertThat(interactionRows()).hasSize(3);
    }

    @Test
    void emptyMentionGetsAOneLineHint() throws Exception {
        await(handler.handle(mention("<@UBOT>  ", "100.1", null, "Ev1")));

        FakeMessenger.Posted hint = slack.onlyPost();
        assertThat(hint.text()).isEqualTo(QuestionHandler.HINT).doesNotContain("\n");
        assertThat(hint.threadTs()).isEqualTo("100.1");
        assertThat(agent.requests).isEmpty();
        assertThat(slack.updates).isEmpty();
    }

    // ---- reasons not to call the agent -----------------------------------------------------------

    @Test
    void saysSoWhenTheAgentIsNotConnected() throws Exception {
        agent.available = false;

        await(handler.handle(mention("<@UBOT> what is our pricing?", "100.1", null, "Ev1")));

        assertThat(slack.onlyPost().text()).isEqualTo(QuestionHandler.NOT_CONNECTED);
        assertThat(agent.requests).isEmpty();
        assertThat(interactionRows()).isEmpty();
    }

    @Test
    void refusesAndLogsOverBudgetWithoutCallingTheAgent() throws Exception {
        handler = newHandler(Duration.ZERO, new BigDecimal("0.05"));
        agent.behaviour = (request, progress) -> new AgentResult(Kind.ANSWER, "It is $10.", Confidence.HIGH,
                List.of(), List.of(), List.of(), 2, 1000, 200, new BigDecimal("0.05"), 900, null);
        await(handler.handle(mention("<@UBOT> what is our pricing?", "100.1", null, "Ev1")));
        assertThat(agent.requests).hasSize(1);

        await(handler.handle(mention("<@UBOT> and for enterprise?", "100.5", null, "Ev2")));

        assertThat(agent.requests).hasSize(1);
        FakeMessenger.Posted refusal = slack.posts.get(1);
        assertThat(refusal.text()).isEqualTo(QuestionHandler.OVER_BUDGET);
        assertThat(refusal.threadTs()).isEqualTo("100.5");
        Map<String, Object> row = interactionRows().get(1);
        assertThat(row).containsEntry("kind", "OVER_BUDGET")
                .containsEntry("question", "and for enterprise?")
                .containsEntry("slack_user_id", "U1")
                .containsEntry("answer_ts", refusal.ts());
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0");
    }

    @Test
    void saysSoWhenNoSourcesAreSynced() throws Exception {
        knowledge.count = 0;

        await(handler.handle(dm("what is our pricing?", "200.1", null, "Ev1")));

        assertThat(slack.onlyPost().text()).isEqualTo(QuestionHandler.NO_SOURCES);
        assertThat(agent.requests).isEmpty();
    }

    // ---- status line -------------------------------------------------------------------------------

    @Test
    void statusMessageIsUpdatedInPlaceAndReplacedByTheAnswer() throws Exception {
        AtomicReference<Agent.ProgressListener> kept = new AtomicReference<>();
        agent.behaviour = (request, progress) -> {
            kept.set(progress);
            assertThat(slack.posts).as("status is posted before the agent runs").hasSize(1);
            progress.onStatus("Searching for pricing…");
            progress.onStatus("Reading *Pricing 2026*…");
            return SlackFakes.result(Kind.ANSWER, "It is **$10** per seat.");
        };

        await(handler.handle(mention("<@UBOT> what is our pricing?", "100.1", null, "Ev1")));
        // A straggling status after the answer must not overwrite it.
        kept.get().onStatus("Still searching…");

        String statusTs = slack.onlyPost().ts();
        assertThat(slack.updates).extracting(FakeMessenger.Updated::text)
                .containsExactly("Searching for pricing…", "Reading *Pricing 2026*…", "It is *$10* per seat.");
        assertThat(slack.updates).allSatisfy(u -> {
            assertThat(u.ts()).isEqualTo(statusTs);
            assertThat(u.channel()).isEqualTo(CHANNEL);
        });
        assertThat(slack.lastUpdate().blocks()).isNotEmpty();
    }

    @Test
    void statusUpdatesInsideTheThrottleIntervalAreNotSent() throws Exception {
        handler = newHandler(Duration.ofHours(1), new BigDecimal("100"));
        agent.behaviour = (request, progress) -> {
            progress.onStatus("Searching for pricing…");
            progress.onStatus("Reading *Pricing 2026*…");
            return SlackFakes.result(Kind.ANSWER, "It is $10.");
        };

        await(handler.handle(mention("<@UBOT> what is our pricing?", "100.1", null, "Ev1")));

        assertThat(slack.updates).extracting(FakeMessenger.Updated::text).containsExactly("It is $10.");
    }

    // ---- failures ----------------------------------------------------------------------------------

    @Test
    void agentErrorEndsInAnApologyAndALoggedInteraction() throws Exception {
        agent.behaviour = (request, progress) -> SlackFakes.result(Kind.ERROR, null);

        await(handler.handle(mention("<@UBOT> what is our pricing?", "100.1", null, "Ev1")));

        FakeMessenger.Updated last = slack.lastUpdate();
        assertThat(last.ts()).isEqualTo(slack.onlyPost().ts());
        assertThat(last.text()).isEqualTo(QuestionHandler.APOLOGY).doesNotContain("secret-internal-detail");
        assertThat(last.blocks()).isNullOrEmpty();
        assertThat(interactionRows()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "ERROR").containsEntry("answer_ts", last.ts())
                    .containsEntry("error", "model exploded: secret-internal-detail");
        });
    }

    @Test
    void thrownExceptionEndsInAnApologyAndALoggedInteraction() throws Exception {
        agent.behaviour = (request, progress) -> {
            progress.onStatus("Searching…");
            throw new IllegalStateException("boom at com.docs0.internal");
        };

        await(handler.handle(dm("what is our pricing?", "200.1", null, "Ev1")));

        FakeMessenger.Updated last = slack.lastUpdate();
        assertThat(last.ts()).isEqualTo(slack.onlyPost().ts());
        assertThat(last.text()).isEqualTo(QuestionHandler.APOLOGY);
        assertThat(interactionRows()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "ERROR").containsEntry("question", "what is our pricing?")
                    .containsEntry("answer", null);
            assertThat((String) row.get("error")).contains("IllegalStateException").contains("boom");
        });
    }

    @Test
    void answerIsSentAsPlainTextWhenSlackRejectsTheLayout() throws Exception {
        slack.rejectBlocks = true;

        await(handler.handle(dm("what is our pricing?", "200.1", null, "Ev1")));

        assertThat(slack.updates).singleElement().satisfies(u -> {
            assertThat(u.text()).isEqualTo("Answer to: what is our pricing?");
            assertThat(u.blocks()).isNull();
        });
        assertThat(interactionRows()).singleElement().satisfies(row -> assertThat(row).containsEntry("kind", "ANSWER"));
    }

    @Test
    void anAnswerThatCannotBeShownIsLoggedAsAnError() throws Exception {
        slack.rejectAllUpdates = true;

        await(handler.handle(dm("what is our pricing?", "200.1", null, "Ev1")));

        assertThat(interactionRows()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "ERROR");
            assertThat((String) row.get("error")).contains("invalid_blocks");
        });
    }

    // ---- history, logging, ordering, audience ---------------------------------------------------------

    @Test
    void historyIsOldestFirstCappedAtTenAndExcludesErrors() throws Exception {
        agent.behaviour = (request, progress) -> request.question().equals("q5")
                ? SlackFakes.result(Kind.ERROR, null)
                : SlackFakes.result(Kind.ANSWER, "a-" + request.question());
        for (int i = 1; i <= 13; i++) {
            await(handler.handle(dm("q" + i, "200." + i, null, "Ev" + i)));
        }

        List<Exchange> history = agent.requests.get(12).history();
        // 12 earlier questions, q5 failed: the last ten good ones are q2..q12 without q5.
        assertThat(history).extracting(Exchange::question)
                .containsExactly("q2", "q3", "q4", "q6", "q7", "q8", "q9", "q10", "q11", "q12");
        assertThat(history.get(0).answer()).isEqualTo("a-q2");
    }

    @Test
    void interactionRowHoldsTheWholeResult() throws Exception {
        agent.behaviour = (request, progress) -> new AgentResult(Kind.ANSWER, "It is $10 [1].", Confidence.MEDIUM,
                List.of(new Citation(1, 42, "Pricing 2026", "https://docs.example/pricing", "Seats cost $10.")),
                List.of("And enterprise?"), List.of("Asha"), 4, 12345, 678, new BigDecimal("0.031470"), 2500, null);

        await(handler.handle(mention("<@UBOT> what is our pricing?", "100.1", null, "Ev1")));

        Map<String, Object> row = interactionRows().get(0);
        assertThat(row).containsEntry("tenant_id", tenantId)
                .containsEntry("slack_user_id", "U1")
                .containsEntry("question", "what is our pricing?")
                .containsEntry("answer", "It is $10 [1].")
                .containsEntry("kind", "ANSWER")
                .containsEntry("confidence", "MEDIUM")
                .containsEntry("steps", 4)
                .containsEntry("input_tokens", 12345L)
                .containsEntry("output_tokens", 678L)
                .containsEntry("duration_ms", 2500L)
                .containsEntry("error", null)
                .containsEntry("answer_ts", slack.onlyPost().ts());
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.031470");
        assertThat(jdbc.sql("""
                        SELECT citations -> 0 ->> 'title' || '|' || (citations -> 0 ->> 'documentId') || '|' ||
                               (citations -> 0 ->> 'index') || '|' || (citations -> 0 ->> 'url') || '|' ||
                               (citations -> 0 ->> 'quote') || '|' || jsonb_array_length(citations)
                        FROM interaction WHERE tenant_id = :tenantId
                        """).param("tenantId", tenantId).query(String.class).single())
                .isEqualTo("Pricing 2026|42|1|https://docs.example/pricing|Seats cost $10.|1");
    }

    @Test
    void questionsInOneConversationRunOneAtATimeInOrder() throws Exception {
        List<String> trace = new CopyOnWriteArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        agent.behaviour = (request, progress) -> {
            trace.add("start " + request.question());
            if (request.question().equals("first")) {
                firstStarted.countDown();
                awaitLatch(releaseFirst);
            }
            trace.add("end " + request.question());
            return SlackFakes.result(Kind.ANSWER, "a-" + request.question());
        };

        CompletableFuture<Void> first = handler.handle(dm("first", "200.1", null, "Ev1"));
        assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> second = handler.handle(dm("second", "200.2", null, "Ev2"));
        // A question in another conversation is not held up.
        await(handler.handle(mention("<@UBOT> elsewhere", "100.1", null, "Ev3")));
        assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        assertThat(trace).containsExactly("start first", "start elsewhere", "end elsewhere");

        releaseFirst.countDown();
        await(first);
        await(second);

        assertThat(trace).containsExactly("start first", "start elsewhere", "end elsewhere", "end first",
                "start second", "end second");
        assertThat(agent.requests.get(2).history()).containsExactly(new Exchange("first", "a-first"));
    }

    @Test
    void audienceIsTheChannelMembers() throws Exception {
        slack.members = java.util.Set.of("U1", "U2", "U3");

        await(handler.handle(mention("<@UBOT> what is our pricing?", "100.1", null, "Ev1")));

        assertThat(agent.requests.get(0).audience()).isEqualTo(Audience.of("U1", "U2", "U3"));
        assertThat(agent.requests.get(0).tenantId()).isEqualTo(tenantId);
    }

    @Test
    void audienceFallsBackToTheAskerWhenMemberLookupFails() throws Exception {
        slack.memberLookupFails = true;

        await(handler.handle(mention("<@UBOT> what is our pricing?", "100.1", null, "Ev1")));

        assertThat(agent.requests.get(0).audience()).isEqualTo(Audience.of("U1"));
        assertThat(slack.lastUpdate().text()).isEqualTo("Answer to: what is our pricing?");
    }

    // ---- follow-up buttons -----------------------------------------------------------------------------

    @Test
    void followUpIsShownInTheThreadAndAnsweredThereForTheClicker() throws Exception {
        await(handler.handle(mention("<@UBOT> what is our pricing?", "100.1", null, "Ev1")));

        await(handler.followUp(CHANNEL, "100.1", "U7", "And <enterprise>?", "act-1"));
        await(handler.followUp(CHANNEL, "100.1", "U7", "And <enterprise>?", "act-1"));

        assertThat(slack.posts).hasSize(3);
        assertThat(slack.posts.get(1).text()).isEqualTo("*<@U7> asked:* And &lt;enterprise&gt;?");
        assertThat(slack.posts.get(1).threadTs()).isEqualTo("100.1");
        assertThat(slack.posts.get(2).threadTs()).isEqualTo("100.1");
        assertThat(agent.requests.get(1).question()).isEqualTo("And <enterprise>?");
        assertThat(agent.requests.get(1).history()).hasSize(1);
        assertThat(interactionRows().get(1)).containsEntry("slack_user_id", "U7");
        assertThat(conversationRows()).hasSize(1);
    }

    @Test
    void followUpInAnUnthreadedDmStaysUnthreadedInTheSameConversation() throws Exception {
        await(handler.handle(dm("what is our pricing?", "200.1", null, "Ev1")));

        await(handler.followUp(DM, null, "U1", "And enterprise?", "act-1"));

        assertThat(slack.posts).hasSize(3).allSatisfy(p -> assertThat(p.threadTs()).isNull());
        assertThat(agent.requests.get(1).audience()).isEqualTo(Audience.of("U1"));
        assertThat(conversationRows()).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("thread_ts", "").containsEntry("is_dm", true));
    }

    @Test
    void stripsOnlyDocs0sMention() {
        assertThat(QuestionHandler.stripMention("<@UBOT> ask <@U2> about pricing", "UBOT", true))
                .isEqualTo("ask <@U2> about pricing");
        assertThat(QuestionHandler.stripMention("hey <@UBOT|docs0> pricing?", "UBOT", true)).isEqualTo("hey   pricing?");
        assertThat(QuestionHandler.stripMention("<@UXYZ> pricing?", null, true)).isEqualTo("pricing?");
        assertThat(QuestionHandler.stripMention(null, "UBOT", true)).isEmpty();
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private static IncomingEvent mention(String text, String ts, String threadTs, String eventId) {
        return IncomingEvent.of(IncomingEvent.Kind.MENTION, CHANNEL, "U1", text, ts, threadTs, eventId);
    }

    private static IncomingEvent threadReply(String text, String ts, String threadTs, String eventId) {
        return IncomingEvent.of(IncomingEvent.Kind.THREAD_REPLY, CHANNEL, "U1", text, ts, threadTs, eventId);
    }

    private static IncomingEvent dm(String text, String ts, String threadTs, String eventId) {
        return IncomingEvent.of(IncomingEvent.Kind.DIRECT_MESSAGE, DM, "U1", text, ts, threadTs, eventId);
    }

    private static void await(CompletableFuture<Void> work) throws Exception {
        work.get(10, TimeUnit.SECONDS);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> conversationRows() {
        return jdbc.sql("SELECT * FROM conversation WHERE tenant_id = :tenantId ORDER BY id")
                .param("tenantId", tenantId).query().listOfRows();
    }

    private List<Map<String, Object>> interactionRows() {
        return jdbc.sql("SELECT * FROM interaction WHERE tenant_id = :tenantId ORDER BY id")
                .param("tenantId", tenantId).query().listOfRows();
    }
}
