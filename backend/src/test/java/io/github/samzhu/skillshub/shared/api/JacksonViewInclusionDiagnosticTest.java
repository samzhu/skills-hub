package io.github.samzhu.skillshub.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * S165 診斷 — runtime 檢視 {@link JsonMapper} 的 {@link MapperFeature#DEFAULT_VIEW_INCLUSION} 狀態。
 *
 * <p>用 full {@code @SpringBootTest} 載真實 ApplicationContext + Jackson auto-config — 對齊
 * production bootstrap。若這個 test 通過代表 fix 在 prod 生效；slice test 失敗則 slice 自身設定問題。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JacksonViewInclusionDiagnosticTest {

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void defaultViewInclusion_shouldBeEnabled() {
        boolean enabled = jsonMapper.serializationConfig().isEnabled(MapperFeature.DEFAULT_VIEW_INCLUSION);
        System.out.println("[DIAG] DEFAULT_VIEW_INCLUSION (serialization) = " + enabled);
        boolean enabledDeser = jsonMapper.deserializationConfig().isEnabled(MapperFeature.DEFAULT_VIEW_INCLUSION);
        System.out.println("[DIAG] DEFAULT_VIEW_INCLUSION (deserialization) = " + enabledDeser);
        assertThat(enabled).as("DEFAULT_VIEW_INCLUSION must be enabled per S165 fix").isTrue();
    }
}
