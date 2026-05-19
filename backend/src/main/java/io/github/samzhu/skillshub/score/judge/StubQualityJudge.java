package io.github.samzhu.skillshub.score.judge;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** S202: stub judge when quality judging is disabled; keeps score rows deterministic. */
@Component
@ConditionalOnProperty(name = "skillshub.quality.judge.enabled", havingValue = "false")
public class StubQualityJudge extends QualityJudge {

    public StubQualityJudge() {
        super();
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
        return new JudgeResponse(dims, "stub verdict - all dims score 2");
    }
}
