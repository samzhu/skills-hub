package io.github.samzhu.skillshub.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AC-2 (/me 部分)：LAB 模式下 {@code /api/v1/me} 行為。
 *
 * <p>S025b T03 — {@code @SpringBootTest extends LabModeTestBase} → {@code @WebMvcTest} slice
 * extends {@link WebMvcSliceTestBase} + {@code @TestPropertySource(properties = "skillshub.security.oauth.enabled=false")}
 * 觸發 {@link SecurityConfig} 走 LAB 分支：JwtDecoder bean 不建立、SecurityFilterChain anyRequest
 * permitAll、{@link LabSecurityFilter} 注入預設 lab user（principal=lab-user, authorities=[ROLE_admin]）。
 *
 * <p><b>S025b deviation</b>：原 test 含第 2 個 {@code /api/v1/skills} smoke assertion 跨
 * {@code SkillQueryController}，slice 內 {@code @WebMvcTest(MeController.class)} 不載 skill controller；
 * 既有 LAB filter permitAll 行為已由本 test {@code /api/v1/me} anonymous→200 覆蓋（驗證
 * permitAll filter chain + LAB user injection）。skills endpoint 在 LAB mode 的 permitAll 行為
 * 由 E2E `S016EndToEndSmokeTest` LAB profile 覆蓋。
 *
 * <p>{@code skillshub.security.oauth.enabled=false} 透過 {@code @TestPropertySource} 覆蓋
 * base class 預設（base 不設此 property）；觸發 {@link SecurityConfig} LAB 分支。
 */
@WebMvcTest(MeController.class)
@TestPropertySource(properties = {
        "management.tracing.enabled=false",  // base 已設；slice 顯式宣告免被 override 機制吃掉
        "skillshub.security.oauth.enabled=false"
})
class LabModeMeControllerTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: LAB 模式下 GET /api/v1/me 無 token → 200 + sub=lab-user, roles=[admin]，6 欄 shape 完整")
    void labMode_meWithoutToken_returns200WithLabUserPayload() throws Exception {
        // LAB filter chain 注入 UsernamePasswordAuthenticationToken，MeController fallback 路徑
        // 走 CurrentUserProvider；mock 回 lab-user 對齊 LabSecurityFilter 預設行為
        Mockito.when(currentUserProvider.current())
                .thenReturn(CurrentUser.synthetic("lab-user", List.of("admin"), List.of(), null));

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
}
