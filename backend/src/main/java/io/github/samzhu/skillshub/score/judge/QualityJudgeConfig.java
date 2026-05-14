package io.github.samzhu.skillshub.score.judge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * S135a: Registers the real {@link QualityJudge} bean when {@code skillshub.quality.judge.enabled=true}
 * (default). When disabled (test env), {@code StubQualityJudge} fills the slot.
 *
 * <p>不再對 {@code skillshub.genai.api-key} 做 build-time 條件 — Spring AOT 會在 build time 評估
 * {@code @ConditionalOnProperty}，CI/AOT 階段 api-key 缺席會把整個 bean 從 baked context 排除，
 * 導致 runtime {@code QualityScoreService}（unconditional consumer）無法 satisfy 而炸。
 * 改由 factory method runtime 觸發時讀取 api-key（沒設則 client builder 失敗，fail-fast）。
 *
 * <p>S171: Google provider builders live in AiModelConfig. This config only wires the
 * quality use case to its named {@link ChatClient}.
 */
@Configuration
public class QualityJudgeConfig {

    private static final Logger log = LoggerFactory.getLogger(QualityJudgeConfig.class);

    @Bean
    @ConditionalOnProperty(name = "skillshub.quality.judge.enabled", matchIfMissing = true)
    QualityJudge qualityJudge(@Qualifier("qualityJudgeChatClient") ChatClient qualityJudgeChatClient) {
        log.info("Initialising quality judge with named ChatClient");
        return new QualityJudge(qualityJudgeChatClient);
    }
}
