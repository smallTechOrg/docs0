package com.docs0.action;

import com.docs0.action.PendingAction.Kind;
import com.docs0.source.linear.LinearClient;
import com.docs0.source.linear.LinearConnection;
import com.docs0.source.trello.TrelloClient;
import com.docs0.source.trello.TrelloConnection;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Carries out or drops a drafted issue or card when the person who asked clicks a button. This
 * is the only place docs0 writes to Linear or Trello. Only the person who asked may confirm, a
 * draft older than an hour has expired, and of two clicks only the first creates anything.
 */
@Service
public class ActionService {

    public static final Duration EXPIRES_AFTER = Duration.ofHours(1);

    public enum Result { CREATED, FAILED, EXPIRED, CANCELLED, NOT_FOUND, NOT_REQUESTER, ALREADY_HANDLED }

    /** What happened; {@code action} is the draft as it was (null for NOT_FOUND), {@code url} and {@code identifier} are set for CREATED. */
    public record Outcome(Result result, PendingAction action, String identifier, String url) {}

    private static final Logger log = LoggerFactory.getLogger(ActionService.class);
    private static final int MAX_ERROR_CHARS = 300;

    private final PendingActionRepository pending;
    private final LinearConnection linearConnection;
    private final LinearClient linear;
    private final TrelloConnection trelloConnection;
    private final TrelloClient trello;
    private final Clock clock;

    @Autowired
    public ActionService(PendingActionRepository pending, LinearConnection linearConnection, LinearClient linear,
                         TrelloConnection trelloConnection, TrelloClient trello) {
        this(pending, linearConnection, linear, trelloConnection, trello, Clock.systemUTC());
    }

    public ActionService(PendingActionRepository pending, LinearConnection linearConnection, LinearClient linear,
                         TrelloConnection trelloConnection, TrelloClient trello, Clock clock) {
        this.pending = pending;
        this.linearConnection = linearConnection;
        this.linear = linear;
        this.trelloConnection = trelloConnection;
        this.trello = trello;
        this.clock = clock;
    }

    /** Creates the drafted item for the person who asked for it. */
    public Outcome confirm(long tenantId, long id, String user) {
        Optional<PendingAction> found = pending.find(tenantId, id);
        if (found.isEmpty()) {
            return new Outcome(Result.NOT_FOUND, null, null, null);
        }
        PendingAction draft = found.get();
        if (!draft.requestedBy().equals(user)) {
            return new Outcome(Result.NOT_REQUESTER, draft, null, null);
        }
        Instant notBefore = clock.instant().minus(EXPIRES_AFTER);
        Optional<PendingAction> claimed = pending.claim(tenantId, id, user, notBefore);
        if (claimed.isEmpty()) {
            // A second click, a click after cancelling, or a draft that has waited too long.
            if (pending.expire(tenantId, id, notBefore)) {
                return new Outcome(Result.EXPIRED, draft, null, null);
            }
            PendingAction now = pending.find(tenantId, id).orElse(draft);
            return new Outcome(now.status() == PendingAction.Status.EXPIRED ? Result.EXPIRED : Result.ALREADY_HANDLED,
                    now, null, null);
        }
        try {
            Made created = create(tenantId, claimed.get());
            pending.markCreated(tenantId, id, created.url());
            log.info("Created {} from draft {} for tenant {}", draft.kind(), id, tenantId);
            return new Outcome(Result.CREATED, claimed.get(), created.identifier(), created.url());
        } catch (IOException | RuntimeException e) {
            String error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            pending.markFailed(tenantId, id, error.length() > MAX_ERROR_CHARS ? error.substring(0, MAX_ERROR_CHARS) : error);
            log.warn("Draft {} for tenant {} could not be created: {}", id, tenantId, e.getClass().getSimpleName());
            return new Outcome(Result.FAILED, claimed.get(), null, null);
        }
    }

    /** Drops the draft for the person who asked for it. */
    public Outcome cancel(long tenantId, long id, String user) {
        Optional<PendingAction> found = pending.find(tenantId, id);
        if (found.isEmpty()) {
            return new Outcome(Result.NOT_FOUND, null, null, null);
        }
        PendingAction draft = found.get();
        if (!draft.requestedBy().equals(user)) {
            return new Outcome(Result.NOT_REQUESTER, draft, null, null);
        }
        if (pending.cancel(tenantId, id, user)) {
            return new Outcome(Result.CANCELLED, draft, null, null);
        }
        return new Outcome(Result.ALREADY_HANDLED, pending.find(tenantId, id).orElse(draft), null, null);
    }

    private record Made(String identifier, String url) {}

    private Made create(long tenantId, PendingAction draft) throws IOException {
        if (draft.kind() == Kind.LINEAR_ISSUE) {
            String key = linearConnection.apiKey(tenantId);
            LinearClient.Created issue = linear.createIssue(key, draft.params().get("teamId"), draft.title(),
                    draft.description(), priorityNumber(draft.params().get("priority")));
            return new Made(issue.identifier(), issue.url());
        }
        TrelloConnection.Credentials credentials = trelloConnection.credentials(tenantId);
        TrelloClient.CreatedCard card = trello.createCard(credentials.key(), credentials.token(),
                draft.params().get("listId"), draft.title(), draft.description());
        return new Made(draft.title(), card.url());
    }

    /** Linear's numbers: 1 urgent, 2 high, 3 medium, 4 low. No priority or none: left unset. */
    static Integer priorityNumber(String priority) {
        if (priority == null) {
            return null;
        }
        return switch (priority.toLowerCase(java.util.Locale.ROOT)) {
            case "urgent" -> 1;
            case "high" -> 2;
            case "medium" -> 3;
            case "low" -> 4;
            default -> null;
        };
    }
}
