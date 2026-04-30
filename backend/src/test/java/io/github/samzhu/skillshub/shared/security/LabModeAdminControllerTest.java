package io.github.samzhu.skillshub.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * AC-2 (/admin/echo 部分)：LAB 模式下 {@code /api/v1/admin/echo} 行為。
 *
 * <p>S025b T03 — extends {@link WebMvcSliceTestBase} +
 * {@code @TestPropertySource(properties = "skillshub.security.oauth.enabled=false")} 觸發 LAB 分支。
 *
 * <p>關鍵驗證：lab user 帶 ROLE_admin，{@code @PreAuthorize("hasRole('admin')")} 通過；
 * AdminController 改用 {@link CurrentUserProvider} 後，回傳 by 欄位為 lab user
 * （非 JWT subject，避免 LAB 模式 NPE）。
 */
@WebMvcTest(AdminController.class)
@TestPropertySource(properties = {
        "management.tracing.enabled=false",
        "skillshub.security.oauth.enabled=false"
})
class LabModeAdminControllerTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: LAB 模式下 GET /api/v1/admin/echo?msg=hello 無 token → 200 + echo=hello, by=lab-user")
    void labMode_adminEchoWithoutToken_returns200() throws Exception {
        // LAB filter chain 不依 JWT；AdminController 透過 CurrentUserProvider.userId() shortcut
        Mockito.when(currentUserProvider.userId()).thenReturn("lab-user");

        mockMvc.perform(get("/api/v1/admin/echo").param("msg", "hello"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.echo").value("hello"))
            .andExpect(jsonPath("$.by").value("lab-user"));
    }
}
