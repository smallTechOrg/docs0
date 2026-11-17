package com.docs0.source.slack;

import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.slack.SlackMessenger.SlackMessage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** Builds the documents of Slack history from fetched messages. Pure: no Slack, no database. */
final class SlackHistoryRender {

    static final String THREAD_MIME = "application/vnd.slack.thread";
    static final String DAY_MIME = "application/vnd.slack.day";

    /** Message kinds that are conversation. Joins, leaves, topic changes and the like are left out. */
    private static final Set<String> CONVERSATION_SUBTYPES =
            Set.of("bot_message", "file_share", "me_message", "thread_broadcast", "reply_broadcast");
    private static final int TITLE_CHARS = 60;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY_TITLE =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    /** A document, and the ts of its first message (for the permalink). */
    record Rendered(DocumentWrite doc, String firstTs) {}

    private SlackHistoryRender() {}

    static String threadId(String channelId, String rootTs) {
        return "thread:" + channelId + ":" + rootTs;
    }

    static String dayId(String channelId, LocalDate day) {
        return "day:" + channelId + ":" + day;
    }

    /** The moment of a Slack ts such as {@code 1700000000.000200}. */
    static Instant instant(String ts) {
        int dot = ts.indexOf('.');
        long seconds = Long.parseLong(dot < 0 ? ts : ts.substring(0, dot));
        long micros = dot < 0 ? 0 : Long.parseLong((ts.substring(dot + 1) + "000000").substring(0, 6));
        return Instant.ofEpochSecond(seconds, micros * 1000);
    }

    /** The latest thing that happened to a message: it was posted, edited or (for a root) replied to. */
    static Instant activity(SlackMessage m) {
        Instant latest = instant(m.ts());
        for (String ts : new String[] {m.editedTs(), m.latestReply()}) {
            if (ts != null && !ts.isBlank() && instant(ts).isAfter(latest)) {
                latest = instant(ts);
            }
        }
        return latest;
    }

    /** One thread: the root and its replies. Null when nothing in it is worth indexing. */
    static Rendered thread(String channelId, String channelName, List<SlackMessage> messages, String ownBotId,
            Function<String, String> names) {
        if (messages.isEmpty()) {
            return null;
        }
        List<SlackMessage> shown = shown(messages, ownBotId);
        if (shown.isEmpty()) {
            return null;
        }
        String rootTs = messages.get(0).ts();
        return build(threadId(channelId, rootTs), THREAD_MIME, messages, shown, ownBotId, names,
                title(channelName, plainText(shown.get(0), names)));
    }

    /** The messages of one UTC day that are not part of a thread. Null when nothing is worth indexing. */
    static Rendered day(String channelId, String channelName, LocalDate day, List<SlackMessage> messages,
            String ownBotId, Function<String, String> names) {
        List<SlackMessage> shown = shown(messages, ownBotId);
        if (shown.isEmpty()) {
            return null;
        }
        String title = "#" + channelName + " — " + DAY_TITLE.format(day.atStartOfDay(ZoneOffset.UTC));
        return build(dayId(channelId, day), DAY_MIME, messages, shown, ownBotId, names, title);
    }

    private static Rendered build(String externalId, String mime, List<SlackMessage> all, List<SlackMessage> shown,
            String ownBotId, Function<String, String> names, String title) {
        StringBuilder body = new StringBuilder();
        for (SlackMessage m : shown) {
            if (!body.isEmpty()) {
                body.append('\n');
            }
            body.append('[').append(STAMP.format(instant(m.ts()))).append("] ").append(author(m, names)).append(": ")
                    .append(plainText(m, names));
        }
        Instant modified = null;
        for (SlackMessage m : all) {
            Instant at = activity(m);
            if (modified == null || at.isAfter(modified)) {
                modified = at;
            }
        }
        SlackMessage first = shown.get(0);
        DocumentWrite doc = new DocumentWrite(externalId, title, mime, null, author(first, names), null, modified,
                body.toString());
        return new Rendered(doc, first.ts());
    }

    /** The messages that become text: conversation only, and never docs0's own. */
    private static List<SlackMessage> shown(List<SlackMessage> messages, String ownBotId) {
        List<SlackMessage> shown = new ArrayList<>();
        for (SlackMessage m : messages) {
            boolean conversation = m.subtype() == null || CONVERSATION_SUBTYPES.contains(m.subtype());
            boolean own = ownBotId != null && ownBotId.equals(m.user());
            boolean empty = (m.text() == null || m.text().isBlank()) && (m.fileTitles() == null || m.fileTitles().isEmpty());
            if (conversation && !own && !empty) {
                shown.add(m);
            }
        }
        return shown;
    }

    private static String author(SlackMessage m, Function<String, String> names) {
        if (m.user() != null && !m.user().isBlank()) {
            String name = names.apply(m.user());
            return name != null && !name.isBlank() ? name : m.user();
        }
        if (m.username() != null && !m.username().isBlank()) {
            return m.username().strip();
        }
        return m.botId() != null && !m.botId().isBlank() ? m.botId() : "unknown";
    }

    /** The message text in plain form, with the titles of attached files added. */
    private static String plainText(SlackMessage m, Function<String, String> names) {
        String text = SlackText.plain(m.text(), names).strip();
        if (m.fileTitles() != null) {
            for (String title : m.fileTitles()) {
                text = (text.isEmpty() ? "" : text + " ") + "(file: " + title + ")";
            }
        }
        return text;
    }

    private static String title(String channelName, String firstText) {
        String line = firstText.lines().findFirst().orElse("").strip();
        if (line.length() > TITLE_CHARS) {
            line = line.substring(0, TITLE_CHARS).stripTrailing() + "…";
        }
        return "#" + channelName + " — " + line;
    }
}
