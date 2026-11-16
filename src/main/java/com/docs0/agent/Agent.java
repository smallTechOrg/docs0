package com.docs0.agent;

import com.docs0.action.PendingAction;
import com.docs0.knowledge.Audience;
import java.math.BigDecimal;
import java.util.List;

/** Answers one question from the tenant's knowledge. Contract between the Slack layer and the agent. */
public interface Agent {

    /** False when no LLM credential is configured; callers should say so instead of calling answer. */
    boolean available();

    /**
     * Runs one agentic search loop. Never throws for model or tool failures: those come
     * back as a result with {@link Kind#ERROR} and {@code error} set.
     */
    AgentResult answer(AgentRequest request, ProgressListener progress);

    enum Kind { ANSWER, CLARIFY, UNKNOWN, ERROR }

    enum Confidence { HIGH, MEDIUM, LOW }

    /** One earlier question and docs0's final answer text, oldest first in a history list. */
    record Exchange(String question, String answer) {}

    /**
     * {@code conversationId} and {@code requesterSlackId} say where and who the question came from; the
     * tools that draft an issue or card need them. Without them (0 and null) nothing can be drafted.
     */
    record AgentRequest(long tenantId, Audience audience, String question, List<Exchange> history,
            long conversationId, String requesterSlackId) {

        /** A question that cannot lead to a draft. */
        public AgentRequest(long tenantId, Audience audience, String question, List<Exchange> history) {
            this(tenantId, audience, question, history, 0, null);
        }
    }

    /** A verified citation: the quote occurs in the document. {@code index} is the [n] used in the answer. */
    record Citation(int index, long documentId, String title, String url, String quote) {}

    record AgentResult(
            Kind kind,
            String answer,
            Confidence confidence,
            List<Citation> citations,
            List<String> followUps,
            List<String> suggestedPeople,
            int steps,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd,
            long durationMs,
            String error,
            /** Ids of the approved corrections that were put in front of the model for this answer. */
            List<Long> correctionIds,
            /** Issues or cards drafted for the asker to confirm; nothing has been created yet. */
            List<PendingAction> drafts) {

        /** A result that drafted nothing. */
        public AgentResult(Kind kind, String answer, Confidence confidence, List<Citation> citations,
                List<String> followUps, List<String> suggestedPeople, int steps, long inputTokens,
                long outputTokens, BigDecimal costUsd, long durationMs, String error, List<Long> correctionIds) {
            this(kind, answer, confidence, citations, followUps, suggestedPeople, steps, inputTokens, outputTokens,
                    costUsd, durationMs, error, correctionIds, List.of());
        }

        /** A result that used no corrections and drafted nothing. */
        public AgentResult(Kind kind, String answer, Confidence confidence, List<Citation> citations,
                List<String> followUps, List<String> suggestedPeople, int steps, long inputTokens,
                long outputTokens, BigDecimal costUsd, long durationMs, String error) {
            this(kind, answer, confidence, citations, followUps, suggestedPeople, steps, inputTokens, outputTokens,
                    costUsd, durationMs, error, List.of(), List.of());
        }
    }

    /** Receives short human-readable progress lines, e.g. "Searching for pricing 2026…". */
    @FunctionalInterface
    interface ProgressListener {
        void onStatus(String text);

        ProgressListener NONE = text -> {};
    }
}
