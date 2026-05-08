package io.github.samzhu.skillshub.shared.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

/**
 * S152: SPA fallback catchall pattern 行為驗證。
 *
 * <p>{@code @WebMvcTest(SpaFallbackController.class)} 只載入本 controller，
 * 不啟用 ResourceHttpRequestHandler，故含副檔名的 path 在此 test 環境下回 404
 * （production 由 static resource handler 服務真實檔案）。Extend
 * {@link WebMvcSliceTestBase} 共用 SecurityConfig + AotStubBeans + CacheManager。
 */
@WebMvcTest(SpaFallbackController.class)
class SpaFallbackControllerTest extends WebMvcSliceTestBase {

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("AC-1: 未知 path（單層、無副檔名）forward 至 /index.html")
    void unknownSinglePathForwards() throws Exception {
        mvc.perform(get("/random-xyz"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    @DisplayName("AC-1: 未知 path（多層）forward 至 /index.html")
    void unknownDeepPathForwards() throws Exception {
        mvc.perform(get("/foo/bar/baz"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    @DisplayName("AC-2: /api/ 開頭即使沒對應 controller 也回 404，不走 SPA shell")
    void apiPathReturns404NotForwarded() throws Exception {
        mvc.perform(get("/api/foo"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("AC-2: /api/v1 typo 也回 404，不 forward")
    void apiV1TypoReturns404() throws Exception {
        mvc.perform(get("/api/v1/nonexistent-endpoint"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("AC-4: 含副檔名 path 不被本 controller 攔（走 static resource handler；test 環境無此 handler 故 404）")
    void dottedPathNotHandledHere() throws Exception {
        mvc.perform(get("/foo.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("AC-5: 既有 SPA route /browse forward 行為與舊 allowlist 一致")
    void knownRouteBrowseForwards() throws Exception {
        mvc.perform(get("/browse"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    @DisplayName("AC-6: 新加的 nested route /collections/abc 自動 forward（無需動 backend allowlist）")
    void newNestedRouteAutoForwards() throws Exception {
        mvc.perform(get("/collections/abc-123"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    @DisplayName("AC-5: docs nested route /docs/overview forward")
    void docsNestedForwards() throws Exception {
        mvc.perform(get("/docs/overview"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }
}
