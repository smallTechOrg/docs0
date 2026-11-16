package com.docs0.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.TestData;
import com.docs0.TestDatabase;
import com.docs0.action.ActionService;
import com.docs0.action.PendingAction;
import com.docs0.action.PendingAction.Kind;
import com.docs0.action.PendingAction.Status;
import com.docs0.action.PendingActionRepository;
import com.docs0.config.Docs0Properties;
import com.docs0.conversation.Conversation;
import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.DocumentStore;
import com.docs0.slack.SlackFakes.FakeMessenger;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SourceRepository;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.source.linear.FakeLinearClient;
import com.docs0.source.linear.LinearConnection;
import com.docs0.source.trello.FakeTrelloClient;
import com.docs0.source.trello.TrelloConnection;
import com.docs0.tenant.TenantService;
import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.SectionBlock;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A click on Create or Cancel under a draft, from the Slack payload's values to the message that replaces the draft. */
class ActionHandlerTest {

    private static final String CHANNEL = "C100";

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final FakeMessenger slack = new FakeMessenger();
    private final FakeLinearClient linearApi = new FakeLinearClient();
    private final FakeTrelloClient trelloApi = new FakeTrelloClient();
    private long tenantId;
    private PendingActionRepository pending;
    private ActionHandler handler;
    private Conversation conversation;
    private TenantService tenants;
    private LinearConnection linear;
    private TrelloConnection trello;

    @BeforeEach
    void setUp() {
        tenantId = TestDatabase.resetWithTenant();
        Docs0Properties props = new Docs0Properties(new Docs0Properties.Tenant("smalltech", "smallTech"),
                new Docs0Properties.Anthropic("", "m", new BigDecimal("2"), new BigDecimal("10"), 12),
                new Docs0Properties.Budget(new BigDecimal("100")), new Docs0Properties.Slack("", ""),
                new Docs0Properties.Drive(15, false), null, "", "", "");
        tenants = new TenantService(jdbc, props);
        TokenCipher cipher = new TokenCipher(DriveTestSetup.ENCRYPTION_KEY);
        SourceRepository sources = new SourceRepository(jdbc);
        SourceCredentialRepository credentials = new SourceCredentialRepository(jdbc);
        DocumentStore store = new DocumentStore(jdbc);
        linear = new LinearConnection(cipher, sources, credentials, store);
        trello = new TrelloConnection(cipher, sources, credentials, store);
        linear.connect(tenantId, "Acme", FakeLinearClient.GOOD_KEY);
        trello.connect(tenantId, "Asha", FakeTrelloClient.GOOD_KEY, FakeTrelloClient.GOOD_TOKEN);
        pending = new PendingActionRepository(jdbc);
        handler = handler(Clock.systemUTC());
        conversation = TestData.conversation(jdbc, tenantId, CHANNEL, "100.1", false);
    }

    private ActionHandler handler(Clock clock) {
        return new ActionHandler(tenants, new ActionService(pending, linear, linearApi, trello, trelloApi, clock), slack);
    }

    private PendingAction issueDraft(String requester) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("teamId", "team-eng");
        params.put("teamKey", "ENG");
        params.put("teamName", "Engineering");
        params.put("title", "Fix login");
        params.put("description", "Safari users cannot sign in");
        return pending.insert(tenantId, conversation.id(), requester, Kind.LINEAR_ISSUE, params);
    }

    private PendingAction cardDraft(String requester) {
        return pending.insert(tenantId, conversation.id(), requester, Kind.TRELLO_CARD, Map.of("boardId", "b1",
                "boardName", "Roadmap", "listId", "l-doing", "listName", "Doing", "title", "Book venue",
                "description", "d"));
    }

    private void click(PendingAction draft, String user, String actionId) throws Exception {
        handler.handle(CHANNEL, "9000.0007", "100.1", user, actionId, String.valueOf(draft.id())).get();
    }

    private Status status(PendingAction draft) {
        return pending.find(tenantId, draft.id()).orElseThrow().status();
    }

    private static boolean hasButtons(List<com.slack.api.model.block.LayoutBlock> blocks) {
        return blocks != null && blocks.stream().anyMatch(b -> b instanceof ActionsBlock);
    }

    @Test
    void createByTheRequesterMakesTheIssueAndReplacesTheDraftWithALinkToIt() throws Exception {
        PendingAction draft = issueDraft("U1");

        click(draft, "U1", "draft_create");

        assertThat(linearApi.creates).hasSize(1);
        assertThat(status(draft)).isEqualTo(Status.DONE);
        FakeMessenger.Updated update = slack.lastUpdate();
        assertThat(update.channel()).isEqualTo(CHANNEL);
        assertThat(update.ts()).isEqualTo("9000.0007");
        assertThat(update.text()).contains("Created in Linear")
                .contains("<https://linear.app/acme/issue/ENG-99|ENG-99: Fix login>");
        assertThat(hasButtons(update.blocks())).isFalse();
        assertThat(((SectionBlock) update.blocks().get(0)).getText().getText()).contains("ENG-99");
        assertThat(slack.ephemerals).isEmpty();
    }

    @Test
    void createMakesATrelloCardAndLinksIt() throws Exception {
        PendingAction draft = cardDraft("U1");

        click(draft, "U1", "draft_create");

        assertThat(trelloApi.creates).hasSize(1);
        assertThat(slack.lastUpdate().text()).contains("Created in Trello")
                .contains("<https://trello.com/c/NewCard|Book venue>");
    }

    @Test
    void someoneElseGetsAShortPrivateReplyAndNothingChanges() throws Exception {
        PendingAction draft = issueDraft("U1");

        click(draft, "U2", "draft_create");
        click(draft, "U2", "draft_cancel");

        assertThat(linearApi.creates).isEmpty();
        assertThat(status(draft)).isEqualTo(Status.PENDING);
        assertThat(slack.updates).isEmpty();
        assertThat(slack.posts).isEmpty();
        assertThat(slack.ephemerals).hasSize(2);
        assertThat(slack.ephemerals.get(0)).isEqualTo(new FakeMessenger.Ephemeral(CHANNEL, "100.1", "U2",
                "Only the person who asked can create or cancel this draft."));
    }

    @Test
    void cancelByTheRequesterDropsTheDraft() throws Exception {
        PendingAction draft = issueDraft("U1");

        click(draft, "U1", "draft_cancel");

        assertThat(status(draft)).isEqualTo(Status.CANCELLED);
        assertThat(slack.lastUpdate().text()).contains("Draft cancelled").contains("Nothing was created in Linear");
        assertThat(hasButtons(slack.lastUpdate().blocks())).isFalse();
        assertThat(linearApi.creates).isEmpty();
    }

    @Test
    void aDoubleClickCreatesOneItemAndTheSecondClickIsToldItIsDone() throws Exception {
        PendingAction draft = issueDraft("U1");

        CompletableFuture<Void> first = handler.handle(CHANNEL, "9000.0007", "100.1", "U1", "draft_create", String.valueOf(draft.id()));
        CompletableFuture<Void> second = handler.handle(CHANNEL, "9000.0007", "100.1", "U1", "draft_create", String.valueOf(draft.id()));
        CompletableFuture.allOf(first, second).get();

        assertThat(linearApi.creates).hasSize(1);
        assertThat(slack.updates).hasSize(1);
        assertThat(slack.ephemerals).hasSize(1);
        assertThat(slack.ephemerals.get(0).text()).isEqualTo("That draft has already been dealt with.");
    }

    @Test
    void aDraftThatWaitedOverAnHourIsReplacedByAnExpiredNote() throws Exception {
        PendingAction draft = issueDraft("U1");
        handler = handler(Clock.fixed(Instant.now().plus(Duration.ofMinutes(90)), ZoneOffset.UTC));

        click(draft, "U1", "draft_create");

        assertThat(linearApi.creates).isEmpty();
        assertThat(status(draft)).isEqualTo(Status.EXPIRED);
        assertThat(slack.lastUpdate().text()).contains("This draft expired").contains("nothing was created in Linear");
        assertThat(hasButtons(slack.lastUpdate().blocks())).isFalse();
    }

    @Test
    void aFailedCreateShowsAPlainApologyAndRecordsTheError() throws Exception {
        PendingAction draft = issueDraft("U1");
        linearApi.createFailure = new IOException("Linear answered HTTP 500");

        click(draft, "U1", "draft_create");

        PendingAction failed = pending.find(tenantId, draft.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.error()).contains("HTTP 500");
        assertThat(slack.lastUpdate().text()).isEqualTo(
                "Sorry, I couldn't create that in Linear. Nothing was created. You can ask me to try again.");
        assertThat(slack.lastUpdate().text()).doesNotContain("HTTP 500").doesNotContain("IOException");
        assertThat(hasButtons(slack.lastUpdate().blocks())).isFalse();
    }

    @Test
    void ifSlackWillNotUpdateTheDraftTheLinkIsPostedInTheThreadInstead() throws Exception {
        PendingAction draft = issueDraft("U1");
        slack.rejectAllUpdates = true;

        click(draft, "U1", "draft_create");

        assertThat(linearApi.creates).hasSize(1);
        FakeMessenger.Posted post = slack.onlyPost();
        assertThat(post.threadTs()).isEqualTo("100.1");
        assertThat(post.text()).contains("https://linear.app/acme/issue/ENG-99");
    }

    @Test
    void clicksThatMakeNoSenseAreIgnored() throws Exception {
        PendingAction draft = issueDraft("U1");

        handler.handle(CHANNEL, "9000.1", "100.1", "U1", "draft_create", "not-a-number").get();
        handler.handle(CHANNEL, "9000.1", "100.1", "U1", "something_else", String.valueOf(draft.id())).get();
        handler.handle(null, "9000.1", "100.1", "U1", "draft_create", String.valueOf(draft.id())).get();
        handler.handle(CHANNEL, "9000.1", "100.1", "U1", "draft_create", null).get();
        handler.handle(CHANNEL, "9000.1", "100.1", "U1", "draft_create", "999999").get();

        assertThat(linearApi.creates).isEmpty();
        assertThat(status(draft)).isEqualTo(Status.PENDING);
        assertThat(slack.ephemerals).extracting(FakeMessenger.Ephemeral::text)
                .containsExactly("I can't find that draft any more.");
    }
}
