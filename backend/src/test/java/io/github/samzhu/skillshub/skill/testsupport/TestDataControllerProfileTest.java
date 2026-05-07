package io.github.samzhu.skillshub.skill.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import io.github.samzhu.skillshub.skill.command.SkillCommandService;
import io.github.samzhu.skillshub.storage.PackageService;

/**
 * S140 T01 — Profile gating 驗證（per spec §4.6 表）。
 *
 * <p>確保 production binary（{@code prod} profile）完全不含 {@link TestDataController} +
 * {@link E2EEmbeddingConfig} bean — security-by-build-time。
 */
class TestDataControllerProfileTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(MockDeps.class, TestDataController.class, E2EEmbeddingConfig.class);

    @Test
    @DisplayName("prod profile：TestDataController + E2EEmbeddingConfig bean 都不存在")
    void prodProfile_neitherBeanRegistered() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
            .run(ctx -> {
                assertThat(ctx).doesNotHaveBean(TestDataController.class);
                assertThat(ctx).doesNotHaveBean(E2EEmbeddingConfig.class);
            });
    }

    @Test
    @DisplayName("local profile：TestDataController 存在、E2EEmbeddingConfig 不存在")
    void localProfile_onlyControllerRegistered() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("local"))
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(TestDataController.class);
                assertThat(ctx).doesNotHaveBean(E2EEmbeddingConfig.class);
            });
    }

    @Test
    @DisplayName("dev profile：TestDataController 存在、E2EEmbeddingConfig 不存在")
    void devProfile_onlyControllerRegistered() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(TestDataController.class);
                assertThat(ctx).doesNotHaveBean(E2EEmbeddingConfig.class);
            });
    }

    @Test
    @DisplayName("e2e profile：TestDataController + E2EEmbeddingConfig 都存在；e2eStubEmbeddingModel bean 註冊")
    void e2eProfile_bothRegistered() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("e2e"))
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(TestDataController.class);
                assertThat(ctx).hasSingleBean(E2EEmbeddingConfig.class);
                assertThat(ctx).hasBean("e2eStubEmbeddingModel");
                assertThat(ctx.getBean(EmbeddingModel.class)).isNotNull();
            });
    }

    @Configuration
    static class MockDeps {
        @Bean
        SkillCommandService skillCommandService() {
            return Mockito.mock(SkillCommandService.class);
        }

        @Bean
        PackageService packageService() {
            return Mockito.mock(PackageService.class);
        }

        @Bean
        NamedParameterJdbcTemplate jdbc() {
            return Mockito.mock(NamedParameterJdbcTemplate.class);
        }
    }
}
