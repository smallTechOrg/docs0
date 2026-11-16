package com.docs0.agent;

import com.docs0.agent.Agent.Citation;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Kind;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.Document;
import com.docs0.knowledge.KnowledgeSearch;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deterministic grounding check; no model call. A citation survives only if its
 * document exists for the tenant and audience and its quote occurs in the body, ignoring
 * differences in whitespace runs, straight versus curly quote marks and letter case.
 */
public final class AnswerVerifier {

    static final int MIN_QUOTE_CHARS = 12;
    static final int MAX_SUGGESTIONS = 3;
    static final String UNVERIFIED_NOTE =
            "_I could not verify this against a source document, so please treat it with caution._";

    /** A marker such as [2] or [1, 3], or a run of them such as [1][3], handled as one unit. */
    private static final String ONE_MARKER = "\\[\\d{1,4}(?:\\s*,\\s*\\d{1,4})*]";
    private static final Pattern MARKER = Pattern.compile(ONE_MARKER + "(?:[ \\t]*" + ONE_MARKER + ")*");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0\\u2007\\u202F]+");

    private final KnowledgeSearch knowledge;

    public AnswerVerifier(KnowledgeSearch knowledge) {
        this.knowledge = knowledge;
    }

    /** What the model submitted. A citation's {@code documentId} is null when it was not a valid id. */
    public record Draft(
            Kind kind,
            String answer,
            Confidence confidence,
            List<DraftCitation> citations,
            List<String> followUps,
            List<String> suggestedPeople) {}

    public record DraftCitation(Long documentId, String quote) {}

    public record Verified(
            Kind kind,
            String answer,
            Confidence confidence,
            List<Citation> citations,
            List<String> followUps,
            List<String> suggestedPeople,
            int droppedCitations) {}

    public Verified verify(Draft draft, long tenantId, Audience audience) {
        return verify(draft, tenantId, audience, false);
    }

    /**
     * As {@link #verify(Draft, long, Audience)}. When {@code drafted} is true the reply
     * announces a drafted issue or card, which no document supports, so lacking citations
     * is not a reason to mark it unverified.
     */
    public Verified verify(Draft draft, long tenantId, Audience audience, boolean drafted) {
        List<DraftCitation> submitted = draft.citations() == null ? List.of() : draft.citations();

        // Position i holds the document when citation i+1 is valid, else null.
        Document[] valid = new Document[submitted.size()];
        if (draft.kind() == Kind.ANSWER) {
            Map<Long, Optional<Document>> lookups = new HashMap<>();
            Map<Long, String> bodies = new HashMap<>();
            for (int i = 0; i < submitted.size(); i++) {
                valid[i] = check(submitted.get(i), tenantId, audience, lookups, bodies);
            }
        }

        int[] newIndex = new int[submitted.size()];
        int[] next = {1};
        String answer = rewriteMarkers(draft.answer() == null ? "" : draft.answer(), valid, newIndex, next);

        // Valid citations the text never referenced keep their place after the referenced ones.
        List<Citation> citations = new ArrayList<>();
        int dropped = 0;
        for (int i = 0; i < submitted.size(); i++) {
            if (valid[i] == null) {
                dropped++;
                continue;
            }
            if (newIndex[i] == 0) {
                newIndex[i] = next[0]++;
            }
            citations.add(new Citation(
                    newIndex[i], valid[i].id(), valid[i].title(), valid[i].url(), submitted.get(i).quote().strip()));
        }
        citations.sort(Comparator.comparingInt(Citation::index));

        Confidence confidence = draft.confidence() == null ? Confidence.LOW : draft.confidence();
        if (draft.kind() == Kind.ANSWER && citations.isEmpty() && !drafted) {
            confidence = Confidence.LOW;
            answer = answer.isEmpty() ? UNVERIFIED_NOTE : answer + "\n\n" + UNVERIFIED_NOTE;
        }

        return new Verified(
                draft.kind(),
                answer,
                confidence,
                List.copyOf(citations),
                clamp(draft.followUps()),
                clamp(draft.suggestedPeople()),
                dropped);
    }

    private Document check(
            DraftCitation citation,
            long tenantId,
            Audience audience,
            Map<Long, Optional<Document>> lookups,
            Map<Long, String> bodies) {
        if (citation == null || citation.documentId() == null || citation.quote() == null) {
            return null;
        }
        String quote = normalise(citation.quote());
        if (quote.length() < MIN_QUOTE_CHARS) {
            return null;
        }
        Optional<Document> document = lookups.computeIfAbsent(
                citation.documentId(), id -> knowledge.findById(tenantId, audience, id));
        if (document.isEmpty() || document.get().body() == null) {
            return null;
        }
        String body = bodies.computeIfAbsent(citation.documentId(), id -> normalise(document.get().body()));
        return body.contains(quote) ? document.get() : null;
    }

    /** Collapses whitespace runs, straightens curly quote marks and lower-cases. */
    static String normalise(String text) {
        String straightened = text
                .replace('‘', '\'')
                .replace('’', '\'')
                .replace('‚', '\'')
                .replace('‛', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replace('„', '"')
                .replace('‟', '"');
        return WHITESPACE.matcher(straightened).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Renumbers markers of valid citations 1..n in order of first appearance and removes the
     * rest, together with the space in front of them so no gap is left behind.
     */
    private static String rewriteMarkers(String text, Document[] valid, int[] newIndex, int[] next) {
        StringBuilder out = new StringBuilder();
        Matcher matcher = MARKER.matcher(text);
        int position = 0;
        boolean skipLeadingSpace = false;
        while (matcher.find()) {
            appendSegment(out, text.substring(position, matcher.start()), skipLeadingSpace);
            position = matcher.end();

            Set<Integer> kept = new LinkedHashSet<>();
            Matcher numbers = NUMBER.matcher(matcher.group());
            while (numbers.find()) {
                int number = Integer.parseInt(numbers.group());
                if (number >= 1 && number <= valid.length && valid[number - 1] != null) {
                    if (newIndex[number - 1] == 0) {
                        newIndex[number - 1] = next[0]++;
                    }
                    kept.add(newIndex[number - 1]);
                }
            }
            if (kept.isEmpty()) {
                trimTrailingBlanks(out);
                // At the start of a line there is nothing to attach the following space to.
                skipLeadingSpace = out.isEmpty() || out.charAt(out.length() - 1) == '\n';
            } else {
                kept.forEach(n -> out.append('[').append(n).append(']'));
                skipLeadingSpace = false;
            }
        }
        appendSegment(out, text.substring(position), skipLeadingSpace);
        return out.toString().strip();
    }

    private static void appendSegment(StringBuilder out, String segment, boolean skipLeadingSpace) {
        if (skipLeadingSpace) {
            int start = 0;
            while (start < segment.length() && isBlank(segment.charAt(start))) {
                start++;
            }
            segment = segment.substring(start);
        }
        out.append(segment);
    }

    private static void trimTrailingBlanks(StringBuilder out) {
        while (!out.isEmpty() && isBlank(out.charAt(out.length() - 1))) {
            out.setLength(out.length() - 1);
        }
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }

    private static List<String> clamp(List<String> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::strip)
                .limit(MAX_SUGGESTIONS)
                .toList();
    }
}
