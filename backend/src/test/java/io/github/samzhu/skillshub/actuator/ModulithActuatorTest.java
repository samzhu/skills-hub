package io.github.samzhu.skillshub.actuator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S023-T06 — 驗證 {@code /actuator/modulith} endpoint 暴露 + 列出 6 個模組（per S023 spec §3 AC-11）。
 *
 * <p>S025b T04 — 從 {@code @SpringBootTest(RANDOM_PORT) + TestRestTemplate +
 * management.server.port=0} 改 {@code @SpringBootTest + MockMvc}（per spec AC-5 RANDOM_PORT
 * ≤3 收斂；本 test 不屬 3 個保留 e2e）。actuator endpoint 在 main port 暴露
 * （無 {@code management.server.port} 分離）— body content + module 列表斷言不變。
 */
@SpringBootTest(properties = {
        // 顯式 override exposure 避免 dev profile 是否載入造成的差異
        "management.endpoints.web.exposure.include=health,info,metrics,modulith"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ModulithActuatorTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("AC-11: /actuator/modulith 回 200 + 列出 6 個 module")
    @Tag("AC-11")
    void modulithEndpoint_listsAllModules() throws Exception {
        var resultActions = mockMvc.perform(get("/actuator/modulith"))
                .andExpect(status().isOk());

        // Skills Hub 6 個 module（per architecture.md）— body 為 JSON 字串，包含 module 名稱即代表 endpoint 正確列出
        for (String moduleName : new String[] { "shared", "skill", "security", "search", "analytics", "storage" }) {
            resultActions.andExpect(content().string(Matchers.containsString(moduleName)));
        }
    }
}
