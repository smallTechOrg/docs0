package com.docs0.agent;

import com.docs0.knowledge.Audience;
import com.docs0.knowledge.Document;
import com.docs0.knowledge.KnowledgeSearch;
import com.docs0.knowledge.SearchHit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** In-memory knowledge base for one tenant. Search matches all words, then any word (broadened). */
final class FakeKnowledge implements KnowledgeSearch {

    static final long TENANT = 7L;

    final List<Document> documents = new ArrayList<>();
    final List<String> queries = new ArrayList<>();
    final List<Integer> limits = new ArrayList<>();
    boolean failSearch;

    FakeKnowledge add(long id, String title, String owner, String modified, String body) {
        documents.add(new Document(
                id, TENANT, 1L, "GOOGLE_DRIVE", "ext-" + id, title, "text/plain", "https://docs.example/" + id,
                owner, owner.toLowerCase(Locale.ROOT) + "@example.com", Instant.parse(modified), body));
        return this;
    }

    @Override
    public SearchResult search(long tenantId, Audience audience, String query, int limit) {
        if (failSearch) {
            throw new IllegalStateException("database is down");
        }
        queries.add(query);
        limits.add(limit);
        List<String> words = Arrays.asList(query.toLowerCase(Locale.ROOT).split("\\s+"));
        List<SearchHit> all = documents.stream()
                .filter(d -> d.tenantId() == tenantId && words.stream().allMatch(w -> text(d).contains(w)))
                .map(FakeKnowledge::hit)
                .limit(limit)
                .toList();
        if (!all.isEmpty()) {
            return new SearchResult(all, false);
        }
        List<SearchHit> any = documents.stream()
                .filter(d -> d.tenantId() == tenantId && words.stream().anyMatch(w -> text(d).contains(w)))
                .map(FakeKnowledge::hit)
                .limit(limit)
                .toList();
        return new SearchResult(any, true);
    }

    @Override
    public Optional<Document> findById(long tenantId, Audience audience, long documentId) {
        return documents.stream().filter(d -> d.tenantId() == tenantId && d.id() == documentId).findFirst();
    }

    @Override
    public List<SearchHit> recent(long tenantId, Audience audience, int limit) {
        return documents.stream()
                .filter(d -> d.tenantId() == tenantId)
                .sorted(Comparator.comparing(Document::modifiedAt).reversed())
                .map(FakeKnowledge::hit)
                .limit(limit)
                .toList();
    }

    @Override
    public long count(long tenantId) {
        return documents.stream().filter(d -> d.tenantId() == tenantId).count();
    }

    private static String text(Document d) {
        return (d.title() + " " + d.body()).toLowerCase(Locale.ROOT);
    }

    private static SearchHit hit(Document d) {
        String snippet = d.body().substring(0, Math.min(80, d.body().length()));
        return new SearchHit(d.id(), d.title(), d.sourceKind(), d.url(), d.ownerName(), d.modifiedAt(), snippet, 1.0);
    }
}
