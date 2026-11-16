package com.docs0.web.admin;

import com.docs0.knowledge.DocumentStore;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SourceCredential;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.SyncService;
import com.docs0.source.linear.LinearClient;
import com.docs0.source.linear.LinearConnection;
import com.docs0.source.linear.LinearTeam;
import com.docs0.source.trello.TrelloBoard;
import com.docs0.source.trello.TrelloClient;
import com.docs0.source.trello.TrelloConnection;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * What the Sources page shows for Linear and Trello: connection state, the teams or boards to
 * choose from (asked of the tool, then remembered briefly because the page reloads itself during
 * a sync), the sync log, and how many documents each source kind has.
 */
@Component
public class ToolPanels {

    public static final String LINEAR = "linear";
    public static final String TRELLO = "trello";

    /** A team or board in the checklist. */
    public record Choice(String id, String label, boolean checked) {}

    /**
     * One tool's section. {@code state} is setup (cannot store a secret yet), disconnected or
     * connected. {@code choices} is null when the tool could not be asked; see {@code listError}.
     */
    public record Panel(String id, String title, String state, String account, boolean confirmDisconnect,
                        boolean syncRunning, long documents, List<Choice> choices, String listError,
                        List<SourcesController.RunRow> runs) {}

    /** One row of the "documents indexed" table. */
    public record KindCount(String label, long documents) {}

    private static final Logger log = LoggerFactory.getLogger(ToolPanels.class);
    private static final Duration KEEP = Duration.ofMinutes(2);
    private static final int RECENT_RUNS = 5;
    private static final Map<String, String> KIND_LABELS = Map.of(
            "GOOGLE_DRIVE", "Google Drive", "LINEAR", "Linear", "TRELLO", "Trello");

    private record Cached<T>(List<T> items, Instant at) {}

    private final LinearConnection linear;
    private final LinearClient linearClient;
    private final TrelloConnection trello;
    private final TrelloClient trelloClient;
    private final SyncService syncService;
    private final SyncRunRepository runs;
    private final DocumentStore documents;
    private final Map<Long, Cached<LinearTeam>> teamCache = new ConcurrentHashMap<>();
    private final Map<Long, Cached<TrelloBoard>> boardCache = new ConcurrentHashMap<>();

    public ToolPanels(LinearConnection linear, LinearClient linearClient, TrelloConnection trello,
                      TrelloClient trelloClient, SyncService syncService, SyncRunRepository runs,
                      DocumentStore documents) {
        this.linear = linear;
        this.linearClient = linearClient;
        this.trello = trello;
        this.trelloClient = trelloClient;
        this.syncService = syncService;
        this.runs = runs;
        this.documents = documents;
    }

    /** Puts the Linear and Trello sections and the per-kind counts into the page model. */
    public void addTo(Model model, long tenantId, String confirmDisconnect) {
        model.addAttribute("linear", linearPanel(tenantId, LINEAR.equals(confirmDisconnect)));
        model.addAttribute("trello", trelloPanel(tenantId, TRELLO.equals(confirmDisconnect)));
        List<KindCount> counts = new ArrayList<>();
        documents.countsByKind(tenantId).forEach((kind, n) -> counts.add(new KindCount(
                KIND_LABELS.getOrDefault(kind, kind), n)));
        model.addAttribute("kindCounts", counts);
    }

    /** The teams Linear lists for the key, remembered for a couple of minutes. */
    List<LinearTeam> availableTeams(long tenantId) throws IOException {
        Cached<LinearTeam> cached = teamCache.get(tenantId);
        if (cached != null && cached.at().plus(KEEP).isAfter(Instant.now())) {
            return cached.items();
        }
        List<LinearTeam> teams = linearClient.teams(linear.apiKey(tenantId));
        teamCache.put(tenantId, new Cached<>(teams, Instant.now()));
        return teams;
    }

    /** The open boards Trello lists for the token, remembered for a couple of minutes. */
    List<TrelloBoard> availableBoards(long tenantId) throws IOException {
        Cached<TrelloBoard> cached = boardCache.get(tenantId);
        if (cached != null && cached.at().plus(KEEP).isAfter(Instant.now())) {
            return cached.items();
        }
        TrelloConnection.Credentials credentials = trello.credentials(tenantId);
        List<TrelloBoard> boards = trelloClient.boards(credentials.key(), credentials.token());
        boardCache.put(tenantId, new Cached<>(boards, Instant.now()));
        return boards;
    }

    void forget(long tenantId) {
        teamCache.remove(tenantId);
        boardCache.remove(tenantId);
    }

    private Panel linearPanel(long tenantId, boolean confirm) {
        String title = "Linear";
        if (!linear.canStoreSecrets()) {
            return new Panel(LINEAR, title, "setup", null, false, false, 0, null, null, List.of());
        }
        Optional<SourceCredential> credential = linear.credential(tenantId);
        if (credential.isEmpty()) {
            return new Panel(LINEAR, title, "disconnected", null, false, false, 0, null, null, List.of());
        }
        long sourceId = credential.get().sourceId();
        List<Choice> choices = null;
        String error = null;
        try {
            Set<String> chosen = linear.teams(tenantId).stream().map(LinearTeam::id).collect(Collectors.toSet());
            choices = availableTeams(tenantId).stream()
                    .map(t -> new Choice(t.id(), t.name() + " (" + t.key() + ")", chosen.contains(t.id()))).toList();
        } catch (IOException | RuntimeException e) {
            error = describe("Linear", e);
        }
        return connected(LINEAR, title, credential.get(), sourceId, confirm, choices, error);
    }

    private Panel trelloPanel(long tenantId, boolean confirm) {
        String title = "Trello";
        if (!trello.canStoreSecrets()) {
            return new Panel(TRELLO, title, "setup", null, false, false, 0, null, null, List.of());
        }
        Optional<SourceCredential> credential = trello.credential(tenantId);
        if (credential.isEmpty()) {
            return new Panel(TRELLO, title, "disconnected", null, false, false, 0, null, null, List.of());
        }
        long sourceId = credential.get().sourceId();
        List<Choice> choices = null;
        String error = null;
        try {
            Set<String> chosen = trello.boards(tenantId).stream().map(TrelloBoard::id).collect(Collectors.toSet());
            choices = availableBoards(tenantId).stream()
                    .map(b -> new Choice(b.id(), b.name(), chosen.contains(b.id()))).toList();
        } catch (IOException | RuntimeException e) {
            error = describe("Trello", e);
        }
        return connected(TRELLO, title, credential.get(), sourceId, confirm, choices, error);
    }

    private Panel connected(String id, String title, SourceCredential credential, long sourceId, boolean confirm,
                            List<Choice> choices, String error) {
        long tenantId = credential.tenantId();
        boolean running = syncService.isRunning(sourceId);
        List<SourcesController.RunRow> rows = new ArrayList<>();
        for (SyncRun run : runs.recent(tenantId, sourceId, RECENT_RUNS)) {
            rows.add(SourcesController.runRow(run, running));
        }
        return new Panel(id, title, "connected", credential.accountName(), confirm, running,
                documents.count(tenantId, sourceId), choices, error, rows);
    }

    private static String describe(String tool, Exception e) {
        if (e instanceof SourceAccessException) {
            return e.getMessage();
        }
        log.warn("Could not ask {} for the Sources page: {}", tool, e.getClass().getSimpleName());
        return tool + " could not be reached. Try again in a moment.";
    }
}
