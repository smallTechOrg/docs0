package com.docs0.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.TestData;
import com.docs0.action.PendingAction;
import com.docs0.action.PendingAction.Kind;
import com.docs0.action.PendingAction.Status;
import com.docs0.action.PendingActionRepository;
import com.docs0.agent.Agent.AgentRequest;
import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.LlmClient.ToolCall;
import com.docs0.agent.LlmClient.ToolSpec;
import com.docs0.config.Docs0Properties;
import com.docs0.conversation.Conversation;
import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.DocumentStore;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SourceRepository;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.source.linear.LinearConnection;
import com.docs0.source.linear.LinearTeam;
import com.docs0.source.trello.FakeTrelloClient;
import com.docs0.source.trello.TrelloBoard;
import com.docs0.source.trello.TrelloConnection;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The tools that draft a Linear issue or Trello card: when they exist, what they validate, what they store. */
class DraftToolsTest extends IntegrationTest {

    private static final LinearTeam ENG = new LinearTeam("team-eng", "ENG", "Engineering");

    @Autowired
    SourceRepository sources;

    @Autowired
    SourceCredentialRepository credentials;

    @Autowired
    DocumentStore store;

    @Autowired
    PendingActionRepository pending;

    FakeTrelloClient trelloApi;
    LinearConnection linear;
    TrelloConnection trello;
    DraftTools tools;
    Conversation conversation;
    final List<String> progress = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TokenCipher cipher = new TokenCipher(DriveTestSetup.ENCRYPTION_KEY);
        trelloApi = new FakeTrelloClient();
        trelloApi.content.put("b1", FakeTrelloClient.board("b1", "Roadmap", Instant.parse("2026-03-01T00:00:00Z"), List.of()));
        linear = new LinearConnection(cipher, sources, credentials, store);
        trello = new TrelloConnection(cipher, sources, credentials, store);
        tools = new DraftTools(linear, trello, trelloApi, pending);
        conversation = TestData.conversation(jdbc, tenant.id(), "C1", "100.1", false);
    }

    private void connectBoth() {
        linear.connect(tenant.id(), "Acme", "lin_api_x");
        linear.setTeams(tenant.id(), List.of(ENG));
        trello.connect(tenant.id(), "Asha", "k", "t");
        trello.setBoards(tenant.id(), List.of(new TrelloBoard("b1", "Roadmap")));
    }

    private AgentRequest request(String requester) {
        return new AgentRequest(tenant.id(), Audience.of("U1"), "please file this", List.of(), conversation.id(), requester);
    }

    private KnowledgeTools.Output run(String name, List<PendingAction> drafts, Object... input) {
        return tools.run(name, ScriptedLlm.map(input), request("U1"), progress::add, drafts);
    }

    private long rows() {
        return jdbc.sql("SELECT count(*) FROM pending_action").query(Long.class).single();
    }

    // ---- which tools exist -----------------------------------------------------------------

    @Test
    void noToolsWhenNothingIsConnected() {
        assertThat(tools.specs(tenant.id())).isEmpty();
    }

    @Test
    void aConnectedToolWithNothingChosenOffersNothing() {
        linear.connect(tenant.id(), "Acme", "lin_api_x");
        trello.connect(tenant.id(), "Asha", "k", "t");

        assertThat(tools.specs(tenant.id())).isEmpty();
    }

    @Test
    void eachToolIsOfferedOnlyWhenItIsConnectedAndHasASelection() {
        linear.connect(tenant.id(), "Acme", "lin_api_x");
        linear.setTeams(tenant.id(), List.of(ENG));
        assertThat(names()).containsExactly("list_linear_teams", "create_linear_issue");

        trello.connect(tenant.id(), "Asha", "k", "t");
        trello.setBoards(tenant.id(), List.of(new TrelloBoard("b1", "Roadmap")));
        assertThat(names()).containsExactly("list_linear_teams", "create_linear_issue",
                "list_trello_boards_and_lists", "create_trello_card");

        linear.disconnect(tenant.id());
        assertThat(names()).containsExactly("list_trello_boards_and_lists", "create_trello_card");
        trello.disconnect(tenant.id());
        assertThat(names()).isEmpty();
    }

    private List<String> names() {
        return tools.specs(tenant.id()).stream().map(ToolSpec::name).toList();
    }

    @Test
    void theCreateToolsHaveStrictSchemasAndTellTheModelToUseThemOnlyWhenAsked() {
        connectBoth();

        for (ToolSpec spec : tools.specs(tenant.id())) {
            if (spec.name().startsWith("create_")) {
                assertThat(spec.strict()).as(spec.name()).isTrue();
                assertThat(spec.required()).containsExactlyInAnyOrderElementsOf(spec.properties().keySet());
                assertThat(spec.description()).contains("ONLY when the person explicitly asked")
                        .contains("Never use it on your own initiative").contains("draft")
                        .contains("never say it has been created");
                // The schema builds into a request tool as the real client would send it.
                assertThat(AnthropicLlmClient.toTool(spec).name()).isEqualTo(spec.name());
            } else {
                assertThat(spec.properties()).isEmpty();
                assertThat(spec.strict()).isFalse();
            }
        }
        ToolSpec issue = tools.specs(tenant.id()).stream().filter(s -> s.name().equals("create_linear_issue")).findFirst().orElseThrow();
        assertThat(issue.properties()).containsOnlyKeys("team_key", "title", "description", "priority");
        @SuppressWarnings("unchecked")
        Map<String, Object> priority = (Map<String, Object>) issue.properties().get("priority");
        assertThat(priority.get("enum")).isEqualTo(List.of("urgent", "high", "medium", "low", "none"));
        ToolSpec card = tools.specs(tenant.id()).stream().filter(s -> s.name().equals("create_trello_card")).findFirst().orElseThrow();
        assertThat(card.properties()).containsOnlyKeys("board", "list", "title", "description");
    }

    @Test
    void aToolIsRefusedWhenItsSourceIsNotConnected() {
        List<PendingAction> drafts = new ArrayList<>();

        KnowledgeTools.Output out = run("create_linear_issue", drafts, "team_key", "ENG", "title", "T",
                "description", "D", "priority", "none");

        assertThat(out.isError()).isTrue();
        assertThat(out.content()).contains("Linear is not connected");
        assertThat(rows()).isZero();
    }

    // ---- Linear drafts ---------------------------------------------------------------------

    @Test
    void draftingAnIssueStoresAPendingRowAndSaysNothingWasCreated() {
        connectBoth();
        List<PendingAction> drafts = new ArrayList<>();

        KnowledgeTools.Output out = run("create_linear_issue", drafts, "team_key", "eng",
                "title", "  Fix the login page ", "description", "Users on Safari cannot sign in.", "priority", "high");

        assertThat(out.isError()).isFalse();
        assertThat(out.content()).contains("Draft saved").contains("NOTHING HAS BEEN CREATED")
                .contains("Fix the login page").contains("team ENG");
        assertThat(drafts).hasSize(1);
        PendingAction draft = pending.find(tenant.id(), drafts.get(0).id()).orElseThrow();
        assertThat(draft.status()).isEqualTo(Status.PENDING);
        assertThat(draft.kind()).isEqualTo(Kind.LINEAR_ISSUE);
        assertThat(draft.requestedBy()).isEqualTo("U1");
        assertThat(draft.conversationId()).isEqualTo(conversation.id());
        assertThat(draft.interactionId()).isNull();
        assertThat(draft.resultUrl()).isNull();
        assertThat(draft.params()).containsEntry("teamId", "team-eng").containsEntry("teamKey", "ENG")
                .containsEntry("teamName", "Engineering").containsEntry("title", "Fix the login page")
                .containsEntry("description", "Users on Safari cannot sign in.").containsEntry("priority", "high");
        assertThat(draft.target()).isEqualTo("Linear, team ENG (Engineering)");
        assertThat(progress).contains("Drafting a Linear issue…");
    }

    @Test
    void anIssueWithNoPriorityStoresNone() {
        connectBoth();
        List<PendingAction> drafts = new ArrayList<>();

        run("create_linear_issue", drafts, "team_key", "ENG", "title", "T", "description", "D", "priority", "none");

        assertThat(drafts.get(0).params()).doesNotContainKey("priority");
    }

    @Test
    void badInputIsAnErrorTheModelCanFixAndStoresNothing() {
        connectBoth();
        List<PendingAction> drafts = new ArrayList<>();

        KnowledgeTools.Output noTeam = run("create_linear_issue", drafts, "team_key", "NOPE", "title", "T",
                "description", "D", "priority", "none");
        KnowledgeTools.Output noTitle = run("create_linear_issue", drafts, "team_key", "ENG", "title", "  ",
                "description", "D", "priority", "none");
        KnowledgeTools.Output badPriority = run("create_linear_issue", drafts, "team_key", "ENG", "title", "T",
                "description", "D", "priority", "asap");
        KnowledgeTools.Output longTitle = run("create_linear_issue", drafts, "team_key", "ENG",
                "title", "x".repeat(300), "description", "D", "priority", "none");

        assertThat(noTeam.isError()).isTrue();
        assertThat(noTeam.content()).contains("no Linear team with the key \"NOPE\"").contains("ENG").contains("list_linear_teams");
        assertThat(noTitle.isError()).isTrue();
        assertThat(badPriority.isError()).isTrue();
        assertThat(badPriority.content()).contains("urgent, high, medium, low, none");
        assertThat(longTitle.isError()).isTrue();
        assertThat(drafts).isEmpty();
        assertThat(rows()).isZero();
    }

    @Test
    void withoutKnowingWhoAskedNothingIsDrafted() {
        connectBoth();
        List<PendingAction> drafts = new ArrayList<>();
        AgentRequest anonymous = new AgentRequest(tenant.id(), Audience.of("U1"), "q", List.of());

        KnowledgeTools.Output out = tools.run("create_linear_issue", ScriptedLlm.map("team_key", "ENG", "title", "T",
                "description", "D", "priority", "none"), anonymous, progress::add, drafts);

        assertThat(out.isError()).isTrue();
        assertThat(out.content()).contains("cannot be drafted from here");
        assertThat(rows()).isZero();
    }

    @Test
    void theSameDraftTwiceInOneAnswerIsStoredOnceAndAtMostThreeAreMade() {
        connectBoth();
        List<PendingAction> drafts = new ArrayList<>();

        run("create_linear_issue", drafts, "team_key", "ENG", "title", "A", "description", "D", "priority", "none");
        KnowledgeTools.Output again = run("create_linear_issue", drafts, "team_key", "ENG", "title", "A",
                "description", "D", "priority", "none");
        run("create_linear_issue", drafts, "team_key", "ENG", "title", "B", "description", "D", "priority", "none");
        run("create_linear_issue", drafts, "team_key", "ENG", "title", "C", "description", "D", "priority", "none");
        KnowledgeTools.Output fourth = run("create_linear_issue", drafts, "team_key", "ENG", "title", "D",
                "description", "D", "priority", "none");

        assertThat(again.content()).contains("already waiting");
        assertThat(drafts).hasSize(3);
        assertThat(fourth.isError()).isTrue();
        assertThat(rows()).isEqualTo(3);
    }

    @Test
    void listingTeamsShowsTheSelectedOnesOnly() {
        connectBoth();

        KnowledgeTools.Output out = run("list_linear_teams", new ArrayList<>());

        assertThat(out.content()).contains("team_key: ENG, name: Engineering").doesNotContain("OPS")
                .contains("urgent, high, medium, low, none");
    }

    // ---- Trello drafts ---------------------------------------------------------------------

    @Test
    void draftingACardResolvesBoardAndListByNameAndWritesNothingToTrello() {
        connectBoth();
        List<PendingAction> drafts = new ArrayList<>();

        KnowledgeTools.Output out = run("create_trello_card", drafts, "board", "roadmap", "list", "DOING",
                "title", "Book venue", "description", "For the offsite");

        assertThat(out.isError()).isFalse();
        assertThat(out.content()).contains("NOTHING HAS BEEN CREATED").contains("Roadmap / Doing");
        PendingAction draft = pending.find(tenant.id(), drafts.get(0).id()).orElseThrow();
        assertThat(draft.kind()).isEqualTo(Kind.TRELLO_CARD);
        assertThat(draft.status()).isEqualTo(Status.PENDING);
        assertThat(draft.params()).containsEntry("boardId", "b1").containsEntry("boardName", "Roadmap")
                .containsEntry("listId", "l-doing").containsEntry("listName", "Doing")
                .containsEntry("title", "Book venue").containsEntry("description", "For the offsite");
        assertThat(draft.target()).isEqualTo("Trello, board Roadmap, list Doing");
        assertThat(trelloApi.creates).isEmpty();
    }

    @Test
    void anUnknownBoardOrListIsAnErrorThatNamesTheValidChoices() {
        connectBoth();
        List<PendingAction> drafts = new ArrayList<>();

        KnowledgeTools.Output noBoard = run("create_trello_card", drafts, "board", "Hiring", "list", "Doing", "title", "T",
                "description", "D");
        KnowledgeTools.Output noList = run("create_trello_card", drafts, "board", "Roadmap", "list", "Done", "title", "T",
                "description", "D");

        assertThat(noBoard.isError()).isTrue();
        assertThat(noBoard.content()).contains("no connected Trello board called \"Hiring\"").contains("Roadmap");
        assertThat(noList.isError()).isTrue();
        assertThat(noList.content()).contains("no list called \"Done\"").contains("To do, Doing");
        assertThat(rows()).isZero();
    }

    @Test
    void listingBoardsShowsTheirLists() {
        connectBoth();

        KnowledgeTools.Output out = run("list_trello_boards_and_lists", new ArrayList<>());

        assertThat(out.content()).contains("board: Roadmap").contains("list: To do").contains("list: Doing");
    }

    // ---- inside the agent loop -------------------------------------------------------------

    private AgentService service(ScriptedLlm llm) {
        Docs0Properties properties = new Docs0Properties(null, new Docs0Properties.Anthropic("test-key",
                "claude-sonnet-5-5", new BigDecimal("2.00"), new BigDecimal("10.00"), 12),
                null, null, null, null, null, null, null);
        return new AgentService(llm, new FakeKnowledge(), properties, (t, q, l) -> List.of(), tools,
                Clock.fixed(Instant.parse("2026-11-17T09:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    void theAgentDraftsThenAnswersAndTheResultCarriesTheDraft() {
        connectBoth();
        ScriptedLlm llm = new ScriptedLlm();
        ToolCall create = llm.call("create_linear_issue", "team_key", "ENG", "title", "Fix login",
                "description", "Safari users cannot sign in", "priority", "none");
        llm.calls(create).calls(llm.call("submit_answer", "kind", "answer",
                "answer", "I drafted a Linear issue in ENG. Confirm it with the Create button.",
                "confidence", "high", "citations", List.of(), "follow_ups", List.of(), "suggested_people", List.of()));

        AgentResult result = service(llm).answer(request("U1"), progress::add);

        assertThat(result.kind()).isEqualTo(Agent.Kind.ANSWER);
        assertThat(result.drafts()).hasSize(1);
        assertThat(result.drafts().get(0).kind()).isEqualTo(Kind.LINEAR_ISSUE);
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(result.answer()).doesNotContain("could not verify");
        assertThat(llm.resultFor(create).content()).contains("NOTHING HAS BEEN CREATED");
        assertThat(llm.resultFor(create).isError()).isFalse();
        assertThat(llm.tools.stream().map(ToolSpec::name)).contains("create_linear_issue", "create_trello_card",
                "list_linear_teams", "list_trello_boards_and_lists", "search_knowledge", "submit_answer");
        assertThat(llm.systemPrompt).isEqualTo(SystemPrompt.TEXT);
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void withNothingConnectedTheModelGetsOnlyTheReadingToolsAndCannotCreate() {
        ScriptedLlm llm = new ScriptedLlm();
        ToolCall create = llm.call("create_linear_issue", "team_key", "ENG", "title", "T", "description", "D",
                "priority", "none");
        llm.calls(create).calls(llm.call("submit_answer", "kind", "unknown", "answer", "I can't file that.",
                "confidence", "low", "citations", List.of(), "follow_ups", List.of(), "suggested_people", List.of()));

        AgentResult result = service(llm).answer(request("U1"), progress::add);

        assertThat(llm.tools.stream().map(ToolSpec::name)).containsExactly("search_knowledge", "read_document",
                "list_recent_documents", "submit_answer");
        assertThat(llm.resultFor(create).isError()).isTrue();
        assertThat(result.drafts()).isEmpty();
        assertThat(rows()).isZero();
    }
}
