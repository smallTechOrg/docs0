package com.docs0.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.knowledge.Audience.Member;
import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.source.SourceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/** Who may see a restricted document: every read, against the real database. */
class AccessAwareSearchTest extends IntegrationTest {

    private static final Instant T = Instant.parse("2026-11-01T10:00:00Z");

    @Autowired
    KnowledgeSearch search;

    @Autowired
    DocumentStore store;

    @Autowired
    SourceRepository sources;

    long drive;
    long slack;
    /** Titles by key, and document ids by key. */
    final java.util.Map<String, Long> ids = new java.util.LinkedHashMap<>();

    @BeforeEach
    void documents() {
        drive = sources.findOrCreate(tenant.id(), "GOOGLE_DRIVE", "Google Drive", "SHARED").id();
        slack = sources.findOrCreate(tenant.id(), "SLACK", "Slack history", "SHARED").id();
        store.upsert(tenant.id(), drive, doc("shared", "Okapi handbook", "okapi handbook for everyone"));
        // Two private channels that overlap in U2 and U3.
        store.upsertRestricted(tenant.id(), slack, doc("chanA", "Okapi salary bands", "okapi salary-bands-secret"),
                Set.of("u1", "u2", "u3"));
        store.upsertRestricted(tenant.id(), slack, doc("chanB", "Okapi launch plans", "okapi launch-plans-secret"),
                Set.of("u2", "u3", "u4"));
        // Shared by email.
        store.upsertRestricted(tenant.id(), slack, doc("mail", "Okapi board mail", "okapi board-mail-secret"),
                Set.of("carol@example.com", "dave@example.com"));
        // Mixed: one Slack id and one email.
        store.upsertRestricted(tenant.id(), slack, doc("mixed", "Okapi mixed list", "okapi mixed-list-secret"),
                Set.of("u1", "bob@example.com"));
        jdbc.sql("SELECT id, external_id FROM document WHERE tenant_id = :t").param("t", tenant.id())
                .query(rs -> {
                    ids.put(rs.getString("external_id"), rs.getLong("id"));
                });
    }

    private static DocumentWrite doc(String externalId, String title, String body) {
        return new DocumentWrite(externalId, title, "text/plain", "https://example.com/" + externalId, "Priya", null, T,
                body);
    }

    private static Audience members(Member... members) {
        return new Audience(Set.of(members));
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("public-only audience sees only shared", Audience.sharedOnly(), Set.of("shared")),
                Arguments.of("empty audience sees no restricted document", members(), Set.of("shared")),
                Arguments.of("audience inside the principal set sees it", Audience.of("U1", "U2"),
                        Set.of("shared", "chanA")),
                Arguments.of("one member outside hides it", Audience.of("U1", "U9"), Set.of("shared")),
                Arguments.of("Slack id principal matched (any case)", Audience.of("u2"), Set.of("shared", "chanA", "chanB")),
                Arguments.of("email principal matched through the member's email",
                        members(new Member("U7", "Carol@Example.com"), new Member("U8", "dave@example.com")),
                        Set.of("shared", "mail")),
                Arguments.of("an email principal is not matched by Slack id alone", Audience.of("U7", "U8"),
                        Set.of("shared")),
                Arguments.of("mixed ids and emails",
                        members(new Member("U1", null), new Member("U5", "bob@example.com")), Set.of("shared", "mixed")),
                Arguments.of("mixed, one member only by an unlisted email",
                        members(new Member("U1", null), new Member("U5", "eve@example.com")), Set.of("shared")),
                Arguments.of("two private channels overlap: U2 and U3 see both", Audience.of("U2", "U3"),
                        Set.of("shared", "chanA", "chanB")),
                Arguments.of("adding U1 leaves only the first channel", Audience.of("U1", "U2", "U3"),
                        Set.of("shared", "chanA")),
                Arguments.of("adding U4 leaves only the second channel", Audience.of("U2", "U3", "U4"),
                        Set.of("shared", "chanB")),
                Arguments.of("a member with no id and no email is never covered",
                        members(new Member("", null)), Set.of("shared")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void hiddenDocumentsAreInvisibleEverywhere(String name, Audience audience, Set<String> visible) {
        // search, strict
        assertThat(searchKeys(audience, "okapi", Set.of())).as("search").containsExactlyInAnyOrderElementsOf(visible);
        // search restricted to the Slack source
        Set<String> slackOnly = new LinkedHashSet<>(visible);
        slackOnly.remove("shared");
        assertThat(searchKeys(audience, "okapi", Set.of("SLACK"))).as("source kind filter")
                .containsExactlyInAnyOrderElementsOf(slackOnly);
        // search, broadened to any term
        KnowledgeSearch.SearchResult broad = search.search(tenant.id(), audience, "okapi unicornword", 20);
        assertThat(broad.broadened()).isTrue();
        assertThat(keys(broad.hits())).as("broadened retry").containsExactlyInAnyOrderElementsOf(visible);
        // recent
        assertThat(keys(search.recent(tenant.id(), audience, 20))).as("recent").containsExactlyInAnyOrderElementsOf(visible);
        // findById
        Set<String> readable = new LinkedHashSet<>();
        ids.forEach((key, id) -> {
            if (search.findById(tenant.id(), audience, id).isPresent()) {
                readable.add(key);
            }
        });
        assertThat(readable).as("findById").containsExactlyInAnyOrderElementsOf(visible);
        // count stays tenant-wide
        assertThat(search.count(tenant.id())).isEqualTo(5);

        // Nothing of a hidden document leaks: not its title, not a snippet, not its text.
        Set<String> hidden = new LinkedHashSet<>(ids.keySet());
        hidden.removeAll(visible);
        String everything = String.valueOf(search.search(tenant.id(), audience, "okapi", 20).hits())
                + broad.hits() + search.recent(tenant.id(), audience, 20);
        for (String key : hidden) {
            assertThat(everything).as("no trace of " + key).doesNotContain(secret(key)).doesNotContain(titleOf(key));
        }
    }

    private static String secret(String key) {
        return switch (key) {
            case "chanA" -> "salary-bands-secret";
            case "chanB" -> "launch-plans-secret";
            case "mail" -> "board-mail-secret";
            case "mixed" -> "mixed-list-secret";
            default -> "okapi handbook for everyone";
        };
    }

    private static String titleOf(String key) {
        return switch (key) {
            case "chanA" -> "salary bands";
            case "chanB" -> "launch plans";
            case "mail" -> "board mail";
            case "mixed" -> "mixed list";
            default -> "Okapi handbook";
        };
    }

    @Test
    void aHiddenDocumentCannotBeToldApartFromAMissingOne() {
        long hidden = ids.get("chanA");

        assertThat(search.findById(tenant.id(), Audience.of("U9"), hidden)).isEmpty();
        assertThat(search.findById(tenant.id(), Audience.of("U9"), 987_654L)).isEmpty();
        assertThat(search.findById(tenant.id(), Audience.of("U1"), hidden)).isPresent();
    }

    @Test
    void aNullAudienceIsTreatedAsEmpty() {
        assertThat(search.findById(tenant.id(), null, ids.get("chanA"))).isEmpty();
        assertThat(keys(search.recent(tenant.id(), null, 20))).containsExactly("shared");
    }

    @Test
    void otherTenantsNeverSeeTheDocumentsEvenWithTheSamePrincipals() {
        long other = tenants.ensure("other", "Other").id();
        long otherSource = sources.findOrCreate(other, "SLACK", "Slack history", "SHARED").id();
        store.upsertRestricted(other, otherSource, doc("chanA", "Okapi other tenant", "okapi other-secret"),
                Set.of("u1", "u2", "u3"));

        List<SearchHit> mine = search.search(tenant.id(), Audience.of("U1"), "okapi", 20).hits();
        List<SearchHit> theirs = search.search(other, Audience.of("U1"), "okapi", 20).hits();

        assertThat(mine).extracting(SearchHit::title).doesNotContain("Okapi other tenant");
        assertThat(theirs).extracting(SearchHit::title).containsExactly("Okapi other tenant");
        long theirId = jdbc.sql("SELECT id FROM document WHERE tenant_id = :t").param("t", other).query(Long.class).single();
        assertThat(search.findById(tenant.id(), Audience.of("U1"), theirId)).isEmpty();
        assertThat(search.findById(other, Audience.of("U1"), ids.get("chanA"))).isEmpty();
    }

    @Test
    void aDeletedRestrictedDocumentStaysGone() {
        store.markDeleted(tenant.id(), slack, List.of("chanA"));

        assertThat(searchKeys(Audience.of("U1", "U2", "U3"), "okapi", Set.of())).doesNotContain("chanA");
        assertThat(search.findById(tenant.id(), Audience.of("U1"), ids.get("chanA"))).isEmpty();
    }

    // ---- writing principals ---------------------------------------------------------------------------

    @Test
    void upsertReplacesThePrincipalSet() {
        store.upsertRestricted(tenant.id(), slack, doc("chanA", "Okapi salary bands", "okapi salary-bands-secret"),
                Set.of("u1", "u5"));

        assertThat(principals("chanA")).containsExactlyInAnyOrder("u1", "u5");
        assertThat(searchKeys(Audience.of("U1", "U2"), "okapi", Set.of("SLACK"))).doesNotContain("chanA");
        assertThat(searchKeys(Audience.of("U1", "U5"), "okapi", Set.of("SLACK"))).contains("chanA");
    }

    @Test
    void principalsAreStoredLowercaseAndTrimmedWithoutBlanks() {
        store.upsertRestricted(tenant.id(), slack, doc("chanA", "Okapi salary bands", "okapi salary-bands-secret"),
                Set.of(" U1 ", "Carol@Example.com", " "));

        assertThat(principals("chanA")).containsExactlyInAnyOrder("u1", "carol@example.com");
    }

    @Test
    void anEmptyPrincipalSetHidesTheDocumentFromEveryone() {
        store.upsertRestricted(tenant.id(), slack, doc("chanA", "Okapi salary bands", "okapi salary-bands-secret"),
                Set.of());

        assertThat(searchKeys(Audience.of("U1", "U2", "U3"), "okapi", Set.of("SLACK"))).doesNotContain("chanA");
    }

    @Test
    void refreshAccessUpdatesUnchangedDocumentsByPrefix() {
        store.upsertRestricted(tenant.id(), slack, doc("thread:C1:1.1", "Okapi one", "okapi one"), Set.of("u1", "u2"));
        store.upsertRestricted(tenant.id(), slack, doc("thread:C1:2.2", "Okapi two", "okapi two"), Set.of("u1", "u2"));
        store.upsertRestricted(tenant.id(), slack, doc("thread:C2:3.3", "Okapi three", "okapi three"), Set.of("u1", "u2"));

        // U2 leaves channel C1, U3 joins.
        store.refreshAccess(tenant.id(), slack, "thread:C1:", true, Set.of("u1", "u3"));

        assertThat(principals("thread:C1:1.1")).containsExactlyInAnyOrder("u1", "u3");
        assertThat(principals("thread:C1:2.2")).containsExactlyInAnyOrder("u1", "u3");
        assertThat(principals("thread:C2:3.3")).containsExactlyInAnyOrder("u1", "u2");
        assertThat(searchKeys(Audience.of("U1", "U2"), "okapi", Set.of("SLACK")))
                .doesNotContain("thread:C1:1.1", "thread:C1:2.2").contains("thread:C2:3.3");
        assertThat(searchKeys(Audience.of("U1", "U3"), "okapi", Set.of("SLACK")))
                .contains("thread:C1:1.1", "thread:C1:2.2").doesNotContain("thread:C2:3.3");
    }

    @Test
    void refreshAccessCanOpenADocumentUpToEveryoneAgain() {
        store.refreshAccess(tenant.id(), slack, "chanA", false, Set.of("u1"));

        assertThat(principals("chanA")).isEmpty();
        assertThat(searchKeys(Audience.sharedOnly(), "okapi", Set.of("SLACK"))).contains("chanA");
    }

    @Test
    void anOrdinaryUpsertOfAnExistingRestrictedDocumentMakesItShared() {
        store.upsert(tenant.id(), slack, doc("chanA", "Okapi salary bands", "okapi salary-bands-secret"));

        assertThat(searchKeys(Audience.sharedOnly(), "okapi", Set.of("SLACK"))).contains("chanA");
    }

    @Test
    void documentsStoredBeforeTheAccessModelAreUnrestricted() {
        // A row written without mentioning the new column, as every earlier version did.
        jdbc.sql("""
                        INSERT INTO document (tenant_id, source_id, external_id, title, body)
                        VALUES (:t, :s, 'legacy', 'Okapi legacy page', 'okapi from before access control')
                        """).param("t", tenant.id()).param("s", drive).update();

        assertThat(searchKeys(Audience.sharedOnly(), "okapi", Set.of("GOOGLE_DRIVE"))).contains("legacy");
        assertThat(jdbc.sql("SELECT restricted FROM document WHERE external_id = 'legacy'").query(Boolean.class).single())
                .isFalse();
    }

    // ---- scale -------------------------------------------------------------------------------------------

    @Test
    void anAudienceOfAFewHundredIsEvaluatedQuickly() {
        Set<String> crowd = new LinkedHashSet<>();
        for (int i = 0; i < 400; i++) {
            crowd.add("u" + (1000 + i));
        }
        for (int d = 0; d < 50; d++) {
            store.upsertRestricted(tenant.id(), slack, doc("big" + d, "Okapi big " + d, "okapi big"), crowd);
        }
        Audience audience = Audience.forIds(crowd);

        long start = System.nanoTime();
        List<SearchHit> hits = search.search(tenant.id(), audience, "okapi", 20).hits();
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(hits).hasSize(20);
        assertThat(millis).as("search over 50 restricted documents for 400 people, in ms").isLessThan(2000);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private Set<String> searchKeys(Audience audience, String query, Set<String> kinds) {
        return keys(search.search(tenant.id(), audience, query, 20, kinds).hits());
    }

    /** External ids of hits, found through the document id. */
    private Set<String> keys(List<SearchHit> hits) {
        Set<String> keys = new LinkedHashSet<>();
        for (SearchHit hit : hits) {
            keys.add(jdbc.sql("SELECT external_id FROM document WHERE id = :id").param("id", hit.documentId())
                    .query(String.class).single());
        }
        return keys;
    }

    private List<String> principals(String externalId) {
        return new ArrayList<>(jdbc.sql("""
                        SELECT p.principal FROM document_principal p JOIN document d ON d.id = p.document_id
                        WHERE d.tenant_id = :t AND d.external_id = :e ORDER BY 1
                        """).param("t", tenant.id()).param("e", externalId).query(String.class).list());
    }
}
