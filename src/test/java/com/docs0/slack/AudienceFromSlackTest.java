package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.TestDatabase;
import com.docs0.budget.BudgetService;
import com.docs0.config.Docs0Properties;
import com.docs0.conversation.ConversationRepository;
import com.docs0.conversation.InteractionRepository;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.Audience.Member;
import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.knowledge.JdbcKnowledgeSearch;
import com.docs0.slack.IdentityService.Person;
import com.docs0.slack.SlackFakes.FakeAgent;
import com.docs0.slack.SlackFakes.FakeKnowledge;
import com.docs0.slack.SlackFakes.FakeMessenger;
import com.docs0.slack.SlackMessenger.SlackUser;
import com.docs0.source.SourceRepository;
import com.docs0.tenant.TenantService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** How the Slack layer turns a channel or a DM into the audience of an answer, and the identity cache behind it. */
class AudienceFromSlackTest {

    private static final String CHANNEL = "C100";

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final FakeMessenger slack = new FakeMessenger();
    private final FakeAgent agent = new FakeAgent();
    private long tenantId;
    private IdentityService identities;
    private QuestionHandler handler;

    @BeforeEach
    void setUp() {
        tenantId = TestDatabase.resetWithTenant();
        identities = new IdentityService(jdbc, slack);
        Docs0Properties props = new Docs0Properties(new Docs0Properties.Tenant("smalltech", "smallTech"),
                new Docs0Properties.Anthropic("", "test-model", new BigDecimal("2"), new BigDecimal("10"), 12),
                new Docs0Properties.Budget(new BigDecimal("100")), new Docs0Properties.Slack("", ""),
                new Docs0Properties.Drive(15, false), null, "", "", "");
        SlackFakes.FakeLlm llm = new SlackFakes.FakeLlm();
        CorrectionFlow flow = new CorrectionFlow(new CorrectionDetector(llm, props), new ConversationRepository(jdbc),
                new InteractionRepository(jdbc), new com.docs0.feedback.FeedbackRepository(jdbc),
                new com.docs0.feedback.CorrectionRepository(jdbc), slack, new TenantService(jdbc),
                new BudgetService(jdbc, props));
        handler = new QuestionHandler(new TenantService(jdbc), agent, new FakeKnowledge(),
                new BudgetService(jdbc, props), new ConversationRepository(jdbc), new InteractionRepository(jdbc), slack,
                flow, new com.docs0.action.PendingActionRepository(jdbc), identities, Duration.ZERO);
    }

    private void ask(String channel, String user) throws Exception {
        CompletableFuture<Void> work = handler.handle(tenantId, IncomingEvent.of(
                channel.startsWith("D") ? IncomingEvent.Kind.DIRECT_MESSAGE : IncomingEvent.Kind.MENTION,
                channel, user, "<@UBOT> what is the plan?", "100." + System.nanoTime() % 100000, null,
                "Ev" + System.nanoTime()));
        work.get(10, TimeUnit.SECONDS);
    }

    private Audience lastAudience() {
        return agent.requests.get(agent.requests.size() - 1).audience();
    }

    private static SlackUser human(String id, String email) {
        return new SlackUser(id, email, "Name " + id, false, false);
    }

    @Test
    void membersCarryTheirEmailsAndBotsAndDeactivatedUsersAreDropped() throws Exception {
        slack.members = Set.of("U1", "U2", "U3", "U4", "U5", "UBOT");
        slack.users.put("U1", human("U1", "Asha@Example.com"));
        slack.users.put("U2", human("U2", null));
        slack.users.put("U3", new SlackUser("U3", null, "Deploy bot", true, false));
        slack.users.put("U4", new SlackUser("U4", "gone@example.com", "Gone", false, true));
        // U5 is not described by Slack at all: nothing is known, so they stay in, by id only.
        slack.users.put("U5", human("U5", null));

        ask(CHANNEL, "U1");

        assertThat(lastAudience().members()).containsExactlyInAnyOrder(
                new Member("U1", "asha@example.com"), new Member("U2", null), new Member("U5", null));
    }

    @Test
    void docs0ItselfIsNeverPartOfTheAudienceEvenWhenNothingElseIsKnown() throws Exception {
        slack.members = Set.of("U1", "U2", "UBOT");
        slack.userInfoFails = true;

        ask(CHANNEL, "U1");

        assertThat(lastAudience()).isEqualTo(Audience.of("U1", "U2"));
    }

    @Test
    void aChannelWithOnlyOnePersonIsNotAPrivateAudienceSoOnlySharedKnowledgeIsUsed() throws Exception {
        slack.members = Set.of("U1", "UBOT");

        ask(CHANNEL, "U1");

        // More people can join later and read the answer; only a DM is one person's own audience.
        assertThat(lastAudience()).isEqualTo(Audience.sharedOnly());
    }

    @Test
    void aUserSlackCannotDescribeStaysInTheAudienceByIdOnly() throws Exception {
        slack.members = Set.of("U1", "U2");
        slack.userInfoFails = true;

        ask(CHANNEL, "U1");

        assertThat(lastAudience()).isEqualTo(Audience.of("U1", "U2"));
    }

    @Test
    void aStaleCachedUserIsUsedWhenSlackFailsToRefresh() throws Exception {
        slack.members = Set.of("U1", "U2");
        slack.users.put("U2", new SlackUser("U2", null, "Gone", false, true));
        ask(CHANNEL, "U1");
        jdbc.sql("UPDATE identity SET updated_at = now() - interval '3 days'").update();
        slack.userInfoFails = true;

        ask(CHANNEL, "U1");

        // U2 is known to be deactivated from the stale cache, which leaves one person: shared knowledge only.
        assertThat(lastAudience()).isEqualTo(Audience.sharedOnly());
    }

    @Test
    void identitiesAreCachedForADayAndThenRefreshed() throws Exception {
        slack.members = Set.of("U1", "U2");
        ask(CHANNEL, "U1");
        int firstRound = slack.userLookups.get();
        ask(CHANNEL, "U1");

        assertThat(firstRound).isEqualTo(2);
        assertThat(slack.userLookups.get()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM identity WHERE tenant_id = :t").param("t", tenantId)
                .query(Long.class).single()).isEqualTo(2);

        jdbc.sql("UPDATE identity SET updated_at = now() - interval '25 hours' WHERE slack_user_id = 'U2'").update();
        ask(CHANNEL, "U1");
        assertThat(slack.userLookups.get()).isEqualTo(3);
    }

    @Test
    void aMissingEmailScopeDegradesToSlackIdsAndIsReported() throws Exception {
        slack.members = Set.of("U1", "U2");
        slack.scopes = Set.of("users:read", "channels:read");

        ask(CHANNEL, "U1");

        assertThat(lastAudience()).isEqualTo(Audience.of("U1", "U2"));
        assertThat(identities.emailScopeMissing(tenantId)).isTrue();
        slack.scopes = Set.of("users:read", "users:read.email");
        assertThat(new IdentityService(jdbc, slack).emailScopeMissing(tenantId)).isFalse();
    }

    @Test
    void aMissingScopeErrorFromSlackIsReportedToo() {
        slack.userInfoFails = true;
        slack.userInfoError = "missing_scope";
        IdentityService service = new IdentityService(jdbc, slack);

        assertThat(service.resolve(tenantId, Set.of("U1"), Duration.ofSeconds(5))).isEmpty();
        assertThat(service.emailScopeMissing(tenantId)).isTrue();
    }

    @Test
    void aDmAudienceIsTheAskerWithTheirEmail() throws Exception {
        slack.users.put("U1", human("U1", "asha@example.com"));

        ask("D200", "U1");

        assertThat(lastAudience().members()).containsExactly(new Member("U1", "asha@example.com"));
    }

    @Test
    void whenTheMemberListFailsOnlySharedKnowledgeIsUsedBecauseTheChannelIsUnknown() throws Exception {
        long source = new SourceRepository(jdbc).findOrCreate(tenantId, "SLACK", "Slack history", "SHARED").id();
        DocumentStore store = new DocumentStore(jdbc);
        store.upsertRestricted(tenantId, source, new DocumentWrite("thread:C9:1.1", "Okapi in the leads channel",
                "text/plain", "u", "P", null, Instant.now(), "okapi leads only"), Set.of("u2", "u3"));
        store.upsertRestricted(tenantId, source, new DocumentWrite("thread:C8:1.1", "Okapi in my channel",
                "text/plain", "u", "P", null, Instant.now(), "okapi mine"), Set.of("u1", "u2"));
        slack.memberLookupFails = true;

        ask(CHANNEL, "U1");

        Audience audience = lastAudience();
        assertThat(audience).isEqualTo(Audience.sharedOnly());
        // Everyone in the channel will see the answer, so not even the asker's own private channel is used.
        List<String> titles = new JdbcKnowledgeSearch(jdbc).search(tenantId, audience, "okapi", 10).hits().stream()
                .map(h -> h.title()).toList();
        assertThat(titles).isEmpty();
    }

    @Test
    void aWholeChannelOfBotsAndGhostsLeavesAnEmptyAudienceThatSeesNoRestrictedDocuments() throws Exception {
        slack.members = Set.of("UBOT", "U3");
        slack.users.put("U3", new SlackUser("U3", null, "Bot", true, false));

        ask(CHANNEL, "U1");

        assertThat(lastAudience().members()).isEmpty();
    }

    @Test
    void aPublicChannelIsNeverAPrivateAudienceEvenWhenEveryMemberIsEntitled() throws Exception {
        long source = new SourceRepository(jdbc).findOrCreate(tenantId, "SLACK", "Slack history", "SHARED").id();
        new DocumentStore(jdbc).upsertRestricted(tenantId, source, new DocumentWrite("thread:C8:1.1",
                "Okapi in the leads channel", "text/plain", "u", "P", null, Instant.now(), "okapi leads only"),
                Set.of("u1", "u2"));
        slack.members = Set.of("U1", "U2", "UBOT");
        JdbcKnowledgeSearch search = new JdbcKnowledgeSearch(jdbc);

        // A private channel of exactly these two people may see it ...
        ask(CHANNEL, "U1");
        assertThat(search.search(tenantId, lastAudience(), "okapi", 10).hits()).hasSize(1);

        // ... a public channel with the same two members may not: anyone in the workspace can read it.
        slack.publicChannels.add("C101");
        ask("C101", "U1");
        assertThat(lastAudience()).isEqualTo(Audience.sharedOnly());
        assertThat(search.search(tenantId, lastAudience(), "okapi", 10).hits()).isEmpty();
    }

    @Test
    void aChannelWhosePrivacySlackCannotSayIsTreatedAsPublic() throws Exception {
        slack.members = Set.of("U1", "U2", "UBOT");
        slack.privacyLookupFails = true;

        ask(CHANNEL, "U1");

        assertThat(lastAudience()).isEqualTo(Audience.sharedOnly());
    }

    @Test
    void aDmNeedsNoPrivacyLookupAndAChannelsPrivacyIsRememberedForAWhile() throws Exception {
        slack.members = Set.of("U1", "U2", "UBOT");
        ask("D200", "U1");
        assertThat(slack.privacyLookups.get()).isZero();

        ask(CHANNEL, "U1");
        ask(CHANNEL, "U2");

        assertThat(slack.privacyLookups.get()).isEqualTo(1);
    }

    @Test
    void personLooksUpOneUserAndNamesFallBackToTheId() {
        slack.users.put("U1", human("U1", "a@example.com"));

        assertThat(identities.person(tenantId, "U1")).contains(new Person("U1", "a@example.com", "Name U1", false, false));
        assertThat(identities.displayName(tenantId, "U1")).isEqualTo("Name U1");
        slack.userInfoFails = true;
        assertThat(identities.displayName(tenantId, "U404")).isEqualTo("U404");
    }
}
