package io.github.samzhu.skillshub.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

/**
 * AC-1：平台總覽 endpoint 契約驗證 — {@code GET /api/v1/analytics/overview}。
 *
 * <p>S025b T03 — 從 {@code @SpringBootTest(RANDOM_PORT) + TestRestTemplate}（行為近 E2E）
 * 改 {@code @WebMvcTest} slice + extends {@link WebMvcSliceTestBase} +
 * {@code @MockitoBean AnalyticsService}：原 test 透過 POST 真 skill row + 查 analytics
 * 屬 E2E 範圍（service 邏輯 + DB），改 slice 後僅驗 controller HTTP 契約 + service 注入；
 * AnalyticsService 業務邏輯由 service 層 unit/integration test 涵蓋（非本 test 範圍）。
 */
@WebMvcTest(AnalyticsController.class)
class AnalyticsControllerTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalyticsService analyticsService;

    @Test
    @DisplayName("AC-1: 平台總覽 — GET /analytics/overview returns stats")
    void overviewStats() throws Exception {
        // mock service 回 fixture stats — 驗 controller serialization + endpoint mapping
        var fixture = new OverviewStats(2L, 10L, 1L, List.of());
        Mockito.when(analyticsService.getOverview()).thenReturn(fixture);

        mockMvc.perform(get("/api/v1/analytics/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalSkills").value(2))
            .andExpect(jsonPath("$.totalDownloads").value(10))
            .andExpect(jsonPath("$.topSkills").isArray());
    }
}
