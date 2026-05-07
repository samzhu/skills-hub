package io.github.samzhu.skillshub.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.api.SecurityNotScannedException;
import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

/**
 * S142b T02 — SecurityReportController WebMvc slice test。
 * AC-S142b-6 / AC-S142b-10 contract verification。
 */
@WebMvcTest(SecurityReportController.class)
class SecurityReportControllerTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SecurityReportService securityReportService;

    @Test
    @DisplayName("AC-S142b-6: GET /security-report → 200 with 4-quad checks and overall")
    void getReport_returnsSecurityReport() throws Exception {
        var skillId = "skill-abc";
        var report = new SecurityReportResponse(
                skillId, "ver-id-1", "1.0.0", Instant.parse("2026-05-01T00:00:00Z"),
                "risk-scanner v1.0", "2026-05", "fail",
                Map.of(
                        "shell", new SecurityReportResponse.CheckDetail("pass", null),
                        "paths", new SecurityReportResponse.CheckDetail("fail", "SENSITIVE_PATH_SSH · line 5: SSH key"),
                        "secrets", new SecurityReportResponse.CheckDetail("fail", "GITHUB_PAT · line 14: Hardcoded GitHub PAT"),
                        "deps", new SecurityReportResponse.CheckDetail("pass", null)));

        when(permissionEvaluator.hasPermission(any(), eq(skillId), eq("Skill"), eq("read")))
                .thenReturn(true);
        when(securityReportService.getReport(eq(skillId), any())).thenReturn(report);

        mockMvc.perform(get("/api/v1/skills/{id}/security-report", skillId)
                        .with(jwt().jwt(j -> j.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skillId").value(skillId))
                .andExpect(jsonPath("$.engineVersion").value("risk-scanner v1.0"))
                .andExpect(jsonPath("$.ruleSetVersion").value("2026-05"))
                .andExpect(jsonPath("$.overall").value("fail"))
                .andExpect(jsonPath("$.checks.shell.status").value("pass"))
                .andExpect(jsonPath("$.checks.secrets.status").value("fail"))
                .andExpect(jsonPath("$.checks.paths.status").value("fail"))
                .andExpect(jsonPath("$.checks.deps.status").value("pass"));
    }

    @Test
    @DisplayName("AC-S142b-10: skill 未掃描 → 404 SECURITY_NOT_SCANNED")
    void getReport_notScanned_returns404() throws Exception {
        var skillId = "skill-not-scanned";
        when(permissionEvaluator.hasPermission(any(), eq(skillId), eq("Skill"), eq("read")))
                .thenReturn(true);
        when(securityReportService.getReport(eq(skillId), any()))
                .thenThrow(new SecurityNotScannedException(skillId));

        mockMvc.perform(get("/api/v1/skills/{id}/security-report", skillId)
                        .with(jwt().jwt(j -> j.subject("user-1"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SECURITY_NOT_SCANNED"))
                .andExpect(jsonPath("$.message").value("Security report will be available shortly after publish."));
    }

    @Test
    @DisplayName("Anonymous user → 401 Unauthorized")
    void getReport_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/skills/some-id/security-report"))
                .andExpect(status().isUnauthorized());
    }
}
