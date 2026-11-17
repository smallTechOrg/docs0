package com.docs0.slack;

import com.docs0.budget.BudgetService;
import com.docs0.conversation.Conversation;
import com.docs0.conversation.ConversationRepository;
import com.docs0.conversation.InteractionRepository;
import com.docs0.conversation.InteractionRepository.Logged;
import com.docs0.feedback.CorrectionRepository;
import com.docs0.feedback.FeedbackRepository;
import com.docs0.tenant.TenantService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A reply in a thread (or DM) docs0 has answered: if it corrects the answer, stores it as a
 * pending correction for an admin instead of treating it as a new question.
 */
@Component
public class CorrectionFlow {

    static final String THANKS =
            "Thanks, I've passed that to an admin to review. It won't change my answers until they approve it.";
    private static final int MAX_FACT_CHARS = 2000;
    /** The reply is cut to this many characters before the model sees it. */
    static final int MAX_REPLY_CHARS = 1000;

    private static final Logger log = LoggerFactory.getLogger(CorrectionFlow.class);

    private final CorrectionDetector detector;
    private final ConversationRepository conversations;
    private final InteractionRepository interactions;
    private final FeedbackRepository feedback;
    private final CorrectionRepository corrections;
    private final SlackMessengers messengers;
    private final TenantService tenants;
    private final BudgetService budget;

    public CorrectionFlow(CorrectionDetector detector, ConversationRepository conversations,
            InteractionRepository interactions, FeedbackRepository feedback, CorrectionRepository corrections,
            SlackMessengers messengers, TenantService tenants, BudgetService budget) {
        this.tenants = tenants;
        this.budget = budget;
        this.detector = detector;
        this.conversations = conversations;
        this.interactions = interactions;
        this.feedback = feedback;
        this.corrections = corrections;
        this.messengers = messengers;
    }

    /**
     * Returns true when the reply was a correction and has been handled (stored and
     * acknowledged). False means: carry on and answer it as a question. Never throws.
     */
    public boolean handle(long tenantId, String channel, String replyThreadTs, String conversationThreadTs,
            boolean dm, String user, String replyText) {
        try {
            // A suspended or over-budget tenant gets no model call and no stored correction here: the normal
            // question flow produces its refusal.
            if (!tenants.isActive(tenantId) || budget.exhausted(tenantId)) {
                return false;
            }
            String text = replyText.length() <= MAX_REPLY_CHARS ? replyText : replyText.substring(0, MAX_REPLY_CHARS);
            Optional<Conversation> conversation = conversations.find(tenantId, channel, conversationThreadTs);
            if (conversation.isEmpty()) {
                return false;
            }
            Optional<Long> prompted = feedback.pendingPrompt(tenantId, conversation.get().id());
            if (prompted.isEmpty() && !CorrectionDetector.looksLikeCorrection(text)) {
                return false;
            }
            Optional<Logged> target = prompted.isPresent()
                    ? interactions.findById(tenantId, prompted.get())
                    : interactions.latestAnswer(tenantId, conversation.get().id());
            if (target.isEmpty()) {
                return false;
            }
            Logged answer = target.get();

            Optional<CorrectionDetector.Verdict> verdict = detector.classify(answer.question(), answer.answer(), text);
            if (verdict.isEmpty()) {
                return false;
            }
            CorrectionDetector.Verdict v = verdict.get();
            interactions.addCost(tenantId, answer.id(), v.inputTokens(), v.outputTokens(), v.costUsd());
            if (!v.correction()) {
                return false;
            }

            String fact = v.fact().isBlank() ? text : v.fact();
            long id = corrections.insertPending(tenantId, answer.id(), user, authorName(tenantId, user), answer.question(),
                    answer.answer(), cut(fact), dm);
            feedback.clearPrompts(tenantId, conversation.get().id());
            log.info("Correction stored: tenant={} correction={} interaction={} dm={}", tenantId, id, answer.id(), dm);
            try {
                messengers.forTenant(tenantId).postMessage(channel, replyThreadTs, THANKS, null);
            } catch (Exception e) {
                log.warn("Thanks could not be posted: channel={} error={}", channel, e.getClass().getName());
            }
            return true;
        } catch (Exception e) {
            log.warn("Correction could not be handled: tenant={} channel={} error={}", tenantId, channel,
                    e.getClass().getName());
            return false;
        }
    }

    private String authorName(long tenantId, String userId) {
        try {
            String name = messengers.forTenant(tenantId).userDisplayName(userId);
            return name == null || name.isBlank() ? userId : name;
        } catch (Exception e) {
            return userId;
        }
    }

    private static String cut(String text) {
        String t = text.strip();
        return t.length() <= MAX_FACT_CHARS ? t : t.substring(0, MAX_FACT_CHARS);
    }
}
