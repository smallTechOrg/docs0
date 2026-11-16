package com.docs0.slack;

import com.docs0.conversation.InteractionRepository;
import com.docs0.conversation.InteractionRepository.Logged;
import com.docs0.feedback.FeedbackRepository;
import com.docs0.feedback.FeedbackRepository.Rating;
import com.docs0.tenant.TenantService;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns thumbs up and thumbs down reactions on docs0's answers into feedback. The first
 * thumbs down on an answer also asks, once, what was wrong.
 */
@Component
public class ReactionHandler {

    static final String PROMPT =
            "Sorry about that. What was wrong, or what should the answer have said? Reply here and I'll pass it to an admin.";

    private static final Logger log = LoggerFactory.getLogger(ReactionHandler.class);
    private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

    private final TenantService tenants;
    private final InteractionRepository interactions;
    private final FeedbackRepository feedback;
    private final SlackMessenger messenger;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public ReactionHandler(TenantService tenants, InteractionRepository interactions, FeedbackRepository feedback,
            SlackMessenger messenger) {
        this.tenants = tenants;
        this.interactions = interactions;
        this.feedback = feedback;
        this.messenger = messenger;
    }

    /**
     * Handles one reaction. Cheap and non-blocking: safe to call on the Slack event thread.
     * The returned future never completes exceptionally.
     */
    public CompletableFuture<Void> handle(ReactionEvent event) {
        if (event == null || isBlank(event.channel()) || isBlank(event.user()) || isBlank(event.itemTs())) {
            return DONE;
        }
        Rating rating = rating(event.reaction());
        if (rating == null) {
            return DONE;
        }
        return CompletableFuture.runAsync(() -> {
            try {
                process(event, rating);
            } catch (Exception e) {
                log.warn("Reaction could not be handled: channel={} error={}", event.channel(),
                        e instanceof SlackMessenger.SlackCallException slack ? slack.code() : e.getClass().getName());
            }
        }, workers);
    }

    /** UP for +1 and thumbsup, DOWN for -1 and thumbsdown (any skin tone), null for every other emoji. */
    static Rating rating(String reaction) {
        if (reaction == null) {
            return null;
        }
        int tone = reaction.indexOf("::");
        String name = tone < 0 ? reaction : reaction.substring(0, tone);
        return switch (name) {
            case "+1", "thumbsup" -> Rating.UP;
            case "-1", "thumbsdown" -> Rating.DOWN;
            default -> null;
        };
    }

    private void process(ReactionEvent event, Rating rating) {
        if (event.user().equals(botUserId())) {
            return;
        }
        long tenantId = tenants.current().id();
        Optional<Logged> answer = interactions.findByAnswerTs(tenantId, event.channel(), event.itemTs());
        if (answer.isEmpty()) {
            return;
        }
        Logged logged = answer.get();
        if (!event.added()) {
            feedback.remove(tenantId, logged.id(), event.user(), rating);
            log.info("Reaction removed: tenant={} interaction={} rating={}", tenantId, logged.id(), rating);
            return;
        }
        feedback.record(tenantId, logged.id(), event.user(), rating);
        log.info("Reaction recorded: tenant={} interaction={} rating={}", tenantId, logged.id(), rating);
        if (rating == Rating.DOWN && feedback.createPrompt(tenantId, logged.conversationId(), logged.id())) {
            try {
                messenger.postMessage(event.channel(), logged.threadTs().isEmpty() ? null : logged.threadTs(),
                        PROMPT, null);
            } catch (RuntimeException e) {
                // Not posted: forget the prompt so the next thumbs down can ask again.
                feedback.deletePrompt(tenantId, logged.id());
                throw e;
            }
        }
    }

    private String botUserId() {
        try {
            return messenger.botUserId();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
