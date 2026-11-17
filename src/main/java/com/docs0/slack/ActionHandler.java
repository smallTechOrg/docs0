package com.docs0.slack;

import com.docs0.action.ActionService;
import com.docs0.action.ActionService.Outcome;
import com.docs0.tenant.TenantService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles a click on Create or Cancel under a drafted Linear issue or Trello card. The click is
 * acknowledged by Slack elsewhere; the work runs here on its own thread.
 */
@Component
public class ActionHandler {

    static final String NOT_YOURS = "Only the person who asked can create or cancel this draft.";
    static final String ALREADY_DONE = "That draft has already been dealt with.";
    static final String PAUSED = "docs0 is paused for your workspace, so nothing can be created right now.";
    static final String GONE = "I can't find that draft any more.";

    private static final Logger log = LoggerFactory.getLogger(ActionHandler.class);
    private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

    /** Only asks whether a tenant is active: which tenant a click belongs to is decided by the caller. */
    private final TenantService tenants;
    private final ActionService actions;
    private final SlackMessengers messengers;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public ActionHandler(TenantService tenants, ActionService actions, SlackMessengers messengers) {
        this.tenants = tenants;
        this.actions = actions;
        this.messengers = messengers;
    }

    /**
     * @param messageTs the ts of the draft message that was clicked
     * @param threadTs the thread it is in, or null for an unthreaded DM
     * @param actionId {@link AnswerRenderer#DRAFT_CREATE_ACTION} or {@link AnswerRenderer#DRAFT_CANCEL_ACTION}
     * @param value the draft's id, as the button carried it
     * @return completes when the click has been dealt with; never completes exceptionally
     */
    public CompletableFuture<Void> handle(long tenantId, String channel, String messageTs, String threadTs, String user,
            String actionId, String value) {
        if (isBlank(channel) || isBlank(messageTs) || isBlank(user) || value == null) {
            return DONE;
        }
        boolean create = AnswerRenderer.DRAFT_CREATE_ACTION.equals(actionId);
        if (!create && !AnswerRenderer.DRAFT_CANCEL_ACTION.equals(actionId)) {
            return DONE;
        }
        long id;
        try {
            id = Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return DONE;
        }
        return CompletableFuture.runAsync(() -> {
            try {
                process(tenantId, channel, messageTs, threadTs, user, create, id);
            } catch (RuntimeException e) {
                log.warn("Draft click could not be handled: channel={} error={}", channel, reason(e));
            }
        }, workers);
    }

    private void process(long tenantId, String channel, String messageTs, String threadTs, String user, boolean create,
            long id) {
        SlackMessenger messenger = messengers.forTenant(tenantId);
        if (!tenants.isActive(tenantId)) {
            // A paused tenant creates nothing in Linear or Trello.
            ephemeral(messenger, channel, threadTs, user, PAUSED);
            return;
        }
        Outcome outcome = create ? actions.confirm(tenantId, id, user) : actions.cancel(tenantId, id, user);
        log.info("Draft {} clicked: {} -> {}", id, create ? "create" : "cancel", outcome.result());
        switch (outcome.result()) {
            case NOT_REQUESTER -> ephemeral(messenger, channel, threadTs, user, NOT_YOURS);
            case ALREADY_HANDLED -> ephemeral(messenger, channel, threadTs, user, ALREADY_DONE);
            case NOT_FOUND -> ephemeral(messenger, channel, threadTs, user, GONE);
            default -> {
                AnswerRenderer.Rendered rendered = AnswerRenderer.renderDraftOutcome(outcome);
                try {
                    messenger.updateMessage(channel, messageTs, rendered.text(), rendered.blocks());
                } catch (RuntimeException e) {
                    log.warn("Draft message could not be updated: channel={} error={}", channel, reason(e));
                    // The item may exist now; tell the person where it is instead of leaving the buttons up.
                    messenger.postMessage(channel, threadTs, rendered.text(), null);
                }
            }
        }
    }

    private static void ephemeral(SlackMessenger messenger, String channel, String threadTs, String user, String text) {
        try {
            messenger.postEphemeral(channel, threadTs, user, text);
        } catch (RuntimeException e) {
            log.warn("Reply to a draft click could not be sent: channel={} error={}", channel, reason(e));
        }
    }

    private static String reason(Throwable t) {
        return t instanceof SlackMessenger.SlackCallException slack ? slack.code() : t.getClass().getName();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
