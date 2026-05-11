package io.github.samzhu.skillshub.shared.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

/**
 * S160 AC-1 — CSRF 對 cookie-session POST 拒收（非 Bearer 路徑）。
 *
 * <p>對應 spec §3 AC-1：未帶 Authorization Bearer 且無 CSRF token 的 POST → 403。
 *
 * <p>採 {@code @WebMvcTest(CspReportController.class)} — 該 endpoint 為 permitAll path
 * （無 auth gate），CSRF 是 chain 上唯一的擋住點。比 AdminController（GET-only + auth-gated）
 * 更清楚單獨測 CSRF 行為。
 *
 * <p>Note：MockMvc 走的 path 與 production 真實 cookie session 流程不完全等同 — 但 Spring
 * Security CSRF filter chain 內部行為一致：無 token → reject 403；{@code .with(csrf())}
 * post-processor 模擬 valid token round-trip → 通過。Production 上線 cookie session 後
 * 用真實 browser flow 再驗（拆 S160b''''，如果需要的話）。
 */
@WebMvcTest(CspReportController.class)
@TestPropertySource(properties = "skillshub.security.csrf.enabled=true")
class CsrfChainTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: csrf.enabled=true + 無 Bearer + 無 CSRF token POST → 403")
    void anonymousPostWithoutCsrfTokenReturns403() throws Exception {
        mockMvc.perform(post("/api/v1/csp-report")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"violation\":\"x\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1 補強：csrf.enabled=true + 無 Bearer + 有效 CSRF token POST → 204（CSRF token round-trip 成立）")
    void anonymousPostWithValidCsrfTokenSucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/csp-report")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"violation\":\"x\"}")
                .with(csrf()))   // 注入 valid CSRF token + 對應 _csrf parameter / header
            .andExpect(status().isNoContent());
    }
}
