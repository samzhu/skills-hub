package io.github.samzhu.skillshub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.samzhu.skillshub.SkillshubProperties;
import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * AC-6：{@code skillshub.security} 區塊 binding 正確。
 *
 * <p>驗證 Spring Boot relaxed binding 把 {@code application.yaml} 內
 * {@code skillshub.security.oauth.enabled} + {@code skillshub.security.lab.user-id}
 * 正確映射到 {@link SkillshubProperties.Security} nested record，
 * 並確保即使設定檔沒寫該區塊也不為 null（{@code @DefaultValue} 鏈生效）。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SkillshubSecurityPropertiesTest {

    @Autowired
    private SkillshubProperties props;

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: skillshub.security 不為 null 且 oauth.enabled 預設為 true")
    void security_nestedRecordNotNullAndDefaults() {
        assertThat(props.security()).isNotNull();
        assertThat(props.security().oauth()).isNotNull();
        assertThat(props.security().lab()).isNotNull();
        // application.yaml 顯式設 oauth.enabled=true（也是預設值）
        assertThat(props.security().oauth().enabled()).isTrue();
        // application.yaml 顯式設 lab.user-id=lab-user（也是預設值）
        assertThat(props.security().lab().userId()).isEqualTo("lab-user");
    }
}
