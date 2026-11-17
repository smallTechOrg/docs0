package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docs0.TestData;
import com.docs0.TestDatabase;
import com.docs0.action.ActionService;
import com.docs0.action.PendingActionRepository;
import com.docs0.budget.BudgetService;
import com.docs0.config.Docs0Properties;
import com.docs0.conversation.Conversation;
import com.docs0.conversation.ConversationRepository;
import com.docs0.conversation.InteractionRepository;
import com.docs0.crypto.TokenCipher;
import com.docs0.feedback.CorrectionRepository;
import com.docs0.feedback.FeedbackRepository;
import com.docs0.slack.SlackFakes.FakeAgent;
import com.docs0.slack.SlackFakes.FakeKnowledge;
import com.docs0.slack.SlackFakes.FakeMessenger;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.tenant.TenantService;
import com.docs0.web.slack.SlackEventsController;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Hosted mode freezes whatever runs after the HTTP response. These tests use the real handlers (fake Slack, fake
 * model) behind the real controller in http transport and look at the result at the very moment the request returns:
 * no waiting, no polling. Every Slack path that does work after an event (answers, follow-up buttons, the thumbs-down
 * prompt, draft buttons) goes through the future the controller holds open.
 */
class SlackHttpHoldOpenAuditTest {

    private static final String SECRET = "signing-secret-for-tests";
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final String CHANNEL = "C100";

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final FakeMessenger slack = new FakeMessenger();
    private final FakeAgent agent = new FakeAgent();
    private long tenantId;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        tenantId = TestDatabase.resetWithTenant();
        SlackWorkspaces workspaces = new SlackWorkspaces(jdbc, new TokenCipher(DriveTestSetup.ENCRYPTION_KEY));
        workspaces.register(tenantId, "T_PILOT", "Pilot", "xoxb-secret", "UBOT", "test", null);
        Docs0Properties props = new Docs0Properties(new Docs0Properties.Tenant("smalltech", "smallTech"),
                new Docs0Properties.Anthropic("", "test-model", new BigDecimal("2"), new BigDecimal("10"), 12),
                new Docs0Properties.Budget(new BigDecimal("100")),
                new Docs0Properties.Slack("xoxb-pilot", "", "", "", SECRET, "http", 30),
                new Docs0Properties.Drive(15, false), null, "", "https://docs0.example", "");
        TenantService tenants = new TenantService(jdbc);
        CorrectionFlow corrections = new CorrectionFlow(new CorrectionDetector(new SlackFakes.FakeLlm(), props),
                new ConversationRepository(jdbc), new InteractionRepository(jdbc), new FeedbackRepository(jdbc),
                new CorrectionRepository(jdbc), slack, tenants, new BudgetService(jdbc, props));
        QuestionHandler questions = new QuestionHandler(tenants, agent, new FakeKnowledge(),
                new BudgetService(jdbc, props), new ConversationRepository(jdbc), new InteractionRepository(jdbc),
                slack, corrections, new PendingActionRepository(jdbc), new IdentityService(jdbc, slack),
                Duration.ZERO);
        ReactionHandler reactions = new ReactionHandler(new InteractionRepository(jdbc), new FeedbackRepository(jdbc),
                slack);
        ActionHandler actions = new ActionHandler(tenants,
                new ActionService(new PendingActionRepository(jdbc), null, null, null, null, Clock.systemUTC()), slack);
        SlackGateway gateway = new SlackGateway(props, workspaces, null, questions, reactions, actions);
        mvc = MockMvcBuilders.standaloneSetup(new SlackEventsController(props, new SlackHttpEvents(gateway, workspaces),
                Clock.fixed(NOW, ZoneOffset.UTC))).build();
    }

    private static MockHttpServletRequestBuilder signed(String url, String contentType, String body) {
        String ts = Long.toString(NOW.getEpochSecond());
        return post(url).contentType(contentType).content(body).header(SlackSignature.TIMESTAMP_HEADER, ts)
                .header(SlackSignature.SIGNATURE_HEADER,
                        SlackSignature.sign(SECRET, ts, body.getBytes(StandardCharsets.UTF_8)));
    }

    private void event(String eventId, String eventJson) throws Exception {
        mvc.perform(signed("/slack/events", "application/json",
                "{\"type\":\"event_callback\",\"team_id\":\"T_PILOT\",\"event_id\":\"" + eventId + "\",\"event\":"
                        + eventJson + "}")).andExpect(status().isOk());
    }

    private void click(String payload) throws Exception {
        mvc.perform(signed("/slack/interactions", "application/x-www-form-urlencoded",
                "payload=" + URLEncoder.encode(payload, StandardCharsets.UTF_8))).andExpect(status().isOk());
    }

    private long interactions() {
        return jdbc.sql("SELECT count(*) FROM interaction WHERE tenant_id = :t").param("t", tenantId)
                .query(Long.class).single();
    }

    @Test
    void aMentionIsFullyAnsweredAndLoggedWhenTheRequestReturns() throws Exception {
        agent.behaviour = (request, progress) -> {
            try {
                Thread.sleep(300); // slower than a bare acknowledgement would ever wait
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return SlackFakes.result(com.docs0.agent.Agent.Kind.ANSWER, "Answer to: " + request.question());
        };

        event("Ev1", "{\"type\":\"app_mention\",\"user\":\"U1\",\"text\":\"<@UBOT> what is our pricing?\","
                + "\"ts\":\"100.1\",\"channel\":\"" + CHANNEL + "\"}");

        assertThat(slack.updates).as("the final answer replaced the status line").isNotEmpty();
        assertThat(slack.lastUpdate().text()).isEqualTo("Answer to: what is our pricing?");
        assertThat(interactions()).isEqualTo(1);
    }

    @Test
    void aFollowUpButtonIsFullyAnsweredWhenTheRequestReturns() throws Exception {
        click("{\"type\":\"block_actions\",\"user\":{\"id\":\"U1\",\"team_id\":\"T_PILOT\"},\"team\":{\"id\":\"T_PILOT\"},"
                + "\"channel\":{\"id\":\"" + CHANNEL + "\"},\"message\":{\"ts\":\"100.5\",\"thread_ts\":\"100.1\"},"
                + "\"actions\":[{\"action_id\":\"follow_up_0\",\"value\":\"And for enterprise?\",\"action_ts\":\"1.1\"}]}");

        assertThat(slack.posts).anySatisfy(p -> assertThat(p.text()).contains("asked:"));
        assertThat(slack.updates).isNotEmpty();
        assertThat(interactions()).isEqualTo(1);
    }

    @Test
    void theThumbsDownPromptIsPostedWhenTheRequestReturns() throws Exception {
        Conversation thread = TestData.conversation(jdbc, tenantId, CHANNEL, "100.1", false);
        TestData.interaction(jdbc, thread, "U1", "What is the price?", "It is $12.", "9000.0001", "0.01");

        event("Ev2", "{\"type\":\"reaction_added\",\"user\":\"U2\",\"reaction\":\"-1\",\"item\":{\"type\":\"message\","
                + "\"channel\":\"" + CHANNEL + "\",\"ts\":\"9000.0001\"},\"event_ts\":\"1.2\"}");

        assertThat(jdbc.sql("SELECT count(*) FROM feedback WHERE tenant_id = :t").param("t", tenantId)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(slack.posts).extracting(FakeMessenger.Posted::text).contains(ReactionHandler.PROMPT);
    }

    @Test
    void aDraftButtonClickIsDealtWithWhenTheRequestReturns() throws Exception {
        click("{\"type\":\"block_actions\",\"user\":{\"id\":\"U1\",\"team_id\":\"T_PILOT\"},\"team\":{\"id\":\"T_PILOT\"},"
                + "\"channel\":{\"id\":\"" + CHANNEL + "\"},\"message\":{\"ts\":\"9000.0007\",\"thread_ts\":\"100.1\"},"
                + "\"actions\":[{\"action_id\":\"" + AnswerRenderer.DRAFT_CANCEL_ACTION + "\",\"value\":\"4242\","
                + "\"action_ts\":\"1.3\"}]}");

        assertThat(slack.ephemerals).as("the person is told the draft is gone").hasSize(1);
    }
}
