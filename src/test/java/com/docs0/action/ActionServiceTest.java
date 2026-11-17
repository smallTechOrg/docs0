package com.docs0.action;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.TestData;
import com.docs0.action.ActionService.Outcome;
import com.docs0.action.ActionService.Result;
import com.docs0.action.PendingAction.Kind;
import com.docs0.action.PendingAction.Status;
import com.docs0.conversation.Conversation;
import com.docs0.crypto.TokenCipher;
import com.docs0.knowledge.DocumentStore;
import com.docs0.source.SourceAccessException;
import com.docs0.source.SourceCredentialRepository;
import com.docs0.source.SourceRepository;
import com.docs0.source.drive.DriveTestSetup;
import com.docs0.source.linear.FakeLinearClient;
import com.docs0.source.linear.LinearConnection;
import com.docs0.source.trello.FakeTrelloClient;
import com.docs0.source.trello.TrelloConnection;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The confirm and cancel rules: who may, once only, within an hour, and what is recorded. */
class ActionServiceTest extends IntegrationTest {

    @Autowired
    SourceRepository sources;

    @Autowired
    SourceCredentialRepository credentials;

    @Autowired
    DocumentStore store;

    @Autowired
    PendingActionRepository pending;

    FakeLinearClient linearApi = new FakeLinearClient();
    FakeTrelloClient trelloApi = new FakeTrelloClient();
    LinearConnection linear;
    TrelloConnection trello;
    ActionService service;
    Conversation conversation;

    @BeforeEach
    void setUp() {
        TokenCipher cipher = new TokenCipher(DriveTestSetup.ENCRYPTION_KEY);
        linear = new LinearConnection(cipher, sources, credentials, store);
        trello = new TrelloConnection(cipher, sources, credentials, store);
        linear.connect(tenant.id(), "Acme", FakeLinearClient.GOOD_KEY);
        trello.connect(tenant.id(), "Asha", FakeTrelloClient.GOOD_KEY, FakeTrelloClient.GOOD_TOKEN);
        service = new ActionService(pending, linear, linearApi, trello, trelloApi);
        conversation = TestData.conversation(jdbc, tenant.id(), "C1", "100.1", false);
    }

    private PendingAction issueDraft(String requester) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("teamId", "team-eng");
        params.put("teamKey", "ENG");
        params.put("teamName", "Engineering");
        params.put("title", "Fix login");
        params.put("description", "Safari users cannot sign in");
        params.put("priority", "high");
        return pending.insert(tenant.id(), conversation.id(), requester, Kind.LINEAR_ISSUE, params);
    }

    private PendingAction cardDraft(String requester) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("boardId", "b1");
        params.put("boardName", "Roadmap");
        params.put("listId", "l-doing");
        params.put("listName", "Doing");
        params.put("title", "Book venue");
        params.put("description", "For the offsite");
        return pending.insert(tenant.id(), conversation.id(), requester, Kind.TRELLO_CARD, params);
    }

    private PendingAction row(PendingAction draft) {
        return pending.find(tenant.id(), draft.id()).orElseThrow();
    }

    private ActionService at(Instant now) {
        return new ActionService(pending, linear, linearApi, trello, trelloApi, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void theRequesterConfirmsAndTheIssueIsCreatedWithTheDraftedFields() {
        PendingAction draft = issueDraft("U1");

        Outcome outcome = service.confirm(tenant.id(), draft.id(), "U1");

        assertThat(outcome.result()).isEqualTo(Result.CREATED);
        assertThat(outcome.identifier()).isEqualTo("ENG-99");
        assertThat(outcome.url()).isEqualTo("https://linear.app/acme/issue/ENG-99");
        assertThat(linearApi.creates).containsExactly(new FakeLinearClient.CreateCall(
                FakeLinearClient.GOOD_KEY, "team-eng", "Fix login", "Safari users cannot sign in", 2));
        PendingAction done = row(draft);
        assertThat(done.status()).isEqualTo(Status.DONE);
        assertThat(done.resultUrl()).isEqualTo("https://linear.app/acme/issue/ENG-99");
        assertThat(done.resolvedAt()).isNotNull();
        assertThat(done.error()).isNull();
    }

    @Test
    void theRequesterConfirmsAndTheCardIsCreatedOnTheDraftedList() {
        PendingAction draft = cardDraft("U1");

        Outcome outcome = service.confirm(tenant.id(), draft.id(), "U1");

        assertThat(outcome.result()).isEqualTo(Result.CREATED);
        assertThat(outcome.url()).isEqualTo("https://trello.com/c/NewCard");
        assertThat(trelloApi.creates).containsExactly(new FakeTrelloClient.CreateCall(FakeTrelloClient.GOOD_KEY,
                FakeTrelloClient.GOOD_TOKEN, "l-doing", "Book venue", "For the offsite"));
        assertThat(row(draft).status()).isEqualTo(Status.DONE);
        assertThat(row(draft).resultUrl()).isEqualTo("https://trello.com/c/NewCard");
    }

    @Test
    void aDraftWithoutAPriorityLeavesItUnset() {
        PendingAction draft = pending.insert(tenant.id(), conversation.id(), "U1", Kind.LINEAR_ISSUE,
                Map.of("teamId", "team-eng", "teamKey", "ENG", "title", "T", "description", "D"));

        service.confirm(tenant.id(), draft.id(), "U1");

        assertThat(linearApi.creates.get(0).priority()).isNull();
        assertThat(ActionService.priorityNumber("urgent")).isEqualTo(1);
        assertThat(ActionService.priorityNumber("medium")).isEqualTo(3);
        assertThat(ActionService.priorityNumber("low")).isEqualTo(4);
        assertThat(ActionService.priorityNumber("none")).isNull();
    }

    @Test
    void onlyTheRequesterMayConfirmOrCancel() {
        PendingAction draft = issueDraft("U1");

        assertThat(service.confirm(tenant.id(), draft.id(), "U2").result()).isEqualTo(Result.NOT_REQUESTER);
        assertThat(service.cancel(tenant.id(), draft.id(), "U2").result()).isEqualTo(Result.NOT_REQUESTER);

        assertThat(linearApi.creates).isEmpty();
        assertThat(row(draft).status()).isEqualTo(Status.PENDING);
        // The requester can still go ahead afterwards.
        assertThat(service.confirm(tenant.id(), draft.id(), "U1").result()).isEqualTo(Result.CREATED);
    }

    @Test
    void aSecondClickCreatesNothingMore() {
        PendingAction draft = issueDraft("U1");

        assertThat(service.confirm(tenant.id(), draft.id(), "U1").result()).isEqualTo(Result.CREATED);
        assertThat(service.confirm(tenant.id(), draft.id(), "U1").result()).isEqualTo(Result.ALREADY_HANDLED);

        assertThat(linearApi.creates).hasSize(1);
    }

    @Test
    void manyClicksAtTheSameMomentCreateExactlyOneItem() throws Exception {
        PendingAction draft = cardDraft("U1");
        int clicks = 8;
        ExecutorService pool = Executors.newFixedThreadPool(clicks);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> outcomes = new ArrayList<>();
        for (int i = 0; i < clicks; i++) {
            outcomes.add(pool.submit(() -> {
                go.await();
                return service.confirm(tenant.id(), draft.id(), "U1");
            }));
        }
        go.countDown();
        List<Result> results = new ArrayList<>();
        for (Future<Outcome> f : outcomes) {
            results.add(f.get().result());
        }
        pool.shutdown();

        assertThat(results).containsOnlyOnce(Result.CREATED);
        assertThat(results.stream().filter(r -> r == Result.ALREADY_HANDLED).count()).isEqualTo(clicks - 1);
        assertThat(trelloApi.creates).hasSize(1);
        assertThat(row(draft).status()).isEqualTo(Status.DONE);
    }

    @Test
    void aDraftOlderThanAnHourHasExpiredAndCreatesNothing() {
        PendingAction draft = issueDraft("U1");
        Instant now = Instant.now();

        Outcome late = at(now.plus(Duration.ofMinutes(61))).confirm(tenant.id(), draft.id(), "U1");

        assertThat(late.result()).isEqualTo(Result.EXPIRED);
        assertThat(linearApi.creates).isEmpty();
        assertThat(row(draft).status()).isEqualTo(Status.EXPIRED);
        // Once expired it stays expired, even if the clock were right again.
        assertThat(service.confirm(tenant.id(), draft.id(), "U1").result()).isEqualTo(Result.EXPIRED);
        assertThat(linearApi.creates).isEmpty();
    }

    @Test
    void aDraftJustUnderAnHourOldCanStillBeConfirmed() {
        PendingAction draft = issueDraft("U1");

        Outcome outcome = at(Instant.now().plus(Duration.ofMinutes(59))).confirm(tenant.id(), draft.id(), "U1");

        assertThat(outcome.result()).isEqualTo(Result.CREATED);
    }

    @Test
    void cancellingStopsAnyLaterConfirm() {
        PendingAction draft = issueDraft("U1");

        assertThat(service.cancel(tenant.id(), draft.id(), "U1").result()).isEqualTo(Result.CANCELLED);
        assertThat(service.confirm(tenant.id(), draft.id(), "U1").result()).isEqualTo(Result.ALREADY_HANDLED);
        assertThat(service.cancel(tenant.id(), draft.id(), "U1").result()).isEqualTo(Result.ALREADY_HANDLED);

        assertThat(linearApi.creates).isEmpty();
        assertThat(row(draft).status()).isEqualTo(Status.CANCELLED);
    }

    @Test
    void aFailedCreateIsRecordedWithItsErrorAndIsNotRetriedByAnotherClick() {
        PendingAction draft = issueDraft("U1");
        linearApi.createFailure = new IOException("Linear answered HTTP 502");

        Outcome outcome = service.confirm(tenant.id(), draft.id(), "U1");

        assertThat(outcome.result()).isEqualTo(Result.FAILED);
        assertThat(outcome.url()).isNull();
        PendingAction failed = row(draft);
        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.error()).isEqualTo("IOException: Linear answered HTTP 502");
        assertThat(failed.resultUrl()).isNull();
        assertThat(service.confirm(tenant.id(), draft.id(), "U1").result()).isEqualTo(Result.ALREADY_HANDLED);
        assertThat(linearApi.creates).hasSize(1);
    }

    @Test
    void aRejectedKeyAtConfirmTimeIsAFailureToo() {
        PendingAction draft = cardDraft("U1");
        trelloApi.createFailure = new SourceAccessException("Trello rejected the key or token. Reconnect Trello on the Sources page.");

        assertThat(service.confirm(tenant.id(), draft.id(), "U1").result()).isEqualTo(Result.FAILED);
        assertThat(row(draft).error()).contains("Reconnect Trello");
    }

    @Test
    void ifTheToolWasDisconnectedSinceDraftingTheClickFailsCleanly() {
        PendingAction draft = issueDraft("U1");
        linear.disconnect(tenant.id());

        Outcome outcome = service.confirm(tenant.id(), draft.id(), "U1");

        assertThat(outcome.result()).isEqualTo(Result.FAILED);
        assertThat(row(draft).error()).contains("Linear is not connected");
        assertThat(linearApi.creates).isEmpty();
    }

    @Test
    void aDraftOfAnotherTenantIsNotFound() {
        PendingAction draft = issueDraft("U1");
        long other = jdbc.sql("INSERT INTO tenant (slug, name) VALUES ('other', 'Other') RETURNING id")
                .query(Long.class).single();

        assertThat(service.confirm(other, draft.id(), "U1").result()).isEqualTo(Result.NOT_FOUND);
        assertThat(service.cancel(other, draft.id(), "U1").result()).isEqualTo(Result.NOT_FOUND);
        assertThat(service.confirm(tenant.id(), 9999, "U1").result()).isEqualTo(Result.NOT_FOUND);
        assertThat(linearApi.creates).isEmpty();
    }

    @Test
    void aDraftCanBeLinkedToTheInteractionItCameFrom() {
        PendingAction draft = issueDraft("U1");
        long interaction = TestData.interaction(jdbc, conversation, "U1", "file this", "Drafted.", "9000.1", "0.01");

        pending.attachInteraction(tenant.id(), List.of(draft.id()), interaction);

        assertThat(row(draft).interactionId()).isEqualTo(interaction);
        // An interaction of another tenant is not accepted.
        long other = jdbc.sql("INSERT INTO tenant (slug, name) VALUES ('other', 'Other') RETURNING id")
                .query(Long.class).single();
        PendingAction second = issueDraft("U1");
        pending.attachInteraction(other, List.of(second.id()), interaction);
        assertThat(row(second).interactionId()).isNull();
    }
}
