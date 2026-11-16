package com.docs0.agent;

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

    record AgentRequest(long tenantId, Audience audience, String question, List<Exchange> history) {}

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
            List<Long> correctionIds) {

        /** A result that used no corrections. */
        public AgentResult(Kind kind, String answer, Confidence confidence, List<Citation> citations,
                List<String> followUps, List<String> suggestedPeople, int steps, long inputTokens,
                long outputTokens, BigDecimal costUsd, long durationMs, String error) {
            this(kind, answer, confidence, citations, followUps, suggestedPeople, steps, inputTokens, outputTokens,
                    costUsd, durationMs, error, List.of());
        }
    }

    /** Receives short human-readable progress lines, e.g. "Searching for pricing 2026…". */
    @FunctionalInterface
    interface ProgressListener {
        void onStatus(String text);

        ProgressListener NONE = text -> {};
    }
}
