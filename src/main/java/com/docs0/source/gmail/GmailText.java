package com.docs0.source.gmail;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turning the parts of a mail into plain text: charsets, HTML, quoted replies. No dependencies. */
final class GmailText {

    private GmailText() {}

    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*\"?([A-Za-z0-9_.:+-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern HIDDEN = Pattern.compile(
            "<(script|style|head|title)\\b[^>]*>.*?</\\1\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern INNER_QUOTE = Pattern.compile(
            "<blockquote\\b[^>]*>(?:(?!<blockquote\\b).)*?</blockquote\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern BREAKS = Pattern.compile(
            "<\\s*(br|/p|/div|/tr|/li|/h[1-6]|/table|/ul|/ol|hr)\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_ITEM = Pattern.compile("<\\s*li\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CELL_END = Pattern.compile("</t[dh]\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]*>", Pattern.DOTALL);
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,9});");
    private static final Pattern WROTE_ONE_LINE = Pattern.compile("^On .{5,300}wrote:\\s*$");
    private static final Pattern WROTE_START = Pattern.compile("^On .{5,300}$");
    private static final Pattern WROTE_END = Pattern.compile("^(.{0,200}\\s)?wrote:\\s*$");

    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""),
            Map.entry("apos", "'"), Map.entry("nbsp", " "), Map.entry("hellip", "…"), Map.entry("mdash", "—"),
            Map.entry("ndash", "–"), Map.entry("rsquo", "’"), Map.entry("lsquo", "‘"), Map.entry("rdquo", "”"),
            Map.entry("ldquo", "“"), Map.entry("copy", "©"), Map.entry("reg", "®"), Map.entry("trade", "™"),
            Map.entry("bull", "•"), Map.entry("euro", "€"), Map.entry("pound", "£"), Map.entry("middot", "·"),
            Map.entry("laquo", "«"), Map.entry("raquo", "»"), Map.entry("eacute", "é"), Map.entry("egrave", "è"),
            Map.entry("uuml", "ü"), Map.entry("ouml", "ö"), Map.entry("auml", "ä"), Map.entry("szlig", "ß"));

    /** Gmail's base64url body data as text in the charset the part's Content-Type names (UTF-8 when unknown). */
    static String decode(String base64Url, String contentType) {
        if (base64Url == null || base64Url.isEmpty()) {
            return "";
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(base64Url.strip());
        } catch (IllegalArgumentException e) {
            try {
                bytes = Base64.getMimeDecoder().decode(base64Url.strip());
            } catch (IllegalArgumentException again) {
                return "";
            }
        }
        return new String(bytes, charset(contentType));
    }

    static Charset charset(String contentType) {
        if (contentType != null) {
            Matcher m = CHARSET.matcher(contentType);
            if (m.find()) {
                try {
                    return Charset.forName(m.group(1));
                } catch (RuntimeException e) {
                    // unknown or illegal name: fall through to UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** Readable text of an HTML mail: tags dropped, block ends become line breaks, entities decoded, quoted replies cut. */
    static String htmlToText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String s = COMMENT.matcher(html).replaceAll("");
        s = HIDDEN.matcher(s).replaceAll("");
        String before;
        do {
            before = s;
            s = INNER_QUOTE.matcher(s).replaceAll("");
        } while (!s.equals(before));
        s = LIST_ITEM.matcher(s).replaceAll("- ");
        s = BREAKS.matcher(s).replaceAll("\n");
        s = CELL_END.matcher(s).replaceAll("\t");
        s = TAG.matcher(s).replaceAll("");
        s = decodeEntities(s);
        return tidy(s);
    }

    static String decodeEntities(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String replacement = null;
            try {
                if (name.startsWith("#x") || name.startsWith("#X")) {
                    replacement = new String(Character.toChars(Integer.parseInt(name.substring(2), 16)));
                } else if (name.startsWith("#")) {
                    replacement = new String(Character.toChars(Integer.parseInt(name.substring(1))));
                } else {
                    replacement = NAMED.get(name);
                }
            } catch (IllegalArgumentException e) {
                replacement = null;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? m.group() : replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Trims each line, drops runs of blank lines and control characters. */
    static String tidy(String text) {
        StringBuilder out = new StringBuilder();
        int blank = 0;
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String l = line.replace('\u00a0', ' ').replaceAll("[\\p{Cntrl}&&[^\t]]", "").replaceAll("[ \t]+", " ").strip();
            if (l.isEmpty()) {
                blank++;
                continue;
            }
            if (out.length() > 0) {
                out.append(blank > 0 ? "\n\n" : "\n");
            }
            blank = 0;
            out.append(l);
        }
        return out.toString();
    }

    /**
     * Drops what repeats earlier messages of the thread: lines starting with {@code >}, an "On ... wrote:"
     * line when only quoted lines follow it, and an "-- " signature when it is short and runs to the end.
     * Anything else stays, so a reply written between quoted lines is kept.
     */
    static String trimQuotes(String text) {
        List<String> lines = new ArrayList<>(List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));
        for (int i = 0; i < lines.size(); i++) {
            int span = wroteSpan(lines, i);
            if (span > 0 && onlyQuotesAfter(lines, i + span)) {
                lines = new ArrayList<>(lines.subList(0, i));
                break;
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals("-- ") || lines.get(i).equals("--")) {
                if (lines.size() - i <= 12) {
                    lines = new ArrayList<>(lines.subList(0, i));
                }
                break;
            }
        }
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            if (!line.stripLeading().startsWith(">")) {
                kept.add(line);
            }
        }
        return tidy(String.join("\n", kept));
    }

    /** 1 or 2: how many lines the "On ... wrote:" attribution takes at line i; 0 when there is none. */
    private static int wroteSpan(List<String> lines, int i) {
        String line = lines.get(i).strip();
        if (WROTE_ONE_LINE.matcher(line).matches()) {
            return 1;
        }
        if (i + 1 < lines.size() && WROTE_START.matcher(line).matches()
                && WROTE_END.matcher(lines.get(i + 1).strip()).matches()) {
            return 2;
        }
        return 0;
    }

    private static boolean onlyQuotesAfter(List<String> lines, int from) {
        for (int i = from; i < lines.size(); i++) {
            String l = lines.get(i).strip();
            if (!l.isEmpty() && !l.startsWith(">")) {
                return false;
            }
        }
        return true;
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
