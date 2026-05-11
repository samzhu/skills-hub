package io.github.samzhu.skillshub.shared.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * S160b — CSRF feature-flag 啟用後行為驗證。
 *
 * <p>本測試走 {@code @TestPropertySource("skillshub.security.csrf.enabled=true")} 啟用 CSRF；
 * 預設 false 行為（既有 csrf().disable()）由所有現存 mutation slice test 隱含驗證
 * （任何 POST/PUT/DELETE 都沒帶 CSRF token 仍然通過 = CSRF 沒啟）。
 *
 * <p>用 {@code /api/v1/admin/echo} POST？不對，AdminController 只有 GET。改用「假設打到任意
 * POST endpoint」— 啟用 CSRF 後沒帶 token 應該被擋下回 403/401（其實 CSRF 拒收會在 chain
 * 早期，回 403 forbidden CSRF token missing）。但 @WebMvcTest(AdminController) 只 routing
 * AdminController；任意 path POST 因 controller 不掃會 405/404，CSRF 也不會驗到。
 *
 * <p>Trim：本 tick 只驗 Bearer JWT exempt 行為（POST 帶 Bearer 仍通過 — CSRF 對 Bearer
 * 路徑 ignoringRequestMatchers）。Cookie session POST 無 token 拒收的整合測試屬整合層
 * scope（需要走完整 oauth2Login + cookie 流程），拆 S160b'' integration test。
 */
@WebMvcTest(AdminController.class)
@TestPropertySource(properties = "skillshub.security.csrf.enabled=true")
class CsrfFlagTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: CSRF 啟用後 Bearer JWT POST 仍通過（exempt by ignoringRequestMatchers）")
    void bearerJwtPostExemptFromCsrf() throws Exception {
        Mockito.when(currentUserProvider.userId()).thenReturn("admin-001");

        // AdminController 無 POST endpoint，但 SecurityFilterChain 對 Bearer path exempt
        // 才不會在 chain 早期被 CSRF 擋。AdminController POST → 405 Method Not Allowed
        // （routing 階段，比 CSRF 還後面）— 但只要回 405 而非 403 CSRF forbidden 即證
        // exempt 生效。
        mockMvc.perform(post("/api/v1/admin/echo")
                .with(jwt()
                    .jwt(j -> j.subject("admin-001").claim("roles", List.of("admin")))
                    .authorities(new SimpleGrantedAuthority("ROLE_admin"))))
            // 不檢查 405 / 404 細節；只確認不是 CSRF 擋的 403（status() != 403）
            .andExpect(result -> {
                int s = result.getResponse().getStatus();
                if (s == 403) {
                    throw new AssertionError(
                            "Bearer JWT POST 應該 CSRF exempt，但實得 403 — 可能 ignoringRequestMatchers "
                                    + "未生效或 Authorization header check fail");
                }
            });
    }
}
