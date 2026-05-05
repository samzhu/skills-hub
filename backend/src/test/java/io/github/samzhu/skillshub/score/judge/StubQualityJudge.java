package io.github.samzhu.skillshub.score.judge;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * S135a: Deterministic stub — activated when {@code skillshub.quality.judge.enabled=false} (test env).
 * Returns fixed scores so unit/slice tests don't need a real Gemini API key.
 *
 * <p>Extends {@link QualityJudge} so callers injecting QualityJudge receive this stub seamlessly.
 */
@Component
@ConditionalOnProperty(name = "skillshub.quality.judge.enabled", havingValue = "false")
public class StubQualityJudge extends QualityJudge {

    public StubQualityJudge() {
        super();  // protected no-arg ctor — real ChatClient not used
    }

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
        return "stub@v0";
    }

    private JudgeResponse fixedResponse(String d1, String d2, String d3, String d4) {
        var dims = List.of(
                new JudgeResponse.DimensionScore(d1, 2, "stub score"),
                new JudgeResponse.DimensionScore(d2, 2, "stub score"),
                new JudgeResponse.DimensionScore(d3, 2, "stub score"),
                new JudgeResponse.DimensionScore(d4, 2, "stub score")
        );
        return new JudgeResponse(dims, "stub verdict — all dims score 2");
    }
}
