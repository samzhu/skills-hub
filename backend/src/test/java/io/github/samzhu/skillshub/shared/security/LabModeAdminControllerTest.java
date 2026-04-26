package io.github.samzhu.skillshub.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * AC-2 (/admin/echo 部分)：LAB 模式下 {@code /api/v1/admin/echo} 行為。
 *
 * <p>關鍵驗證：lab user 帶 ROLE_admin，{@code @PreAuthorize("hasRole('admin')")} 通過；
 * AdminController 改用 {@link CurrentUserProvider} 後，回傳 by 欄位為 lab user
 * （非 JWT subject，避免 LAB 模式 NPE）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "skillshub.security.oauth.enabled=false")
class LabModeAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: LAB 模式下 GET /api/v1/admin/echo?msg=hello 無 token → 200 + echo=hello, by=lab-user")
    void labMode_adminEchoWithoutToken_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/admin/echo").param("msg", "hello"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.echo").value("hello"))
            .andExpect(jsonPath("$.by").value("lab-user"));
    }
}
