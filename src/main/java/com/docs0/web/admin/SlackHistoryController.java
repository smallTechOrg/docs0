package com.docs0.web.admin;

import com.docs0.slack.SlackMessenger;
import com.docs0.slack.SlackMessengers;
import com.docs0.slack.SlackMessenger.ChannelInfo;
import com.docs0.slack.SlackMessenger.SlackMessage;
import com.docs0.source.Source;
import com.docs0.job.SyncNow;
import com.docs0.source.SyncService;
import com.docs0.source.slack.SlackChannel;
import com.docs0.source.slack.SlackHistoryConnection;
import com.docs0.source.slack.SlackHistoryConnector;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

/** The form posts of the Slack history section of the Sources page: choose channels, check access, sync now. */
@Controller
public class SlackHistoryController {

    private static final Logger log = LoggerFactory.getLogger(SlackHistoryController.class);
    private static final String BASE = SourcesController.PAGE + "/slack";
    static final int CHECK_DAYS = 30;
    /** Check access stops counting here; the page then shows "2,000+". */
    static final int CHECK_CAP = 2000;

    private final SlackHistoryConnection connection;
    private final SlackHistoryConnector connector;
    private final SlackHistoryPanel panel;
    private final SlackMessengers messengers;
    private final SyncService syncService;
    private final SyncNow syncNow;

    public SlackHistoryController(SlackHistoryConnection connection,
                                  SlackHistoryConnector connector, SlackHistoryPanel panel,
                                  SlackMessengers messengers, SyncService syncService, SyncNow syncNow) {
        this.connection = connection;
        this.connector = connector;
        this.panel = panel;
        this.messengers = messengers;
        this.syncService = syncService;
        this.syncNow = syncNow;
    }

    @PostMapping(BASE + "/channels")
    public RedirectView save(AdminPrincipal admin, @RequestParam(name = "choice", required = false) List<String> choice,
                             RedirectAttributes flash) {
        long tenantId = admin.tenantId();
        if (!panel.workspaceConnected(tenantId)) {
            return failed(flash, "Set DOCS0_SLACK_BOT_TOKEN first. See the README.");
        }
        Set<String> wanted = choice == null ? Set.of() : Set.copyOf(choice);
        List<SlackChannel> chosen = new ArrayList<>();
        try {
            Set<String> listed = new java.util.HashSet<>();
            for (ChannelInfo info : panel.available(tenantId)) {
                listed.add(info.id());
                if (wanted.contains(info.id())) {
                    chosen.add(new SlackChannel(info.id(), info.name(), info.isPrivate()));
                }
            }
            // A channel chosen earlier that Slack no longer lists (a private one docs0 is not in) stays chosen while ticked.
            for (SlackChannel old : connection.channels(tenantId)) {
                if (!listed.contains(old.id()) && wanted.contains(old.id())) {
                    chosen.add(old);
                }
            }
        } catch (RuntimeException e) {
            return failed(flash, "Could not ask Slack for its channels, so nothing was saved. Try again.");
        }
        connection.setChannels(tenantId, chosen);
        flash.addFlashAttribute("notice", chosen.isEmpty()
                ? "No channels chosen. Nothing from Slack is indexed."
                : "Saved. " + chosen.size() + (chosen.size() == 1 ? " channel is" : " channels are")
                        + " indexed at the next sync.");
        return to();
    }

    @PostMapping(BASE + "/check")
    public RedirectView check(AdminPrincipal admin, RedirectAttributes flash) {
        long tenantId = admin.tenantId();
        List<SlackChannel> chosen = connection.channels(tenantId);
        if (!panel.workspaceConnected(tenantId) || chosen.isEmpty()) {
            return failed(flash, "Choose at least one channel first.");
        }
        Instant since = Instant.now().minus(Duration.ofDays(CHECK_DAYS));
        Map<String, ChannelInfo> visible;
        try {
            visible = panel.available(tenantId).stream().collect(Collectors.toMap(ChannelInfo::id, c -> c, (a, b) -> a));
        } catch (RuntimeException e) {
            return failed(flash, SlackHistoryPanel.describe(e));
        }
        List<String> lines = new ArrayList<>();
        for (SlackChannel channel : chosen) {
            ChannelInfo info = visible.get(channel.id());
            if (info == null) {
                lines.add("#" + channel.name() + ": not visible to docs0" + (channel.isPrivate()
                        ? " (invite @docs0 to #" + channel.name() + ")" : ""));
                continue;
            }
            if (!info.member()) {
                lines.add("#" + channel.name() + ": docs0 has not joined yet, it joins at the next sync");
                continue;
            }
            try {
                List<SlackMessage> messages = messengers.forTenant(admin.tenantId()).history(info.id(), since);
                long threads = messages.stream().filter(m -> m.replyCount() > 0).count();
                lines.add("#" + channel.name() + ": " + describe(messages.size(), "message") + ", "
                        + describe((int) threads, "thread") + " in the last " + CHECK_DAYS + " days");
            } catch (RuntimeException e) {
                log.warn("Slack access check failed for {}: {}", channel.id(), e instanceof SlackMessenger.SlackCallException s
                        ? s.code() : e.getClass().getSimpleName());
                lines.add("#" + channel.name() + ": could not be read");
            }
        }
        flash.addFlashAttribute("notice", "docs0 can read " + String.join("; ", lines) + ".");
        return to();
    }

    @PostMapping(BASE + "/sync")
    public RedirectView sync(AdminPrincipal admin, RedirectAttributes flash) {
        long tenantId = admin.tenantId();
        if (!panel.workspaceConnected(tenantId)) {
            return failed(flash, "Set DOCS0_SLACK_BOT_TOKEN first. See the README.");
        }
        Optional<Source> source = connection.source(tenantId);
        if (connection.channels(tenantId).isEmpty() && source.isEmpty()) {
            return failed(flash, "Choose at least one channel first.");
        }
        panel.forget(tenantId);
        syncNow.request(tenantId, connector).flash(flash, "A Slack history sync is already running.");
        return to();
    }

    private static String describe(int count, String noun) {
        if (count >= CHECK_CAP) {
            return String.format("%,d+ %ss", CHECK_CAP, noun);
        }
        return count == 1 ? "1 " + noun : String.format("%,d %ss", count, noun);
    }

    private static RedirectView failed(RedirectAttributes flash, String message) {
        flash.addFlashAttribute("error", message);
        return to();
    }

    private static RedirectView to() {
        RedirectView view = new RedirectView(SourcesController.PAGE + "#slack", true);
        view.setExpandUriTemplateVariables(false);
        view.setExposeModelAttributes(false);
        return view;
    }
}
