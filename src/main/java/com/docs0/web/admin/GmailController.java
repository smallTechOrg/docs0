package com.docs0.web.admin;

import com.docs0.source.Source;
import com.docs0.source.SourceAccessException;
import com.docs0.job.SyncNow;
import com.docs0.source.SyncService;
import com.docs0.source.gmail.GmailClient;
import com.docs0.source.gmail.GmailClient.DirectoryUser;
import com.docs0.source.gmail.GmailClient.KeyRejectedException;
import com.docs0.source.gmail.GmailClient.Profile;
import com.docs0.source.gmail.GmailConnection;
import com.docs0.source.gmail.GmailConnector;
import com.docs0.source.gmail.GmailKey;
import com.docs0.source.gmail.Mailbox;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

/**
 * The form posts of the Gmail section of the Sources page: paste the service account key, choose
 * mailboxes (typed or loaded from the Workspace directory), check access, sync now, disconnect. The
 * key is only ever read from the form, checked, encrypted and stored; it is never shown or logged.
 */
@Controller
public class GmailController {

    /** One line of the access check. */
    public record CheckRow(String email, boolean ok, String text) implements Serializable {}

    static final int MAX_MAILBOXES = 500;
    private static final Logger log = LoggerFactory.getLogger(GmailController.class);
    private static final String BASE = SourcesController.PAGE + "/gmail";
    private static final String VIEWERS = "viewers:";

    private final GmailConnection connection;
    private final GmailClient client;
    private final GmailConnector connector;
    private final GmailPanel panel;
    private final SyncService syncService;
    private final SyncNow syncNow;

    public GmailController(GmailConnection connection, GmailClient client,
                           GmailConnector connector, GmailPanel panel, SyncService syncService, SyncNow syncNow) {
        this.connection = connection;
        this.client = client;
        this.connector = connector;
        this.panel = panel;
        this.syncService = syncService;
        this.syncNow = syncNow;
    }

    @PostMapping(BASE + "/connect")
    public RedirectView connect(AdminPrincipal admin, @RequestParam(name = "key", required = false) String key, RedirectAttributes flash) {
        if (!connection.canStoreSecrets()) {
            return failed(flash, "Gmail needs DOCS0_ENCRYPTION_KEY to be set first. See the README.");
        }
        Optional<GmailKey> account = GmailKey.parse(key);
        if (account.isEmpty()) {
            return failed(flash, "That is not a Google service account key. Paste the whole JSON file you "
                    + "downloaded from Google Cloud. Nothing was saved.");
        }
        connection.connect(admin.tenantId(), account.get(), key.strip());
        flash.addFlashAttribute("notice", "Key saved for " + account.get().email() + ". Now authorise it in the "
                + "Workspace Admin console (steps below) and choose the mailboxes.");
        return to();
    }

    @PostMapping(BASE + "/mailboxes")
    public RedirectView save(AdminPrincipal admin, @RequestParam(name = "mailbox", required = false) List<String> ticked,
                             @RequestParam(name = "newMailboxes", required = false) String typed,
                             HttpServletRequest request, RedirectAttributes flash) {
        long tenantId = admin.tenantId();
        if (connection.credential(tenantId).isEmpty()) {
            return failed(flash, "Add the service account key first.");
        }
        Map<String, List<String>> viewersOf = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (entry.getKey().startsWith(VIEWERS) && entry.getValue().length > 0) {
                viewersOf.put(entry.getKey().substring(VIEWERS.length()).strip().toLowerCase(Locale.ROOT),
                        List.of(entry.getValue()[0]));
            }
        }
        Map<String, Mailbox> chosen = new LinkedHashMap<>();
        for (String email : ticked == null ? List.<String>of() : ticked) {
            if (!Mailbox.valid(email)) {
                return failed(flash, "One of the ticked mailboxes is not an email address on a domain. "
                        + "Nothing was saved.");
            }
            String address = email.strip().toLowerCase(Locale.ROOT);
            List<String> viewers = new ArrayList<>();
            for (String raw : viewersOf.getOrDefault(address, List.of())) {
                for (String v : raw.split("[,;\\s]+")) {
                    if (v.isBlank()) {
                        continue;
                    }
                    if (!Mailbox.valid(v)) {
                        return failed(flash, "The \"also visible to\" list of " + address
                                + " has something that is not an email address on a domain. Nothing was saved.");
                    }
                    viewers.add(v);
                }
            }
            chosen.put(address, new Mailbox(address, viewers));
        }
        int line = 0;
        for (String raw : typed == null ? new String[0] : typed.split("\\R")) {
            line++;
            if (raw.isBlank()) {
                continue;
            }
            if (!Mailbox.valid(raw)) {
                return failed(flash, "Line " + line + " is not an email address on a domain, like "
                        + "sam@example.com. Nothing was saved.");
            }
            String address = raw.strip().toLowerCase(Locale.ROOT);
            chosen.putIfAbsent(address, new Mailbox(address, List.of()));
        }
        if (chosen.size() > MAX_MAILBOXES) {
            return failed(flash, "That is more than " + MAX_MAILBOXES + " mailboxes. Nothing was saved.");
        }
        connection.setMailboxes(tenantId, List.copyOf(chosen.values()));
        flash.addFlashAttribute("notice", chosen.isEmpty()
                ? "No mailboxes chosen. Nothing from Gmail is indexed."
                : "Saved. " + chosen.size() + (chosen.size() == 1 ? " mailbox is" : " mailboxes are")
                        + " indexed at the next sync.");
        return to();
    }

    @PostMapping(BASE + "/directory")
    public RedirectView loadDirectory(AdminPrincipal admin, @RequestParam(name = "adminEmail", required = false) String adminEmail,
                                      RedirectAttributes flash) {
        long tenantId = admin.tenantId();
        if (connection.credential(tenantId).isEmpty()) {
            return failed(flash, "Add the service account key first.");
        }
        if (!Mailbox.valid(adminEmail)) {
            return failed(flash, "Type the email of a Google Workspace administrator first.");
        }
        String workspaceAdmin = adminEmail.strip().toLowerCase(Locale.ROOT);
        try {
            List<DirectoryUser> users = client.directory(connection.keyJson(tenantId), workspaceAdmin);
            connection.setAdminEmail(tenantId, workspaceAdmin);
            panel.remember(tenantId, users);
            flash.addFlashAttribute("notice", users.isEmpty()
                    ? "Google returned no people."
                    : "Loaded " + users.size() + (users.size() == 1 ? " person" : " people")
                            + " from Google Workspace. Tick the mailboxes to include and press Save.");
        } catch (SourceAccessException e) {
            return failed(flash, e.getMessage());
        } catch (IOException | RuntimeException e) {
            log.warn("Gmail directory could not be read: {}", e.getClass().getSimpleName());
            return failed(flash, "Could not reach Google. Try again in a moment.");
        }
        return to();
    }

    @PostMapping(BASE + "/check")
    public RedirectView check(AdminPrincipal admin, RedirectAttributes flash) {
        long tenantId = admin.tenantId();
        List<Mailbox> mailboxes = connection.mailboxes(tenantId);
        if (connection.credential(tenantId).isEmpty() || mailboxes.isEmpty()) {
            return failed(flash, "Add the key and choose at least one mailbox first.");
        }
        List<CheckRow> rows = new ArrayList<>();
        try {
            String key = connection.keyJson(tenantId);
            for (Mailbox mailbox : mailboxes) {
                try {
                    Profile profile = client.profile(key, mailbox.email());
                    rows.add(new CheckRow(mailbox.email(), true, count(profile.messagesTotal())));
                } catch (KeyRejectedException e) {
                    throw e;
                } catch (SourceAccessException e) {
                    rows.add(new CheckRow(mailbox.email(), false, e.getMessage()));
                } catch (IOException | RuntimeException e) {
                    log.warn("Gmail access check failed for one mailbox: {}", e.getClass().getSimpleName());
                    rows.add(new CheckRow(mailbox.email(), false, "Could not reach Google. Try again in a moment."));
                }
            }
        } catch (SourceAccessException e) {
            return failed(flash, e.getMessage());
        }
        flash.addFlashAttribute("gmailCheck", rows);
        return to();
    }

    @PostMapping(BASE + "/sync")
    public RedirectView sync(AdminPrincipal admin, RedirectAttributes flash) {
        if (connection.credential(admin.tenantId()).isEmpty()) {
            return failed(flash, "Add the service account key first.");
        }
        syncNow.request(admin.tenantId(), connector).flash(flash, "A Gmail sync is already running.");
        return to();
    }

    @PostMapping(BASE + "/disconnect")
    public RedirectView disconnect(AdminPrincipal admin, RedirectAttributes flash) {
        long tenantId = admin.tenantId();
        Optional<Source> source = connection.source(tenantId);
        if (source.isPresent() && syncService.isRunning(admin.tenantId(), source.get().id())) {
            return failed(flash, "A sync is running. Disconnect when it has finished.");
        }
        connection.disconnect(tenantId);
        panel.forget(tenantId);
        flash.addFlashAttribute("notice", "Gmail disconnected. The key is deleted and mail no longer appears in answers.");
        return to();
    }

    private static String count(long messages) {
        return messages == 1 ? "1 message" : String.format("%,d messages", messages);
    }

    private static RedirectView failed(RedirectAttributes flash, String message) {
        flash.addFlashAttribute("error", message);
        return to();
    }

    private static RedirectView to() {
        RedirectView view = new RedirectView(SourcesController.PAGE + "#gmail", true);
        view.setExpandUriTemplateVariables(false);
        view.setExposeModelAttributes(false);
        return view;
    }
}
