package com.docs0.web.admin;

import com.docs0.knowledge.DocumentStore;
import com.docs0.source.Folder;
import com.docs0.source.Source;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SourceCredential;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.SyncService;
import com.docs0.source.drive.DriveClient;
import com.docs0.source.drive.DriveConnection;
import com.docs0.source.drive.DriveConnector;
import com.docs0.source.drive.GoogleOAuth;
import com.docs0.config.Docs0Properties;
import com.docs0.tenant.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

/**
 * The Sources page: connect Google Drive by signing in, choose the folders to index, sync
 * now, disconnect. Server-rendered; every change is a form POST followed by a redirect.
 */
@Controller
public class SourcesController {

    static final String PAGE = "/admin/sources";

    private static final Logger log = LoggerFactory.getLogger(SourcesController.class);
    private static final String STATE_SESSION_ATTRIBUTE = "docs0.googleOAuthState";
    private static final String TRAIL_PARAMETER = "p";
    private static final Pattern DRIVE_ID = Pattern.compile("[A-Za-z0-9_-]{1,200}");
    private static final int MAX_NAME_CHARS = 300;
    private static final int RECENT_RUNS = 5;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    /** A chosen folder and what docs0 can see in it; {@code files} is null when Drive could not be asked. */
    public record SelectedFolder(Folder folder, String files) {}

    /** A folder in the browser. */
    public record BrowseFolder(Folder folder, String openUrl, boolean selected) {}

    /** One card of the browser: a top-level section, or the open folder. */
    public record Section(String id, String title, String emptyText, List<BrowseFolder> folders) {}

    /** One step of the path from the top of the browser to the open folder. */
    public record Crumb(String name, String url) {}

    /** One line of the sync log. */
    public record RunRow(String started, String state, String label, SyncRun run) {}

    private final Docs0Properties props;
    private final TenantService tenants;
    private final DriveConnection connection;
    private final DriveClient drive;
    private final DriveConnector connector;
    private final GoogleOAuth oauth;
    private final FolderCounts counts;
    private final SyncService syncService;
    private final SyncRunRepository runs;
    private final DocumentStore documents;
    private final ToolPanels toolPanels;

    public SourcesController(Docs0Properties props, TenantService tenants, DriveConnection connection,
                             DriveClient drive, DriveConnector connector, GoogleOAuth oauth, FolderCounts counts,
                             SyncService syncService, SyncRunRepository runs, DocumentStore documents,
                             ToolPanels toolPanels) {
        this.props = props;
        this.tenants = tenants;
        this.connection = connection;
        this.drive = drive;
        this.connector = connector;
        this.oauth = oauth;
        this.counts = counts;
        this.syncService = syncService;
        this.runs = runs;
        this.documents = documents;
        this.toolPanels = toolPanels;
    }

    @GetMapping(PAGE)
    public String page(HttpServletRequest request,
                       @RequestParam(name = "disconnect", required = false) String disconnect,
                       @RequestParam(name = "confirm", required = false) String confirm, Model model) {
        long tenantId = tenants.current().id();
        toolPanels.addTo(model, tenantId, confirm);
        model.addAttribute("csrf", AdminGuard.csrfToken(request.getSession()));
        model.addAttribute("tenantName", tenants.current().name());
        model.addAttribute("redirectUri", props.googleRedirectUri());

        List<String> missing = connection.missingSettings();
        Optional<SourceCredential> credential = connection.credential(tenantId);
        if (!missing.isEmpty()) {
            model.addAttribute("state", "setup");
            model.addAttribute("missing", missing);
            return "sources";
        }
        if (credential.isEmpty()) {
            model.addAttribute("state", "disconnected");
            return "sources";
        }

        model.addAttribute("state", "connected");
        model.addAttribute("accountEmail", credential.get().accountEmail());
        model.addAttribute("confirmDisconnect", disconnect != null);

        long sourceId = credential.get().sourceId();
        boolean running = syncService.isRunning(sourceId);
        model.addAttribute("syncRunning", running);
        model.addAttribute("documents", documents.count(tenantId, sourceId));
        model.addAttribute("runs", runs.recent(tenantId, sourceId, RECENT_RUNS).stream()
                .map(run -> runRow(run, running)).toList());

        List<Folder> trail = trail(request);
        model.addAttribute("here", url(trail));
        model.addAttribute("atTop", trail.isEmpty());
        model.addAttribute("crumbs", crumbs(trail));

        List<Folder> chosen = connection.folders(tenantId);
        Set<String> chosenIds = chosen.stream().map(Folder::id).collect(Collectors.toSet());
        try {
            List<SelectedFolder> selected = new ArrayList<>();
            for (Folder folder : chosen) {
                selected.add(new SelectedFolder(folder, counts.describe(tenantId, folder.id())));
            }
            model.addAttribute("selected", selected);
            List<Section> sections = new ArrayList<>();
            if (trail.isEmpty()) {
                sections.add(new Section("browse-my-drive", "My Drive", "No folders here.",
                        browse(drive.childFolders(tenantId, DriveClient.MY_DRIVE), trail, chosenIds)));
                sections.add(new Section("browse-shared-with-me", "Shared with me",
                        "No folders have been shared with this account.",
                        browse(drive.sharedWithMe(tenantId), trail, chosenIds)));
                sections.add(new Section("browse-shared-drives", "Shared drives",
                        "This account is not in any shared drive.",
                        browse(drive.sharedDrives(tenantId), trail, chosenIds)));
            } else {
                Folder open = trail.get(trail.size() - 1);
                sections.add(new Section("browse-folder", open.name(), "No folders inside this one.",
                        browse(drive.childFolders(tenantId, open.id()), trail, chosenIds)));
            }
            model.addAttribute("sections", sections);
        } catch (IOException | RuntimeException e) {
            // The page still shows the account, the selection, the sync log and Disconnect.
            model.addAttribute("selected", chosen.stream().map(f -> new SelectedFolder(f, null)).toList());
            model.addAttribute("driveError", driveError(e));
        }
        return "sources";
    }

    @GetMapping(PAGE + "/google/connect")
    public RedirectView connect(HttpServletRequest request) {
        if (!connection.setupComplete()) {
            return to(PAGE);
        }
        String state = AdminGuard.randomToken();
        request.getSession().setAttribute(STATE_SESSION_ATTRIBUTE, state);
        RedirectView google = new RedirectView(oauth.authorizationUrl(state));
        google.setExpandUriTemplateVariables(false);
        google.setExposeModelAttributes(false);
        return google;
    }

    @GetMapping(PAGE + "/google/callback")
    public RedirectView callback(HttpServletRequest request,
                                 @RequestParam(name = "state", required = false) String state,
                                 @RequestParam(name = "code", required = false) String code,
                                 @RequestParam(name = "error", required = false) String error,
                                 RedirectAttributes flash) {
        // The state is good for one callback only, whatever the outcome.
        HttpSession session = request.getSession(false);
        Object expected = session == null ? null : session.getAttribute(STATE_SESSION_ATTRIBUTE);
        if (session != null) {
            session.removeAttribute(STATE_SESSION_ATTRIBUTE);
        }
        if (!(expected instanceof String expectedState) || !AdminGuard.sameValue(expectedState, state)) {
            return failed(flash, "That sign-in link has expired or was already used. "
                    + "Click Connect Google Drive to start again.");
        }
        if (!connection.setupComplete()) {
            return to(PAGE);
        }
        if (error != null || code == null || code.isBlank()) {
            return failed(flash, "Google sign-in was cancelled. Nothing was connected.");
        }
        GoogleOAuth.Grant grant;
        try {
            grant = oauth.exchange(code);
        } catch (IOException | RuntimeException e) {
            log.warn("Google sign-in could not be completed: {}", e.getMessage());
            return failed(flash, "Google sign-in did not complete. Try again.");
        }
        if (!grant.canReadDrive()) {
            return failed(flash, "docs0 was not given permission to read Google Drive. "
                    + "Try again and leave the Google Drive box ticked.");
        }
        if (grant.refreshToken() == null || grant.refreshToken().isBlank()) {
            return failed(flash, "Google did not give docs0 lasting access. "
                    + "Click Connect Google Drive to try again.");
        }
        long tenantId = tenants.current().id();
        String email = grant.accountEmail() == null || grant.accountEmail().isBlank()
                ? "a Google account" : grant.accountEmail();
        connection.connect(tenantId, email, grant.refreshToken());
        counts.forget(tenantId);
        flash.addFlashAttribute("notice", "Connected as " + email + ". Now choose the folders to index.");
        return to(PAGE);
    }

    @PostMapping(PAGE + "/folders/add")
    public RedirectView addFolder(@RequestParam("id") String id, @RequestParam("name") String name,
                                  @RequestParam(name = "back", required = false) String back,
                                  RedirectAttributes flash) {
        long tenantId = tenants.current().id();
        String cleanName = name.strip();
        if (connection.credential(tenantId).isEmpty() || !DRIVE_ID.matcher(id).matches() || cleanName.isEmpty()) {
            return failed(flash, "That folder could not be added.");
        }
        if (cleanName.length() > MAX_NAME_CHARS) {
            cleanName = cleanName.substring(0, MAX_NAME_CHARS);
        }
        connection.addFolder(tenantId, new Folder(id, cleanName));
        flash.addFlashAttribute("notice", "Added \"" + cleanName + "\". It is indexed at the next sync.");
        return to(safeBack(back));
    }

    @PostMapping(PAGE + "/folders/remove")
    public RedirectView removeFolder(@RequestParam("id") String id,
                                     @RequestParam(name = "back", required = false) String back,
                                     RedirectAttributes flash) {
        connection.removeFolder(tenants.current().id(), id);
        flash.addFlashAttribute("notice", "Folder removed. Its documents drop out of answers at the next sync.");
        return to(safeBack(back));
    }

    @PostMapping(PAGE + "/sync")
    public RedirectView syncNow(@RequestParam(name = "back", required = false) String back,
                                RedirectAttributes flash) {
        long tenantId = tenants.current().id();
        if (connection.credential(tenantId).isEmpty()) {
            return failed(flash, "Connect Google Drive first.");
        }
        counts.forget(tenantId);
        if (!syncService.startInBackground(connector)) {
            flash.addFlashAttribute("notice", "A sync is already running.");
        }
        return to(safeBack(back));
    }

    @PostMapping(PAGE + "/disconnect")
    public RedirectView disconnect(RedirectAttributes flash) {
        long tenantId = tenants.current().id();
        Optional<Source> source = connection.source(tenantId);
        if (source.isPresent() && syncService.isRunning(source.get().id())) {
            return failed(flash, "A sync is running. Disconnect when it has finished.");
        }
        connection.disconnect(tenantId);
        counts.forget(tenantId);
        flash.addFlashAttribute("notice", "Google Drive disconnected. Its documents no longer appear in answers.");
        return to(PAGE);
    }

    static RunRow runRow(SyncRun run, boolean running) {
        String state;
        String label;
        if (SyncRun.OK.equals(run.status())) {
            state = "UP";
            label = "Complete";
        } else if (SyncRun.FAILED.equals(run.status())) {
            state = "DOWN";
            label = "Failed";
        } else if (running) {
            state = "OFF";
            label = "Running";
        } else {
            // Left as RUNNING by an app that stopped mid-sync.
            state = "DOWN";
            label = "Interrupted";
        }
        return new RunRow(TIME.format(run.startedAt()), state, label, run);
    }

    private static String driveError(Exception e) {
        if (e instanceof SourceAccessException) {
            return e.getMessage();
        }
        log.warn("Could not read Google Drive for the Sources page: {}", e.toString());
        return "Google Drive could not be reached. Try again in a moment.";
    }

    /**
     * The open folder and the folders above it, top first, as carried in the page URL:
     * one {@code p=<id>|<name>} parameter per step. Anything malformed means the top.
     */
    private static List<Folder> trail(HttpServletRequest request) {
        String[] steps = request.getParameterValues(TRAIL_PARAMETER);
        List<Folder> trail = new ArrayList<>();
        if (steps == null) {
            return trail;
        }
        for (String step : steps) {
            int bar = step.indexOf('|');
            if (bar <= 0 || !DRIVE_ID.matcher(step.substring(0, bar)).matches()) {
                return List.of();
            }
            trail.add(new Folder(step.substring(0, bar), step.substring(bar + 1)));
        }
        return trail;
    }

    private static String url(List<Folder> trail) {
        if (trail.isEmpty()) {
            return PAGE;
        }
        return PAGE + "?" + trail.stream()
                .map(f -> TRAIL_PARAMETER + "=" + URLEncoder.encode(f.id() + "|" + f.name(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static List<Crumb> crumbs(List<Folder> trail) {
        List<Crumb> crumbs = new ArrayList<>();
        crumbs.add(new Crumb("All folders", PAGE));
        for (int i = 0; i < trail.size(); i++) {
            crumbs.add(new Crumb(trail.get(i).name(), url(trail.subList(0, i + 1))));
        }
        return crumbs;
    }

    private static List<BrowseFolder> browse(List<Folder> folders, List<Folder> trail, Set<String> chosenIds) {
        List<BrowseFolder> result = new ArrayList<>();
        for (Folder folder : folders) {
            List<Folder> deeper = new ArrayList<>(trail);
            deeper.add(folder);
            result.add(new BrowseFolder(folder, url(deeper), chosenIds.contains(folder.id())));
        }
        return result;
    }

    /** Where a form sends the admin back to: only ever this page, at the folder they were looking at. */
    private static String safeBack(String back) {
        boolean ours = back != null && (back.equals(PAGE) || back.startsWith(PAGE + "?"))
                && back.chars().noneMatch(c -> c < 0x20 || c == '{' || c == '}');
        return ours ? back : PAGE;
    }

    private static RedirectView failed(RedirectAttributes flash, String message) {
        flash.addFlashAttribute("error", message);
        return to(PAGE);
    }

    private static RedirectView to(String path) {
        RedirectView view = new RedirectView(path, true);
        view.setExpandUriTemplateVariables(false);
        view.setExposeModelAttributes(false);
        return view;
    }
}
