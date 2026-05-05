package io.github.samzhu.skillshub.score.judge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import com.google.genai.Client;

import io.github.samzhu.skillshub.SkillshubProperties;

/**
 * S135a: Registers the real {@link QualityJudge} bean when both conditions are met:
 * {@code skillshub.quality.judge.enabled=true} (default) AND {@code skillshub.genai.api-key} is set.
 *
 * <p>Mirrors {@code ScannerAiConfig} pattern — manual ChatModel construction (API key mode, not Vertex AI).
 * When conditions fail (test env with judge.enabled=false), {@code StubQualityJudge} fills the slot.
 */
@Configuration
public class QualityJudgeConfig {

    private static final Logger log = LoggerFactory.getLogger(QualityJudgeConfig.class);

    @Bean
    @Conditional(JudgeEnabledCondition.class)
    GoogleGenAiChatModel qualityJudgeChatModel(SkillshubProperties props) {
        log.info("Initialising quality judge ChatModel (API key mode, model=GEMINI_2_5_FLASH)");
        var client = Client.builder()
                .apiKey(props.genai().apiKey())
                .build();
        var options = GoogleGenAiChatOptions.builder()
                .model(GoogleGenAiChatModel.ChatModel.GEMINI_2_5_FLASH)
                .temperature(0.0)
                .build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .defaultOptions(options)
                .build();
    }

    @Bean
    @Conditional(JudgeEnabledCondition.class)
    QualityJudge qualityJudge(GoogleGenAiChatModel qualityJudgeChatModel) {
        return new QualityJudge(qualityJudgeChatModel);
    }

    /** Both conditions must pass — same AllNestedConditions pattern as ScannerAiConfig.LlmEnabledCondition. */
    public static class JudgeEnabledCondition extends AllNestedConditions {
        public JudgeEnabledCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        /** Judge can be disabled per-env (e.g. false in test to use StubQualityJudge). */
        @ConditionalOnProperty(name = "skillshub.quality.judge.enabled", matchIfMissing = true)
        static class JudgeEnabled {}

        /** Requires API key — same key as scanner (skillshub.genai.api-key). */
        @ConditionalOnProperty(name = "skillshub.genai.api-key")
        static class ApiKeyPresent {}
    }
}
