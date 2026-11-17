package com.docs0.source.slack;

import com.docs0.config.Docs0Properties;
import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.knowledge.DocumentStore.Stored;
import com.docs0.slack.IdentityService;
import com.docs0.slack.SlackMessenger;
import com.docs0.slack.SlackMessengers;
import com.docs0.slack.SlackMessenger.ChannelInfo;
import com.docs0.slack.SlackMessenger.SlackCallException;
import com.docs0.slack.SlackMessenger.SlackMessage;
import com.docs0.source.Source;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SourceConnector;
import com.docs0.source.StatusText;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.slack.SlackHistoryRender.Rendered;
import com.docs0.web.StatusContributor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Indexes the Slack channels the admin chose: one document per thread and one per channel per UTC
 * day for messages outside threads. Public channels are shared knowledge. A private channel's
 * documents are restricted to the channel's current members, refreshed on every run. Direct and
 * group messages are never read: only channels picked on the Sources page are ever asked for.
 *
 * <p>The first run of each UTC day reads the whole window ({@code docs0.slack-history.days}); the
 * runs in between read from the last clean run minus a day, which keeps the load on Slack small.
 */
@Component
public class SlackHistoryConnector implements SourceConnector, StatusContributor {

    public static final String KIND = SlackHistoryConnection.KIND;
    public static final String NAME = SlackHistoryConnection.NAME;

    private static final Logger log = LoggerFactory.getLogger(SlackHistoryConnector.class);
    private static final Duration NAME_BUDGET = Duration.ofSeconds(30);

    private final SlackMessengers messengers;
    private final IdentityService identities;
    private final SlackHistoryConnection connection;
    private final DocumentStore documents;
    private final SyncRunRepository runs;
    private final int days;
    private final Clock clock;
    /** Channel id -> why it was skipped in the last run, for the Sources page. */
    private final Map<Long, Map<String, String>> notes = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public SlackHistoryConnector(SlackMessengers messengers, IdentityService identities,
                                 SlackHistoryConnection connection, DocumentStore documents, SyncRunRepository runs,
                                 @Value("${docs0.slack-history.days:180}") int days) {
        this(messengers, identities, connection, documents, runs, days, Clock.systemUTC());
    }

    SlackHistoryConnector(SlackMessengers messengers, IdentityService identities, SlackHistoryConnection connection,
                          DocumentStore documents, SyncRunRepository runs, int days, Clock clock) {
        this.messengers = messengers;
        this.identities = identities;
        this.connection = connection;
        this.documents = documents;
        this.runs = runs;
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
        return SlackHistoryConnection.VISIBILITY;
    }

    /** True when the bot token is set and channels are chosen, or documents remain from an earlier choice. */
    @Override
    public boolean configured(long tenantId) {
        if (!messengers.connected(tenantId)) {
            return false;
        }
        Optional<Source> source = connection.source(tenantId);
        if (source.isEmpty()) {
            return false;
        }
        return !connection.channels(tenantId).isEmpty() || documents.count(tenantId, source.get().id()) > 0;
    }

    /** Why this tenant's channels were skipped in its last run, by channel id (for example "invite @docs0 to #name"). */
    public Map<String, String> notes(long tenantId) {
        return notes.getOrDefault(tenantId, Map.of());
    }

    /** What one run has done so far. */
    private static final class Tally {
        int added;
        int updated;
        int removed;
        int skipped;
        int failed;
    }

    @Override
    public SyncCounts sync(long tenantId, long sourceId) throws SourceAccessException {
        if (!messengers.connected(tenantId)) {
            throw new SourceAccessException("Slack is not connected for this company. Set DOCS0_SLACK_BOT_TOKEN and restart docs0.");
        }
        SlackMessenger messenger = messengers.forTenant(tenantId);
        Instant now = clock.instant();
        nameCache.keySet().removeIf(key -> key.tenantId() == tenantId);
        List<SlackChannel> selected = connection.channels(tenantId);
        Map<String, Stored> stored = documents.stored(tenantId, sourceId);
        Tally tally = new Tally();
        Map<String, String> newNotes = new LinkedHashMap<>();

        // Asked before anything is changed: if Slack cannot answer, the run fails and nothing is deleted.
        Map<String, ChannelInfo> visible = new HashMap<>();
        String ownBot = null;
        if (!selected.isEmpty()) {
            for (ChannelInfo info : messenger.listChannels()) {
                visible.put(info.id(), info);
            }
            ownBot = messenger.botUserId();
        }

        Instant retentionStart = startOfDay(now.minus(days, ChronoUnit.DAYS));
        Optional<Instant> lastClean = runs.lastCleanStart(tenantId, sourceId);
        boolean fullPass = lastClean.isEmpty() || utcDay(lastClean.get()).isBefore(utcDay(now));
        Instant incrementalStart = lastClean.map(t -> startOfDay(t.minus(1, ChronoUnit.DAYS))).orElse(retentionStart);

        Set<String> chosenIds = new HashSet<>();
        for (SlackChannel channel : selected) {
            chosenIds.add(channel.id());
            ChannelInfo info = visible.get(channel.id());
            try {
                if (info == null) {
                    // Not listed: a private channel the bot is not in, or a channel that is gone or archived.
                    String why = channel.isPrivate() ? "invite @docs0 to #" + channel.name()
                            : "#" + channel.name() + " was not found or is archived";
                    newNotes.put(channel.id(), why);
                    tally.skipped++;
                    log.info("Slack channel {} skipped: {}", channel.id(), channel.isPrivate() ? "bot not invited" : "not found");
                    tally.removed += removeChannel(tenantId, sourceId, stored, channel.id());
                    continue;
                }
                if (!info.member() && !join(messenger, info, newNotes, tally)) {
                    continue;
                }
                boolean channelHasDocs = stored.keySet().stream().anyMatch(id -> channelOf(id).equals(channel.id()));
                Instant from = fullPass || !channelHasDocs ? retentionStart
                        : max(incrementalStart, retentionStart);
                syncChannel(messenger, tenantId, sourceId, info, ownBot, from, retentionStart, stored, tally);
            } catch (RuntimeException e) {
                tally.failed++;
                log.warn("Slack channel {} could not be synced: {}", channel.id(), describe(e));
            }
        }

        // Channels no longer chosen: their documents go.
        Set<String> leftovers = new LinkedHashSet<>();
        for (String id : stored.keySet()) {
            if (!chosenIds.contains(channelOf(id))) {
                leftovers.add(channelOf(id));
            }
        }
        for (String channelId : leftovers) {
            tally.removed += removeChannel(tenantId, sourceId, stored, channelId);
        }
        notes.put(tenantId, Map.copyOf(newNotes));
        return new SyncCounts(tally.added, tally.updated, tally.removed, tally.skipped, tally.failed);
    }

    /** Joins a public channel the bot is not in. False (and the channel is skipped) when that is not possible. */
    private boolean join(SlackMessenger messenger, ChannelInfo info, Map<String, String> newNotes, Tally tally) {
        if (info.isPrivate()) {
            newNotes.put(info.id(), "invite @docs0 to #" + info.name());
            tally.skipped++;
            return false;
        }
        try {
            messenger.joinChannel(info.id());
            return true;
        } catch (SlackCallException e) {
            newNotes.put(info.id(), "docs0 could not join #" + info.name() + " (" + e.code() + ")");
            tally.skipped++;
            log.warn("Slack channel {} could not be joined: {}", info.id(), e.code());
            return false;
        }
    }

    private void syncChannel(SlackMessenger messenger, long tenantId, long sourceId, ChannelInfo info, String ownBot, Instant from,
                             Instant retentionStart, Map<String, Stored> stored, Tally tally) {
        String channelId = info.id();
        boolean restricted = info.isPrivate();

        // Who may see the documents is settled first, from the channel's current members.
        Set<String> principals = Set.of();
        if (restricted) {
            try {
                principals = principals(tenantId, messenger.channelMembers(channelId));
            } catch (RuntimeException e) {
                // Membership unknown: hide the channel's documents from everyone until it is known again.
                hide(tenantId, sourceId, channelId);
                throw e;
            }
        }
        documents.refreshAccess(tenantId, sourceId, "thread:" + channelId + ":", restricted, principals);
        documents.refreshAccess(tenantId, sourceId, "day:" + channelId + ":", restricted, principals);

        List<SlackMessage> messages = new ArrayList<>(messenger.history(channelId, from));
        messages.sort(Comparator.comparing(m -> SlackHistoryRender.instant(m.ts())));
        final Function<String, String> names = names(tenantId, messages);

        Map<String, Rendered> wanted = new LinkedHashMap<>();
        Set<String> keep = new HashSet<>();
        Map<LocalDate, List<SlackMessage>> byDay = new TreeMap<>();
        List<SlackMessage> roots = new ArrayList<>();
        for (SlackMessage m : messages) {
            if (m.threadTs() != null && !m.threadTs().equals(m.ts())) {
                continue; // a reply also sent to the channel: it is part of its thread
            }
            if (m.replyCount() > 0) {
                roots.add(m);
            } else {
                byDay.computeIfAbsent(utcDay(SlackHistoryRender.instant(m.ts())), d -> new ArrayList<>()).add(m);
            }
        }
        byDay.forEach((day, dayMessages) -> {
            Rendered rendered = SlackHistoryRender.day(channelId, info.name(), day, dayMessages, ownBot, names);
            if (rendered != null) {
                wanted.put(rendered.doc().externalId(), rendered);
            }
        });
        for (SlackMessage root : roots) {
            String externalId = SlackHistoryRender.threadId(channelId, root.ts());
            Stored old = stored.get(externalId);
            if (old != null && old.modifiedAt() != null && !SlackHistoryRender.activity(root).isAfter(old.modifiedAt())) {
                keep.add(externalId); // nothing new in this thread
                continue;
            }
            try {
                List<SlackMessage> thread = messenger.replies(channelId, root.ts());
                Rendered rendered = SlackHistoryRender.thread(channelId, info.name(), thread, ownBot,
                        names(tenantId, thread));
                if (rendered != null) {
                    wanted.put(externalId, rendered);
                }
            } catch (SlackCallException e) {
                if ("thread_not_found".equals(e.code())) {
                    continue;
                }
                keep.add(externalId);
                tally.failed++;
                log.warn("Slack thread {} in {} could not be read: {}", root.ts(), channelId, e.code());
            }
        }

        for (Rendered rendered : wanted.values()) {
            write(messenger, tenantId, sourceId, info, rendered, restricted, principals, stored, tally);
        }

        // A document in the part of the window that was read, or past the retention limit, that was not
        // rebuilt belongs to messages that are gone.
        Set<String> gone = new LinkedHashSet<>();
        for (String id : stored.keySet()) {
            if (channelOf(id).equals(channelId) && !wanted.containsKey(id) && !keep.contains(id)
                    && (startsBefore(id, retentionStart) || !startsBefore(id, from))) {
                gone.add(id);
            }
        }
        tally.removed += documents.markDeleted(tenantId, sourceId, gone);
    }

    private void write(SlackMessenger messenger, long tenantId, long sourceId, ChannelInfo info, Rendered rendered, boolean restricted,
                       Set<String> principals, Map<String, Stored> stored, Tally tally) {
        DocumentWrite doc = rendered.doc();
        Stored old = stored.get(doc.externalId());
        if (old != null && DocumentStore.contentHash(doc.body()).equals(old.contentHash())) {
            return;
        }
        try {
            String url = old != null && old.url() != null ? old.url() : permalink(messenger, info.id(), rendered.firstTs());
            DocumentWrite withUrl = new DocumentWrite(doc.externalId(), doc.title(), doc.mimeType(), url,
                    doc.ownerName(), doc.ownerEmail(), doc.modifiedAt(), doc.body());
            if (restricted) {
                documents.upsertRestricted(tenantId, sourceId, withUrl, principals);
            } else {
                documents.upsert(tenantId, sourceId, withUrl);
            }
            if (old == null) {
                tally.added++;
            } else {
                tally.updated++;
            }
        } catch (RuntimeException e) {
            tally.failed++;
            log.warn("Slack document {} could not be stored: {}", doc.externalId(), e.getClass().getName());
        }
    }

    /** A link to the first message, or to the channel when Slack will not give one. */
    private String permalink(SlackMessenger messenger, String channelId, String ts) {
        try {
            String link = messenger.permalink(channelId, ts);
            if (link != null && !link.isBlank()) {
                return link;
            }
        } catch (RuntimeException e) {
            log.debug("Permalink failed: {}", describe(e));
        }
        return "https://slack.com/app_redirect?channel=" + channelId;
    }

    /** The Slack ids of the members and their emails where known, lowercase. */
    private Set<String> principals(long tenantId, Set<String> members) {
        Set<String> principals = new LinkedHashSet<>();
        for (String id : members) {
            principals.add(id.toLowerCase(java.util.Locale.ROOT));
        }
        identities.resolve(tenantId, members, NAME_BUDGET).values().forEach(person -> {
            if (person.email() != null && !person.email().isBlank()) {
                principals.add(person.email().toLowerCase(java.util.Locale.ROOT));
            }
        });
        return principals;
    }

    private void hide(long tenantId, long sourceId, String channelId) {
        try {
            documents.refreshAccess(tenantId, sourceId, "thread:" + channelId + ":", true, Set.of());
            documents.refreshAccess(tenantId, sourceId, "day:" + channelId + ":", true, Set.of());
        } catch (RuntimeException e) {
            log.warn("Slack channel {} documents could not be hidden: {}", channelId, e.getClass().getName());
        }
    }

    private int removeChannel(long tenantId, long sourceId, Map<String, Stored> stored, String channelId) {
        List<String> ids = stored.keySet().stream().filter(id -> channelOf(id).equals(channelId)).toList();
        return documents.markDeleted(tenantId, sourceId, ids);
    }

    /** Names for the authors of these messages, looked up once and remembered for the run. */
    private record NameKey(long tenantId, String user) {}

    /** Slack user ids are only unique within a workspace, so the tenant is part of the key. */
    private final Map<NameKey, Optional<String>> nameCache = new java.util.concurrent.ConcurrentHashMap<>();

    private Function<String, String> names(long tenantId, List<SlackMessage> messages) {
        Set<String> ids = new LinkedHashSet<>();
        for (SlackMessage m : messages) {
            if (m.user() != null && !m.user().isBlank() && !nameCache.containsKey(new NameKey(tenantId, m.user()))) {
                ids.add(m.user());
            }
        }
        if (!ids.isEmpty()) {
            identities.resolve(tenantId, ids, NAME_BUDGET); // warms the cache in bulk
        }
        return id -> nameCache.computeIfAbsent(new NameKey(tenantId, id), key ->
                identities.person(tenantId, id).map(IdentityService.Person::displayName)
                        .filter(n -> !n.isBlank())).orElse(null);
    }

    // ---- ids and times ---------------------------------------------------------------------------------

    /** The channel an external id ({@code thread:C1:123.4} or {@code day:C1:2026-11-02}) belongs to. */
    static String channelOf(String externalId) {
        String[] parts = externalId.split(":", 3);
        return parts.length == 3 ? parts[1] : "";
    }

    /** True when the document's time (the thread's first message, or the day) is before {@code limit}. */
    private static boolean startsBefore(String externalId, Instant limit) {
        String[] parts = externalId.split(":", 3);
        if (parts.length < 3) {
            return false;
        }
        try {
            if (parts[0].equals("thread")) {
                return SlackHistoryRender.instant(parts[2]).isBefore(limit);
            }
            return LocalDate.parse(parts[2]).atStartOfDay(ZoneOffset.UTC).toInstant().isBefore(limit);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Instant startOfDay(Instant t) {
        return utcDay(t).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static LocalDate utcDay(Instant t) {
        return t.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static String describe(RuntimeException e) {
        return e instanceof SlackCallException slack ? slack.code() : e.getClass().getName();
    }

    // ---- status page ----------------------------------------------------------------------------------

    /** Reads only the database; never calls Slack. */
    @Override
    public ComponentStatus status(long tenantId) {
        if (!messengers.connected(tenantId)) {
            return ComponentStatus.off(NAME, "no Slack bot token configured");
        }
        Optional<Source> source = connection.source(tenantId);
        if (source.isEmpty() || connection.channels(tenantId).isEmpty()) {
            return ComponentStatus.off(NAME, "nothing chosen yet — pick channels on the Sources page");
        }
        long sourceId = source.get().id();
        Optional<SyncRun> last = runs.latest(tenantId, sourceId);
        if (last.isEmpty()) {
            return ComponentStatus.up(NAME, connection.channels(tenantId).size() + " channels chosen, waiting for first sync");
        }
        SyncRun run = last.get();
        if (SyncRun.FAILED.equals(run.status())) {
            return ComponentStatus.down(NAME, "last sync failed: " + StatusText.shorten(run.error()));
        }
        long count = documents.count(tenantId, sourceId);
        if (SyncRun.RUNNING.equals(run.status())) {
            return ComponentStatus.up(NAME, count + " documents, sync in progress");
        }
        Map<String, String> skippedNotes = notes(tenantId);
        String skipped = skippedNotes.isEmpty() ? "" : ", " + skippedNotes.size() + " channels skipped";
        return ComponentStatus.up(NAME, count + " documents, last sync " + run.finishedAt().truncatedTo(ChronoUnit.SECONDS)
                + skipped);
    }
}
