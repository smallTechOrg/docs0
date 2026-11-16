package com.docs0.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.docs0.IntegrationTest;
import com.docs0.slack.CorrectionDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real-key check that the model sorts thread replies into correction or question. Needs DOCS0_LIVE=1. */
@EnabledIfEnvironmentVariable(named = "DOCS0_LIVE", matches = "1")
class LiveClassifierCheck extends IntegrationTest {

    @DynamicPropertySource
    static void realKey(DynamicPropertyRegistry registry) {
        registry.add("docs0.anthropic.api-key", () -> System.getenv().getOrDefault("DOCS0_ANTHROPIC_API_KEY", ""));
    }

    @Autowired
    CorrectionDetector detector;

    private static final String Q = "How long do monthly customers have to ask for a refund?";
    private static final String A = "Monthly-plan customers have 21 days from purchase [1].";

    @Test
    void sortsCorrectionsFromQuestions() {
        var fix = detector.classify(Q, A, "That's wrong, it's actually 30 days now.").orElseThrow();
        System.out.println("LIVE CLASSIFIER correction -> " + fix.correction() + " fact=" + fix.fact()
                + " cost=" + fix.costUsd());
        assertThat(fix.correction()).isTrue();
        assertThat(fix.fact()).contains("30");

        var q = detector.classify(Q, A, "Thanks. And what about annual plans?").orElseThrow();
        System.out.println("LIVE CLASSIFIER question -> " + q.correction() + " cost=" + q.costUsd());
        assertThat(q.correction()).isFalse();
    }
}
