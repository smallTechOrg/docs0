package com.docs0.source.slack;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns Slack's message markup into plain text a person (or the model) can read. */
final class SlackText {

    private static final Pattern TOKEN = Pattern.compile("<([^<>]*)>");

    private SlackText() {}

    /**
     * Resolves mentions (<code>&lt;@U1&gt;</code> becomes @Name), channel links, special mentions
     * and links (<code>&lt;url|text&gt;</code> becomes text (url)), and decodes the HTML entities Slack
     * adds. {@code names} gives a user's name, or null when unknown.
     */
    static String plain(String text, Function<String, String> names) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        Matcher m = TOKEN.matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(decode(text.substring(last, m.start())));
            out.append(token(m.group(1), names));
            last = m.end();
        }
        out.append(decode(text.substring(last)));
        return out.toString();
    }

    private static String token(String body, Function<String, String> names) {
        int bar = body.indexOf('|');
        String target = bar < 0 ? body : body.substring(0, bar);
        String label = bar < 0 ? null : decode(body.substring(bar + 1));
        if (target.startsWith("@")) {
            String id = target.substring(1);
            String name = names.apply(id);
            return "@" + (name != null && !name.isBlank() ? name : label != null && !label.isBlank() ? label : id);
        }
        if (target.startsWith("#")) {
            return "#" + (label != null && !label.isBlank() ? label : target.substring(1));
        }
        if (target.startsWith("!")) {
            String special = target.substring(1);
            if (label != null && !label.isBlank()) {
                return label;
            }
            int caret = special.indexOf('^');
            return "@" + (caret < 0 ? special : special.substring(0, caret));
        }
        String url = decode(target);
        if (url.startsWith("mailto:")) {
            url = url.substring("mailto:".length());
            return label == null || label.isBlank() || label.equals(url) ? url : label + " (" + url + ")";
        }
        return label == null || label.isBlank() || label.equals(url) ? url : label + " (" + url + ")";
    }

    /** Slack escapes only &amp;, &lt; and &gt;; the others are accepted for safety. */
    static String decode(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&amp;", "&");
    }
}
