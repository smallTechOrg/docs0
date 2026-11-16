package com.docs0.source.gmail;

import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.source.Source;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SourceConnector;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.gmail.GmailClient.KeyRejectedException;
import com.docs0.source.gmail.GmailClient.MailThread;
import com.docs0.source.gmail.GmailClient.ThreadPage;
import com.docs0.source.gmail.GmailClient.ThreadRef;
import com.docs0.tenant.TenantService;
import com.docs0.web.StatusContributor;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Indexes the mailboxes the admin chose: one document per thread from the last {@code docs0.gmail.days}
 * days, every one restricted to the mailbox owner (and any extra viewers set for a shared inbox). A
 * thread is fetched again only when its Gmail {@code historyId} changed. The principals of every
 * mailbox are refreshed on each run, so removing a viewer or a mailbox takes effect at the next sync.
 *
 * <p>Nothing here logs an address, a subject or text: ids and counts only.
 */
@Component
public class GmailConnector implements SourceConnector, StatusContributor {

    public static final String KIND = GmailConnection.KIND;
    public static final String NAME = GmailConnection.NAME;

    /** Leaves out spam, trash, drafts and the bulk-mail categories. */
    static final String EXCLUDE = "-in:spam -in:trash -in:draft -category:promotions -category:social -category:forums";
    private static final int MAX_PAGES = 500;
    private static final int MAX_STATUS_ERROR_CHARS = 120;

    private static final Logger log = LoggerFactory.getLogger(GmailConnector.class);

    private final GmailClient client;
    private final GmailConnection connection;
    private final DocumentStore documents;
    private final SyncRunRepository runs;
    private final TenantService tenants;
    private final int days;
    private final Clock clock;
    /** Mailbox -> why it was skipped in the last run, for the Sources page. */
    private volatile Map<String, String> notes = Map.of();

    @Autowired
    public GmailConnector(GmailClient client, GmailConnection connection, DocumentStore documents,
                          SyncRunRepository runs, TenantService tenants,
                          @Value("${docs0.gmail.days:90}") int days) {
        this(client, connection, documents, runs, tenants, days, Clock.systemUTC());
    }

    GmailConnector(GmailClient client, GmailConnection connection, DocumentStore documents, SyncRunRepository runs,
                   TenantService tenants, int days, Clock clock) {
        this.client = client;
        this.connection = connection;
        this.documents = documents;
        this.runs = runs;
        this.tenants = tenants;
        this.days = Math.max(1, days);
        this.clock = clock;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String visibility() {
        return GmailConnection.VISIBILITY;
    }

    /** How many days back mail is indexed. */
    public int historyDays() {
        return days;
    }

    /** The Gmail search that picks the threads to index. */
    public String query() {
        return "after:" + clock.instant().minus(Duration.ofDays(days)).getEpochSecond() + " " + EXCLUDE;
    }

    /** Why mailboxes were skipped in the last run, by mailbox address. */
    public Map<String, String> notes() {
        return notes;
    }

    /** True when a key is stored and mailboxes are chosen, or documents remain from an earlier choice. */
    @Override
    public boolean configured() {
        long tenantId = tenants.current().id();
        Optional<Source> source = connection.source(tenantId);
        if (source.isEmpty()) {
            return false;
        }
        if (connection.credential(tenantId).isPresent() && !connection.mailboxes(tenantId).isEmpty()) {
            return true;
        }
        return documents.count(tenantId, source.get().id()) > 0;
    }

    /** What one run has done so far. */
    private static final class Tally {
        int added;
        int updated;
        int removed;
        int failed;
    }

    @Override
    public SyncCounts sync(long tenantId, long sourceId) throws IOException {
        List<Mailbox> chosen = connection.mailboxes(tenantId);
        Map<String, String> versions = documents.versions(tenantId, sourceId);
        Set<String> stored = new LinkedHashSet<>(documents.modifiedTimes(tenantId, sourceId).keySet());
        Tally tally = new Tally();
        Map<String, String> newNotes = new LinkedHashMap<>();

        // Mailboxes that are no longer chosen: their mail goes at once, whatever Google says.
        Set<String> chosenEmails = new HashSet<>();
        chosen.forEach(m -> chosenEmails.add(m.email()));
        Set<String> leftovers = new LinkedHashSet<>();
        for (String id : stored) {
            if (!chosenEmails.contains(GmailRender.mailboxOf(id))) {
                leftovers.add(id);
            }
        }
        tally.removed += documents.markDeleted(tenantId, sourceId, leftovers);
        if (chosen.isEmpty()) {
            notes = Map.of();
            return new SyncCounts(0, 0, tally.removed, 0, 0);
        }

        // An unreadable key fails the run before any mailbox is touched.
        String key = connection.keyJson(tenantId);
        int index = 0;
        for (Mailbox mailbox : chosen) {
            index++;
            try {
                syncMailbox(tenantId, sourceId, key, mailbox, stored, versions, tally);
            } catch (KeyRejectedException e) {
                throw e; // concerns every mailbox: nothing more is changed
            } catch (SourceAccessException e) {
                newNotes.put(mailbox.email(), e.getMessage());
                tally.failed++;
                log.info("Gmail mailbox #{} skipped: access refused", index);
            } catch (IOException | RuntimeException e) {
                newNotes.put(mailbox.email(), "Gmail could not be read for " + mailbox.email()
                        + " this time. docs0 will try again.");
                tally.failed++;
                log.warn("Gmail mailbox #{} could not be synced: {}", index, e.getClass().getName());
            }
        }
        notes = Map.copyOf(newNotes);
        return new SyncCounts(tally.added, tally.updated, tally.removed, 0, tally.failed);
    }

    private void syncMailbox(long tenantId, long sourceId, String key, Mailbox mailbox, Set<String> stored,
                             Map<String, String> versions, Tally tally) throws IOException {
        String email = mailbox.email();
        Set<String> principals = new LinkedHashSet<>();
        principals.add(email);
        principals.addAll(mailbox.viewers());
        // Who may see this mailbox is settled first, so a removed viewer loses access even if Google fails below.
        documents.refreshAccess(tenantId, sourceId, GmailRender.prefix(email), true, principals);

        // The whole list is read before anything is written or deleted.
        List<ThreadRef> listed = new ArrayList<>();
        String pageToken = null;
        String query = query();
        for (int page = 0; page < MAX_PAGES; page++) {
            ThreadPage result = client.threads(key, email, query, pageToken);
            listed.addAll(result.threads());
            pageToken = result.nextPageToken();
            if (pageToken == null) {
                break;
            }
        }

        Set<String> present = new HashSet<>();
        for (ThreadRef ref : listed) {
            String externalId = GmailRender.externalId(email, ref.id());
            present.add(externalId);
            if (ref.historyId() != null && ref.historyId().equals(versions.get(externalId))) {
                continue; // unchanged since it was stored
            }
            try {
                MailThread thread = client.thread(key, email, ref.id());
                DocumentWrite doc = thread == null ? null : GmailRender.thread(email, thread);
                if (doc == null) {
                    present.remove(externalId); // gone, or nothing left that is not spam, trash or a draft
                    continue;
                }
                String version = ref.historyId() != null ? ref.historyId() : thread.historyId();
                documents.upsertRestricted(tenantId, sourceId, doc, principals, version);
                if (stored.contains(externalId)) {
                    tally.updated++;
                } else {
                    tally.added++;
                }
            } catch (SourceAccessException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                // The stored copy (if any) stays; the next sync tries again.
                tally.failed++;
                log.warn("Gmail thread could not be read: {}", e.getClass().getName());
            }
        }

        Set<String> gone = new LinkedHashSet<>();
        String prefix = GmailRender.prefix(email);
        for (String id : stored) {
            if (id.startsWith(prefix) && !present.contains(id)) {
                gone.add(id);
            }
        }
        tally.removed += documents.markDeleted(tenantId, sourceId, gone);
    }

    /** Reads only the database; never calls Google. */
    @Override
    public ComponentStatus status() {
        long tenantId = tenants.current().id();
        Optional<com.docs0.source.SourceCredential> credential = connection.credential(tenantId);
        if (credential.isEmpty()) {
            return ComponentStatus.off(NAME, "not connected — add a service account key on the Sources page");
        }
        long sourceId = credential.get().sourceId();
        int mailboxes = connection.mailboxes(tenantId).size();
        if (mailboxes == 0) {
            return ComponentStatus.off(NAME, "nothing chosen yet — add mailboxes on the Sources page");
        }
        Optional<SyncRun> last = runs.latest(tenantId, sourceId);
        if (last.isEmpty()) {
            return ComponentStatus.up(NAME, mailboxes + " mailboxes chosen, waiting for first sync");
        }
        SyncRun run = last.get();
        if (SyncRun.FAILED.equals(run.status())) {
            return ComponentStatus.down(NAME, "last sync failed: " + shorten(run.error()));
        }
        long count = documents.count(tenantId, sourceId);
        if (SyncRun.RUNNING.equals(run.status())) {
            return ComponentStatus.up(NAME, count + " threads, sync in progress");
        }
        String skipped = notes.isEmpty() ? "" : ", " + notes.size() + " mailboxes skipped";
        return ComponentStatus.up(NAME, count + " threads in " + mailboxes + " mailboxes, last sync "
                + run.finishedAt().truncatedTo(ChronoUnit.SECONDS) + skipped);
    }

    private static String shorten(String error) {
        if (error == null || error.isBlank()) {
            return "unknown error";
        }
        String oneLine = error.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_STATUS_ERROR_CHARS ? oneLine.substring(0, MAX_STATUS_ERROR_CHARS) + "..." : oneLine;
    }
}
