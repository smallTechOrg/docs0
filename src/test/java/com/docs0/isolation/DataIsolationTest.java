package com.docs0.isolation;

import static com.docs0.isolation.TwoTenants.CHANNEL;
import static com.docs0.isolation.TwoTenants.KEYWORD;
import static com.docs0.isolation.TwoTenants.QUESTION;
import static com.docs0.isolation.TwoTenants.THREAD;
import static com.docs0.isolation.TwoTenants.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docs0.Phase6IntegrationTest;
import com.docs0.agent.Agent;
import com.docs0.agent.AnswerVerifier;
import com.docs0.agent.AnswerVerifier.Draft;
import com.docs0.agent.AnswerVerifier.DraftCitation;
import com.docs0.budget.BudgetService;
import com.docs0.budget.CostReport;
import com.docs0.conversation.Conversation;
import com.docs0.conversation.ConversationRepository;
import com.docs0.conversation.InteractionRepository;
import com.docs0.feedback.Correction;
import com.docs0.feedback.CorrectionRepository;
import com.docs0.agent.CorrectionSource.ApprovedCorrection;
import com.docs0.feedback.FeedbackRepository;
import com.docs0.feedback.FeedbackRepository.Rating;
import com.docs0.insights.DigestService;
import com.docs0.insights.InsightsService;
import com.docs0.insights.ThemeService;
import com.docs0.knowledge.Audience;
import com.docs0.knowledge.DocumentStore;
import com.docs0.knowledge.DocumentStore.DocumentWrite;
import com.docs0.knowledge.KnowledgeSearch;
import com.docs0.knowledge.SearchHit;
import com.docs0.settings.ChannelSettings;
import com.docs0.settings.TenantSettings;
import com.docs0.slack.ProactiveOffers;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SyncRunRepository;
import com.docs0.source.SyncCounts;
import com.docs0.source.SyncRun;
import com.docs0.source.slack.FakeSlack;
import com.docs0.stale.StaleDocs;
import com.docs0.isolation.TwoTenants.Side;
import com.docs0.tenant.TenantService;
import com.docs0.web.StatusService;
import com.docs0.web.admin.FakeThemesLlmConfig.ThemesLlm;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Two companies, one database: for every read and every write that takes a tenant id, a caller acting for one
 * company never sees or changes the other's rows, even with identical text and identical Slack ids.
 */
class DataIsolationTest extends Phase6IntegrationTest {

    static final Audience SHARED = Audience.sharedOnly();
    static final Audience LEADS = Audience.of("U1", "U2");

    @Autowired KnowledgeSearch search;
    @Autowired DocumentStore documents;
    @Autowired CorrectionRepository corrections;
    @Autowired FeedbackRepository feedback;
    @Autowired ConversationRepository conversations;
    @Autowired InteractionRepository interactions;
    @Autowired ProactiveOffers offers;
    @Autowired StaleDocs stale;
    @Autowired InsightsService insights;
    @Autowired ThemeService themes;
    @Autowired DigestService digest;
    @Autowired CostReport costs;
    @Autowired BudgetService budget;
    @Autowired StatusService status;
    @Autowired SyncRunRepository runs;
    @Autowired SourceCredentialRepository credentials;
    @Autowired TenantSettings tenantSettings;
    @Autowired ChannelSettings channelSettings;
    @Autowired ThemesLlm llm;
    @Autowired FakeSlack slack;
    @Autowired TenantService tenantService;

    TwoTenants two;
    Side a;
    Side b;

    @BeforeEach
    void seed() {
        two = TwoTenants.seed(jdbc, tenant.id());
        a = two.a;
        b = two.b;
        slack.reset();
        llm.reset();
    }

    private static List<Long> ids(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::documentId).toList();
    }

    // ---- knowledge ---------------------------------------------------------------------------------

    @Test
    void keywordSearchOnlyFindsTheCallersDocuments() {
        for (Side side : List.of(a, b)) {
            var strict = search.search(side.tenantId(), SHARED, "refund policy", 20);
            assertThat(ids(strict.hits())).containsExactly(side.document());
            assertThat(strict.hits().get(0).snippet()).doesNotContain(two.other(side).marker());
        }
    }

    @Test
    void anyTermSearchOnlyFindsTheCallersDocuments() {
        // No document has "zebra", so the strict query is empty and the any-term retry runs.
        for (Side side : List.of(a, b)) {
            var broadened = search.search(side.tenantId(), SHARED, "refund zebra", 20);
            assertThat(broadened.broadened()).isTrue();
            assertThat(ids(broadened.hits())).containsExactly(side.document());
        }
    }

    @Test
    void searchRestrictedBySourceKindOnlyFindsTheCallersDocuments() {
        for (Side side : List.of(a, b)) {
            assertThat(ids(search.search(side.tenantId(), LEADS, KEYWORD, 20, Set.of("SLACK")).hits()))
                    .containsExactly(side.restrictedDocument());
            assertThat(ids(search.search(side.tenantId(), LEADS, KEYWORD, 20, Set.of("GOOGLE_DRIVE")).hits()))
                    .containsExactly(side.document());
        }
    }

    @Test
    void findByIdNeverReturnsAnotherTenantsDocument() {
        for (Side side : List.of(a, b)) {
            Side other = two.other(side);
            assertThat(search.findById(side.tenantId(), LEADS, side.document())).isPresent();
            assertThat(search.findById(side.tenantId(), LEADS, other.document())).isEmpty();
            assertThat(search.findById(side.tenantId(), LEADS, other.restrictedDocument())).isEmpty();
        }
    }

    @Test
    void recentAndCountOnlyCoverTheCallersDocuments() {
        for (Side side : List.of(a, b)) {
            assertThat(ids(search.recent(side.tenantId(), LEADS, 20)))
                    .containsExactlyInAnyOrder(side.document(), side.restrictedDocument());
            assertThat(search.recent(side.tenantId(), SHARED, 20)).extracting(SearchHit::documentId)
                    .containsExactly(side.document());
            assertThat(search.count(side.tenantId())).isEqualTo(2);
        }
    }

    @Test
    void restrictedDocumentsAndTheirPrincipalsStayWithTheirTenant() {
        // Same principals ("u1", "u2") on both sides: the audience U1+U2 sees only its own tenant's private document.
        assertThat(search.findById(b.tenantId(), LEADS, b.restrictedDocument())).isPresent();
        assertThat(search.findById(a.tenantId(), LEADS, b.restrictedDocument())).isEmpty();
        for (Side side : List.of(a, b)) {
            assertThat(jdbc.sql("SELECT DISTINCT tenant_id FROM document_principal WHERE document_id = :d")
                    .param("d", side.restrictedDocument()).query(Long.class).list()).containsExactly(side.tenantId());
        }
        // Changing who may see a tenant's source never touches the other tenant's rows.
        documents.refreshAccess(b.tenantId(), a.slackSource(), "thread:", false, Set.of());
        assertThat(search.findById(a.tenantId(), SHARED, a.restrictedDocument())).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM document_principal WHERE tenant_id = :t").param("t", a.tenantId())
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void documentWritesWithAnotherTenantsSourceChangeNothing() {
        DocumentWrite doc = new DocumentWrite("planted", "Planted", "text/plain", null, null, null, Instant.now(),
                "refund planted");
        assertThatThrownBy(() -> documents.upsert(b.tenantId(), a.driveSource(), doc)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> documents.upsertRestricted(b.tenantId(), a.driveSource(), doc, Set.of("u1")))
                .isInstanceOf(Exception.class);

        assertThat(documents.markDeleted(b.tenantId(), a.driveSource(), List.of("refunds"))).isZero();
        assertThat(documents.markAllDeleted(b.tenantId(), a.driveSource())).isZero();
        assertThat(documents.count(b.tenantId(), a.driveSource())).isZero();
        assertThat(documents.stored(b.tenantId(), a.driveSource())).isEmpty();
        assertThat(documents.modifiedTimes(b.tenantId(), a.driveSource())).isEmpty();
        assertThat(documents.versions(b.tenantId(), a.driveSource())).isEmpty();
        assertThat(documents.countsByKind(b.tenantId())).containsOnlyKeys("GOOGLE_DRIVE", "SLACK")
                .containsEntry("GOOGLE_DRIVE", 1L);
        assertThat(search.count(a.tenantId())).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM document WHERE external_id = 'planted'").query(Long.class).single())
                .isZero();
    }

    @Test
    void theVerifierDoesNotConfirmAQuoteFromAnotherTenantsDocument() {
        AnswerVerifier verifier = new AnswerVerifier(search);
        Draft draft = new Draft(Agent.Kind.ANSWER, "Refunds are quick [1].", Agent.Confidence.HIGH,
                List.of(new DraftCitation(a.document(), "customers get a refund")), List.of(), List.of());

        assertThat(verifier.verify(draft, a.tenantId(), SHARED).citations()).hasSize(1);
        var asB = verifier.verify(draft, b.tenantId(), SHARED);
        assertThat(asB.citations()).isEmpty();
        assertThat(asB.droppedCitations()).isEqualTo(1);
    }

    // ---- corrections and feedback -------------------------------------------------------------------

    @Test
    void approvedCorrectionsMatchOnlyWithinTheTenantEvenWithIdenticalText() {
        for (Side side : List.of(a, b)) {
            List<ApprovedCorrection> found = corrections.find(side.tenantId(), QUESTION, 10);
            assertThat(found).extracting(ApprovedCorrection::id).containsExactly(side.approvedCorrection());
            assertThat(found.get(0).fact()).contains(side.marker()).doesNotContain(two.other(side).marker());
        }
    }

    @Test
    void reviewingAnotherTenantsCorrectionIsRefusedAndChangesNothing() {
        assertThat(corrections.approve(b.tenantId(), a.pendingCorrection(), "hijacked")).isFalse();
        assertThat(corrections.reject(b.tenantId(), a.pendingCorrection())).isFalse();
        assertThat(corrections.unapprove(b.tenantId(), a.approvedCorrection())).isFalse();

        assertThat(corrections.pending(b.tenantId(), 10)).extracting(Correction::id).containsExactly(b.pendingCorrection());
        assertThat(corrections.reviewed(b.tenantId(), 10)).extracting(Correction::id)
                .containsExactly(b.approvedCorrection());
        assertThat(corrections.countPending(a.tenantId())).isEqualTo(1);
        assertThat(corrections.find(a.tenantId(), QUESTION, 10)).extracting(ApprovedCorrection::id)
                .containsExactly(a.approvedCorrection());
    }

    @Test
    void aCorrectionCannotPointAtAnotherTenantsAnswer() {
        assertThatThrownBy(() -> corrections.insertPending(b.tenantId(), a.interaction(), USER, "x", QUESTION, "old",
                "planted", false)).isInstanceOf(Exception.class);
        assertThat(jdbc.sql("SELECT count(*) FROM correction WHERE correction_text = 'planted'").query(Long.class)
                .single()).isZero();
    }

    @Test
    void feedbackAndPromptsBelongToTheTenantOfTheAnswer() {
        feedback.record(b.tenantId(), a.interaction(), "U2", Rating.UP);
        assertThat(feedback.count(a.tenantId(), a.interaction(), Rating.UP)).isZero();
        assertThat(feedback.count(b.tenantId(), a.interaction(), Rating.UP)).isZero();
        assertThat(feedback.count(a.tenantId(), a.interaction(), Rating.DOWN)).isEqualTo(1);

        assertThat(feedback.createPrompt(b.tenantId(), a.conversation(), a.interaction())).isFalse();
        assertThat(feedback.pendingPrompt(b.tenantId(), a.conversation())).isEmpty();
        assertThat(feedback.pendingPrompt(a.tenantId(), a.conversation())).contains(a.interaction());
        feedback.clearPrompts(b.tenantId(), a.conversation());
        assertThat(feedback.pendingPrompt(a.tenantId(), a.conversation())).contains(a.interaction());
        feedback.remove(b.tenantId(), a.interaction(), USER, Rating.DOWN);
        assertThat(feedback.count(a.tenantId(), a.interaction(), Rating.DOWN)).isEqualTo(1);
        feedback.deletePrompt(b.tenantId(), a.interaction());
        assertThat(feedback.pendingPrompt(a.tenantId(), a.conversation())).isPresent();

        for (Side side : List.of(a, b)) {
            assertThat(feedback.unhappy(side.tenantId(), 10)).extracting(FeedbackRepository.UnhappyAnswer::interactionId)
                    .containsExactly(side.interaction());
        }
    }

    // ---- conversations and interactions -------------------------------------------------------------

    @Test
    void theSameChannelAndThreadAreSeparateConversationsPerTenant() {
        assertThat(a.conversation()).isNotEqualTo(b.conversation());
        Conversation inA = conversations.find(a.tenantId(), CHANNEL, THREAD).orElseThrow();
        Conversation inB = conversations.find(b.tenantId(), CHANNEL, THREAD).orElseThrow();
        assertThat(inA.id()).isEqualTo(a.conversation());
        assertThat(inB.id()).isEqualTo(b.conversation());
        // Asking again in either tenant lands in that tenant's conversation.
        assertThat(conversations.findOrCreate(b.tenantId(), CHANNEL, THREAD, false).id()).isEqualTo(b.conversation());
        assertThat(conversations.hasInteractions(a.tenantId(), CHANNEL, THREAD)).isTrue();
        assertThat(conversations.history(b.tenantId(), a.conversation())).isEmpty();
        assertThat(conversations.history(b.tenantId(), b.conversation())).extracting(e -> e.answer())
                .allSatisfy(answer -> assertThat(answer).contains("BETA"));
    }

    @Test
    void interactionsAreFoundAndChangedOnlyByTheirOwnTenant() {
        assertThat(interactions.findById(b.tenantId(), a.interaction())).isEmpty();
        assertThat(interactions.findByAnswerTs(b.tenantId(), CHANNEL, "200." + a.tenantId())).isEmpty();
        assertThat(interactions.findByAnswerTs(a.tenantId(), CHANNEL, "200." + a.tenantId())).isPresent();
        assertThat(interactions.latestAnswer(b.tenantId(), a.conversation())).isEmpty();
        assertThat(interactions.questionTexts(b.tenantId(), List.of(a.interaction()))).isEmpty();
        assertThat(interactions.usedRestricted(b.tenantId(), List.of(a.interaction()))).isEmpty();
        assertThat(interactions.count(a.tenantId())).isEqualTo(2);

        interactions.addCost(b.tenantId(), a.interaction(), 1, 1, new BigDecimal("99"));
        assertThat(budget.questionSpentThisMonth(a.tenantId())).isEqualByComparingTo("10.00");

        var cheap = new Agent.AgentResult(Agent.Kind.ANSWER, "x", Agent.Confidence.HIGH, List.of(), List.of(),
                List.of(), 1, 0, 0, BigDecimal.ZERO, 0, null);
        assertThatThrownBy(() -> interactions.insert(b.tenantId(), a.conversation(), USER, "x", cheap, "1.1"))
                .isInstanceOf(Exception.class);
        assertThat(interactions.count(b.tenantId())).isEqualTo(2);
    }

    @Test
    void repeatQuestionMatchingOnlyConsidersTheTenantsOwnAnswers() {
        for (Side side : List.of(a, b)) {
            var similar = interactions.similarAnswered(side.tenantId(), QUESTION, "C-other", "1.1", 0.0, 10);
            // The first answer got a thumbs down, so only the later one is a candidate.
            Set<Long> own = Set.copyOf(jdbc.sql("SELECT id FROM interaction WHERE tenant_id = :t AND question LIKE 'Earlier%'")
                    .param("t", side.tenantId()).query(Long.class).list());
            assertThat(similar).hasSize(1);
            assertThat(similar).allSatisfy(s -> assertThat(own).contains(s.id()));
        }
        // The same message in the same channel is a separate offer in each tenant.
        assertThat(offers.exists(a.tenantId(), CHANNEL, "300." + a.tenantId())).isTrue();
        assertThat(offers.exists(b.tenantId(), CHANNEL, "300." + a.tenantId())).isFalse();
        assertThat(offers.claim(b.tenantId(), CHANNEL, "300." + a.tenantId(),
                jdbc.sql("SELECT min(id) FROM interaction WHERE tenant_id = :t").param("t", b.tenantId())
                        .query(Long.class).single())).isPresent();
        assertThat(offers.offersSince(b.tenantId(), CHANNEL, Instant.now().minus(1, ChronoUnit.HOURS))).isZero();
        assertThatThrownBy(() -> offers.claim(b.tenantId(), CHANNEL, "x", a.interaction())).isInstanceOf(Exception.class);
    }

    // ---- stale documents, insights, digest ---------------------------------------------------------------

    @Test
    void staleFlagsAndPingsStayInsideTheTenant() {
        var citation = new Agent.Citation(1, a.document(), "Refund policy", null, "q");
        assertThat(stale.staleAmong(a.tenantId(), List.of(citation))).hasSize(1);
        assertThat(stale.staleAmong(b.tenantId(), List.of(citation))).isEmpty();

        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        for (Side side : List.of(a, b)) {
            assertThat(stale.report(side.tenantId(), since, 10)).extracting(StaleDocs.Row::documentId)
                    .containsExactly(side.document());
        }
        // Recording that B's answer relied on A's document writes nothing.
        stale.record(b.tenantId(), b.interaction(), List.of(new StaleDocs.Stale(a.document(), Instant.now(), false)));
        assertThat(jdbc.sql("SELECT count(*) FROM stale_flag WHERE document_id = :d").param("d", a.document())
                .query(Long.class).single()).isEqualTo(1);
        // B's admin cannot nudge the owner of A's document.
        assertThat(stale.askOwner(b.tenantId(), a.document())).isEqualTo(StaleDocs.PingResult.GONE);
        assertThat(slack.sent).isEmpty();
    }

    @Test
    void insightTotalsAndGapsOnlyCountTheTenantsOwnQuestions() {
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        addQuestion(a, "ALPHA only question about parking?");
        addQuestion(b, "BETA only question about badges?");
        addQuestion(b, "BETA second question about lanyards?");

        assertThat(insights.totals(a.tenantId(), since).questions()).isEqualTo(3);
        assertThat(insights.totals(b.tenantId(), since).questions()).isEqualTo(4);
        assertThat(insights.totals(a.tenantId(), since).thumbsDown()).isEqualTo(1);
        // UNKNOWN answers are the gaps.
        var gapsA = insights.gaps(a.tenantId(), since);
        var gapsB = insights.gaps(b.tenantId(), since);
        assertThat(gapsA.items()).extracting(InsightsService.Gap::question).contains("ALPHA only question about parking?")
                .noneMatch(q -> q.contains("BETA"));
        assertThat(gapsB.items()).extracting(InsightsService.Gap::question).hasSize(3)
                .allSatisfy(q -> assertThat(q).doesNotContain("ALPHA"));
        assertThat(insights.staleDocuments(b.tenantId(), since)).extracting(StaleDocs.Row::documentId)
                .containsExactly(b.document());
    }

    @Test
    void themesAreWorkedOutFromOnlyTheTenantsQuestions() {
        addQuestion(a, "ALPHA only question about parking?");
        addQuestion(b, "BETA only question about badges?");

        themes.themes(b.tenantId(), 30);

        assertThat(llm.asked).hasSize(1);
        assertThat(llm.asked.get(0)).contains("BETA only question about badges?").doesNotContain("ALPHA");
        // And what was cached for B is not served to A.
        assertThat(themes.cached(a.tenantId(), 30)).isEmpty();
        themes.themes(a.tenantId(), 30);
        assertThat(llm.asked).hasSize(2);
        assertThat(llm.asked.get(1)).contains("ALPHA only question about parking?").doesNotContain("BETA");
    }

    @Test
    void theDigestIsBuiltFromAndPostedToOnlyTheTenantsOwnWorkspace() {
        FakeSlack workspaceB = new FakeSlack();
        slack.workspaceFor(b.tenantId(), workspaceB);
        slack.channel("C1", "general", false, true);
        workspaceB.channel("C1", "general", false, true).channel("C2", "random", false, true);
        addQuestion(a, "ALPHA only question about parking?");
        addQuestion(b, "BETA only question about badges?");
        tenantSettings.put(a.tenantId(), TenantSettings.DIGEST_CHANNEL, "C1");

        assertThat(digest.postNow(b.tenantId())).isEqualTo(DigestService.Outcome.POSTED);

        assertThat(slack.sent).isEmpty();
        assertThat(workspaceB.sent).hasSize(1);
        String text = workspaceB.sent.get(0).blocks().toString();
        assertThat(text).contains("BETA only question about badges?").doesNotContain("ALPHA");
        assertThat(tenantSettings.getInstant(b.tenantId(), TenantSettings.DIGEST_LAST_AT)).isPresent();
        assertThat(tenantSettings.getInstant(a.tenantId(), TenantSettings.DIGEST_LAST_AT)).isEmpty();
    }

    @Test
    void settingsAreKeptPerTenant() {
        tenantSettings.put(a.tenantId(), "note", "changed in A");
        assertThat(tenantSettings.get(b.tenantId(), "note")).contains("note BETA");
        tenantSettings.remove(b.tenantId(), "note");
        assertThat(tenantSettings.get(a.tenantId(), "note")).contains("changed in A");

        channelSettings.saveProactive(b.tenantId(), List.of(new ChannelSettings.Channel(CHANNEL, "general")), Set.of());
        assertThat(channelSettings.proactive(b.tenantId(), CHANNEL)).isFalse();
        assertThat(channelSettings.proactive(a.tenantId(), CHANNEL)).isTrue();
        assertThat(channelSettings.proactiveChannels(a.tenantId())).containsExactly(CHANNEL);
    }

    // ---- money and status -----------------------------------------------------------------------------

    @Test
    void costReportsAndSpendCoverOnlyTheTenant() {
        assertThat(budget.spentThisMonth(a.tenantId())).isEqualByComparingTo("11.00"); // 2 answers at 5.00 + 1.00
        assertThat(budget.spentThisMonth(b.tenantId())).isEqualByComparingTo("4.50");  // 2 answers at 2.00 + 0.50
        assertThat(budget.otherSpentThisMonth(b.tenantId())).isEqualByComparingTo("0.50");
        assertThat(costs.perDay(b.tenantId(), 7)).hasSize(1);
        assertThat(costs.perDay(b.tenantId(), 7).get(0).questions()).isEqualTo(2);
        assertThat(costs.perDay(b.tenantId(), 7).get(0).costUsd()).isEqualByComparingTo("4.00");
        assertThat(costs.otherSpend(a.tenantId(), 7)).isEqualByComparingTo("1.00");
        assertThat(costs.topQuestionsThisMonth(b.tenantId(), 10)).allSatisfy(e ->
                assertThat(e.costUsd()).isEqualByComparingTo("2.00"));
    }

    @Test
    void eachTenantHasItsOwnMonthlyCap() {
        // The global default is 100 USD (application-test.yml). A tenant's own limit replaces it for that tenant only.
        assertThat(budget.monthlyCap(a.tenantId())).isEqualByComparingTo("100");
        assertThat(budget.monthlyCap(b.tenantId())).isEqualByComparingTo("100");

        tenantService.setMonthlyBudget(b.tenantId(), new BigDecimal("4.50"));
        assertThat(budget.monthlyCap(b.tenantId())).isEqualByComparingTo("4.50");
        assertThat(budget.exhausted(b.tenantId())).isTrue();
        assertThat(budget.monthlyCap(a.tenantId())).isEqualByComparingTo("100");
        assertThat(budget.exhausted(a.tenantId())).isFalse();

        // A's spend (11.00) is above 6, but B's own 4.50 limit and spend are what decide for B.
        tenantService.setMonthlyBudget(a.tenantId(), new BigDecimal("6.00"));
        tenantService.setMonthlyBudget(b.tenantId(), new BigDecimal("50"));
        assertThat(budget.exhausted(a.tenantId())).isTrue();
        assertThat(budget.exhausted(b.tenantId())).isFalse();

        // No limit of its own: back to the global default.
        tenantService.setMonthlyBudget(b.tenantId(), null);
        assertThat(budget.monthlyCap(b.tenantId())).isEqualByComparingTo("100");
    }

    @Test
    void statusCountsAndNamesBelongToTheTenantItReportsOn() {
        for (Side side : List.of(a, b)) {
            StatusService.Snapshot snap = status.snapshot(side.tenantId());
            assertThat(snap.tenantName()).isEqualTo(side.slug().equals("acme") ? "Acme" : "smallTech");
            assertThat(snap.documents()).isEqualTo(2);
            assertThat(snap.questions()).isEqualTo(2);
            assertThat(snap.pendingCorrections()).isEqualTo(1);
            assertThat(snap.lastSync()).isPresent();
            assertThat(snap.lastSync().get().sourceName()).isEqualTo("Google Drive");
        }
        assertThat(status.snapshot(b.tenantId()).spentThisMonth()).isEqualByComparingTo("4.50");
        assertThat(status.snapshot(a.tenantId()).spentThisMonth()).isEqualByComparingTo("11.00");
    }

    // ---- sync runs and credentials ---------------------------------------------------------------------

    @Test
    void syncRunsAreInvisibleAndUnchangeableFromAnotherTenant() {
        assertThat(runs.recent(b.tenantId(), a.driveSource(), 10)).isEmpty();
        assertThat(runs.latest(b.tenantId(), a.driveSource())).isEmpty();
        assertThat(runs.lastCleanStart(b.tenantId(), a.driveSource())).isEmpty();
        assertThatThrownBy(() -> runs.start(b.tenantId(), a.driveSource())).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> runs.finish(b.tenantId(), a.syncRun(), SyncRun.FAILED, SyncCounts.NONE, "boom"))
                .isInstanceOf(Exception.class);

        SyncRun still = runs.latest(a.tenantId(), a.driveSource()).orElseThrow();
        assertThat(still.status()).isEqualTo(SyncRun.OK);
        assertThat(runs.recent(a.tenantId(), a.driveSource(), 10)).hasSize(1);
    }

    @Test
    void storedCredentialsAreOnlyReachableByTheirOwnTenant() {
        assertThat(credentials.find(b.tenantId(), a.driveSource())).isEmpty();
        assertThat(credentials.delete(b.tenantId(), a.driveSource())).isFalse();
        credentials.saveSecret(b.tenantId(), a.driveSource(), "Hijacked", "x");
        credentials.save(b.tenantId(), a.driveSource(), "hijack@example.com", "x");

        var kept = credentials.find(a.tenantId(), a.driveSource()).orElseThrow();
        assertThat(kept.accountEmail()).isEqualTo("Account ALPHA");
        assertThat(kept.tenantId()).isEqualTo(a.tenantId());
        assertThat(credentials.find(b.tenantId(), b.driveSource()).orElseThrow().accountEmail())
                .isEqualTo("Account BETA");
    }

    @Test
    void deletingATenantsDataLeavesTheOtherTenantsRowsInEveryTable() {
        Map<String, Long> before = TwoTenants.counts(jdbc, a.tenantId());
        assertThat(before.values()).allSatisfy(count -> assertThat(count).isPositive());
        assertThat(TwoTenants.counts(jdbc, b.tenantId())).hasSameSizeAs(before)
                .allSatisfy((table, count) -> assertThat(count).as(table).isPositive());
    }

    private void addQuestion(Side side, String question) {
        Conversation c = conversations.findOrCreate(side.tenantId(), "C2", "700." + question.length(), false);
        var unknown = new Agent.AgentResult(Agent.Kind.UNKNOWN, "I could not find that.", Agent.Confidence.LOW,
                List.of(), List.of(), List.of(), 1, 10, 1, BigDecimal.ZERO, 5, null);
        interactions.insert(side.tenantId(), c.id(), USER, question, unknown, "800." + question.length());
    }
}
