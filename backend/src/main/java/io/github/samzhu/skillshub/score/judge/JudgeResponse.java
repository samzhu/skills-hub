package io.github.samzhu.skillshub.score.judge;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * S135a: LLM judge response schema — Spring AI BeanOutputConverter generates JSON schema from this record.
 * All @JsonClassDescription / @JsonPropertyDescription annotations guide the LLM.
 */
@JsonClassDescription("Quality evaluation result for one SKILL.md axis.")
public record JudgeResponse(

        @JsonPropertyDescription("List of dimension scores for this axis. Provide exactly 4 entries.")
        List<DimensionScore> scores,

        @JsonPropertyDescription("One sentence overall verdict summarizing this axis quality.")
        String verdict

) {

    @JsonClassDescription("Score and reasoning for one evaluation dimension.")
    public record DimensionScore(

            @JsonPropertyDescription("Name of the evaluation dimension (e.g. Conciseness).")
            String dimension,

            @JsonPropertyDescription("Integer score 0–3: 0=missing/absent, 1=weak, 2=adequate, 3=excellent.")
            int score,

            @JsonPropertyDescription("1–2 sentence explanation justifying the score. Shown directly in UI.")
            String reasoning

    ) {}
}
