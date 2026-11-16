package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.TestDatabase;
import com.docs0.action.PendingAction;
import com.docs0.action.PendingAction.Status;
import com.docs0.action.PendingActionRepository;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Kind;
import com.docs0.budget.BudgetService;
import com.docs0.config.Docs0Properties;
import com.docs0.conversation.ConversationRepository;
import com.docs0.conversation.InteractionRepository;
import com.docs0.slack.SlackFakes.FakeAgent;
import com.docs0.slack.SlackFakes.FakeKnowledge;
import com.docs0.slack.SlackFakes.FakeMessenger;
import com.docs0.tenant.TenantService;
import com.slack.api.model.block.ActionsBlock;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** What the question handler does with drafts the agent made: shows them after the answer, links and drops them. */
class QuestionHandlerDraftTest {

    private static final String CHANNEL = "C100";

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final FakeMessenger slack = new FakeMessenger();
    private final FakeAgent agent = new FakeAgent();
    private final SlackFakes.FakeLlm llm = new SlackFakes.FakeLlm();
    private PendingActionRepository pending;
    private QuestionHandler handler;
    private long tenantId;

    @BeforeEach
    void setUp() {
        tenantId = TestDatabase.resetWithTenant();
        Docs0Properties props = new Docs0Properties(new Docs0Properties.Tenant("smalltech", "smallTech"),
                new Docs0Properties.Anthropic("", "m", new BigDecimal("2"), new BigDecimal("10"), 12),
                new Docs0Properties.Budget(new BigDecimal("100")), new Docs0Properties.Slack("", ""),
                new Docs0Properties.Drive(15, false), null, "", "", "");
        pending = new PendingActionRepository(jdbc);
        CorrectionFlow flow = new CorrectionFlow(new CorrectionDetector(llm, props), new ConversationRepository(jdbc),
                new InteractionRepository(jdbc), new com.docs0.feedback.FeedbackRepository(jdbc),
                new com.docs0.feedback.CorrectionRepository(jdbc), slack);
        handler = new QuestionHandler(new TenantService(jdbc, props), agent, new FakeKnowledge(),
                new BudgetService(jdbc, props), new ConversationRepository(jdbc), new InteractionRepository(jdbc), slack,
                flow, pending, Duration.ZERO);
    }

    private static IncomingEvent mention(String text) {
        return new IncomingEvent(IncomingEvent.Kind.MENTION, CHANNEL, "U1", "<@UBOT> " + text, "100.1", null, "Ev1", null, false);
    }

    /** An agent that drafts one issue for the conversation and requester it was given, as the real tools do. */
    private void agentDrafts(Kind kind, boolean fail) {
        agent.behaviour = (request, progress) -> {
            PendingAction draft = pending.insert(request.tenantId(), request.conversationId(), request.requesterSlackId(),
                    PendingAction.Kind.LINEAR_ISSUE, Map.of("teamId", "t1", "teamKey", "ENG", "teamName", "Engineering",
                            "title", "Fix login", "description", "Details"));
            return new AgentResult(kind, "I drafted an issue in ENG.", Confidence.HIGH, List.of(), List.of(), List.of(),
                    2, 10, 5, BigDecimal.ZERO, 5, kind == Kind.ERROR ? "boom" : null, List.of(), List.of(draft));
        };
    }

    private Status onlyDraftStatus() {
        return jdbc.sql("SELECT status FROM pending_action").query(String.class).list().stream().map(Status::valueOf)
                .findFirst().orElseThrow();
    }

    @Test
    void theAgentIsToldWhereAndWhoAskedSoItCanDraft() throws Exception {
        handler.handle(mention("please file an issue")).get();

        assertThat(agent.requests).hasSize(1);
        assertThat(agent.requests.get(0).requesterSlackId()).isEqualTo("U1");
        assertThat(agent.requests.get(0).conversationId()).isPositive();
    }

    @Test
    void aDraftIsPostedInTheThreadAfterTheAnswerWithItsButtonsAndLinkedToTheInteraction() throws Exception {
        agentDrafts(Kind.ANSWER, false);

        handler.handle(mention("please file an issue for the login bug")).get();

        // The status line became the answer; the draft is a separate message in the same thread.
        assertThat(slack.lastUpdate().text()).contains("I drafted an issue in ENG.");
        assertThat(slack.posts).hasSize(2);
        FakeMessenger.Posted draftMessage = slack.posts.get(1);
        assertThat(draftMessage.channel()).isEqualTo(CHANNEL);
        assertThat(draftMessage.threadTs()).isEqualTo("100.1");
        assertThat(draftMessage.text()).isEqualTo("Draft Linear issue: Fix login");
        ActionsBlock buttons = (ActionsBlock) draftMessage.blocks().stream().filter(b -> b instanceof ActionsBlock).findFirst().orElseThrow();
        assertThat(buttons.getElements()).hasSize(2);
        long interaction = jdbc.sql("SELECT id FROM interaction").query(Long.class).single();
        PendingAction row = jdbc.sql("SELECT id FROM pending_action").query(Long.class).optional()
                .flatMap(id -> pending.find(tenantId, id)).orElseThrow();
        assertThat(row.interactionId()).isEqualTo(interaction);
        assertThat(row.status()).isEqualTo(Status.PENDING);
        assertThat(row.requestedBy()).isEqualTo("U1");
    }

    @Test
    void aDraftThatSlackWillNotShowIsDroppedSoNobodyCanConfirmWhatNobodySaw() throws Exception {
        agentDrafts(Kind.ANSWER, false);
        slack.failPostWhen = text -> text.startsWith("Draft ");

        handler.handle(mention("file it")).get();

        assertThat(slack.lastUpdate().text()).contains("I drafted an issue");
        assertThat(onlyDraftStatus()).isEqualTo(Status.CANCELLED);
    }

    @Test
    void anAnswerThatFailedShowsNoDraftAndDropsIt() throws Exception {
        agentDrafts(Kind.ERROR, true);

        handler.handle(mention("file it")).get();

        assertThat(slack.posts).hasSize(1); // only the status line
        assertThat(slack.lastUpdate().text()).isEqualTo(QuestionHandler.APOLOGY);
        assertThat(onlyDraftStatus()).isEqualTo(Status.CANCELLED);
    }

    @Test
    void anAnswerWithoutDraftsPostsNothingExtra() throws Exception {
        handler.handle(mention("what is our pricing?")).get();

        assertThat(slack.posts).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM pending_action").query(Long.class).single()).isZero();
    }

    @Test
    void aFollowUpButtonClickMakesTheClickerTheRequester() throws Exception {
        List<String> requesters = new ArrayList<>();
        agent.behaviour = (request, progress) -> {
            requesters.add(request.requesterSlackId());
            return SlackFakes.result(Kind.ANSWER, "ok");
        };

        handler.followUp(CHANNEL, "100.1", "U2", "And the other one?", "act-1").get();

        assertThat(requesters).containsExactly("U2");
    }
}
