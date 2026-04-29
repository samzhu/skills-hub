package io.github.samzhu.skillshub.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S023-T06 — 驗證 {@code /actuator/modulith} endpoint 暴露 + 列出 6 個模組（per S023 spec §3 AC-11）。
 *
 * <p>用 random management port + {@link TestRestTemplate}（既有 test 慣用）直接 HTTP 呼叫。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                // 顯式 override exposure 避免 dev profile 是否載入造成的差異
                "management.endpoints.web.exposure.include=health,info,metrics,modulith"
        })
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
class ModulithActuatorTest {

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("AC-11: /actuator/modulith 回 200 + 列出 6 個 module")
    @Tag("AC-11")
    void modulithEndpoint_listsAllModules() {
        var response = restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/modulith", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = response.getBody();
        assertThat(body).as("/actuator/modulith 應回非空 body").isNotNull();

        // Skills Hub 6 個 module（per architecture.md）— body 為 JSON 字串，包含 module 名稱即代表 endpoint 正確列出
        for (String moduleName : new String[] { "shared", "skill", "security", "search", "analytics", "storage" }) {
            assertThat(body)
                    .as("/actuator/modulith body 應含 module name '%s'", moduleName)
                    .contains(moduleName);
        }
    }
}
