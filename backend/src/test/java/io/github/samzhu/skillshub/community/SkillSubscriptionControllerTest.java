package io.github.samzhu.skillshub.community;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

/**
 * S145-T01 — SkillSubscriptionController HTTP contract for current-user subscription summary.
 */
@WebMvcTest(SkillSubscriptionController.class)
class SkillSubscriptionControllerTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SkillSubscriptionService service;

    @Test
    @DisplayName("AC-S145-2: GET /me/subscriptions/details returns subscription summary JSON")
    void mySubscriptionDetails_returnsSummaryJson() throws Exception {
        when(service.findSubscriptionDetailsOfCurrentUser()).thenReturn(List.of(
                new SkillSubscriptionService.SkillSubscriptionSummary(
                        "skill-1", "deep-research", "u_author1", "Sam Zhu",
                        "1.2.0", "LOW", "PUBLISHED", Instant.parse("2026-05-08T10:15:30Z"))));

        mockMvc.perform(get("/api/v1/me/subscriptions/details")
                        .with(jwt().jwt(j -> j.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skillId").value("skill-1"))
                .andExpect(jsonPath("$[0].skillName").value("deep-research"))
                .andExpect(jsonPath("$[0].author").value("u_author1"))
                .andExpect(jsonPath("$[0].authorDisplayName").value("Sam Zhu"))
                .andExpect(jsonPath("$[0].latestVersion").value("1.2.0"))
                .andExpect(jsonPath("$[0].riskLevel").value("LOW"))
                .andExpect(jsonPath("$[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$[0].subscribedAt").value("2026-05-08T10:15:30Z"));
    }

    @Test
    @DisplayName("AC-S145-5: GET /me/subscriptions still returns string id list")
    void mySubscriptions_keepsIdListContract() throws Exception {
        when(service.findSubscriptionsOfCurrentUser()).thenReturn(List.of("skill-1"));

        mockMvc.perform(get("/api/v1/me/subscriptions")
                        .with(jwt().jwt(j -> j.subject("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("skill-1"));
    }
}
