package com.docs0.web.admin;

import com.docs0.knowledge.DocumentStore;
import com.docs0.source.SourceCredential;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.SyncService;
import com.docs0.source.gmail.GmailClient.DirectoryUser;
import com.docs0.source.gmail.GmailConnection;
import com.docs0.source.gmail.GmailConnector;
import com.docs0.source.gmail.GmailKey;
import com.docs0.source.gmail.Mailbox;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * What the Sources page shows for Gmail: whether a key is stored (only the service account's email
 * and client ID are shown, never the key), the mailboxes to include (chosen ones plus the people
 * loaded from the Workspace directory, remembered briefly), the sync log. Reads only the database.
 */
@Component
public class GmailPanel {

    public static final String ID = "gmail";

    /** One mailbox in the checklist. {@code viewers} is the comma-separated "also visible to" list. */
    public record Row(String email, String name, boolean chosen, String viewers, String note) {}

    /** The section. {@code state} is setup, disconnected or connected. */
    public record Panel(String state, String serviceAccount, String clientId, boolean confirmDisconnect,
                        boolean syncRunning, long documents, List<Row> rows, String adminEmail, int days,
                        List<SourcesController.RunRow> runs) {}

    private static final Duration KEEP = Duration.ofMinutes(10);
    private static final int RECENT_RUNS = 5;

    private record Cached(List<DirectoryUser> users, Instant at) {}

    private final GmailConnection connection;
    private final GmailConnector connector;
    private final SyncService syncService;
    private final SyncRunRepository runs;
    private final DocumentStore documents;
    private final Map<Long, Cached> directory = new ConcurrentHashMap<>();

    public GmailPanel(GmailConnection connection, GmailConnector connector, SyncService syncService,
                      SyncRunRepository runs, DocumentStore documents) {
        this.connection = connection;
        this.connector = connector;
        this.syncService = syncService;
        this.runs = runs;
        this.documents = documents;
    }

    public void addTo(Model model, long tenantId, String confirmDisconnect) {
        model.addAttribute("gmail", panel(tenantId, ID.equals(confirmDisconnect)));
    }

    /** Remembers the people the directory returned, so they can be ticked on the page. */
    void remember(long tenantId, List<DirectoryUser> users) {
        directory.put(tenantId, new Cached(List.copyOf(users), Instant.now()));
    }

    void forget(long tenantId) {
        directory.remove(tenantId);
    }

    private Panel panel(long tenantId, boolean confirm) {
        int historyDays = connector.historyDays();
        if (!connection.canStoreSecrets()) {
            return new Panel("setup", null, null, false, false, 0, List.of(), null, historyDays, List.of());
        }
        Optional<SourceCredential> credential = connection.credential(tenantId);
        if (credential.isEmpty()) {
            return new Panel("disconnected", null, null, false, false, 0, List.of(), null, historyDays, List.of());
        }
        long sourceId = credential.get().sourceId();
        boolean running = syncService.isRunning(sourceId);
        List<SourcesController.RunRow> rows = new ArrayList<>();
        for (SyncRun run : runs.recent(tenantId, sourceId, RECENT_RUNS)) {
            rows.add(SourcesController.runRow(run, running));
        }
        Optional<GmailKey> account = connection.account(tenantId);
        return new Panel("connected", account.map(GmailKey::email).orElse(credential.get().accountName()),
                account.map(GmailKey::clientId).orElse(null), confirm, running,
                documents.count(tenantId, sourceId), mailboxRows(tenantId), connection.adminEmail(tenantId).orElse(null),
                historyDays, rows);
    }

    private List<Row> mailboxRows(long tenantId) {
        Map<String, String> names = new HashMap<>();
        Cached cached = directory.get(tenantId);
        if (cached != null && cached.at().plus(KEEP).isAfter(Instant.now())) {
            cached.users().forEach(u -> names.put(u.email().toLowerCase(Locale.ROOT), u.name() == null ? "" : u.name()));
        }
        Map<String, String> notes = connector.notes();
        List<Row> rows = new ArrayList<>();
        java.util.Set<String> chosen = new java.util.HashSet<>();
        for (Mailbox mailbox : connection.mailboxes(tenantId)) {
            chosen.add(mailbox.email());
            rows.add(new Row(mailbox.email(), names.getOrDefault(mailbox.email(), ""), true,
                    String.join(", ", mailbox.viewers()), notes.get(mailbox.email())));
        }
        names.forEach((email, name) -> {
            if (!chosen.contains(email)) {
                rows.add(new Row(email, name, false, "", null));
            }
        });
        rows.sort(Comparator.comparing(Row::email));
        return rows;
    }
}
