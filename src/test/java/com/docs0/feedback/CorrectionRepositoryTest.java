package com.docs0.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.TestData;
import com.docs0.TestDatabase;
import com.docs0.agent.CorrectionSource.ApprovedCorrection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Full-text matching of approved corrections, and the review transitions, on the real database. */
class CorrectionRepositoryTest {

    private final JdbcClient jdbc = TestDatabase.jdbc();
    private final CorrectionRepository repo = new CorrectionRepository(jdbc);
    private long tenant;

    @BeforeEach
    void setUp() {
        tenant = TestDatabase.resetWithTenant();
    }

    private long add(String status, String question, String text, String edited) {
        return TestData.correction(jdbc, tenant, status, "Arjun", question, text, edited, false);
    }

    @Test
    void findsOnlyApprovedCorrections() {
        long approved = add("APPROVED", "What does the Team plan cost?", "The Team plan costs $14 per seat.", null);
        add("PENDING", "What does the Team plan cost?", "The Team plan costs $99 per seat.", null);
        add("REJECTED", "What does the Team plan cost?", "The Team plan costs $1 per seat.", null);

        List<ApprovedCorrection> found = repo.find(tenant, "How much is the Team plan?", 3);

        assertThat(found).extracting(ApprovedCorrection::id).containsExactly(approved);
        assertThat(found.get(0).fact()).isEqualTo("The Team plan costs $14 per seat.");
        assertThat(found.get(0).authorName()).isEqualTo("Arjun");
        assertThat(found.get(0).question()).isEqualTo("What does the Team plan cost?");
        assertThat(found.get(0).correctedAt()).isNotNull();
    }

    @Test
    void anotherTenantsCorrectionsAreNeverFound() {
        long other = jdbc.sql("INSERT INTO tenant (slug, name) VALUES ('other', 'Other') RETURNING id")
                .query(Long.class).single();
        TestData.correction(jdbc, other, "APPROVED", "Zed", "Team plan price?", "The Team plan costs $1.", null,
                false);

        assertThat(repo.find(tenant, "Team plan price?", 3)).isEmpty();
        assertThat(repo.find(other, "Team plan price?", 3)).hasSize(1);
    }

    @Test
    void theEditedTextIsPreferredAndItIsWhatIsSearched() {
        add("APPROVED", "Parental leave?", "Leave is 12 weeks.", "Parental leave is 16 weeks for everyone.");

        List<ApprovedCorrection> found = repo.find(tenant, "How long is parental leave?", 3);

        assertThat(found).singleElement()
                .satisfies(c -> assertThat(c.fact()).isEqualTo("Parental leave is 16 weeks for everyone."));
        // A word that is only in the replaced extracted text no longer matches.
        assertThat(repo.find(tenant, "12", 3)).isEmpty();
        assertThat(repo.find(tenant, "everyone", 3)).hasSize(1);
    }

    @Test
    void bestMatchComesFirstAndNoMoreThanTheLimitAreReturned() {
        long weak = add("APPROVED", "Office wifi", "The guest wifi password changed.", null);
        long best = add("APPROVED", "Refund window for monthly plans",
                "Monthly plan refund window is 21 days.", null);
        long middle = add("APPROVED", "Refund policy", "Refunds go through billing.", null);
        add("APPROVED", "Refund wifi", "Another refund wifi fact.", null);

        List<ApprovedCorrection> found = repo.find(tenant, "refund window monthly plan", 3);

        assertThat(found).hasSize(3);
        assertThat(found.get(0).id()).isEqualTo(best);
        assertThat(found).extracting(ApprovedCorrection::id).doesNotContain(weak);
        assertThat(found).extracting(ApprovedCorrection::id).contains(middle);
        assertThat(repo.find(tenant, "refund window monthly plan", 1)).hasSize(1);
    }

    @Test
    void questionsWithNothingToMatchOrNothingSearchableFindNothing() {
        add("APPROVED", "Team plan price", "The Team plan costs $14.", null);

        assertThat(repo.find(tenant, "completely unrelated topic", 3)).isEmpty();
        assertThat(repo.find(tenant, "what is the", 3)).isEmpty();
        assertThat(repo.find(tenant, "   ", 3)).isEmpty();
        assertThat(repo.find(tenant, null, 3)).isEmpty();
    }

    @Test
    void aCorrectionFromADmIsCreditedToATeammateAndItsQuestionIsWithheld() {
        TestData.correction(jdbc, tenant, "APPROVED", "Priya Private", "What is Priya's salary band?",
                "The band is L5 for engineers.", null, true);

        List<ApprovedCorrection> found = repo.find(tenant, "engineers band", 3);

        assertThat(found).singleElement().satisfies(c -> {
            assertThat(c.authorName()).isEqualTo("a teammate");
            assertThat(c.question()).isNull();
            assertThat(c.fact()).isEqualTo("The band is L5 for engineers.");
        });
    }

    @Test
    void reviewMovesPendingToApprovedOrRejectedAndBack() {
        long id = repo.insertPending(tenant, null, "U9", "Arjun", "Price?", "It is $12.", "It is $14.", false);
        assertThat(repo.countPending(tenant)).isEqualTo(1);

        assertThat(repo.approve(tenant, id, "It is $14 per seat.")).isTrue();
        Correction approved = repo.reviewed(tenant, 50).get(0);
        assertThat(approved.status()).isEqualTo(Correction.Status.APPROVED);
        assertThat(approved.editedText()).isEqualTo("It is $14 per seat.");
        assertThat(approved.fact()).isEqualTo("It is $14 per seat.");
        assertThat(approved.reviewedAt()).isNotNull();
        assertThat(repo.approve(tenant, id, null)).as("already reviewed").isFalse();
        assertThat(repo.reject(tenant, id)).isFalse();

        assertThat(repo.unapprove(tenant, id)).isTrue();
        assertThat(repo.reviewed(tenant, 50).get(0).status()).isEqualTo(Correction.Status.REJECTED);
        assertThat(repo.find(tenant, "price per seat", 3)).isEmpty();
        assertThat(repo.unapprove(tenant, id)).isFalse();
    }

    @Test
    void approvingWithUnchangedOrBlankTextKeepsTheExtractedFact() {
        long same = repo.insertPending(tenant, null, "U9", "A", "q one", "old", "It is $14.", false);
        long blank = repo.insertPending(tenant, null, "U9", "A", "q two", "old", "It is $15.", false);

        repo.approve(tenant, same, "It is $14.");
        repo.approve(tenant, blank, "   ");

        assertThat(repo.reviewed(tenant, 50)).allSatisfy(c -> assertThat(c.editedText()).isNull());
    }

    @Test
    void reviewNeverTouchesAnotherTenantsCorrection() {
        long other = jdbc.sql("INSERT INTO tenant (slug, name) VALUES ('other', 'Other') RETURNING id")
                .query(Long.class).single();
        long id = repo.insertPending(other, null, "U9", "Zed", "q", "a", "fact", false);

        assertThat(repo.approve(tenant, id, null)).isFalse();
        assertThat(repo.reject(tenant, id)).isFalse();
        assertThat(repo.pending(tenant, 10)).isEmpty();
        assertThat(repo.pending(other, 10)).hasSize(1);
    }
}
