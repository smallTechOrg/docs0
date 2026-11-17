package com.docs0.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.knowledge.KnowledgeSearch.SearchResult;
import com.docs0.source.SourceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcKnowledgeSearchTest extends IntegrationTest {

    private static final Audience ANYONE = Audience.sharedOnly();
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    @Autowired
    KnowledgeSearch search;

    @Autowired
    DocumentStore store;

    @Autowired
    SourceRepository sources;

    long sourceId;

    @BeforeEach
    void createSource() {
        sourceId = sources.findOrCreate(tenant.id(), "GOOGLE_DRIVE", "Google Drive", "SHARED").id();
    }

    @Test
    void theSnippetOfAMatchFarIntoALongDocumentIsHighlightedAsFarAsTheIndexReaches() {
        // 300,000 characters before the only match: inside the 400,000 the index covers.
        put("long", "Long report", "filler ".repeat(50_000) + "zebracrossing", T0);

        SearchResult result = search.search(tenant.id(), ANYONE, "zebracrossing", 8);

        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).snippet()).contains("**zebracrossing**");
    }

    @Test
    void titleMatchOutranksBodyOnlyMatch() {
        put("body", "Team handbook", "Somewhere in here we mention the vacation policy once.", T0);
        put("title", "Vacation policy", "How much time off people get and how to book it.", T0);
        put("none", "Lunch menu", "Soup on Mondays.", T0);

        SearchResult result = search.search(tenant.id(), ANYONE, "vacation", 8);

        assertThat(result.broadened()).isFalse();
        assertThat(result.hits()).extracting(SearchHit::title).containsExactly("Vacation policy", "Team handbook");
        assertThat(result.hits().get(0).rank()).isGreaterThan(result.hits().get(1).rank());
        assertThat(result.hits().get(0).sourceKind()).isEqualTo("GOOGLE_DRIVE");
    }

    @Test
    void snippetHighlightsTheMatchedTermInPlainTextAndStaysShort() {
        String filler = "Lorem ipsum dolor sit amet consectetur adipiscing elit. ".repeat(200);
        put("d", "Onboarding", filler + "Laptops are ordered through the procurement portal. " + filler, T0);

        SearchHit hit = search.search(tenant.id(), ANYONE, "procurement", 8).hits().get(0);

        assertThat(hit.snippet()).contains("**procurement**");
        assertThat(hit.snippet()).doesNotContain("<b>");
        assertThat(hit.snippet().length()).isLessThan(600);
        assertThat(hit.url()).isEqualTo("https://drive.example/d");
        assertThat(hit.ownerName()).isEqualTo("Asha");
        assertThat(hit.modifiedAt()).isEqualTo(T0);
    }

    @Test
    void searchNeverCrossesTenants() {
        long other = tenants.ensure("other", "Other Co").id();
        long otherSource = sources.findOrCreate(other, "GOOGLE_DRIVE", "Google Drive", "SHARED").id();
        store.upsert(other, otherSource, write("x", "Secret roadmap", "Their roadmap.", T0));
        put("mine", "Our roadmap", "Our roadmap.", T0);

        assertThat(search.search(tenant.id(), ANYONE, "roadmap", 8).hits())
                .extracting(SearchHit::title).containsExactly("Our roadmap");
        assertThat(search.search(other, ANYONE, "roadmap", 8).hits())
                .extracting(SearchHit::title).containsExactly("Secret roadmap");
        assertThat(search.count(tenant.id())).isEqualTo(1);
        assertThat(search.recent(tenant.id(), ANYONE, 10)).hasSize(1);

        long othersDocument = search.search(other, ANYONE, "roadmap", 8).hits().get(0).documentId();
        assertThat(search.findById(tenant.id(), ANYONE, othersDocument)).isEmpty();
        assertThat(search.findById(other, ANYONE, othersDocument)).isPresent();
    }

    @Test
    void deletedDocumentsAreExcludedEverywhere() {
        put("gone", "Expense policy", "Old expense rules.", T0);
        put("live", "Expense policy 2026", "Current expense rules.", T0);
        long goneId = idOf("gone");

        assertThat(store.markDeleted(tenant.id(), sourceId, List.of("gone"))).isEqualTo(1);

        assertThat(search.search(tenant.id(), ANYONE, "expense", 8).hits())
                .extracting(SearchHit::title).containsExactly("Expense policy 2026");
        assertThat(search.findById(tenant.id(), ANYONE, goneId)).isEmpty();
        assertThat(search.recent(tenant.id(), ANYONE, 10)).extracting(SearchHit::title)
                .containsExactly("Expense policy 2026");
        assertThat(search.count(tenant.id())).isEqualTo(1);
    }

    @Test
    void retriesWithAnyTermWhenNoDocumentHasAllTerms() {
        put("a", "Parking", "Parking permits are issued by facilities.", T0);
        put("b", "Lunch", "Soup on Mondays.", T0);

        SearchResult result = search.search(tenant.id(), ANYONE, "parking zeppelin", 8);

        assertThat(result.broadened()).isTrue();
        assertThat(result.hits()).extracting(SearchHit::title).containsExactly("Parking");
    }

    @Test
    void doesNotBroadenWhenAllTermsMatch() {
        put("a", "Parking", "Parking permits are issued by facilities.", T0);

        SearchResult result = search.search(tenant.id(), ANYONE, "parking permits", 8);

        assertThat(result.broadened()).isFalse();
        assertThat(result.hits()).hasSize(1);
    }

    @Test
    void blankStopWordOnlyAndOddQueriesReturnNoHitsWithoutError() {
        put("a", "The plan", "This is the plan and it is what it is.", T0);

        for (String q : new String[] {null, "", "   ", "the", "and the of", "\"", "!!! ???", "'", "a | & !", "-", "\u0000"}) {
            SearchResult result = search.search(tenant.id(), ANYONE, q, 8);
            assertThat(result.hits()).as("query [%s]", q).isEmpty();
            assertThat(result.broadened()).as("query [%s]", q).isFalse();
        }
        // Punctuation that is special in tsquery syntax survives the broadened retry too.
        assertThat(search.search(tenant.id(), ANYONE, "o'brien's zeppelin:* (x\\y)", 8).hits()).isEmpty();
    }

    @Test
    void limitIsClampedBetweenOneAndTwenty() {
        for (int i = 0; i < 25; i++) {
            put("d" + i, "Release notes " + i, "Release notes for build " + i, T0.plusSeconds(i));
        }

        assertThat(search.search(tenant.id(), ANYONE, "release", 0).hits()).hasSize(1);
        assertThat(search.search(tenant.id(), ANYONE, "release", 5).hits()).hasSize(5);
        assertThat(search.search(tenant.id(), ANYONE, "release", 500).hits()).hasSize(20);
        assertThat(search.recent(tenant.id(), ANYONE, 500)).hasSize(20);
    }

    @Test
    void recentIsNewestFirstWithTheStartOfTheBodyAsSnippet() {
        put("old", "Old", "Oldest body.", T0);
        put("new", "New", "Newest   body\nwith a line break. " + "x".repeat(1000), T0.plusSeconds(120));
        put("mid", "Mid", "Middle body.", T0.plusSeconds(60));
        put("undated", "Undated", "No modified time.", null);

        List<SearchHit> recent = search.recent(tenant.id(), ANYONE, 10);

        assertThat(recent).extracting(SearchHit::title).containsExactly("New", "Mid", "Old", "Undated");
        assertThat(recent.get(0).snippet()).startsWith("Newest body with a line break.");
        assertThat(recent.get(0).snippet().length()).isLessThanOrEqualTo(300);
        assertThat(search.recent(tenant.id(), ANYONE, 2)).extracting(SearchHit::title).containsExactly("New", "Mid");
    }

    @Test
    void countAndFindByIdReturnLiveDocuments() {
        assertThat(search.count(tenant.id())).isZero();
        put("a", "Alpha", "Alpha body.", T0);
        put("b", "Beta", "Beta body.", T0);

        assertThat(search.count(tenant.id())).isEqualTo(2);
        Document doc = search.findById(tenant.id(), ANYONE, idOf("a")).orElseThrow();
        assertThat(doc.title()).isEqualTo("Alpha");
        assertThat(doc.body()).isEqualTo("Alpha body.");
        assertThat(doc.sourceKind()).isEqualTo("GOOGLE_DRIVE");
        assertThat(doc.sourceId()).isEqualTo(sourceId);
        assertThat(doc.externalId()).isEqualTo("a");
        assertThat(doc.mimeType()).isEqualTo("text/plain");
        assertThat(doc.ownerEmail()).isEqualTo("asha@example.com");
        assertThat(doc.modifiedAt()).isEqualTo(T0);
        assertThat(search.findById(tenant.id(), ANYONE, 999_999)).isEmpty();
    }

    @Test
    void upsertReplacesContentUndeletesAndStripsNulCharacters() {
        put("a", "Draft", "first version about giraffes", T0);
        long id = idOf("a");
        store.markDeleted(tenant.id(), sourceId, List.of("a"));
        assertThat(store.modifiedTimes(tenant.id(), sourceId)).isEmpty();

        store.upsert(tenant.id(), sourceId, write("a", "Fi\u0000nal", "second \u0000version about zebras", T0.plusSeconds(5)));

        assertThat(idOf("a")).isEqualTo(id);
        Document doc = search.findById(tenant.id(), ANYONE, id).orElseThrow();
        assertThat(doc.title()).isEqualTo("Final");
        assertThat(doc.body()).isEqualTo("second version about zebras");
        assertThat(search.search(tenant.id(), ANYONE, "giraffes", 8).hits()).isEmpty();
        assertThat(search.search(tenant.id(), ANYONE, "zebras", 8).hits()).hasSize(1);
        assertThat(store.modifiedTimes(tenant.id(), sourceId)).isEqualTo(Map.of("a", T0.plusSeconds(5)));

        Map<String, Object> row = jdbc.sql("SELECT body_chars, content_hash, deleted FROM document WHERE id = :id")
                .param("id", id).query().singleRow();
        assertThat(row.get("body_chars")).isEqualTo("second version about zebras".length());
        assertThat((String) row.get("content_hash")).hasSize(64);
        assertThat(row.get("deleted")).isEqualTo(false);
    }

    private void put(String externalId, String title, String body, Instant modifiedAt) {
        store.upsert(tenant.id(), sourceId, write(externalId, title, body, modifiedAt));
    }

    private static DocumentWrite write(String externalId, String title, String body, Instant modifiedAt) {
        return new DocumentWrite(externalId, title, "text/plain", "https://drive.example/" + externalId, "Asha",
                "asha@example.com", modifiedAt, body);
    }

    private long idOf(String externalId) {
        return jdbc.sql("SELECT id FROM document WHERE tenant_id = :t AND external_id = :e")
                .param("t", tenant.id()).param("e", externalId).query(Long.class).single();
    }
}
