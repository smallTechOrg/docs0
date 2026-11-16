package com.docs0.agent;

import com.docs0.agent.Agent.ProgressListener;
import com.docs0.agent.LlmClient.ToolSpec;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.Document;
import com.docs0.knowledge.KnowledgeSearch;
import com.docs0.knowledge.SearchHit;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tools the model can call: their declarations and the three that read the knowledge
 * base. Every failure comes back as an error result the model can recover from. Tenant and
 * audience are supplied by the server, never by the model.
 */
final class KnowledgeTools {

    static final String SEARCH = "search_knowledge";
    static final String READ = "read_document";
    static final String RECENT = "list_recent_documents";
    static final String SUBMIT = "submit_answer";

    static final int PAGE_CHARS = 60_000;
    static final int SEARCH_DEFAULT_LIMIT = 8;
    static final int RECENT_DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 20;

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTools.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    /** Text of a tool result and whether it is an error. */
    record Output(String content, boolean isError) {

        static Output ok(String content) {
            return new Output(content, false);
        }

        static Output error(String content) {
            return new Output(content, true);
        }
    }

    static final List<ToolSpec> SPECS = List.of(
            new ToolSpec(
                    SEARCH,
                    "Keyword search over the company's documents, best match first. Use this first for any "
                            + "question, and again with different wording before concluding something is not "
                            + "written down. Returns for each hit a document_id, title, source, modified date, "
                            + "owner, url and a short snippet. Snippets are for choosing what to read, not for "
                            + "quoting. If nothing matches all the words, the search is retried matching any "
                            + "word and the result says so.",
                    obj(
                            "query", obj(
                                    "type", "string",
                                    "description", "A few distinctive keywords or a short phrase that would "
                                            + "appear in the document, e.g. \"pricing 2026\" or \"parental leave\"."),
                            "limit", obj(
                                    "type", "integer",
                                    "description", "Maximum number of hits. Default 8, maximum 20.")),
                    List.of("query"),
                    false),
            new ToolSpec(
                    READ,
                    "Read the text of one document by its document_id from a search or recent-documents "
                            + "result. Call this before relying on or quoting a document. Returns the title, "
                            + "metadata and up to 60,000 characters of body starting at offset, with has_more "
                            + "and next_offset for reading further pages of a long document.",
                    obj(
                            "document_id", obj(
                                    "type", "integer",
                                    "description", "The document_id shown in a search or recent-documents result."),
                            "offset", obj(
                                    "type", "integer",
                                    "description", "Character offset to start from. Default 0; use next_offset "
                                            + "from the previous page to continue.")),
                    List.of("document_id"),
                    false),
            new ToolSpec(
                    RECENT,
                    "List the most recently modified documents, newest first. Use this for questions about "
                            + "what is new or what changed lately, where there are no keywords to search for.",
                    obj("limit", obj(
                            "type", "integer",
                            "description", "Maximum number of documents. Default 10, maximum 20.")),
                    List.of(),
                    false),
            new ToolSpec(
                    SUBMIT,
                    "Deliver the final reply to the user and end the turn. Always finish with this tool, "
                            + "whether you found the answer, need a clarification or found nothing.",
                    obj(
                            "kind", obj(
                                    "type", "string",
                                    "enum", List.of("answer", "clarify", "unknown"),
                                    "description", "answer: supported by documents. clarify: you need the user "
                                            + "to choose between readings. unknown: the documents do not say."),
                            "answer", obj(
                                    "type", "string",
                                    "description", "The reply in Slack formatting. For answer, the direct answer "
                                            + "first with [1], [2] markers after the claims they support. For "
                                            + "clarify, one clarifying question. For unknown, what you searched "
                                            + "for and did not find."),
                            "confidence", obj(
                                    "type", "string",
                                    "enum", List.of("high", "medium", "low"),
                                    "description", "How well the documents support the reply."),
                            "citations", obj(
                                    "type", "array",
                                    "description", "Sources for kind answer, in the order of their [n] markers. "
                                            + "Empty for clarify and unknown.",
                                    "items", obj(
                                            "type", "object",
                                            "properties", obj(
                                                    "document_id", obj(
                                                            "type", "integer",
                                                            "description", "The document the quote comes from."),
                                                    "quote", obj(
                                                            "type", "string",
                                                            "description", "One continuous passage copied exactly "
                                                                    + "from the document body, at least a clause "
                                                                    + "long.")),
                                            "required", List.of("document_id", "quote"),
                                            "additionalProperties", false)),
                            "follow_ups", obj(
                                    "type", "array",
                                    "description", "Up to 3 next questions the documents could answer.",
                                    "items", obj("type", "string")),
                            "suggested_people", obj(
                                    "type", "array",
                                    "description", "Up to 3 document owners worth asking, when confidence is low "
                                            + "or kind is unknown. Otherwise empty.",
                                    "items", obj("type", "string"))),
                    List.of("kind", "answer", "confidence", "citations", "follow_ups", "suggested_people"),
                    true));

    /** An insertion-ordered JSON object, so the tool definitions serialise identically on every start. */
    private static Map<String, Object> obj(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    private final KnowledgeSearch knowledge;

    KnowledgeTools(KnowledgeSearch knowledge) {
        this.knowledge = knowledge;
    }

    /** Runs one of the three knowledge tools, announcing it on {@code progress} first. */
    Output run(String name, Map<String, Object> input, long tenantId, Audience audience, ProgressListener progress) {
        if (!List.of(SEARCH, READ, RECENT).contains(name)) {
            return Output.error("There is no tool named \"" + name + "\". The tools are " + SEARCH + ", " + READ
                    + ", " + RECENT + " and " + SUBMIT + ".");
        }
        if (input == null) {
            return Output.error("The input for " + name + " was not a JSON object. Send an object with the "
                    + "tool's fields.");
        }
        try {
            return switch (name) {
                case SEARCH -> search(input, tenantId, audience, progress);
                case READ -> read(input, tenantId, audience, progress);
                default -> recent(input, tenantId, audience, progress);
            };
        } catch (RuntimeException e) {
            log.warn("tool {} failed for tenant {}: {}", name, tenantId, e.getClass().getSimpleName());
            return Output.error("The tool " + name + " failed (" + e.getClass().getSimpleName()
                    + "). You can try again, or answer from what you already have.");
        }
    }

    private Output search(Map<String, Object> input, long tenantId, Audience audience, ProgressListener progress) {
        if (!(input.get("query") instanceof String query) || query.isBlank()) {
            return Output.error("search_knowledge needs a non-empty string \"query\".");
        }
        query = query.strip();
        Output badLimit = checkOptionalInteger(input, "limit");
        if (badLimit != null) {
            return badLimit;
        }
        int limit = clampLimit(asLong(input.get("limit")), SEARCH_DEFAULT_LIMIT);

        progress.onStatus("Searching for “" + shorten(query, 80) + "”…");
        KnowledgeSearch.SearchResult result = knowledge.search(tenantId, audience, query, limit);
        log.info("search_knowledge tenant={} hits={} broadened={}", tenantId, result.hits().size(), result.broadened());

        if (result.hits().isEmpty()) {
            return Output.ok("No documents matched \"" + query + "\", even after broadening the search to match "
                    + "any of the words. Try different or fewer keywords.");
        }
        StringBuilder out = new StringBuilder();
        out.append("Search results for \"").append(query).append("\": ").append(result.hits().size()).append(" hit(s), best match first.\n");
        if (result.broadened()) {
            out.append("Note: no document contains all of these words, so the search was broadened to match "
                    + "any of them. These results may be only loosely related.\n");
        }
        appendHits(out, result.hits());
        return Output.ok(out.toString());
    }

    private Output read(Map<String, Object> input, long tenantId, Audience audience, ProgressListener progress) {
        Long id = asLong(input.get("document_id"));
        if (id == null) {
            return Output.error("read_document needs an integer \"document_id\" taken from a search or "
                    + "recent-documents result.");
        }
        Output badOffset = checkOptionalInteger(input, "offset");
        if (badOffset != null) {
            return badOffset;
        }
        Long requested = asLong(input.get("offset"));
        long offset = requested == null ? 0 : requested;
        if (offset < 0) {
            return Output.error("\"offset\" must be 0 or greater.");
        }

        Optional<Document> found = knowledge.findById(tenantId, audience, id);
        if (found.isEmpty()) {
            progress.onStatus("Reading a document…");
            return Output.error("No document with document_id " + id + " exists. Use a document_id exactly as "
                    + "shown in a search or recent-documents result.");
        }
        Document document = found.get();
        progress.onStatus("Reading *" + shorten(orDash(document.title()), 80) + "*…");

        String body = document.body() == null ? "" : document.body();
        if (offset > 0 && offset >= body.length()) {
            return Output.error("offset " + offset + " is past the end of document " + id + ", which has "
                    + body.length() + " characters.");
        }
        int start = (int) offset;
        int end = Math.min(body.length(), start + PAGE_CHARS);
        boolean hasMore = end < body.length();
        log.info("read_document tenant={} document={} offset={} chars={}", tenantId, id, start, end - start);

        StringBuilder out = new StringBuilder();
        out.append("document_id: ").append(document.id()).append('\n');
        out.append("title: ").append(orDash(document.title())).append('\n');
        out.append("source: ").append(orDash(document.sourceKind())).append('\n');
        out.append("modified: ").append(day(document.modifiedAt())).append('\n');
        out.append("owner: ").append(owner(document)).append('\n');
        out.append("url: ").append(orDash(document.url())).append('\n');
        out.append("total_chars: ").append(body.length()).append('\n');
        out.append("offset: ").append(start).append('\n');
        out.append("has_more: ").append(hasMore).append('\n');
        if (hasMore) {
            out.append("next_offset: ").append(end).append('\n');
        }
        out.append("--- body, characters ").append(start).append(" to ").append(end).append(" ---\n");
        out.append(body, start, end);
        return Output.ok(out.toString());
    }

    private Output recent(Map<String, Object> input, long tenantId, Audience audience, ProgressListener progress) {
        Output badLimit = checkOptionalInteger(input, "limit");
        if (badLimit != null) {
            return badLimit;
        }
        int limit = clampLimit(asLong(input.get("limit")), RECENT_DEFAULT_LIMIT);

        progress.onStatus("Checking recent documents…");
        List<SearchHit> hits = knowledge.recent(tenantId, audience, limit);
        log.info("list_recent_documents tenant={} hits={}", tenantId, hits.size());

        if (hits.isEmpty()) {
            return Output.ok("There are no documents in the knowledge base yet.");
        }
        StringBuilder out = new StringBuilder();
        out.append("The ").append(hits.size()).append(" most recently modified document(s), newest first.\n");
        appendHits(out, hits);
        return Output.ok(out.toString());
    }

    private static void appendHits(StringBuilder out, List<SearchHit> hits) {
        int position = 1;
        for (SearchHit hit : hits) {
            out.append('\n');
            out.append(position++).append(". document_id: ").append(hit.documentId()).append('\n');
            out.append("   title: ").append(orDash(hit.title())).append('\n');
            out.append("   source: ").append(orDash(hit.sourceKind())).append('\n');
            out.append("   modified: ").append(day(hit.modifiedAt())).append('\n');
            out.append("   owner: ").append(orDash(hit.ownerName())).append('\n');
            out.append("   url: ").append(orDash(hit.url())).append('\n');
            out.append("   snippet: ").append(orDash(hit.snippet()).replaceAll("\\s+", " ")).append('\n');
        }
    }

    private static Output checkOptionalInteger(Map<String, Object> input, String field) {
        if (input.get(field) != null && asLong(input.get(field)) == null) {
            return Output.error("\"" + field + "\" must be an integer.");
        }
        return null;
    }

    /** A whole number from a JSON value, or null. Accepts 12, 12.0 and "12". */
    static Long asLong(Object value) {
        if (value instanceof Number number) {
            double d = number.doubleValue();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.valueOf(number.longValue()) : null;
        }
        if (value instanceof String text) {
            try {
                return Long.valueOf(text.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static int clampLimit(Long requested, int fallback) {
        if (requested == null) {
            return fallback;
        }
        return (int) Math.max(1, Math.min(MAX_LIMIT, requested));
    }

    private static String owner(Document document) {
        boolean hasName = document.ownerName() != null && !document.ownerName().isBlank();
        boolean hasEmail = document.ownerEmail() != null && !document.ownerEmail().isBlank();
        if (hasName && hasEmail) {
            return document.ownerName() + " <" + document.ownerEmail() + ">";
        }
        return hasName ? document.ownerName() : hasEmail ? document.ownerEmail() : "-";
    }

    private static String day(Instant instant) {
        return instant == null ? "-" : DAY.format(instant);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1).stripTrailing() + "…";
    }
}
