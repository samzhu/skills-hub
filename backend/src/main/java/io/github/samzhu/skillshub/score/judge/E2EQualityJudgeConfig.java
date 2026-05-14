package io.github.samzhu.skillshub.score.judge;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** S172-T06: e2e bootRun uses a deterministic judge instead of Gemini. */
@Configuration
@Profile("e2e")
class E2EQualityJudgeConfig {

    @Bean
    @ConditionalOnMissingBean(QualityJudge.class)
    @ConditionalOnProperty(name = "skillshub.quality.judge.enabled", havingValue = "false")
    QualityJudge e2eQualityJudge() {
        return new DeterministicQualityJudge();
    }

    private static final class DeterministicQualityJudge extends QualityJudge {

        @Override
        public JudgeResponse judgeImplementation(String skillBody) {
            return fixedResponse("Conciseness", "Actionability", "WorkflowClarity", "ProgressiveDisclosure");
        }

        @Override
        public JudgeResponse judgeActivation(String description) {
            return fixedResponse("Specificity", "Completeness", "TriggerTermQuality", "Distinctiveness");
        }

        @Override
        public String evaluatorVersion() {
            return "e2e-stub@v0";
        }

        private JudgeResponse fixedResponse(String d1, String d2, String d3, String d4) {
            var dims = List.of(
                    new JudgeResponse.DimensionScore(d1, 2, "e2e score"),
                    new JudgeResponse.DimensionScore(d2, 2, "e2e score"),
                    new JudgeResponse.DimensionScore(d3, 2, "e2e score"),
                    new JudgeResponse.DimensionScore(d4, 2, "e2e score")
            );
            return new JudgeResponse(dims, "e2e verdict");
        }
    }
}
