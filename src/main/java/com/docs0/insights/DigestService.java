package com.docs0.insights;

import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.divider;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;

import com.docs0.budget.BudgetService;
import com.docs0.insights.InsightsService.Gap;
import com.docs0.insights.InsightsService.Gaps;
import com.docs0.insights.InsightsService.Totals;
import com.docs0.insights.ThemeService.Theme;
import com.docs0.settings.TenantSettings;
import com.docs0.slack.AnswerRenderer;
import com.docs0.slack.SlackMessenger;
import com.docs0.slack.SlackMessengers;
import com.docs0.stale.StaleDocs;
import com.docs0.tenant.Tenant;
import com.docs0.tenant.TenantService;
import com.slack.api.model.block.LayoutBlock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The weekly digest: the same insights as the Insights page, condensed, posted to the channel an admin chose.
 * Nothing is posted until a channel is chosen. The digest goes to a channel, so it is stricter than the page:
 * no DM question text, no asker ids, no private-source content, and no question from a private channel.
 */
@Service
public class DigestService {

    public enum Outcome { POSTED, NO_CHANNEL, ALREADY_POSTED, OVER_BUDGET, FAILED }

    public static final int DAYS = 7;
    static final int TOP = 5;
    static final int MAX_QUESTION_CHARS = 120;
    /** A weekly job that already ran this week (within this time) does not run again. */
    static final Duration ONCE_PER = Duration.ofDays(6);

    private static final Logger log = LoggerFactory.getLogger(DigestService.class);

    private final TenantService tenants;
    private final TenantSettings settings;
    private final InsightsService insights;
    private final ThemeService themes;
    private final BudgetService budget;
    private final SlackMessengers messengers;

    public DigestService(TenantService tenants, TenantSettings settings, InsightsService insights,
            ThemeService themes, BudgetService budget, SlackMessengers messengers) {
        this.tenants = tenants;
        this.settings = settings;
        this.insights = insights;
        this.themes = themes;
        this.budget = budget;
        this.messengers = messengers;
    }

    /** The scheduled run. Disabled by setting DOCS0_DIGEST_CRON to "-". */
    @Scheduled(cron = "${docs0.digest.cron:0 0 9 * * MON}", zone = "${docs0.digest.zone:UTC}")
    public void scheduled() {
        // Every active tenant in turn; one tenant's failure never stops the others.
        for (Tenant tenant : tenants.active()) {
            try {
                Outcome outcome = postIfDue(tenant.id());
                log.info("Weekly digest run: tenant={} outcome={}", tenant.id(), outcome);
            } catch (RuntimeException e) {
                log.warn("Weekly digest failed: tenant={} error={}", tenant.id(), e.getClass().getName());
            }
        }
    }

    public Optional<String> channel(long tenantId) {
        return settings.get(tenantId, TenantSettings.DIGEST_CHANNEL).filter(c -> !c.isBlank());
    }

    public Optional<String> channelName(long tenantId) {
        return settings.get(tenantId, TenantSettings.DIGEST_CHANNEL_NAME).filter(c -> !c.isBlank());
    }

    /** Chooses the channel the digest goes to, or turns the digest off when {@code channelId} is blank. */
    public void setChannel(long tenantId, String channelId, String channelName) {
        if (channelId == null || channelId.isBlank()) {
            settings.remove(tenantId, TenantSettings.DIGEST_CHANNEL);
            settings.remove(tenantId, TenantSettings.DIGEST_CHANNEL_NAME);
        } else {
            settings.put(tenantId, TenantSettings.DIGEST_CHANNEL, channelId.strip());
            settings.put(tenantId, TenantSettings.DIGEST_CHANNEL_NAME, channelName == null ? "" : channelName.strip());
        }
    }

    public Optional<Instant> lastPostedAt(long tenantId) {
        return settings.getInstant(tenantId, TenantSettings.DIGEST_LAST_AT);
    }

    /** Posts the digest unless one went out this week, there is no channel, or the budget is used up. */
    public Outcome postIfDue(long tenantId) {
        if (channel(tenantId).isEmpty()) {
            return Outcome.NO_CHANNEL;
        }
        if (lastPostedAt(tenantId).filter(t -> t.isAfter(Instant.now().minus(ONCE_PER))).isPresent()) {
            return Outcome.ALREADY_POSTED;
        }
        return post(tenantId);
    }

    /** Posts the digest now, even if one went out this week. */
    public Outcome postNow(long tenantId) {
        if (channel(tenantId).isEmpty()) {
            return Outcome.NO_CHANNEL;
        }
        return post(tenantId);
    }

    private Outcome post(long tenantId) {
        if (budget.exhausted(tenantId)) {
            return Outcome.OVER_BUDGET;
        }
        String channel = channel(tenantId).orElseThrow();
        Instant since = Instant.now().minus(Duration.ofDays(DAYS));
        Totals totals = insights.totals(tenantId, since);
        Gaps gaps = insights.gaps(tenantId, since);
        List<StaleDocs.Row> stale = insights.staleDocuments(tenantId, since);
        ThemeService.Result themeResult = themes.themes(tenantId, DAYS);
        List<LayoutBlock> blocks;
        try {
            SlackMessenger messenger = messengers.forTenant(tenantId);
            blocks = blocks(totals, themeResult, gaps, stale, publicChannels(messenger));
            messenger.postMessage(channel, null, "docs0 weekly digest", blocks);
        } catch (RuntimeException e) {
            log.warn("Weekly digest could not be posted: tenant={} error={}", tenantId,
                    e instanceof SlackMessenger.SlackCallException s ? s.code() : e.getClass().getName());
            return Outcome.FAILED;
        }
        settings.putInstant(tenantId, TenantSettings.DIGEST_LAST_AT, Instant.now());
        log.info("Weekly digest posted: tenant={} channel={}", tenantId, channel);
        return Outcome.POSTED;
    }

    /** Ids of public channels. Empty when Slack cannot say: then no question is quoted. */
    private static Set<String> publicChannels(SlackMessenger messenger) {
        try {
            Set<String> ids = new HashSet<>();
            for (SlackMessenger.ChannelInfo c : messenger.listChannels()) {
                if (!c.isPrivate()) {
                    ids.add(c.id());
                }
            }
            return ids;
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    /** The message. Pure: a few totals, the themes, five gaps and five stale documents. */
    static List<LayoutBlock> blocks(Totals totals, ThemeService.Result themeResult, Gaps gaps,
            List<StaleDocs.Row> stale, Set<String> publicChannels) {
        List<LayoutBlock> blocks = new ArrayList<>();
        StringBuilder head = new StringBuilder("*docs0 weekly digest* — the last " + DAYS + " days\n");
        head.append(totals.questions()).append(totals.questions() == 1 ? " question" : " questions");
        head.append(" · :+1: ").append(totals.thumbsUp()).append(" · :-1: ").append(totals.thumbsDown());
        if (totals.thumbsDownPercent() >= 0) {
            head.append(" (").append(totals.thumbsDownPercent()).append("% thumbs down)");
        }
        if (totals.repeatAnswers() > 0) {
            head.append(" · ").append(totals.repeatAnswers()).append(" repeat-question ")
                    .append(totals.repeatAnswers() == 1 ? "answer" : "answers");
        }
        head.append(" · $").append(totals.spendUsd().setScale(2, java.math.RoundingMode.HALF_UP));
        blocks.add(section(s -> s.text(markdownText(head.toString()))));

        if (themeResult != null && themeResult.status() == ThemeService.Status.OK && !themeResult.themes().isEmpty()) {
            StringBuilder t = new StringBuilder("*Top themes*");
            for (Theme theme : themeResult.themes()) {
                t.append("\n• ").append(AnswerRenderer.escape(theme.label())).append(" (about ").append(theme.count())
                        .append(")");
            }
            long privately = themeResult.askedPrivately() + themeResult.usedPrivateSources();
            if (privately > 0) {
                t.append("\n_").append(privately).append(privately == 1 ? " question" : " questions")
                        .append(" asked privately or using private sources ").append(privately == 1 ? "is" : "are")
                        .append(" not part of the themes._");
            }
            blocks.add(section(s -> s.text(markdownText(t.toString()))));
        }

        StringBuilder g = new StringBuilder("*Questions docs0 could not answer well*");
        int shown = 0;
        long hidden = gaps.privateCount();
        for (Gap gap : gaps.items()) {
            if (shown < TOP && !gap.redacted() && publicChannels.contains(gap.channelId())) {
                g.append("\n• ").append(AnswerRenderer.escape(shorten(gap.question())));
                if (gap.times() > 1) {
                    g.append(" (asked ").append(gap.times()).append(" times)");
                }
                shown++;
            } else {
                hidden += gap.times();
            }
        }
        if (shown == 0 && hidden == 0) {
            g.append("\nNone this week.");
        }
        if (hidden > 0) {
            g.append("\n_").append(hidden).append(hidden == 1 ? " more question" : " more questions")
                    .append(" not shown (asked privately or in a private channel)._");
        }
        blocks.add(section(s -> s.text(markdownText(g.toString()))));

        StringBuilder d = new StringBuilder("*Old documents people rely on*");
        if (stale.isEmpty()) {
            d.append("\nNone this week.");
        }
        for (StaleDocs.Row row : stale.subList(0, Math.min(TOP, stale.size()))) {
            String title = AnswerRenderer.escape(shorten(row.title() == null ? "Untitled" : row.title())).replace("|", "¦");
            d.append("\n• ");
            if (row.url() == null || row.url().isBlank()) {
                d.append(title);
            } else {
                d.append('<').append(row.url().strip().replace("|", "%7C").replace(">", "%3E").replace("<", "%3C")
                        .replace(" ", "%20")).append('|').append(title).append('>');
            }
            d.append(" — relied on ").append(row.reliedOn()).append(row.reliedOn() == 1 ? " time" : " times")
                    .append(", last updated ").append(StaleDocs.month(row.modifiedAt()));
        }
        blocks.add(section(s -> s.text(markdownText(d.toString()))));
        blocks.add(divider());
        blocks.add(context(List.of(markdownText("Questions asked in direct messages are counted, never shown."))));
        return blocks;
    }

    private static String shorten(String text) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return t.length() <= MAX_QUESTION_CHARS ? t : t.substring(0, MAX_QUESTION_CHARS - 1).stripTrailing() + "…";
    }
}
