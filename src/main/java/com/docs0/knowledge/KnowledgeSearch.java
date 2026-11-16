package com.docs0.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Read side of the knowledge base, as used by the agent's tools. Every call is scoped to
 * a tenant and to the audience that will see the answer. Deleted documents never appear.
 */
public interface KnowledgeSearch {

    /** One search over the keyword index. */
    record SearchResult(List<SearchHit> hits, boolean broadened) {}

    /**
     * Keyword search, best match first. If the query matches nothing with all terms
     * required, it is retried matching any term and {@code broadened} is true.
     */
    SearchResult search(long tenantId, Audience audience, String query, int limit);

    /**
     * As {@link #search(long, Audience, String, int)}, but only documents of the given source
     * kinds (GOOGLE_DRIVE, LINEAR, TRELLO). An empty set means every kind. The default filters
     * after the search; the database implementation filters in the query.
     */
    default SearchResult search(long tenantId, Audience audience, String query, int limit, Set<String> sourceKinds) {
        SearchResult result = search(tenantId, audience, query, limit);
        if (sourceKinds == null || sourceKinds.isEmpty()) {
            return result;
        }
        return new SearchResult(result.hits().stream().filter(h -> sourceKinds.contains(h.sourceKind())).toList(),
                result.broadened());
    }

    Optional<Document> findById(long tenantId, Audience audience, long documentId);

    /** Most recently modified documents, newest first. Snippet is the start of the body. */
    List<SearchHit> recent(long tenantId, Audience audience, int limit);

    /** Number of live (not deleted) documents for the tenant. */
    long count(long tenantId);
}
