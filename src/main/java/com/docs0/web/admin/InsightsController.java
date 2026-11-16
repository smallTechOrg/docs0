package com.docs0.web.admin;

import com.docs0.insights.DigestService;
import com.docs0.insights.InsightsService;
import com.docs0.insights.InsightsService.Gap;
import com.docs0.insights.InsightsService.Gaps;
import com.docs0.insights.InsightsService.Totals;
import com.docs0.insights.ThemeService;
import com.docs0.slack.SlackMessenger;
import com.docs0.slack.SlackMessengers;
import com.docs0.slack.SlackMessenger.ChannelInfo;
import com.docs0.stale.StaleDocs;
import com.docs0.tenant.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;

/**
 * The Insights page: totals, top themes, questions docs0 could not answer well, stale documents people rely
 * on, repeat-question answers and the weekly digest settings. Every change is a form POST and a redirect.
 */
@Controller
public class InsightsController {

    static final String PAGE = "/admin/insights";
    static final int MAX_QUESTION_CHARS = 160;
    static final int GAPS_SHOWN = 20;

    private static final Map<String, String> NOTICES = Map.ofEntries(
            Map.entry("refreshed", "Themes refreshed."),
            Map.entry("too-soon", "The themes were refreshed a few minutes ago. Try again a bit later."),
            Map.entry("themes-failed", "Themes could not be worked out right now. Try again later."),
            Map.entry("themes-budget", "This month's budget is used up, so themes were not refreshed."),
            Map.entry("themes-off", "docs0 is not connected to its AI model, so themes cannot be worked out."),
            Map.entry("themes-none", "There are no questions in this period yet."),
            Map.entry("channel-saved", "Saved. The weekly digest goes to that channel."),
            Map.entry("channel-off", "Saved. No weekly digest will be posted."),
            Map.entry("channel-bad", "That channel was not found, so nothing was changed."),
            Map.entry("posted", "Digest posted."),
            Map.entry("digest-no-channel", "Choose a channel for the digest first."),
            Map.entry("digest-budget", "This month's budget is used up, so no digest was posted."),
            Map.entry("digest-failed", "docs0 could not post there. Invite @docs0 to the channel and try again."),
            Map.entry("pinged", "Sent. The owner has been asked to review it."),
            Map.entry("ping-no-owner", "This document has no known owner, so nobody was asked."),
            Map.entry("ping-not-in-slack", "docs0 could not find the owner in Slack (by email), so nobody was asked."),
            Map.entry("ping-too-soon", "The owner was already asked in the last 14 days."),
            Map.entry("ping-failed", "Slack did not accept the message. Try again later."),
            Map.entry("ping-gone", "That document is no longer on the list."));

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    public record GapRow(String question, boolean redacted, long times, String when, String reason) {}

    public record StaleRow(long documentId, String title, String url, String owner, long reliedOn, String updated,
            String pinged) {}

    public record ChannelChoice(String id, String name, boolean isPrivate, boolean chosen) {}

    private final TenantService tenants;
    private final InsightsService insights;
    private final ThemeService themes;
    private final DigestService digest;
    private final StaleDocs staleDocs;
    private final SlackMessengers messengers;

    public InsightsController(TenantService tenants, InsightsService insights, ThemeService themes,
            DigestService digest, StaleDocs staleDocs, SlackMessengers messengers) {
        this.tenants = tenants;
        this.insights = insights;
        this.themes = themes;
        this.digest = digest;
        this.staleDocs = staleDocs;
        this.messengers = messengers;
    }

    @GetMapping(PAGE)
    public String page(AdminPrincipal admin, HttpServletRequest request, @RequestParam(name = "days", required = false) Integer daysParam,
            @RequestParam(name = "notice", required = false) String notice, Model model) {
        long tenantId = admin.tenantId();
        int days = InsightsService.period(daysParam);
        Instant since = Instant.now().minus(Duration.ofDays(days));

        Totals totals = insights.totals(tenantId, since);
        ThemeService.Result themeResult = themes.themes(tenantId, days);
        Gaps gaps = insights.gaps(tenantId, since);
        List<GapRow> gapRows = new ArrayList<>();
        for (Gap g : gaps.items().subList(0, Math.min(GAPS_SHOWN, gaps.items().size()))) {
            gapRows.add(new GapRow(g.redacted() ? null : CorrectionsController.truncate(g.question(), MAX_QUESTION_CHARS),
                    g.redacted(), g.times(), DAY.format(g.latest()), g.reason()));
        }
        List<StaleRow> stale = new ArrayList<>();
        for (StaleDocs.Row r : insights.staleDocuments(tenantId, since)) {
            stale.add(new StaleRow(r.documentId(), r.title() == null || r.title().isBlank() ? "Untitled" : r.title(),
                    safeUrl(r.url()), r.ownerName(), r.reliedOn(), DAY.format(r.modifiedAt()),
                    r.pingedAt() == null ? null : DAY.format(r.pingedAt())));
        }

        List<ChannelChoice> channels = null;
        String channelError = null;
        String chosen = digest.channel(tenantId).orElse("");
        try {
            channels = new ArrayList<>();
            for (ChannelInfo c : messengers.forTenant(admin.tenantId()).listChannels()) {
                channels.add(new ChannelChoice(c.id(), c.name(), c.isPrivate(), c.id().equals(chosen)));
            }
            channels.sort(Comparator.comparing((ChannelChoice c) -> c.name().toLowerCase(Locale.ROOT)));
        } catch (RuntimeException e) {
            channelError = SlackHistoryPanel.describe(e);
        }

        model.addAttribute("csrf", AdminGuard.csrfToken(request.getSession()));
        model.addAttribute("tenantName", tenants.name(admin.tenantId()));
        model.addAttribute("days", days);
        model.addAttribute("periods", InsightsService.PERIODS);
        model.addAttribute("notice", notice == null ? null : NOTICES.get(notice));
        model.addAttribute("totals", totals);
        model.addAttribute("thumbsDownPercent", totals.thumbsDownPercent());
        model.addAttribute("repeatDownPercent", totals.repeatThumbsDownPercent());
        model.addAttribute("themeStatus", themeResult.status().name());
        model.addAttribute("themes", themeResult.themes());
        model.addAttribute("themesAt", themeResult.computedAt() == null ? null : TIME.format(themeResult.computedAt()));
        model.addAttribute("gaps", gapRows);
        model.addAttribute("gapsMore", Math.max(0, gaps.items().size() - GAPS_SHOWN));
        model.addAttribute("privateCount", gaps.privateCount());
        model.addAttribute("stale", stale);
        model.addAttribute("staleDays", staleDocs.staleDays());
        model.addAttribute("channels", channels);
        model.addAttribute("channelError", channelError);
        model.addAttribute("digestChannel", chosen);
        model.addAttribute("digestChannelName", digest.channelName(tenantId).orElse(null));
        model.addAttribute("digestLast", digest.lastPostedAt(tenantId).map(TIME::format).orElse(null));
        return "insights";
    }

    @PostMapping(PAGE + "/refresh")
    public RedirectView refresh(AdminPrincipal admin, @RequestParam(name = "days", required = false) Integer days) {
        int period = InsightsService.period(days);
        ThemeService.Result result = themes.refresh(admin.tenantId(), period);
        String notice = result.tooSoon() ? "too-soon" : switch (result.status()) {
            case OK -> "refreshed";
            case FAILED -> "themes-failed";
            case OVER_BUDGET -> "themes-budget";
            case NOT_CONFIGURED -> "themes-off";
            case NO_QUESTIONS -> "themes-none";
        };
        return to(period, notice);
    }

    @PostMapping(PAGE + "/digest/channel")
    public RedirectView digestChannel(AdminPrincipal admin, @RequestParam(name = "channel", required = false) String channel,
            @RequestParam(name = "days", required = false) Integer days) {
        long tenantId = admin.tenantId();
        int period = InsightsService.period(days);
        if (channel == null || channel.isBlank()) {
            digest.setChannel(tenantId, null, null);
            return to(period, "channel-off");
        }
        try {
            for (ChannelInfo c : messengers.forTenant(admin.tenantId()).listChannels()) {
                if (c.id().equals(channel)) {
                    digest.setChannel(tenantId, c.id(), c.name());
                    return to(period, "channel-saved");
                }
            }
        } catch (RuntimeException e) {
            return to(period, "digest-failed");
        }
        return to(period, "channel-bad");
    }

    @PostMapping(PAGE + "/digest/now")
    public RedirectView digestNow(AdminPrincipal admin, @RequestParam(name = "days", required = false) Integer days) {
        int period = InsightsService.period(days);
        String notice = switch (digest.postNow(admin.tenantId())) {
            case POSTED -> "posted";
            case NO_CHANNEL -> "digest-no-channel";
            case OVER_BUDGET -> "digest-budget";
            case ALREADY_POSTED, FAILED -> "digest-failed";
        };
        return to(period, notice);
    }

    @PostMapping(PAGE + "/stale/{documentId}/ping")
    public RedirectView ping(AdminPrincipal admin, @PathVariable long documentId, @RequestParam(name = "days", required = false) Integer days) {
        String notice = switch (staleDocs.askOwner(admin.tenantId(), documentId)) {
            case SENT -> "pinged";
            case NO_OWNER -> "ping-no-owner";
            case NOT_IN_SLACK -> "ping-not-in-slack";
            case TOO_SOON -> "ping-too-soon";
            case FAILED -> "ping-failed";
            case GONE -> "ping-gone";
        };
        return to(InsightsService.period(days), notice);
    }

    /** Only web links are linked: a document's url comes from a connected tool. */
    private static String safeUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url.strip();
        return u.regionMatches(true, 0, "https://", 0, 8) || u.regionMatches(true, 0, "http://", 0, 7) ? u : null;
    }

    private static RedirectView to(int days, String notice) {
        RedirectView view = new RedirectView(PAGE + "?days=" + days + "&notice=" + notice, true);
        view.setExpandUriTemplateVariables(false);
        view.setExposeModelAttributes(false);
        return view;
    }
}
