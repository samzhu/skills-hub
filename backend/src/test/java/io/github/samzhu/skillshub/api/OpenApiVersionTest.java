package io.github.samzhu.skillshub.api;

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
 * S099a — 鎖契約：{@code GET /v3/api-docs} 返 OpenAPI 3.1.0 spec。
 *
 * <p>對齊 agentskills.io trust maturity 標準 + JSON Schema 2020-12 對齊；
 * 防 SpringDoc 升版 default 漂回 3.0.x（Swagger Core 預設 3.0.3）。
 *
 * <p>正式環境 {@code application.yaml} 設 {@code springdoc.api-docs.enabled: false}
 * 不暴露 schema；{@code application-local.yaml} explicit 啟用 + 設 {@code version: openapi_3_1}。
 * 此 test 透過 {@link TestPropertySource} 模擬 local profile，確保契約鎖在 CI。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.api-docs.version=openapi_3_1",
        "springdoc.swagger-ui.enabled=false"
})
class OpenApiVersionTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("AC-1/AC-3: GET /v3/api-docs returns openapi=3.1.0")
    @Tag("AC-1")
    @Tag("AC-3")
    void apiDocs_returnsOpenApi3_1() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.1.0"));
    }
}
