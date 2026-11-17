package com.docs0.web.admin;

import com.docs0.knowledge.DocumentStore;
import com.docs0.slack.IdentityService;
import com.docs0.slack.SlackMessenger;
import com.docs0.slack.SlackMessengers;
import com.docs0.slack.SlackMessenger.ChannelInfo;
import com.docs0.source.Source;
import com.docs0.source.SyncRun;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.SyncService;
import com.docs0.source.slack.SlackChannel;
import com.docs0.source.slack.SlackHistoryConnection;
import com.docs0.source.slack.SlackHistoryConnector;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
 * What the Sources page shows for Slack history: whether the bot is connected, the channels it can
 * see with a checkbox each (asked of Slack, then remembered briefly because the page reloads itself
 * during a sync), the sync log and the email-scope warning.
 */
@Component
public class SlackHistoryPanel {

    /** One channel in the checklist. {@code note} is a short hint, or null. */
    public record ChannelRow(String id, String name, boolean isPrivate, boolean chosen, String note) {}

    /**
     * The section. {@code state} is {@code off} (no Slack workspace) or {@code connected}. {@code channels}
     * is null when Slack could not be asked; see {@code error}.
     */
    public record Panel(String state, String error, boolean syncRunning, long documents, List<ChannelRow> channels,
                        boolean emailWarning, List<SourcesController.RunRow> runs) {}

    private static final Logger log = LoggerFactory.getLogger(SlackHistoryPanel.class);
    private static final Duration KEEP = Duration.ofMinutes(2);
    private static final int RECENT_RUNS = 5;

    private record Cached(List<ChannelInfo> channels, Instant at) {}

    private final SlackMessengers messengers;
    private final IdentityService identities;
    private final SlackHistoryConnection connection;
    private final SlackHistoryConnector connector;
    private final SyncService syncService;
    private final SyncRunRepository runs;
    private final DocumentStore documents;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public SlackHistoryPanel(SlackMessengers messengers, IdentityService identities,
                             SlackHistoryConnection connection, SlackHistoryConnector connector,
                             SyncService syncService, SyncRunRepository runs, DocumentStore documents) {
        this.messengers = messengers;
        this.identities = identities;
        this.connection = connection;
        this.connector = connector;
        this.syncService = syncService;
        this.runs = runs;
        this.documents = documents;
    }

    /** True when the tenant has a Slack workspace connected. */
    public boolean workspaceConnected(long tenantId) {
        return messengers.connected(tenantId);
    }

    public void addTo(Model model, long tenantId) {
        model.addAttribute("slackHistory", panel(tenantId));
    }

    /** The channels Slack lists for the bot, remembered for a couple of minutes. */
    List<ChannelInfo> available(long tenantId) {
        Cached cached = cache.get(tenantId);
        if (cached != null && cached.at().plus(KEEP).isAfter(Instant.now())) {
            return cached.channels();
        }
        List<ChannelInfo> channels = messengers.forTenant(tenantId).listChannels();
        cache.put(tenantId, new Cached(channels, Instant.now()));
        return channels;
    }

    void forget(long tenantId) {
        cache.remove(tenantId);
    }

    private Panel panel(long tenantId) {
        Optional<Source> source = connection.source(tenantId);
        boolean running = source.isPresent() && syncService.isRunning(tenantId, source.get().id());
        List<SourcesController.RunRow> rows = new ArrayList<>();
        long count = 0;
        if (source.isPresent()) {
            for (SyncRun run : runs.recent(tenantId, source.get().id(), RECENT_RUNS)) {
                rows.add(SourcesController.runRow(run, running && rows.isEmpty()));
            }
            count = documents.count(tenantId, source.get().id());
        }
        if (!workspaceConnected(tenantId)) {
            return new Panel("off", null, false, 0, null, false, List.of());
        }
        List<ChannelRow> channels = null;
        String error = null;
        try {
            channels = rows(tenantId, available(tenantId));
        } catch (RuntimeException e) {
            error = describe(e);
        }
        boolean emailWarning = false;
        try {
            emailWarning = identities.emailScopeMissing(tenantId);
        } catch (RuntimeException e) {
            // The warning is an extra; the page works without it.
        }
        return new Panel("connected", error, running, count, channels, emailWarning, rows);
    }

    private List<ChannelRow> rows(long tenantId, List<ChannelInfo> visible) {
        List<SlackChannel> chosen = connection.channels(tenantId);
        Set<String> chosenIds = chosen.stream().map(SlackChannel::id).collect(Collectors.toSet());
        Map<String, String> skipped = connector.notes(tenantId);
        List<ChannelRow> rows = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (ChannelInfo info : visible) {
            seen.add(info.id());
            boolean isChosen = chosenIds.contains(info.id());
            String note = isChosen && !info.member() && !info.isPrivate()
                    ? "docs0 joins this channel at the next sync" : skipped.get(info.id());
            rows.add(new ChannelRow(info.id(), info.name(), info.isPrivate(), isChosen, isChosen ? note : null));
        }
        for (SlackChannel channel : chosen) {
            if (!seen.contains(channel.id())) {
                rows.add(new ChannelRow(channel.id(), channel.name(), channel.isPrivate(), true,
                        channel.isPrivate() ? "invite @docs0 to #" + channel.name()
                                : "#" + channel.name() + " was not found or is archived"));
            }
        }
        rows.sort(Comparator.comparing((ChannelRow r) -> r.name().toLowerCase(java.util.Locale.ROOT)));
        return rows;
    }

    static String describe(RuntimeException e) {
        if (e instanceof SlackMessenger.SlackCallException slack) {
            if ("no_workspace".equals(slack.code())) {
                return "Slack is not connected yet. Set DOCS0_SLACK_BOT_TOKEN and restart docs0 (see the README).";
            }
            if ("missing_scope".equals(slack.code())) {
                return "The Slack app is missing a permission needed to list channels. Re-paste slack/manifest.yml "
                        + "and reinstall the app (see the README).";
            }
            if (slack.code().equals("invalid_auth") || slack.code().equals("not_authed")
                    || slack.code().equals("token_revoked") || slack.code().equals("account_inactive")) {
                return "Slack did not accept the bot token. Check DOCS0_SLACK_BOT_TOKEN.";
            }
        }
        log.warn("Could not ask Slack for the Sources page: {}", e instanceof SlackMessenger.SlackCallException s
                ? s.code() : e.getClass().getSimpleName());
        return "Slack could not be reached. Try again in a moment.";
    }
}
