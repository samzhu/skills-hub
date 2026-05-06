package io.github.samzhu.skillshub.score.judge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.genai.Client;

import io.github.samzhu.skillshub.SkillshubProperties;

/**
 * S135a: Registers the real {@link QualityJudge} bean when {@code skillshub.quality.judge.enabled=true}
 * (default). When disabled (test env), {@code StubQualityJudge} fills the slot.
 *
 * <p>不再對 {@code skillshub.genai.api-key} 做 build-time 條件 — Spring AOT 會在 build time 評估
 * {@code @ConditionalOnProperty}，CI/AOT 階段 api-key 缺席會把整個 bean 從 baked context 排除，
 * 導致 runtime {@code QualityScoreService}（unconditional consumer）無法 satisfy 而炸。
 * 改由 factory method runtime 觸發時讀取 api-key（沒設則 client builder 失敗，fail-fast）。
 *
 * <p>Mirrors {@code ScannerAiConfig} pattern — manual ChatModel construction (API key mode, not Vertex AI)。
 * 差別：Scanner 的 LlmJudge 是 optional consumer (ScanOrchestrator 可缺席跳過)，所以 Scanner 端
 * 雙條件 gate 沒問題；Quality 端 unconditional consumer 必須改寫。
 */
@Configuration
public class QualityJudgeConfig {

    private static final Logger log = LoggerFactory.getLogger(QualityJudgeConfig.class);

    @Bean
    @ConditionalOnProperty(name = "skillshub.quality.judge.enabled", matchIfMissing = true)
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
    @ConditionalOnProperty(name = "skillshub.quality.judge.enabled", matchIfMissing = true)
    QualityJudge qualityJudge(GoogleGenAiChatModel qualityJudgeChatModel) {
        return new QualityJudge(qualityJudgeChatModel);
    }
}
