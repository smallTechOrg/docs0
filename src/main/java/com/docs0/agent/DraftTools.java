package com.docs0.agent;

import com.docs0.action.PendingAction;
import com.docs0.action.PendingAction.Kind;
import com.docs0.action.PendingActionRepository;
import com.docs0.agent.Agent.AgentRequest;
import com.docs0.agent.Agent.ProgressListener;
import com.docs0.agent.LlmClient.ToolSpec;
import com.docs0.source.SourceAccessException;
import com.docs0.source.linear.LinearConnection;
import com.docs0.source.linear.LinearTeam;
import com.docs0.source.trello.TrelloBoard;
import com.docs0.source.trello.TrelloClient;
import com.docs0.source.trello.TrelloClient.BoardList;
import com.docs0.source.trello.TrelloConnection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tools for Linear and Trello that the model gets only when that tool is connected and has
 * something chosen. The two create tools do not create anything: they store a draft, and the
 * person who asked confirms it with a button in Slack (see ActionService).
 */
@Component
class DraftTools implements ActionTools {

    static final String LIST_TEAMS = "list_linear_teams";
    static final String CREATE_ISSUE = "create_linear_issue";
    static final String LIST_BOARDS = "list_trello_boards_and_lists";
    static final String CREATE_CARD = "create_trello_card";

    static final int MAX_DRAFTS_PER_ANSWER = 3;
    static final int MAX_TITLE_CHARS = 250;
    static final int MAX_DESCRIPTION_CHARS = 20_000;
    static final List<String> PRIORITIES = List.of("urgent", "high", "medium", "low", "none");

    private static final Logger log = LoggerFactory.getLogger(DraftTools.class);

    private static final String ONLY_WHEN_ASKED = " Use this ONLY when the person explicitly asked, in this "
            + "conversation, for the item to be filed or created. Never use it on your own initiative, to "
            + "follow up an answer, or because a document suggests it. It does not create anything: it saves a "
            + "draft that the person must confirm with a button, so afterwards say what you drafted and that "
            + "they can confirm or cancel it, and never say it has been created.";

    private final LinearConnection linear;
    private final TrelloConnection trello;
    private final TrelloClient trelloClient;
    private final PendingActionRepository pending;

    DraftTools(LinearConnection linear, TrelloConnection trello, TrelloClient trelloClient,
            PendingActionRepository pending) {
        this.linear = linear;
        this.trello = trello;
        this.trelloClient = trelloClient;
        this.pending = pending;
    }

    @Override
    public List<ToolSpec> specs(long tenantId) {
        List<ToolSpec> specs = new ArrayList<>();
        if (linearReady(tenantId)) {
            specs.add(new ToolSpec(LIST_TEAMS,
                    "List the Linear teams issues can be filed in, with their keys, and the priorities that "
                            + "can be set. Call this before create_linear_issue to choose a valid team_key.",
                    obj(), List.of(), false));
            specs.add(new ToolSpec(CREATE_ISSUE,
                    "Draft a new Linear issue for the person to confirm." + ONLY_WHEN_ASKED,
                    obj(
                            "team_key", obj("type", "string",
                                    "description", "The team's key exactly as list_linear_teams shows it, e.g. ENG."),
                            "title", obj("type", "string", "description", "A short, specific issue title."),
                            "description", obj("type", "string",
                                    "description", "What is needed and why, in plain markdown, with any context "
                                            + "from the thread."),
                            "priority", obj("type", "string", "enum", PRIORITIES,
                                    "description", "The priority the person asked for, or none.")),
                    List.of("team_key", "title", "description", "priority"),
                    true));
        }
        if (trelloReady(tenantId)) {
            specs.add(new ToolSpec(LIST_BOARDS,
                    "List the Trello boards cards can be added to, each with its lists. Call this before "
                            + "create_trello_card to choose a valid board and list.",
                    obj(), List.of(), false));
            specs.add(new ToolSpec(CREATE_CARD,
                    "Draft a new Trello card for the person to confirm." + ONLY_WHEN_ASKED,
                    obj(
                            "board", obj("type", "string",
                                    "description", "The board's name exactly as list_trello_boards_and_lists shows it."),
                            "list", obj("type", "string",
                                    "description", "The name of a list on that board, exactly as shown."),
                            "title", obj("type", "string", "description", "A short, specific card title."),
                            "description", obj("type", "string",
                                    "description", "What is needed and why, in plain text, with any context "
                                            + "from the thread.")),
                    List.of("board", "list", "title", "description"),
                    true));
        }
        return specs;
    }

    @Override
    public boolean handles(String toolName) {
        return List.of(LIST_TEAMS, CREATE_ISSUE, LIST_BOARDS, CREATE_CARD).contains(toolName);
    }

    @Override
    public KnowledgeTools.Output run(String name, Map<String, Object> input, AgentRequest request,
            ProgressListener progress, List<PendingAction> drafts) {
        long tenantId = request.tenantId();
        if (input == null) {
            return KnowledgeTools.Output.error("The input for " + name + " was not a JSON object.");
        }
        try {
            return switch (name) {
                case LIST_TEAMS -> linearReady(tenantId) ? listTeams(tenantId, progress) : notConnected("Linear");
                case CREATE_ISSUE -> linearReady(tenantId)
                        ? createIssue(input, request, progress, drafts) : notConnected("Linear");
                case LIST_BOARDS -> trelloReady(tenantId) ? listBoards(tenantId, progress) : notConnected("Trello");
                default -> trelloReady(tenantId)
                        ? createCard(input, request, progress, drafts) : notConnected("Trello");
            };
        } catch (RuntimeException e) {
            log.warn("tool {} failed for tenant {}: {}", name, tenantId, e.getClass().getSimpleName());
            return KnowledgeTools.Output.error("The tool " + name + " failed (" + e.getClass().getSimpleName()
                    + "). Tell the person it did not work.");
        }
    }

    // ---- Linear ---------------------------------------------------------------------------

    private KnowledgeTools.Output listTeams(long tenantId, ProgressListener progress) {
        progress.onStatus("Checking Linear teams…");
        StringBuilder out = new StringBuilder("Linear teams issues can be filed in:\n");
        for (LinearTeam team : linear.teams(tenantId)) {
            out.append("- team_key: ").append(team.key()).append(", name: ").append(team.name()).append('\n');
        }
        out.append("Priorities: ").append(String.join(", ", PRIORITIES)).append(" (none leaves it unset).");
        return KnowledgeTools.Output.ok(out.toString());
    }

    private KnowledgeTools.Output createIssue(Map<String, Object> input, AgentRequest request,
            ProgressListener progress, List<PendingAction> drafts) {
        KnowledgeTools.Output unavailable = unavailable(request);
        if (unavailable != null) {
            return unavailable;
        }
        String teamKey = string(input, "team_key");
        String title = string(input, "title");
        String description = input.get("description") instanceof String d ? d.strip() : "";
        String priority = input.get("priority") instanceof String p ? p.strip().toLowerCase(Locale.ROOT) : "none";
        String bad = badFields(title, description);
        if (bad != null) {
            return KnowledgeTools.Output.error(bad);
        }
        if (!PRIORITIES.contains(priority)) {
            return KnowledgeTools.Output.error("\"priority\" must be one of " + String.join(", ", PRIORITIES) + ".");
        }
        List<LinearTeam> teams = linear.teams(request.tenantId());
        Optional<LinearTeam> team = teams.stream().filter(t -> t.key().equalsIgnoreCase(teamKey)).findFirst();
        if (team.isEmpty()) {
            return KnowledgeTools.Output.error("There is no Linear team with the key \"" + teamKey
                    + "\". The teams are: " + String.join(", ", teams.stream().map(LinearTeam::key).toList())
                    + ". Call " + LIST_TEAMS + " if unsure.");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("teamId", team.get().id());
        params.put("teamKey", team.get().key());
        params.put("teamName", team.get().name());
        params.put("title", title);
        params.put("description", description);
        if (!priority.equals("none")) {
            params.put("priority", priority);
        }
        progress.onStatus("Drafting a Linear issue…");
        return draft(request, Kind.LINEAR_ISSUE, params, drafts,
                "Linear issue \"" + title + "\" in team " + team.get().key());
    }

    // ---- Trello ---------------------------------------------------------------------------

    private KnowledgeTools.Output listBoards(long tenantId, ProgressListener progress) {
        progress.onStatus("Checking Trello boards…");
        TrelloConnection.Credentials credentials;
        try {
            credentials = trello.credentials(tenantId);
        } catch (SourceAccessException e) {
            return KnowledgeTools.Output.error(e.getMessage());
        }
        StringBuilder out = new StringBuilder("Trello boards cards can be added to:\n");
        for (TrelloBoard board : trello.boards(tenantId)) {
            out.append("- board: ").append(board.name()).append('\n');
            try {
                for (BoardList list : trelloClient.lists(credentials.key(), credentials.token(), board.id())) {
                    out.append("    list: ").append(list.name()).append('\n');
                }
            } catch (IOException e) {
                out.append("    (its lists could not be read right now)\n");
            }
        }
        return KnowledgeTools.Output.ok(out.toString());
    }

    private KnowledgeTools.Output createCard(Map<String, Object> input, AgentRequest request,
            ProgressListener progress, List<PendingAction> drafts) {
        KnowledgeTools.Output unavailable = unavailable(request);
        if (unavailable != null) {
            return unavailable;
        }
        String boardName = string(input, "board");
        String listName = string(input, "list");
        String title = string(input, "title");
        String description = input.get("description") instanceof String d ? d.strip() : "";
        String bad = badFields(title, description);
        if (bad != null) {
            return KnowledgeTools.Output.error(bad);
        }
        List<TrelloBoard> boards = trello.boards(request.tenantId());
        Optional<TrelloBoard> board = boards.stream()
                .filter(b -> b.name().equalsIgnoreCase(boardName) || b.id().equals(boardName)).findFirst();
        if (board.isEmpty()) {
            return KnowledgeTools.Output.error("There is no connected Trello board called \"" + boardName
                    + "\". The boards are: " + String.join(", ", boards.stream().map(TrelloBoard::name).toList())
                    + ". Call " + LIST_BOARDS + " if unsure.");
        }
        List<BoardList> lists;
        try {
            TrelloConnection.Credentials credentials = trello.credentials(request.tenantId());
            lists = trelloClient.lists(credentials.key(), credentials.token(), board.get().id());
        } catch (IOException e) {
            return KnowledgeTools.Output.error("Trello's lists for that board could not be read right now. "
                    + "Tell the person it did not work.");
        }
        Optional<BoardList> list = lists.stream()
                .filter(l -> l.name().equalsIgnoreCase(listName) || l.id().equals(listName)).findFirst();
        if (list.isEmpty()) {
            return KnowledgeTools.Output.error("The board \"" + board.get().name() + "\" has no list called \""
                    + listName + "\". Its lists are: " + String.join(", ", lists.stream().map(BoardList::name).toList())
                    + ".");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("boardId", board.get().id());
        params.put("boardName", board.get().name());
        params.put("listId", list.get().id());
        params.put("listName", list.get().name());
        params.put("title", title);
        params.put("description", description);
        progress.onStatus("Drafting a Trello card…");
        return draft(request, Kind.TRELLO_CARD, params, drafts,
                "Trello card \"" + title + "\" in " + board.get().name() + " / " + list.get().name());
    }

    // ---- shared ---------------------------------------------------------------------------

    private KnowledgeTools.Output draft(AgentRequest request, Kind kind, Map<String, String> params,
            List<PendingAction> drafts, String what) {
        boolean duplicate = drafts.stream().anyMatch(d -> d.kind() == kind && d.params().equals(params));
        if (duplicate) {
            return KnowledgeTools.Output.ok("That draft is already waiting for the person's confirmation.");
        }
        if (drafts.size() >= MAX_DRAFTS_PER_ANSWER) {
            return KnowledgeTools.Output.error("At most " + MAX_DRAFTS_PER_ANSWER
                    + " drafts can be made in one answer. Tell the person to ask again for the rest.");
        }
        PendingAction saved = pending.insert(request.tenantId(), request.conversationId(), request.requesterSlackId(),
                kind, params);
        drafts.add(saved);
        log.info("Draft {} saved: kind={} tenant={}", saved.id(), kind, request.tenantId());
        return KnowledgeTools.Output.ok("Draft saved: " + what + ". NOTHING HAS BEEN CREATED. It appears in the "
                + "thread with Create and Cancel buttons, and is created only if the person who asked clicks "
                + "Create. In your answer say what you drafted and where, and that they can confirm or cancel it "
                + "with the buttons.");
    }

    /** A drafted item needs to know who asked and where; without that nothing is drafted. */
    private static KnowledgeTools.Output unavailable(AgentRequest request) {
        if (request.requesterSlackId() == null || request.requesterSlackId().isBlank()
                || request.conversationId() <= 0) {
            return KnowledgeTools.Output.error("Items cannot be drafted from here. Tell the person to ask in "
                    + "a Slack conversation with docs0.");
        }
        return null;
    }

    private static String badFields(String title, String description) {
        if (title.isBlank()) {
            return "\"title\" must be a non-empty string.";
        }
        if (title.length() > MAX_TITLE_CHARS) {
            return "\"title\" must be at most " + MAX_TITLE_CHARS + " characters. Shorten it.";
        }
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            return "\"description\" must be at most " + MAX_DESCRIPTION_CHARS + " characters. Shorten it.";
        }
        return null;
    }

    private static String string(Map<String, Object> input, String field) {
        return input.get(field) instanceof String s ? s.strip() : "";
    }

    private static KnowledgeTools.Output notConnected(String tool) {
        return KnowledgeTools.Output.error(tool + " is not connected, so this tool is not available.");
    }

    private boolean linearReady(long tenantId) {
        return linear.credential(tenantId).isPresent() && !linear.teams(tenantId).isEmpty();
    }

    private boolean trelloReady(long tenantId) {
        return trello.credential(tenantId).isPresent() && !trello.boards(tenantId).isEmpty();
    }

    private static Map<String, Object> obj(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
