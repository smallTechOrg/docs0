package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.TestDatabase;
import com.docs0.agent.Agent.Kind;
import com.docs0.budget.BudgetService;
import com.docs0.config.Docs0Properties;
import com.docs0.conversation.ConversationRepository;
import com.docs0.conversation.InteractionRepository;
import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.JdbcKnowledgeSearch;
import com.docs0.slack.SlackFakes.FakeAgent;
import com.docs0.slack.SlackFakes.FakeKnowledge;
import com.docs0.slack.SlackFakes.FakeMessenger;
import com.docs0.slack.SlackMessenger.SlackUser;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SourceRepository;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.source.gmail.FakeGmailClient;
import com.docs0.source.gmail.GmailConnection;
import com.docs0.source.gmail.GmailConnector;
import com.docs0.source.gmail.GmailKey;
import com.docs0.source.gmail.Mailbox;
import com.docs0.source.gmail.TestKeys;
import com.docs0.tenant.TenantService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Gmail through the Slack layer: the audience a DM or a channel gets (emails from the identity cache) decides
 * whether a mailbox owner's mail is visible, using the real search over synced mail.
 */
class GmailAudienceTest {

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final FakeMessenger slack = new FakeMessenger();
    private final FakeAgent agent = new FakeAgent();
    private final List<String> titlesSeen = new ArrayList<>();
    private long tenantId;
    private QuestionHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        tenantId = TestDatabase.resetWithTenant();
        Docs0Properties props = new Docs0Properties(new Docs0Properties.Tenant("smalltech", "smallTech"),
                new Docs0Properties.Anthropic("", "test-model", new BigDecimal("2"), new BigDecimal("10"), 12),
                new Docs0Properties.Budget(new BigDecimal("100")), new Docs0Properties.Slack("", ""),
                new Docs0Properties.Drive(15, false), null, "", "", "");
        TenantService tenants = new TenantService(jdbc);

        // Mail for ana@ and for the shared inbox support@ (viewers: lee@), synced the real way.
        DocumentStore store = new DocumentStore(jdbc);
        FakeGmailClient gmail = new FakeGmailClient();
        gmail.put("ana@example.com", "t1", "1", "Okapi acquisition mail", "okapi acquisition text", Instant.parse("2026-11-10T10:00:00Z"));
        gmail.put("support@example.com", "s1", "1", "Okapi refund mail", "okapi refund text", Instant.parse("2026-11-10T10:00:00Z"));
        GmailConnection connection = new GmailConnection(new TokenCipher(DriveTestSetup.ENCRYPTION_KEY),
                new SourceRepository(jdbc), new SourceCredentialRepository(jdbc), store);
        connection.connect(tenantId, new GmailKey(TestKeys.EMAIL, TestKeys.CLIENT_ID), FakeGmailClient.KEY);
        connection.setMailboxes(tenantId, List.of(new Mailbox("ana@example.com", List.of()),
                new Mailbox("support@example.com", List.of("lee@example.com"))));
        new GmailConnector(gmail, connection, store, new SyncRunRepository(jdbc), 90)
                .sync(tenantId, connection.source(tenantId).orElseThrow().id());

        // Whatever audience the Slack layer builds is what the real search is asked with.
        JdbcKnowledgeSearch search = new JdbcKnowledgeSearch(jdbc);
        agent.behaviour = (request, progress) -> {
            search.search(tenantId, request.audience(), "okapi", 10).hits().forEach(h -> titlesSeen.add(h.title()));
            return SlackFakes.result(Kind.ANSWER, "ok");
        };
        IdentityService identities = new IdentityService(jdbc, slack);
        CorrectionFlow flow = new CorrectionFlow(new CorrectionDetector(new SlackFakes.FakeLlm(), props),
                new ConversationRepository(jdbc), new InteractionRepository(jdbc),
                new com.docs0.feedback.FeedbackRepository(jdbc), new com.docs0.feedback.CorrectionRepository(jdbc), slack, tenants,
                new BudgetService(jdbc, props));
        handler = new QuestionHandler(tenants, agent, new FakeKnowledge(), new BudgetService(jdbc, props),
                new ConversationRepository(jdbc), new InteractionRepository(jdbc), slack, flow,
                new com.docs0.action.PendingActionRepository(jdbc), identities, Duration.ZERO);
    }

    private List<String> ask(String channel, String user) throws Exception {
        titlesSeen.clear();
        CompletableFuture<Void> work = handler.handle(tenantId, IncomingEvent.of(
                channel.startsWith("D") ? IncomingEvent.Kind.DIRECT_MESSAGE : IncomingEvent.Kind.MENTION,
                channel, user, "<@UBOT> what is the okapi plan?", "100." + System.nanoTime() % 100000, null,
                "Ev" + System.nanoTime()));
        work.get(10, TimeUnit.SECONDS);
        return List.copyOf(titlesSeen);
    }

    private static SlackUser human(String id, String email) {
        return new SlackUser(id, email, "Name " + id, false, false);
    }

    @Test
    void theOwnerSeesTheirMailInTheirOwnDmOnceTheirSlackEmailIsKnown() throws Exception {
        slack.users.put("U1", human("U1", "Ana@Example.com"));

        assertThat(ask("D100", "U1")).containsExactly("Okapi acquisition mail");
    }

    @Test
    void theSameQuestionInAChannelFindsNoMailEvenFromTheOwner() throws Exception {
        slack.users.put("U1", human("U1", "ana@example.com"));
        slack.users.put("U2", human("U2", "bob@example.com"));
        slack.members = Set.of("U1", "U2", "UBOT");

        assertThat(ask("C100", "U1")).isEmpty();
    }

    @Test
    void aChannelWithOnlyUnknownMembersAndAFailingMemberListFindNoMail() throws Exception {
        slack.users.put("U1", human("U1", "ana@example.com"));
        slack.memberLookupFails = true;

        assertThat(ask("C100", "U1")).isEmpty();
    }

    @Test
    void aColleaguesDmFindsNothingOfSomeoneElsesMail() throws Exception {
        slack.users.put("U1", human("U1", "ana@example.com"));
        slack.users.put("U2", human("U2", "bob@example.com"));

        assertThat(ask("D200", "U2")).isEmpty();
    }

    @Test
    void whenSlackDoesNotShowTheOwnersEmailTheMailIsHiddenEvenInTheirDm() throws Exception {
        slack.users.put("U1", human("U1", null));

        assertThat(ask("D100", "U1")).isEmpty();
    }

    @Test
    void aViewerOfTheSharedInboxSeesItInTheirDmAndNoOneElseDoes() throws Exception {
        slack.users.put("U3", human("U3", "lee@example.com"));
        slack.users.put("U1", human("U1", "ana@example.com"));

        assertThat(ask("D300", "U3")).containsExactly("Okapi refund mail");
        assertThat(ask("D100", "U1")).containsExactly("Okapi acquisition mail");
    }
}
