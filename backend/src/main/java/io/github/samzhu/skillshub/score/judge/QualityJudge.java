package io.github.samzhu.skillshub.score.judge;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;

/**
 * S135a: LLM judge for SKILL.md quality evaluation — wraps Spring AI ChatClient.
 *
 * <p>Instantiated by {@link QualityJudgeConfig} when {@code skillshub.quality.judge.enabled=true}
 * AND {@code skillshub.genai.api-key} is set. Test environments use {@code StubQualityJudge}
 * (subclass) when judge is disabled.
 *
 * <p>temperature=0.0 for maximum determinism — reproducibility is an AC (AC-S135a-8).
 */
public class QualityJudge {

    private final ChatClient client;

    /** Protected no-arg ctor for StubQualityJudge subclass — real client not used in stub. */
    protected QualityJudge() {
        this.client = null;
    }

    public QualityJudge(GoogleGenAiChatModel gemini) {
        this.client = ChatClient.builder(gemini).build();
    }

    /**
     * Evaluate the SKILL.md body content on 4 Implementation dimensions.
     * Throws RuntimeException on failure — re-throw triggers outbox retry (AC-S135a-10).
     */
    public JudgeResponse judgeImplementation(String skillBody) {
        return client.prompt()
                .system(RubricPrompts.IMPLEMENTATION_SYSTEM)
                .user(u -> u.text("<skill_body>{body}</skill_body>").param("body", skillBody))
                .call()
                .entity(JudgeResponse.class);
    }

    /**
     * Evaluate the SKILL.md description on 4 Activation dimensions.
     * Throws RuntimeException on failure — re-throw triggers outbox retry (AC-S135a-10).
     */
    public JudgeResponse judgeActivation(String description) {
        return client.prompt()
                .system(RubricPrompts.ACTIVATION_SYSTEM)
                .user(u -> u.text("<skill_description>{desc}</skill_description>").param("desc", description))
                .call()
                .entity(JudgeResponse.class);
    }

    /** Version string stored in skill_scores.evaluator_version — bump when prompt changes. */
    public String evaluatorVersion() {
        return "gemini-2.5-flash@2026-05-06-prompt-v1";
    }
}
