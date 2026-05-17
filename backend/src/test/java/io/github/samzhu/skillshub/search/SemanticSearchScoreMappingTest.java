package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class SemanticSearchScoreMappingTest {

    @Test
    @DisplayName("AC-S193-1: score equals one minus cosine distance")
    @Tag("AC-S193-1")
    void scoreEqualsOneMinusCosineDistance() {
        var hit = new SkillSemanticHit(
                "skill-subtitle",
                "產生字幕檔",
                "影片與音訊轉字幕",
                "u_current",
                "video",
                "Video",
                "1.2.3",
                "LOW",
                7L,
                0.25d);

        var result = hit.toResult(null);

        assertThat(result.score()).isEqualTo(0.75d);
    }
}
