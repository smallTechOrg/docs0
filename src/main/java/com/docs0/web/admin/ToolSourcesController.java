package com.docs0.web.admin;

import com.docs0.source.Source;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SyncService;
import com.docs0.source.linear.LinearClient;
import com.docs0.source.linear.LinearConnection;
import com.docs0.source.linear.LinearConnector;
import com.docs0.source.linear.LinearTeam;
import com.docs0.source.trello.TrelloBoard;
import com.docs0.source.trello.TrelloClient;
import com.docs0.source.trello.TrelloConnection;
import com.docs0.source.trello.TrelloConnector;
import com.docs0.tenant.TenantService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

/**
 * The form posts of the Linear and Trello sections of the Sources page: connect (testing the
 * key first), choose teams or boards, check access, sync now, disconnect. Keys and tokens are
 * only ever read from the form, tested, encrypted and stored; they are never shown again or logged.
 */
@Controller
public class ToolSourcesController {

    private static final Logger log = LoggerFactory.getLogger(ToolSourcesController.class);
    private static final String LINEAR = SourcesController.PAGE + "/linear";
    private static final String TRELLO = SourcesController.PAGE + "/trello";
    private static final int MAX_SECRET_CHARS = 300;
    /** Check access stops counting here; the page then shows "2,000+". */
    static final int CHECK_CAP = 2000;

    private final TenantService tenants;
    private final LinearConnection linear;
    private final LinearClient linearClient;
    private final LinearConnector linearConnector;
    private final TrelloConnection trello;
    private final TrelloClient trelloClient;
    private final TrelloConnector trelloConnector;
    private final SyncService syncService;
    private final ToolPanels panels;

    public ToolSourcesController(TenantService tenants, LinearConnection linear, LinearClient linearClient,
                                 LinearConnector linearConnector, TrelloConnection trello,
                                 TrelloClient trelloClient, TrelloConnector trelloConnector,
                                 SyncService syncService, ToolPanels panels) {
        this.tenants = tenants;
        this.linear = linear;
        this.linearClient = linearClient;
        this.linearConnector = linearConnector;
        this.trello = trello;
        this.trelloClient = trelloClient;
        this.trelloConnector = trelloConnector;
        this.syncService = syncService;
        this.panels = panels;
    }

    // ---- Linear ---------------------------------------------------------------------------

    @PostMapping(LINEAR + "/connect")
    public RedirectView connectLinear(@RequestParam(name = "apiKey", required = false) String apiKey,
                                      RedirectAttributes flash) {
        if (!linear.canStoreSecrets()) {
            return failed(flash, "Linear needs DOCS0_ENCRYPTION_KEY to be set first. See the README.", "linear");
        }
        String key = clean(apiKey);
        if (key.isEmpty() || key.length() > MAX_SECRET_CHARS) {
            return failed(flash, "Paste your Linear API key first.", "linear");
        }
        LinearClient.Viewer viewer;
        try {
            viewer = linearClient.viewer(key);
        } catch (SourceAccessException e) {
            return failed(flash, "Linear did not accept that key. Check it and try again. Nothing was saved.", "linear");
        } catch (IOException | RuntimeException e) {
            log.warn("Linear could not be reached to test a key: {}", e.getClass().getSimpleName());
            return failed(flash, "Could not reach Linear to test the key. Try again in a moment. Nothing was saved.",
                    "linear");
        }
        long tenantId = tenants.current().id();
        String account = firstNonBlank(viewer.workspaceName(), viewer.userName(), "a Linear workspace");
        linear.connect(tenantId, account, key);
        panels.forget(tenantId);
        flash.addFlashAttribute("notice", "Connected to " + account + ". Now choose the teams to index.");
        return to("linear");
    }

    @PostMapping(LINEAR + "/teams")
    public RedirectView saveTeams(@RequestParam(name = "choice", required = false) List<String> choice,
                                  RedirectAttributes flash) {
        long tenantId = tenants.current().id();
        if (linear.credential(tenantId).isEmpty()) {
            return failed(flash, "Connect Linear first.", "linear");
        }
        List<LinearTeam> chosen = new ArrayList<>();
        try {
            Set<String> wanted = choice == null ? Set.of() : Set.copyOf(choice);
            for (LinearTeam team : panels.availableTeams(tenantId)) {
                if (wanted.contains(team.id())) {
                    chosen.add(team);
                }
            }
        } catch (IOException | RuntimeException e) {
            return failed(flash, "Could not ask Linear for its teams, so nothing was saved. Try again.", "linear");
        }
        linear.setTeams(tenantId, chosen);
        flash.addFlashAttribute("notice", chosen.isEmpty()
                ? "No teams chosen. Nothing from Linear is indexed."
                : "Saved. " + chosen.size() + (chosen.size() == 1 ? " team is" : " teams are")
                        + " indexed at the next sync.");
        return to("linear");
    }

    @PostMapping(LINEAR + "/check")
    public RedirectView checkLinear(RedirectAttributes flash) {
        long tenantId = tenants.current().id();
        List<LinearTeam> teams = linear.teams(tenantId);
        if (linear.credential(tenantId).isEmpty() || teams.isEmpty()) {
            return failed(flash, "Connect Linear and choose a team first.", "linear");
        }
        try {
            String key = linear.apiKey(tenantId);
            List<String> lines = new ArrayList<>();
            for (LinearTeam team : teams) {
                lines.add(team.name() + ": " + describeCount(linearClient.countIssues(key, team.id(), CHECK_CAP), "issue"));
            }
            flash.addFlashAttribute("notice", "docs0 can read " + String.join("; ", lines) + ".");
        } catch (SourceAccessException e) {
            return failed(flash, e.getMessage(), "linear");
        } catch (IOException | RuntimeException e) {
            log.warn("Linear access check failed: {}", e.getClass().getSimpleName());
            return failed(flash, "Could not reach Linear. Try again in a moment.", "linear");
        }
        return to("linear");
    }

    @PostMapping(LINEAR + "/sync")
    public RedirectView syncLinear(RedirectAttributes flash) {
        if (linear.credential(tenants.current().id()).isEmpty()) {
            return failed(flash, "Connect Linear first.", "linear");
        }
        if (!syncService.startInBackground(linearConnector)) {
            flash.addFlashAttribute("notice", "A Linear sync is already running.");
        }
        return to("linear");
    }

    @PostMapping(LINEAR + "/disconnect")
    public RedirectView disconnectLinear(RedirectAttributes flash) {
        long tenantId = tenants.current().id();
        Optional<Source> source = linear.source(tenantId);
        if (source.isPresent() && syncService.isRunning(source.get().id())) {
            return failed(flash, "A sync is running. Disconnect when it has finished.", "linear");
        }
        linear.disconnect(tenantId);
        panels.forget(tenantId);
        flash.addFlashAttribute("notice", "Linear disconnected. Its issues no longer appear in answers.");
        return to("linear");
    }

    // ---- Trello ---------------------------------------------------------------------------

    @PostMapping(TRELLO + "/connect")
    public RedirectView connectTrello(@RequestParam(name = "apiKey", required = false) String apiKey,
                                      @RequestParam(name = "token", required = false) String token,
                                      RedirectAttributes flash) {
        if (!trello.canStoreSecrets()) {
            return failed(flash, "Trello needs DOCS0_ENCRYPTION_KEY to be set first. See the README.", "trello");
        }
        String key = clean(apiKey);
        String secret = clean(token);
        if (key.isEmpty() || secret.isEmpty() || key.length() > MAX_SECRET_CHARS || secret.length() > MAX_SECRET_CHARS) {
            return failed(flash, "Paste both the Trello API key and the token.", "trello");
        }
        TrelloClient.Account account;
        try {
            account = trelloClient.me(key, secret);
        } catch (SourceAccessException e) {
            return failed(flash, "Trello did not accept that key and token. Check them and try again. "
                    + "Nothing was saved.", "trello");
        } catch (IOException | RuntimeException e) {
            log.warn("Trello could not be reached to test a key: {}", e.getClass().getSimpleName());
            return failed(flash, "Could not reach Trello to test the key. Try again in a moment. Nothing was saved.",
                    "trello");
        }
        long tenantId = tenants.current().id();
        String name = firstNonBlank(account.fullName(), account.username(), "a Trello account");
        if (account.username() != null && !account.username().isBlank() && !name.equals(account.username())) {
            name = name + " (@" + account.username() + ")";
        }
        trello.connect(tenantId, name, key, secret);
        panels.forget(tenantId);
        flash.addFlashAttribute("notice", "Connected as " + name + ". Now choose the boards to index.");
        return to("trello");
    }

    @PostMapping(TRELLO + "/boards")
    public RedirectView saveBoards(@RequestParam(name = "choice", required = false) List<String> choice,
                                   RedirectAttributes flash) {
        long tenantId = tenants.current().id();
        if (trello.credential(tenantId).isEmpty()) {
            return failed(flash, "Connect Trello first.", "trello");
        }
        List<TrelloBoard> chosen = new ArrayList<>();
        try {
            Set<String> wanted = choice == null ? Set.of() : Set.copyOf(choice);
            for (TrelloBoard board : panels.availableBoards(tenantId)) {
                if (wanted.contains(board.id())) {
                    chosen.add(board);
                }
            }
        } catch (IOException | RuntimeException e) {
            return failed(flash, "Could not ask Trello for its boards, so nothing was saved. Try again.", "trello");
        }
        trello.setBoards(tenantId, chosen);
        flash.addFlashAttribute("notice", chosen.isEmpty()
                ? "No boards chosen. Nothing from Trello is indexed."
                : "Saved. " + chosen.size() + (chosen.size() == 1 ? " board is" : " boards are")
                        + " indexed at the next sync.");
        return to("trello");
    }

    @PostMapping(TRELLO + "/check")
    public RedirectView checkTrello(RedirectAttributes flash) {
        long tenantId = tenants.current().id();
        List<TrelloBoard> boards = trello.boards(tenantId);
        if (trello.credential(tenantId).isEmpty() || boards.isEmpty()) {
            return failed(flash, "Connect Trello and choose a board first.", "trello");
        }
        try {
            TrelloConnection.Credentials credentials = trello.credentials(tenantId);
            List<String> lines = new ArrayList<>();
            for (TrelloBoard board : boards) {
                lines.add(board.name() + ": " + describeCount(
                        trelloClient.countCards(credentials.key(), credentials.token(), board.id(), CHECK_CAP), "card"));
            }
            flash.addFlashAttribute("notice", "docs0 can read " + String.join("; ", lines) + ".");
        } catch (SourceAccessException e) {
            return failed(flash, e.getMessage(), "trello");
        } catch (IOException | RuntimeException e) {
            log.warn("Trello access check failed: {}", e.getClass().getSimpleName());
            return failed(flash, "Could not reach Trello. Try again in a moment.", "trello");
        }
        return to("trello");
    }

    @PostMapping(TRELLO + "/sync")
    public RedirectView syncTrello(RedirectAttributes flash) {
        if (trello.credential(tenants.current().id()).isEmpty()) {
            return failed(flash, "Connect Trello first.", "trello");
        }
        if (!syncService.startInBackground(trelloConnector)) {
            flash.addFlashAttribute("notice", "A Trello sync is already running.");
        }
        return to("trello");
    }

    @PostMapping(TRELLO + "/disconnect")
    public RedirectView disconnectTrello(RedirectAttributes flash) {
        long tenantId = tenants.current().id();
        Optional<Source> source = trello.source(tenantId);
        if (source.isPresent() && syncService.isRunning(source.get().id())) {
            return failed(flash, "A sync is running. Disconnect when it has finished.", "trello");
        }
        trello.disconnect(tenantId);
        panels.forget(tenantId);
        flash.addFlashAttribute("notice", "Trello disconnected. Its cards no longer appear in answers.");
        return to("trello");
    }

    // ---- helpers --------------------------------------------------------------------------

    private static String describeCount(int count, String noun) {
        if (count >= CHECK_CAP) {
            return String.format("%,d+ %ss", CHECK_CAP, noun);
        }
        return count == 1 ? "1 " + noun : String.format("%,d %ss", count, noun);
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return "";
    }

    private static RedirectView failed(RedirectAttributes flash, String message, String anchor) {
        flash.addFlashAttribute("error", message);
        return to(anchor);
    }

    private static RedirectView to(String anchor) {
        RedirectView view = new RedirectView(SourcesController.PAGE + "#" + anchor, true);
        view.setExpandUriTemplateVariables(false);
        view.setExposeModelAttributes(false);
        return view;
    }
}
