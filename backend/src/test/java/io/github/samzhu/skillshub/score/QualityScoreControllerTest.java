package io.github.samzhu.skillshub.score;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.score.domain.QualityAxis;
import io.github.samzhu.skillshub.score.domain.SkillScore;
import io.github.samzhu.skillshub.score.domain.SkillScoreRepository;
import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

/**
 * S135a T06 AC-S135a-3/AC-S135a-4 — QualityScoreController @WebMvcTest slice。
 *
 * <p>5 BDD scenarios: 200 with 3-axis + total, versionId filter, 404 not-evaluated,
 * 404 nonExistent id, 403 unauthorized。
 */
@WebMvcTest(QualityScoreController.class)
class QualityScoreControllerTest extends WebMvcSliceTestBase {

    private static final String SKILL_ID = "skill-123";
    private static final String VERSION_ID = "ver-abc";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SkillScoreRepository scoreRepo;

    @MockitoBean
    private SkillScoreCalculator skillScoreCalculator;

    @Test
    @DisplayName("AC-S135a-3: GET /scores → 200 with 3-axis breakdown + total")
    @Tag("AC-S135a-3")
    void getScores_returnsAllAxes() throws Exception {
        when(permissionEvaluator.hasPermission(any(), any(), any(), any())).thenReturn(true);
        when(scoreRepo.findLatestBySkillId(SKILL_ID)).thenReturn(threeRows(SKILL_ID, VERSION_ID));

        mockMvc.perform(get("/api/v1/skills/{id}/scores", SKILL_ID).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skillId").value(SKILL_ID))
                .andExpect(jsonPath("$.skillVersionId").value(VERSION_ID))
                .andExpect(jsonPath("$.validation.totalScore").value(100))
                .andExpect(jsonPath("$.implementation.totalScore").value(67))
                .andExpect(jsonPath("$.activation.totalScore").value(100))
                // total = round(0.2×100 + 0.4×67 + 0.4×100) = round(20+26.8+40) = round(86.8) = 87
                .andExpect(jsonPath("$.total").value(87));
    }

    @Test
    @DisplayName("AC-S135a-3: GET /scores?versionId → returns that version's scores")
    @Tag("AC-S135a-3")
    void getScores_withVersionId_returnsVersionRows() throws Exception {
        when(permissionEvaluator.hasPermission(any(), any(), any(), any())).thenReturn(true);
        var v110Id = "ver-v110";
        when(scoreRepo.findLatestBySkillVersionId(v110Id)).thenReturn(threeRows(SKILL_ID, v110Id));

        mockMvc.perform(get("/api/v1/skills/{id}/scores", SKILL_ID)
                        .param("versionId", v110Id)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skillVersionId").value(v110Id));
    }

    @Test
    @DisplayName("AC-S135a-4: GET /scores → 404 QUALITY_NOT_EVALUATED when no rows")
    @Tag("AC-S135a-4")
    void getScores_notEvaluated_returns404() throws Exception {
        when(permissionEvaluator.hasPermission(any(), any(), any(), any())).thenReturn(true);
        when(scoreRepo.findLatestBySkillId(SKILL_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/skills/{id}/scores", SKILL_ID).with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("QUALITY_NOT_EVALUATED"))
                .andExpect(jsonPath("$.message").value("Score will be available shortly after publish."));
    }

    @Test
    @DisplayName("AC-S135a-4: GET /scores nonExistent id → 404 QUALITY_NOT_EVALUATED")
    @Tag("AC-S135a-4")
    void getScores_nonExistentSkill_returns404() throws Exception {
        when(permissionEvaluator.hasPermission(any(), any(), any(), any())).thenReturn(true);
        when(scoreRepo.findLatestBySkillId("no-such-skill")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/skills/{id}/scores", "no-such-skill").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("QUALITY_NOT_EVALUATED"));
    }

    @Test
    @DisplayName("AC-S135a-3: GET /scores without auth → 401")
    @Tag("AC-S135a-3")
    void getScores_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/skills/{id}/scores", SKILL_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static List<SkillScore> threeRows(String skillId, String versionId) {
        return List.of(
                score(skillId, versionId, QualityAxis.VALIDATION, new BigDecimal("100.00")),
                score(skillId, versionId, QualityAxis.IMPLEMENTATION, new BigDecimal("66.67")),
                score(skillId, versionId, QualityAxis.ACTIVATION, new BigDecimal("100.00")));
    }

    private static SkillScore score(String skillId, String versionId, QualityAxis axis, BigDecimal total) {
        return SkillScore.of(skillId, versionId, "1.0.0", axis, total,
                Map.of("dim1", Map.of("score", 3, "reasoning", "ok")),
                "stub@v0", "evt-" + axis.name());
    }
}
