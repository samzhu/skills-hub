package io.github.samzhu.skillshub.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AC-2 (/me 部分) + AC-7：LAB 模式下 {@code /api/v1/me} 與既有 {@code /api/v1/skills} 行為。
 *
 * <p>S025a-T04: extends {@link LabModeTestBase} 收斂 cache key（per spec §4.2）。
 * Base class 已宣告 {@code @SpringBootTest} + {@code @AutoConfigureMockMvc} +
 * {@code @Import(TestcontainersConfiguration.class)} + {@code @TestPropertySource(oauth.enabled=false)}。
 *
 * <p>觸發 SecurityConfig 走 LAB 分支：JwtDecoder bean 不建立、SecurityFilterChain anyRequest
 * permitAll、{@link LabSecurityFilter} 注入預設 lab user（principal=lab-user, authorities=[ROLE_admin]）。
 */
class LabModeMeControllerTest extends LabModeTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: LAB 模式下 GET /api/v1/me 無 token → 200 + sub=lab-user, roles=[admin]，6 欄 shape 完整")
    void labMode_meWithoutToken_returns200WithLabUserPayload() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sub").value("lab-user"))
            .andExpect(jsonPath("$.roles[0]").value("admin"))
            // 6 欄 shape 對 OAuth 模式對齊（避免前端因模式不同收到不同 schema）
            .andExpect(jsonPath("$.groups").isArray())
            .andExpect(jsonPath("$.groups").isEmpty())
            .andExpect(jsonPath("$.companyId").doesNotExist())  // null in JSON 預設不輸出 key
            .andExpect(jsonPath("$.deptId").doesNotExist())
            .andExpect(jsonPath("$.scope").value(""));
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: LAB 模式下既有 GET /api/v1/skills 無 token → 200（S001 行為保留）")
    void labMode_existingSkillsApi_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/skills"))
            .andExpect(status().isOk());
    }
}
