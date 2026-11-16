package com.docs0.slack;

import static com.slack.api.model.block.Blocks.actions;
import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.divider;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.button;

import com.docs0.agent.Agent.AgentResult;
import com.docs0.agent.Agent.Citation;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Kind;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.element.BlockElement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns an agent result into a Slack message: blocks plus the plain fallback text. Pure, no I/O. */
public final class AnswerRenderer {

    /** Action ids of the follow-up buttons are this prefix plus 0, 1 or 2. The button value is the question. */
    public static final String FOLLOW_UP_ACTION_PREFIX = "follow_up_";
    public static final String FOLLOW_UP_BLOCK_ID = "follow_ups";

    static final String EMPTY_ANSWER = "I don't have an answer for that.";
    static final String LOW_CONFIDENCE = ":warning: *Low confidence* — please check the sources before relying on this.";
    static final String LOW_CONFIDENCE_UNVERIFIED =
            ":warning: *Low confidence* — I couldn't verify this against a source.";
    static final String SOURCES_HEADING = "*Sources*";
    static final String PEOPLE_PREFIX = "People who may know more: ";

    // Slack limits.
    static final int MAX_SECTION_CHARS = 3000;
    static final int MAX_BUTTON_CHARS = 75;
    static final int MAX_BUTTON_VALUE_CHARS = 2000;
    static final int MAX_BLOCKS = 50;
    static final int MAX_TEXT_CHARS = 39000;

    static final int MAX_FOLLOW_UPS = 3;
    static final int MAX_QUOTE_CHARS = 300;
    private static final int MAX_TITLE_CHARS = 150;
    private static final int MAX_PEOPLE = 3;
    // 40 answer + note + divider + 6 sources + people + buttons = 50 blocks at most.
    private static final int MAX_ANSWER_SECTIONS = 40;
    private static final int MAX_SOURCE_SECTIONS = 6;

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^>\\s?(.*)$");
    /** A markdown link, a link already in Slack form, or an inline code span. */
    private static final Pattern INLINE = Pattern.compile(
            "\\[([^\\]\\n]+)\\]\\((https?://[^\\s)]+)\\)"
                    + "|<(https?://[^>|\\s]+)(?:\\|([^>]*))?>"
                    + "|`([^`\\n]+)`");
    private static final Pattern BOLD_STARS = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern BOLD_UNDERSCORES = Pattern.compile("__(.+?)__");
    private static final Pattern STRIKE = Pattern.compile("~~(.+?)~~");
    private static final Pattern BARE_AMPERSAND = Pattern.compile("&(?!(?:amp|lt|gt);)");

    /** A message ready to send. {@code blocks} is empty for a plain text message. */
    public record Rendered(String text, List<LayoutBlock> blocks) {}

    private AnswerRenderer() {}

    public static Rendered render(AgentResult result) {
        String answer = toMrkdwn(result.answer());
        if (answer.isBlank()) {
            answer = EMPTY_ANSWER;
        }
        if (result.kind() == Kind.CLARIFY) {
            return new Rendered(truncate(answer, MAX_TEXT_CHARS), List.of());
        }

        List<LayoutBlock> blocks = new ArrayList<>();
        List<String> answerParts = split(answer, MAX_SECTION_CHARS);
        for (String part : answerParts.subList(0, Math.min(answerParts.size(), MAX_ANSWER_SECTIONS))) {
            blocks.add(mrkdwnSection(part));
        }

        if (result.kind() == Kind.ANSWER) {
            List<Citation> citations = result.citations() == null ? List.of() : result.citations();
            if (result.confidence() == Confidence.LOW) {
                String note = citations.isEmpty() ? LOW_CONFIDENCE_UNVERIFIED : LOW_CONFIDENCE;
                blocks.add(context(List.of(markdownText(note))));
            }
            if (!citations.isEmpty()) {
                blocks.add(divider());
                List<String> sourceParts = split(sources(citations), MAX_SECTION_CHARS);
                for (String part : sourceParts.subList(0, Math.min(sourceParts.size(), MAX_SOURCE_SECTIONS))) {
                    blocks.add(mrkdwnSection(part));
                }
            }
        }

        List<String> people = clean(result.suggestedPeople(), MAX_PEOPLE);
        if (!people.isEmpty()) {
            blocks.add(context(List.of(markdownText(PEOPLE_PREFIX + escape(String.join(", ", people))))));
        }

        List<String> followUps = clean(result.followUps(), MAX_FOLLOW_UPS);
        if (!followUps.isEmpty()) {
            List<BlockElement> buttons = new ArrayList<>();
            for (int i = 0; i < followUps.size(); i++) {
                String question = followUps.get(i);
                String actionId = FOLLOW_UP_ACTION_PREFIX + i;
                buttons.add(button(b -> b
                        .actionId(actionId)
                        .text(plainText(truncate(question, MAX_BUTTON_CHARS), true))
                        .value(truncate(question, MAX_BUTTON_VALUE_CHARS))));
            }
            blocks.add(actions(a -> a.blockId(FOLLOW_UP_BLOCK_ID).elements(buttons)));
        }

        return new Rendered(truncate(answer, MAX_SECTION_CHARS), List.copyOf(blocks));
    }

    /** The Sources section: a heading, then each citation as a numbered link with its quote below. */
    private static String sources(List<Citation> citations) {
        StringBuilder out = new StringBuilder(SOURCES_HEADING);
        for (Citation c : citations) {
            String title = escape(truncate(isBlank(c.title()) ? "Untitled" : c.title().strip(), MAX_TITLE_CHARS))
                    .replace("|", "¦");
            out.append("\n\n*[").append(c.index()).append("]* ");
            if (isBlank(c.url())) {
                out.append(title);
            } else {
                out.append('<').append(linkUrl(c.url())).append('|').append(title).append('>');
            }
            if (!isBlank(c.quote())) {
                String quote = truncate(c.quote().strip().replaceAll("\\s*\\n\\s*", " "), MAX_QUOTE_CHARS);
                out.append("\n> ").append(escape(quote));
            }
        }
        return out.toString();
    }

    /** Converts the model's markdown to Slack mrkdwn and escapes Slack's control characters. */
    static String toMrkdwn(String markdown) {
        if (markdown == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean inCode = false;
        for (String line : markdown.strip().split("\n", -1)) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            if (line.strip().startsWith("```")) {
                inCode = !inCode;
                out.append("```");
                continue;
            }
            if (inCode) {
                out.append(escape(line));
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            Matcher bullet = BULLET.matcher(line);
            Matcher quote = QUOTE.matcher(line);
            if (heading.matches()) {
                String text = heading.group(1).replace("**", "").replace("__", "");
                out.append('*').append(inline(text)).append('*');
            } else if (bullet.matches()) {
                out.append(bullet.group(1)).append("• ").append(inline(bullet.group(2)));
            } else if (quote.matches()) {
                out.append("> ").append(inline(quote.group(1)));
            } else {
                out.append(inline(line));
            }
        }
        return out.toString();
    }

    /** Converts links, code spans and bold within one line; everything else is escaped. */
    private static String inline(String text) {
        StringBuilder out = new StringBuilder();
        Matcher m = INLINE.matcher(text);
        int from = 0;
        while (m.find()) {
            out.append(plain(text.substring(from, m.start())));
            if (m.group(2) != null) {
                out.append('<').append(linkUrl(m.group(2))).append('|').append(plain(m.group(1))).append('>');
            } else if (m.group(3) != null) {
                out.append('<').append(linkUrl(m.group(3)));
                if (m.group(4) != null && !m.group(4).isEmpty()) {
                    out.append('|').append(plain(m.group(4)));
                }
                out.append('>');
            } else {
                out.append('`').append(escape(m.group(5))).append('`');
            }
            from = m.end();
        }
        out.append(plain(text.substring(from)));
        return out.toString();
    }

    private static String plain(String text) {
        String s = escape(text);
        s = BOLD_STARS.matcher(s).replaceAll("*$1*");
        s = BOLD_UNDERSCORES.matcher(s).replaceAll("*$1*");
        return STRIKE.matcher(s).replaceAll("~$1~");
    }

    /** Escapes the three characters Slack treats as control characters. Already-escaped entities are kept. */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        return BARE_AMPERSAND.matcher(text).replaceAll("&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String linkUrl(String url) {
        return url.strip().replace("|", "%7C").replace(">", "%3E").replace("<", "%3C").replace(" ", "%20");
    }

    /** Splits text into parts of at most {@code max} characters, at line breaks where possible. */
    static List<String> split(String text, int max) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            while (line.length() > max) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                int cut = line.lastIndexOf(' ', max);
                if (cut < max / 2) {
                    cut = max;
                }
                parts.add(line.substring(0, cut));
                line = line.substring(cut).stripLeading();
            }
            if (current.length() + 1 + line.length() > max) {
                parts.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            } else if (line.isEmpty() && !parts.isEmpty()) {
                continue;
            }
            current.append(line);
        }
        if (!current.toString().isBlank()) {
            parts.add(current.toString());
        }
        parts.removeIf(String::isBlank);
        return parts;
    }

    static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1).stripTrailing() + "…";
    }

    private static List<String> clean(List<String> items, int max) {
        if (items == null) {
            return List.of();
        }
        return items.stream().filter(s -> !isBlank(s)).map(String::strip).limit(max).toList();
    }

    private static LayoutBlock mrkdwnSection(String text) {
        return section(s -> s.text(markdownText(text)));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
