package com.docs0.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.agent.Agent.Citation;
import com.docs0.agent.Agent.Confidence;
import com.docs0.agent.Agent.Kind;
import com.docs0.agent.AnswerVerifier.Draft;
import com.docs0.agent.AnswerVerifier.DraftCitation;
import com.docs0.agent.AnswerVerifier.Verified;
import com.docs0.knowledge.Audience;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnswerVerifierTest {

    private final FakeKnowledge knowledge = new FakeKnowledge()
            .add(1, "Pricing 2026", "Priya", "2026-10-03T00:00:00Z",
                    "The Team plan costs $12 per seat per month from January 2026.\n"
                            + "Annual billing gives   a 15% discount.\n"
                            + "We call this the “fair price” rule; it’s reviewed yearly.")
            .add(2, "Refund policy", "Marco", "2026-08-01T00:00:00Z",
                    "Refunds are available within 30 days of purchase, no questions asked.");
    private final AnswerVerifier verifier = new AnswerVerifier(knowledge);

    private Verified verify(Kind kind, String answer, Confidence confidence, DraftCitation... citations) {
        return verifier.verify(
                new Draft(kind, answer, confidence, List.of(citations), List.of(), List.of()),
                FakeKnowledge.TENANT, Audience.sharedOnly());
    }

    private static DraftCitation cite(long documentId, String quote) {
        return new DraftCitation(documentId, quote);
    }

    @Test
    void keepsACitationWhoseQuoteIsInTheDocument() {
        Verified v = verify(Kind.ANSWER, "Team is $12 per seat [1].", Confidence.HIGH,
                cite(1, "The Team plan costs $12 per seat per month"));

        assertThat(v.answer()).isEqualTo("Team is $12 per seat [1].");
        assertThat(v.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(v.citations()).containsExactly(new Citation(
                1, 1L, "Pricing 2026", "https://docs.example/1", "The Team plan costs $12 per seat per month"));
        assertThat(v.droppedCitations()).isZero();
    }

    @Test
    void matchesAcrossWhitespaceCaseAndCurlyQuoteDifferences() {
        Verified v = verify(Kind.ANSWER, "Discount [1], rule [2].", Confidence.HIGH,
                cite(1, "annual billing gives a\n15% DISCOUNT."),
                cite(1, "We call this the \"fair price\" rule; it's reviewed yearly."));

        assertThat(v.citations()).extracting(Citation::index).containsExactly(1, 2);
        assertThat(v.answer()).isEqualTo("Discount [1], rule [2].");
    }

    @Test
    void matchesAStraightQuotedDocumentAgainstACurlyQuotedCitation() {
        knowledge.add(3, "Notes", "Ana", "2026-01-01T00:00:00Z", "The codename is \"Blue Heron\" for now.");

        Verified v = verify(Kind.ANSWER, "It is Blue Heron [1].", Confidence.MEDIUM,
                cite(3, "The codename is “Blue Heron” for now."));

        assertThat(v.citations()).hasSize(1);
    }

    @Test
    void dropsAHallucinatedQuoteAndRenumbersTheRest() {
        Verified v = verify(Kind.ANSWER,
                "Team is $10 per seat [1]. Annual billing is cheaper [2], and refunds take 30 days [3].",
                Confidence.HIGH,
                cite(1, "The Team plan costs $10 per seat per month"),
                cite(1, "Annual billing gives a 15% discount."),
                cite(2, "Refunds are available within 30 days of purchase"));

        assertThat(v.answer())
                .isEqualTo("Team is $10 per seat. Annual billing is cheaper [1], and refunds take 30 days [2].");
        assertThat(v.citations()).extracting(Citation::index).containsExactly(1, 2);
        assertThat(v.citations()).extracting(Citation::documentId).containsExactly(1L, 2L);
        assertThat(v.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(v.droppedCitations()).isEqualTo(1);
    }

    @Test
    void renumbersInOrderOfFirstAppearanceInTheText() {
        Verified v = verify(Kind.ANSWER, "Refunds take 30 days [2]. Team is $12 [1]. Again [2].", Confidence.HIGH,
                cite(1, "The Team plan costs $12 per seat per month"),
                cite(2, "Refunds are available within 30 days of purchase"));

        assertThat(v.answer()).isEqualTo("Refunds take 30 days [1]. Team is $12 [2]. Again [1].");
        assertThat(v.citations()).extracting(Citation::documentId).containsExactly(2L, 1L);
        assertThat(v.citations()).extracting(Citation::index).containsExactly(1, 2);
    }

    @Test
    void aBracketedNumberThatCannotBeACitationOrIsALinkTextStaysAsWritten() {
        Verified v = verify(Kind.ANSWER,
                "In [2024] the Team plan costs $12 [1]. See [1](https://docs.example/1) and [3] too.", Confidence.HIGH,
                cite(1, "The Team plan costs $12 per seat per month"),
                cite(2, "Refunds are available within 30 days of purchase"));

        // [2024] and [3] are beyond the 2 submitted citations; [1](...) is a markdown link, not a marker.
        assertThat(v.answer()).isEqualTo(
                "In [2024] the Team plan costs $12 [1]. See [1](https://docs.example/1) and [3] too.");
        assertThat(v.citations()).extracting(Citation::index).containsExactly(1, 2);
    }

    @Test
    void twoCitationsOfTheSameDocumentAndQuoteAreOneCitation() {
        Verified v = verify(Kind.ANSWER, "Team is $12 [1]. Again $12 [2]. Refunds take 30 days [3].", Confidence.HIGH,
                cite(1, "The Team plan costs $12 per seat per month"),
                cite(1, "the team plan costs $12 per  seat per month"),
                cite(2, "Refunds are available within 30 days of purchase"));

        assertThat(v.answer()).isEqualTo("Team is $12 [1]. Again $12 [1]. Refunds take 30 days [2].");
        assertThat(v.citations()).extracting(Citation::documentId).containsExactly(1L, 2L);
        assertThat(v.citations()).extracting(Citation::index).containsExactly(1, 2);
        assertThat(v.droppedCitations()).isZero();
    }

    @Test
    void removesDroppedMarkersWithoutLeavingGapsOrBrackets() {
        Verified v = verify(Kind.ANSWER,
                "[1] First claim [1][2]. Second [1, 2] here [2] and [9] there.\n[1] Line two [2] .",
                Confidence.MEDIUM,
                cite(99, "this document does not exist at all"),
                cite(2, "Refunds are available within 30 days of purchase"));

        // [9] is not a citation marker (only 2 were submitted): it stays as written.
        assertThat(v.answer()).isEqualTo("First claim [1]. Second [1] here [1] and [9] there.\nLine two [1] .");
        assertThat(v.answer()).doesNotContain("  ").doesNotContain("[]").doesNotContain("[,");
    }

    @Test
    void anAnswerWithNoValidCitationsIsLowConfidenceWithANote() {
        Verified v = verify(Kind.ANSWER, "Team is $99 per seat [1] or so [2].", Confidence.HIGH,
                cite(1, "The Team plan costs $99 per seat"),
                cite(42, "Refunds are available within 30 days of purchase"));

        assertThat(v.citations()).isEmpty();
        assertThat(v.confidence()).isEqualTo(Confidence.LOW);
        assertThat(v.answer()).isEqualTo("Team is $99 per seat or so.\n\n" + AnswerVerifier.UNVERIFIED_NOTE);
        assertThat(v.droppedCitations()).isEqualTo(2);
    }

    @Test
    void aDocumentOfAnotherTenantDoesNotCount() {
        Verified v = verifier.verify(
                new Draft(Kind.ANSWER, "Refunds [1].", Confidence.HIGH,
                        List.of(cite(2, "Refunds are available within 30 days of purchase")), List.of(), List.of()),
                FakeKnowledge.TENANT + 1, Audience.sharedOnly());

        assertThat(v.citations()).isEmpty();
        assertThat(v.confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    void veryShortQuotesAreInvalid() {
        Verified v = verify(Kind.ANSWER, "Refunds [1].", Confidence.HIGH, cite(2, "Refunds are"));

        assertThat(v.citations()).isEmpty();
    }

    @Test
    void malformedCitationsKeepTheirPositionAndAreDropped() {
        Verified v = verify(Kind.ANSWER, "A [1] B [2] C [3].", Confidence.HIGH,
                new DraftCitation(null, "Refunds are available within 30 days"),
                new DraftCitation(2L, null),
                cite(2, "Refunds are available within 30 days"));

        assertThat(v.answer()).isEqualTo("A B C [1].");
        assertThat(v.citations()).extracting(Citation::documentId).containsExactly(2L);
    }

    @Test
    void validCitationsNotReferencedInTheTextAreKeptAfterTheReferencedOnes() {
        Verified v = verify(Kind.ANSWER, "Refunds take 30 days [2].", Confidence.HIGH,
                cite(1, "The Team plan costs $12 per seat per month"),
                cite(2, "Refunds are available within 30 days of purchase"));

        assertThat(v.answer()).isEqualTo("Refunds take 30 days [1].");
        assertThat(v.citations()).extracting(Citation::documentId).containsExactly(2L, 1L);
    }

    @Test
    void clarifyAndUnknownCarryNoCitationsAndKeepTheirConfidence() {
        for (Kind kind : List.of(Kind.CLARIFY, Kind.UNKNOWN)) {
            Verified v = verify(kind, "Which plan do you mean [1]?", Confidence.MEDIUM,
                    cite(1, "The Team plan costs $12 per seat per month"));

            assertThat(v.kind()).isEqualTo(kind);
            assertThat(v.citations()).isEmpty();
            assertThat(v.answer()).isEqualTo("Which plan do you mean?");
            assertThat(v.confidence()).isEqualTo(Confidence.MEDIUM);
        }
    }

    @Test
    void clampsFollowUpsAndPeopleToThreeAndDropsBlanks() {
        Verified v = verifier.verify(
                new Draft(Kind.UNKNOWN, "Not found.", Confidence.LOW, List.of(),
                        Arrays.asList("a?", " ", null, " b? ", "c?", "d?"),
                        Arrays.asList("", "Priya", "Marco", "Ana", "Lee")),
                FakeKnowledge.TENANT, Audience.sharedOnly());

        assertThat(v.followUps()).containsExactly("a?", "b?", "c?");
        assertThat(v.suggestedPeople()).containsExactly("Priya", "Marco", "Ana");
    }
}
