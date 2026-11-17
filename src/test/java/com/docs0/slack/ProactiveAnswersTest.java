package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.TestDatabase;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Citation;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Kind;
import com.docs0.agent.LlmClient;
import com.docs0.budget.BudgetService;
import com.docs0.budget.UsageLog;
import com.docs0.config.Docs0Properties;
import com.docs0.conversation.Conversation;
import com.docs0.conversation.ConversationRepository;
import com.docs0.conversation.InteractionRepository;
import com.docs0.feedback.FeedbackRepository;
import com.docs0.feedback.FeedbackRepository.Rating;
import com.docs0.knowledge.Audience;
import com.docs0.settings.ChannelSettings;
import com.docs0.slack.SlackFakes.FakeAgent;
import com.docs0.slack.SlackFakes.FakeKnowledge;
import com.docs0.slack.SlackFakes.FakeMessenger;
import com.docs0.stale.StaleDocs;
import com.docs0.tenant.TenantService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Answering a repeat question unprompted: when docs0 stays silent, what it matches, and what it posts. */
class ProactiveAnswersTest {

    private static final String CHANNEL = "C100";
    private static final String NEW_QUESTION = "How do I reset my VPN password?";
    private static final String OLD_QUESTION = "How can I reset the VPN password?";

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final FakeMessenger slack = new FakeMessenger();
    private final FakeAgent agent = new FakeAgent();
    private final Judge judge = new Judge();
    private long tenantId;
    private Docs0Properties props;
    private ChannelSettings channels;
    private InteractionRepository interactions;
    private QuestionHandler handler;
    private int eventCounter;

    /** A fake model for the repeat-question judge. */
    static final class Judge implements LlmClient {
        final List<String> asked = new CopyOnWriteArrayList<>();
        volatile boolean same = true;
        volatile boolean fails;
        volatile boolean noToolCall;

        @Override
        public LlmTurn oneShot(String systemPrompt, String userText, ToolSpec tool) {
            asked.add(userText);
            if (fails) {
                throw new LlmException("overloaded_error", "busy", null);
            }
            List<ToolCall> calls = noToolCall ? List.of()
                    : List.of(new ToolCall("t1", tool.name(), Map.of("same", same, "reason", "because")));
            return new LlmTurn(StopReason.TOOL_USE, "", calls, 500_000, 50_000, "tool_use");
        }

        @Override
        public LlmSession start(String systemPrompt, List<ToolSpec> tools) {
            throw new UnsupportedOperationException();
        }
    }

    @BeforeEach
    void setUp() {
        tenantId = TestDatabase.resetWithTenant();
        props = props(new BigDecimal("100"));
        channels = new ChannelSettings(jdbc);
        interactions = new InteractionRepository(jdbc);
        handler = handler(props, 3);
        channels.saveProactive(tenantId, List.of(new ChannelSettings.Channel(CHANNEL, "general")), Set.of(CHANNEL));
        agent.behaviour = (request, progress) -> new AgentResult(Kind.ANSWER, "Use the self-service portal.",
                Confidence.HIGH, List.of(new Citation(1, 5, "VPN guide", "https://docs.example/vpn", "portal")),
                List.of(), List.of(), 2, 1000, 100, new BigDecimal("0.05"), 10, null);
        slack.members = Set.of("U1", "U2", "UBOT");
        knowledge().count = 3;
        pastAnswer("C200", "50.1", OLD_QUESTION, "ANSWER", "HIGH");
    }

    private final FakeKnowledge knowledgeFake = new FakeKnowledge();

    private FakeKnowledge knowledge() {
        return knowledgeFake;
    }

    private static Docs0Properties props(BigDecimal budget) {
        return new Docs0Properties(new Docs0Properties.Tenant("smalltech", "smallTech"),
                new Docs0Properties.Anthropic("", "test-model", new BigDecimal("2"), new BigDecimal("10"), 12),
                new Docs0Properties.Budget(budget), new Docs0Properties.Slack("", ""),
                new Docs0Properties.Drive(15, false), null, "", "", "");
    }

    private QuestionHandler handler(Docs0Properties p, int maxPerHour) {
        BudgetService budget = new BudgetService(jdbc, p);
        UsageLog usage = new UsageLog(jdbc);
        ProactiveResponder responder = new ProactiveResponder(channels, interactions, new ProactiveOffers(jdbc), budget,
                usage, judge, slack, p, maxPerHour);
        CorrectionFlow flow = new CorrectionFlow(new CorrectionDetector(new SlackFakes.FakeLlm(), p),
                new ConversationRepository(jdbc), interactions, new FeedbackRepository(jdbc),
                new com.docs0.feedback.CorrectionRepository(jdbc), slack, new TenantService(jdbc), budget);
        return new QuestionHandler(new TenantService(jdbc), agent, knowledgeFake, budget,
                new ConversationRepository(jdbc), interactions, slack, flow,
                new com.docs0.action.PendingActionRepository(jdbc), new IdentityService(jdbc, slack), responder,
                new StaleDocs(jdbc, slack, 365), usage, Duration.ZERO);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private long pastAnswer(String channel, String threadTs, String question, String kind, String confidence) {
        boolean dm = channel.startsWith("D");
        Conversation c = new ConversationRepository(jdbc).findOrCreate(tenantId, channel, dm ? "" : threadTs, dm, dm ? Boolean.TRUE : Boolean.FALSE);
        AgentResult result = new AgentResult(Kind.valueOf(kind), "An answer.", Confidence.valueOf(confidence),
                List.of(), List.of(), List.of(), 1, 100, 10, new BigDecimal("0.01"), 5, null);
        return interactions.insert(tenantId, c.id(), "U9", question, result, "60." + System.nanoTime() % 1000000);
    }

    private IncomingEvent message(String text, String ts) {
        return new IncomingEvent(IncomingEvent.Kind.CHANNEL_MESSAGE, CHANNEL, "U1", text, ts, null,
                "Ev" + (++eventCounter), null, false);
    }

    /** Slack's answer to "does this message have replies": none, only the message itself. */
    private void noReplies(String ts) {
        slack.threads.put(CHANNEL + ":" + ts, List.of(new SlackMessenger.SlackMessage(ts, ts, "U1", null, null, null,
                "x", 0, null, null, List.of())));
    }

    private void send(IncomingEvent event) throws Exception {
        CompletableFuture<Void> done = handler.handle(tenantId, event);
        done.get(10, TimeUnit.SECONDS);
    }

    private void ask(String text, String ts) throws Exception {
        noReplies(ts);
        send(message(text, ts));
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :t").param("t", tenantId)
                .query(Long.class).single();
    }

    private void assertSilent() {
        assertThat(slack.posts).isEmpty();
        assertThat(slack.updates).isEmpty();
        assertThat(agent.requests).isEmpty();
    }

    private void offerRow(String ts, boolean same, Instant at) {
        long matched = jdbc.sql("SELECT min(id) FROM interaction WHERE tenant_id = :t").param("t", tenantId)
                .query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO proactive_offer (tenant_id, channel_id, message_ts, matched_interaction_id, judged_same, created_at)
                        VALUES (:t, :c, :ts, :m, :same, :at)
                        """)
                .param("t", tenantId).param("c", CHANNEL).param("ts", ts).param("m", matched).param("same", same)
                .param("at", java.sql.Timestamp.from(at)).update();
    }

    // ---- the happy path ---------------------------------------------------------------------------

    @Test
    void aRepeatQuestionGetsAnAnswerInAThreadWithTheLeadLine() throws Exception {
        ask(NEW_QUESTION, "100.1");

        FakeMessenger.Posted post = slack.onlyPost();
        assertThat(post.channel()).isEqualTo(CHANNEL);
        assertThat(post.threadTs()).isEqualTo("100.1");
        assertThat(post.text()).startsWith(ProactiveResponder.LEAD).contains("Use the self-service portal.");
        assertThat(post.blocks()).isNotEmpty();
        assertThat(post.blocks().get(0).toString()).contains("This looks like something we've covered before");
        // Re-derived from sources: the agent ran on the new question, with no history to replay.
        assertThat(agent.requests).singleElement().satisfies(r -> {
            assertThat(r.question()).isEqualTo(NEW_QUESTION);
            assertThat(r.history()).isEmpty();
            assertThat(r.audience()).isEqualTo(Audience.of("U1", "U2"));
        });
        // Logged as an interaction in its own thread, linked from the offer.
        Map<String, Object> interaction = jdbc.sql("""
                        SELECT i.id, i.answer_ts, i.kind, c.thread_ts, c.is_dm FROM interaction i
                        JOIN conversation c ON c.id = i.conversation_id WHERE i.answer_ts = :ts
                        """).param("ts", post.ts()).query().singleRow();
        assertThat(interaction).containsEntry("kind", "ANSWER").containsEntry("thread_ts", "100.1")
                .containsEntry("is_dm", false);
        Map<String, Object> offer = jdbc.sql("SELECT * FROM proactive_offer WHERE tenant_id = :t").param("t", tenantId)
                .query().singleRow();
        assertThat(offer).containsEntry("channel_id", CHANNEL).containsEntry("message_ts", "100.1")
                .containsEntry("judged_same", true).containsEntry("interaction_id", interaction.get("id"));
        // The judge's cost is in the usage log and counts toward the cap; the answer's is in the interaction.
        assertThat(count("usage_log")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT purpose FROM usage_log").query(String.class).single()).isEqualTo("proactive_judge");
        BudgetService budget = new BudgetService(jdbc, props);
        assertThat(budget.otherSpentThisMonth(tenantId)).isEqualByComparingTo("1.5");
        // 1.50 judge + 0.05 answer + 0.01 for the seeded earlier answer.
        assertThat(budget.spentThisMonth(tenantId)).isEqualByComparingTo("1.56");
    }

    @Test
    void anUnpromptedAnswerInAPublicChannelUsesSharedKnowledgeOnly() throws Exception {
        slack.publicChannels.add(CHANNEL);

        ask(NEW_QUESTION, "100.1");

        assertThat(agent.requests).singleElement().satisfies(r -> assertThat(r.audience()).isEqualTo(Audience.sharedOnly()));
    }

    @Test
    void aShownAnswerWhoseInteractionCouldNotBeLoggedStillCostsSomething() throws Exception {
        interactions = new InteractionRepository(jdbc) {
            @Override
            public long insert(long tenantId, long conversationId, String slackUserId, String question,
                    AgentResult result, String answerTs) {
                throw new IllegalStateException("database is busy");
            }
        };
        handler = handler(props, 3);

        ask(NEW_QUESTION, "100.1");

        assertThat(slack.posts).hasSize(1);
        assertThat(jdbc.sql("SELECT purpose FROM usage_log WHERE tenant_id = :t ORDER BY id").param("t", tenantId)
                .query(String.class).list()).contains("proactive_answer");
        assertThat(jdbc.sql("SELECT sum(cost_usd) FROM usage_log WHERE purpose = 'proactive_answer'")
                .query(java.math.BigDecimal.class).single()).isEqualByComparingTo("0.05");
    }

    @Test
    void aThumbsDownOnAnUnpromptedAnswerCountsLikeAnyOtherAndMutesTheChannel() throws Exception {
        ask(NEW_QUESTION, "100.1");
        String answerTs = slack.onlyPost().ts();
        ReactionHandler reactions = new ReactionHandler(interactions,
                new FeedbackRepository(jdbc), slack);

        reactions.handle(tenantId, new ReactionEvent(true, CHANNEL, "U2", "-1", answerTs)).get(10, TimeUnit.SECONDS);

        assertThat(jdbc.sql("SELECT count(*) FROM feedback WHERE rating = 'DOWN'").query(Long.class).single()).isEqualTo(1);
        // Quiet for a day: a similar question is not answered even though the cool-down is long past.
        jdbc.sql("UPDATE proactive_offer SET created_at = now() - interval '10 minutes'").update();
        slack.posts.clear();
        agent.requests.clear();
        ask("How do I reset my VPN password again?", "100.9");
        assertThat(agent.requests).isEmpty();
    }

    @Test
    void theAnswerOfAThreadCanBeFollowedUpWithoutAMention() throws Exception {
        ask(NEW_QUESTION, "100.1");

        send(new IncomingEvent(IncomingEvent.Kind.THREAD_REPLY, CHANNEL, "U2", "and for the VPN on a phone?", "100.5",
                "100.1", "EvX", null, false));

        assertThat(agent.requests).hasSize(2);
    }

    // ---- when docs0 stays silent: the gating table ----------------------------------------------------

    @Test
    void silentWhenTheChannelIsNotOptedIn() throws Exception {
        channels.saveProactive(tenantId, List.of(new ChannelSettings.Channel(CHANNEL, "general")), Set.of());

        ask(NEW_QUESTION, "100.1");

        assertSilent();
        assertThat(judge.asked).isEmpty();
        assertThat(count("proactive_offer")).isZero();
    }

    @Test
    void silentWhenTheChannelWasNeverConfigured() throws Exception {
        jdbc.sql("DELETE FROM channel_setting").update();

        ask(NEW_QUESTION, "100.1");

        assertSilent();
    }

    @Test
    void silentForThreadRepliesBotsSubtypesAndMentions() throws Exception {
        noReplies("100.1");
        send(new IncomingEvent(IncomingEvent.Kind.THREAD_REPLY, CHANNEL, "U1", NEW_QUESTION, "100.2", "100.1", "E1", null, false));
        send(new IncomingEvent(IncomingEvent.Kind.CHANNEL_MESSAGE, CHANNEL, "U1", NEW_QUESTION, "100.3", "100.1", "E2", null, false));
        send(new IncomingEvent(IncomingEvent.Kind.CHANNEL_MESSAGE, CHANNEL, "U1", NEW_QUESTION, "100.4", null, "E3", null, true));
        send(new IncomingEvent(IncomingEvent.Kind.CHANNEL_MESSAGE, CHANNEL, "UBOT", NEW_QUESTION, "100.5", null, "E4", null, false));
        send(new IncomingEvent(IncomingEvent.Kind.CHANNEL_MESSAGE, CHANNEL, "U1", NEW_QUESTION, "100.6", null, "E5", "me_message", false));
        ask("<@UBOT> " + NEW_QUESTION, "100.7");
        ask("hey <@UBOT|docs0> " + NEW_QUESTION, "100.8");

        assertSilent();
        assertThat(judge.asked).isEmpty();
    }

    @Test
    void silentForDirectAndGroupMessages() throws Exception {
        send(new IncomingEvent(IncomingEvent.Kind.CHANNEL_MESSAGE, "D200", "U1", NEW_QUESTION, "100.1", null, "E1", null, false));
        channels.saveProactive(tenantId, List.of(new ChannelSettings.Channel("D200", "dm")), Set.of("D200"));
        send(new IncomingEvent(IncomingEvent.Kind.CHANNEL_MESSAGE, "D200", "U1", NEW_QUESTION, "100.2", null, "E2", null, false));

        assertSilent();
    }

    @Test
    void silentForMessagesThatDoNotLookLikeQuestions() throws Exception {
        ask("VPN?", "1.1");                                              // too short
        ask("reset vpn password", "1.2");                                // three words, no question
        ask("We changed the VPN password reset flow yesterday", "1.3");  // a statement
        ask("how reset vpn password " + "x".repeat(600), "1.4");         // too long
        ask("<https://example.com/vpn/reset?pw=1>", "1.5");              // just a link
        ask("see https://example.com/vpn/reset?x=1 ?", "1.6");           // a link and a mark

        assertSilent();
        assertThat(judge.asked).isEmpty();
    }

    @Test
    void theCheapQuestionCheck() {
        assertThat(ProactiveResponder.looksLikeQuestion("how do I reset my VPN password")).isTrue();
        assertThat(ProactiveResponder.looksLikeQuestion("anyone know where the wifi password is?")).isTrue();
        assertThat(ProactiveResponder.looksLikeQuestion("Should we move standup to ten")).isTrue();
        assertThat(ProactiveResponder.looksLikeQuestion("what time")).isFalse();
        assertThat(ProactiveResponder.looksLikeQuestion("thanks that worked for me")).isFalse();
        assertThat(ProactiveResponder.plainText("hi <@U1> see <https://x.example/a|the guide> &amp; <https://y.example>"))
                .isEqualTo("hi see the guide &");
    }

    @Test
    void silentOverTheRateLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            offerRow("50." + i, true, Instant.now().minus(Duration.ofMinutes(10 + i)));
        }

        ask(NEW_QUESTION, "100.1");

        assertSilent();
        assertThat(judge.asked).isEmpty();
    }

    @Test
    void anOfferJustUnderTheLimitIsStillAnsweredAndAnOldOneDoesNotCount() throws Exception {
        offerRow("50.1", true, Instant.now().minus(Duration.ofMinutes(10)));
        offerRow("50.2", true, Instant.now().minus(Duration.ofMinutes(15)));
        offerRow("50.3", true, Instant.now().minus(Duration.ofHours(2)));

        ask(NEW_QUESTION, "100.1");

        assertThat(slack.posts).hasSize(1);
    }

    @Test
    void silentDuringTheCoolDown() throws Exception {
        offerRow("50.1", true, Instant.now().minus(Duration.ofSeconds(30)));

        ask(NEW_QUESTION, "100.1");

        assertSilent();
    }

    @Test
    void silentWhenTheBudgetIsUsedUp() throws Exception {
        props = props(new BigDecimal("0.001"));
        handler = handler(props, 3);

        ask(NEW_QUESTION, "100.1");

        assertSilent();
        assertThat(judge.asked).isEmpty();
    }

    @Test
    void theJudgesCostCountsTowardTheCap() throws Exception {
        // The judge costs 1.50 per call here; a cap of 1.20 is used up by one judgement.
        props = props(new BigDecimal("1.20"));
        handler = handler(props, 3);
        judge.same = false;

        ask(NEW_QUESTION, "100.1");
        assertThat(judge.asked).hasSize(1);
        assertThat(new BudgetService(jdbc, props).exhausted(tenantId)).isTrue();

        ask("How do I reset my VPN password now?", "100.2");
        assertThat(judge.asked).hasSize(1);
    }

    @Test
    void silentForAMessageThatAlreadyHasReplies() throws Exception {
        slack.threads.put(CHANNEL + ":100.1", List.of(
                new SlackMessenger.SlackMessage("100.1", "100.1", "U1", null, null, null, "x", 1, "100.2", null, List.of()),
                new SlackMessenger.SlackMessage("100.2", "100.1", "U2", null, null, null, "try IT", 0, null, null, List.of())));

        send(message(NEW_QUESTION, "100.1"));

        assertSilent();
        assertThat(judge.asked).isEmpty();
    }

    @Test
    void silentWhenSlackCannotSayWhetherItHasReplies() throws Exception {
        send(message(NEW_QUESTION, "100.1"));   // no thread registered: the fake raises thread_not_found

        assertSilent();
    }

    @Test
    void neverTwiceForTheSameMessage() throws Exception {
        noReplies("100.1");
        send(message(NEW_QUESTION, "100.1"));
        agent.requests.clear();
        slack.posts.clear();

        send(message(NEW_QUESTION, "100.1"));            // Slack redelivers it with a new event id
        // ...and even if the in-memory de-duplication had been forgotten, the offer log still says it was done
        handler = handler(props, 3);
        offerRowIfMissing("100.1");
        send(message(NEW_QUESTION, "100.1"));

        assertThat(agent.requests).isEmpty();
        assertThat(slack.posts).isEmpty();
        assertThat(count("proactive_offer")).isEqualTo(1);
    }

    private void offerRowIfMissing(String ts) {
        assertThat(new ProactiveOffers(jdbc).exists(tenantId, CHANNEL, ts)).isTrue();
    }

    @Test
    void anAlreadyLoggedOfferBlocksANewHandlerToo() throws Exception {
        offerRow("100.1", false, Instant.now().minus(Duration.ofMinutes(30)));

        ask(NEW_QUESTION, "100.1");

        assertSilent();
        assertThat(judge.asked).isEmpty();
    }

    @Test
    void silentWhenThereIsNoEarlierQuestionLikeIt() throws Exception {
        ask("Where do we keep the quarterly board slides?", "100.1");

        assertSilent();
        assertThat(judge.asked).isEmpty();
        assertThat(count("proactive_offer")).isZero();
    }

    // ---- the judge --------------------------------------------------------------------------------

    @Test
    void whenTheJudgeSaysNoNothingIsPostedButItsCostIsKept() throws Exception {
        judge.same = false;

        ask(NEW_QUESTION, "100.1");

        assertSilent();
        assertThat(judge.asked).singleElement().satisfies(text -> assertThat(text).contains(NEW_QUESTION).contains(OLD_QUESTION));
        assertThat(count("usage_log")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT judged_same FROM proactive_offer").query(Boolean.class).single()).isFalse();
    }

    @Test
    void whenTheJudgeFailsOrGivesNoVerdictNothingIsPosted() throws Exception {
        judge.fails = true;
        ask(NEW_QUESTION, "100.1");
        judge.fails = false;
        judge.noToolCall = true;
        ask("How do I reset my VPN password please?", "100.2");

        assertSilent();
    }

    @Test
    void theJudgeSeesTheQuestionsOnlyAndAtMostThreeCandidates() throws Exception {
        pastAnswer("C200", "50.2", "How to reset VPN password on my laptop?", "ANSWER", "MEDIUM");
        pastAnswer("C200", "50.3", "reset vpn password how", "ANSWER", "HIGH");
        pastAnswer("C200", "50.4", "VPN password reset steps?", "ANSWER", "HIGH");

        ask(NEW_QUESTION, "100.1");

        assertThat(judge.asked).singleElement().satisfies(text ->
                assertThat(text.split("<earlier_question").length - 1).isEqualTo(3));
        assertThat(judge.asked.get(0)).doesNotContain("An answer.");
    }

    // ---- the answer must be worth posting ---------------------------------------------------------

    @Test
    void anAnswerThatIsNotGoodEnoughIsNotPostedAndItsCostGoesToTheUsageLog() throws Exception {
        agent.behaviour = (request, progress) -> new AgentResult(Kind.UNKNOWN, "I could not find it.", Confidence.LOW,
                List.of(), List.of(), List.of(), 2, 1000, 100, new BigDecimal("0.05"), 10, null);

        ask(NEW_QUESTION, "100.1");

        assertThat(slack.posts).isEmpty();
        assertThat(count("interaction")).isEqualTo(1);   // only the seeded past answer
        assertThat(jdbc.sql("SELECT purpose FROM usage_log ORDER BY id").query(String.class).list())
                .containsExactly("proactive_judge", "proactive_answer");
    }

    @Test
    void anAnswerWithoutSourcesOrWithLowConfidenceIsNotPosted() throws Exception {
        agent.behaviour = (request, progress) -> new AgentResult(Kind.ANSWER, "Maybe.", Confidence.HIGH, List.of(),
                List.of(), List.of(), 2, 10, 10, BigDecimal.ZERO, 10, null);
        ask(NEW_QUESTION, "100.1");
        agent.behaviour = (request, progress) -> new AgentResult(Kind.ANSWER, "Maybe.", Confidence.LOW,
                List.of(new Citation(1, 5, "VPN guide", "https://docs.example/vpn", "portal")),
                List.of(), List.of(), 2, 10, 10, BigDecimal.ZERO, 10, null);
        ask("How do I reset my VPN password, please?", "100.2");

        assertThat(slack.posts).isEmpty();
    }

    @Test
    void aFailingAgentMeansSilenceNotAnApology() throws Exception {
        agent.behaviour = (request, progress) -> {
            throw new IllegalStateException("model exploded");
        };

        ask(NEW_QUESTION, "100.1");

        assertThat(slack.posts).isEmpty();
        assertThat(slack.updates).isEmpty();
    }

    @Test
    void ifSlackRejectsThePostNothingIsLogged() throws Exception {
        slack.postsFail = true;

        ask(NEW_QUESTION, "100.1");

        assertThat(count("interaction")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT interaction_id FROM proactive_offer").query(Long.class).optional()).isEmpty();
    }

    // ---- who may see the answer -------------------------------------------------------------------

    @Test
    void theAnswerIsBuiltForEveryoneInTheChannelSoAccessRulesApplyAsForAMention() throws Exception {
        slack.channelMembers.put(CHANNEL, Set.of("U1", "U2", "U3", "UBOT"));

        ask(NEW_QUESTION, "100.1");

        assertThat(agent.requests).singleElement()
                .satisfies(r -> assertThat(r.audience()).isEqualTo(Audience.of("U1", "U2", "U3")));
    }

    @Test
    void whenTheChannelsPeopleAreUnknownOnlySharedKnowledgeIsUsed() throws Exception {
        slack.memberLookupFails = true;

        ask(NEW_QUESTION, "100.1");

        assertThat(agent.requests).singleElement().satisfies(r -> assertThat(r.audience()).isEqualTo(Audience.sharedOnly()));
    }

    // ---- candidates ---------------------------------------------------------------------------------

    private List<Long> candidates(String question) {
        return interactions.similarAnswered(tenantId, question, CHANNEL, "100.1", ProactiveResponder.MIN_RANK, 3)
                .stream().map(InteractionRepository.SimilarQuestion::id).toList();
    }

    @Test
    void candidatesAreChannelQuestionsAnsweredWithMediumOrHighConfidence() {
        long high = pastAnswer("C300", "1.1", "How do I reset my VPN password at home?", "ANSWER", "HIGH");
        long medium = pastAnswer("C300", "1.2", "How do I reset my VPN password at work?", "ANSWER", "MEDIUM");
        pastAnswer("C300", "1.3", "How do I reset my VPN password on a train?", "ANSWER", "LOW");
        pastAnswer("C300", "1.4", "How do I reset my VPN password in a cafe?", "UNKNOWN", "HIGH");
        pastAnswer("C300", "1.5", "How do I reset my VPN password at a gym?", "CLARIFY", "HIGH");
        pastAnswer("D300", "", "How do I reset my VPN password secretly?", "ANSWER", "HIGH");

        assertThat(candidates(NEW_QUESTION)).contains(high, medium).hasSize(3)
                .doesNotContainAnyElementsOf(jdbc.sql("SELECT id FROM interaction WHERE question LIKE '%train%' OR question LIKE '%cafe%' OR question LIKE '%gym%' OR question LIKE '%secretly%'")
                        .query(Long.class).list());
    }

    @Test
    void aDirectMessageQuestionIsNeverACandidate() {
        jdbc.sql("DELETE FROM interaction").update();
        long dm = pastAnswer("D300", "", "How do I reset my VPN password?", "ANSWER", "HIGH");

        assertThat(candidates(NEW_QUESTION)).doesNotContain(dm).isEmpty();
    }

    @Test
    void aQuestionWithAThumbsDownOrAPrivateSourceIsNotACandidate() {
        jdbc.sql("DELETE FROM interaction").update();
        long downed = pastAnswer("C300", "1.1", "How do I reset my VPN password?", "ANSWER", "HIGH");
        long fine = pastAnswer("C300", "1.2", "How do I reset the VPN password?", "ANSWER", "HIGH");
        long restricted = pastAnswer("C300", "1.3", "Reset VPN password how do I?", "ANSWER", "HIGH");
        new FeedbackRepository(jdbc).record(tenantId, downed, "U2", Rating.DOWN);
        jdbc.sql("UPDATE interaction SET used_restricted = true WHERE id = :id").param("id", restricted).update();

        assertThat(candidates(NEW_QUESTION)).containsExactly(fine);
    }

    @Test
    void aQuestionFromTheSameThreadIsNotACandidate() {
        jdbc.sql("DELETE FROM interaction").update();
        long sameThread = pastAnswer(CHANNEL, "100.1", "How do I reset my VPN password?", "ANSWER", "HIGH");
        long other = pastAnswer(CHANNEL, "100.2", "How do I reset the VPN password?", "ANSWER", "HIGH");

        assertThat(candidates(NEW_QUESTION)).containsExactly(other).doesNotContain(sameThread);
    }

    @Test
    void candidatesBelongToTheTenantOnly() {
        long other = jdbc.sql("INSERT INTO tenant (slug, name) VALUES ('other', 'Other') RETURNING id")
                .query(Long.class).single();
        Conversation c = new ConversationRepository(jdbc).findOrCreate(other, "C500", "1.1", false, false);
        interactions.insert(other, c.id(), "U9", "How do I reset my VPN password?",
                new AgentResult(Kind.ANSWER, "a", Confidence.HIGH, List.of(), List.of(), List.of(), 1, 1, 1,
                        BigDecimal.ZERO, 1, null), "1.2");
        jdbc.sql("DELETE FROM interaction WHERE tenant_id = :t").param("t", tenantId).update();

        assertThat(candidates(NEW_QUESTION)).isEmpty();
        assertThat(interactions.similarAnswered(other, NEW_QUESTION, CHANNEL, "100.1", ProactiveResponder.MIN_RANK, 3))
                .hasSize(1);
    }

    @Test
    void candidatesAreRankedAndWeakMatchesAreDropped() {
        jdbc.sql("DELETE FROM interaction").update();
        long weak = pastAnswer("C300", "1.1", "Who owns the password manager licence?", "ANSWER", "HIGH");
        long best = pastAnswer("C300", "1.2", "How do I reset my VPN password?", "ANSWER", "HIGH");
        long middle = pastAnswer("C300", "1.3", "VPN password policy", "ANSWER", "HIGH");
        pastAnswer("C300", "1.4", "Where is the cafeteria menu?", "ANSWER", "HIGH");

        List<Long> found = candidates(NEW_QUESTION);

        assertThat(found).startsWith(best).contains(middle).doesNotContain(weak);
        assertThat(found.indexOf(best)).isLessThan(found.indexOf(middle));
        var ranks = interactions.similarAnswered(tenantId, NEW_QUESTION, CHANNEL, "100.1", 0, 10);
        assertThat(ranks).extracting(InteractionRepository.SimilarQuestion::rank).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(ranks.stream().filter(r -> r.id() == weak).findFirst().orElseThrow().rank())
                .isLessThan(ProactiveResponder.MIN_RANK);
    }

    @Test
    void aProactiveAnswerThatGotAThumbsDownRetiresTheQuestionItMatched() throws Exception {
        ask(NEW_QUESTION, "100.1");
        long matched = jdbc.sql("SELECT matched_interaction_id FROM proactive_offer").query(Long.class).single();
        long answer = jdbc.sql("SELECT interaction_id FROM proactive_offer").query(Long.class).single();
        assertThat(candidates("How do I reset the VPN password?")).contains(matched);

        new FeedbackRepository(jdbc).record(tenantId, answer, "U2", Rating.DOWN);

        assertThat(candidates("How do I reset the VPN password?")).doesNotContain(matched);
    }

    // ---- stale documents under an answer ---------------------------------------------------------------

    private void answerCiting(long documentId) {
        agent.behaviour = (request, progress) -> new AgentResult(Kind.ANSWER, "The window is 30 days.", Confidence.HIGH,
                List.of(new Citation(1, documentId, "Refund policy", "https://docs.example/refund", "30 days")),
                List.of(), List.of(), 2, 1000, 100, new BigDecimal("0.05"), 10, null);
    }

    private IncomingEvent mention(String text, String ts) {
        return IncomingEvent.of(IncomingEvent.Kind.MENTION, CHANNEL, "U1", text, ts, null, "Ev" + (++eventCounter));
    }

    @Test
    void anAnswerThatCitesAnOldDocumentSaysSoAndIsFlaggedOnce() throws Exception {
        long old = com.docs0.TestDocs.document(jdbc, tenantId, "refund", "Refund policy", "https://docs.example/refund",
                "Asha", "asha@example.com", Instant.parse("2023-03-15T10:00:00Z"), false);
        answerCiting(old);

        send(mention("<@UBOT> what is the refund window?", "100.1"));

        assertThat(slack.lastUpdate().blocks().toString())
                .contains(":hourglass: Based on a document last updated March 2023. It may be out of date.");
        assertThat(jdbc.sql("SELECT document_id FROM stale_flag").query(Long.class).list()).containsExactly(old);
        assertThat(jdbc.sql("SELECT document_modified_at FROM stale_flag").query(java.sql.Timestamp.class).single()
                .toInstant()).isEqualTo(Instant.parse("2023-03-15T10:00:00Z"));
    }

    @Test
    void aRecentDocumentGetsNoNoteAndNoFlag() throws Exception {
        long fresh = com.docs0.TestDocs.document(jdbc, tenantId, "refund", "Refund policy", "https://docs.example/refund",
                "Asha", null, Instant.now().minus(Duration.ofDays(30)), false);
        answerCiting(fresh);

        send(mention("<@UBOT> what is the refund window?", "100.1"));

        assertThat(slack.lastUpdate().blocks().toString()).doesNotContain("hourglass");
        assertThat(count("stale_flag")).isZero();
    }

    @Test
    void anOldDocumentUsedInARepeatQuestionAnswerIsFlaggedToo() throws Exception {
        long old = com.docs0.TestDocs.document(jdbc, tenantId, "refund", "Refund policy", "https://docs.example/refund",
                "Asha", null, Instant.parse("2023-03-15T10:00:00Z"), false);
        answerCiting(old);

        ask(NEW_QUESTION, "100.1");

        assertThat(slack.onlyPost().blocks().toString()).contains("hourglass").contains("March 2023");
        assertThat(count("stale_flag")).isEqualTo(1);
    }
}
