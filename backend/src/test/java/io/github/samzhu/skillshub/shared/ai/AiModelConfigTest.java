package io.github.samzhu.skillshub.shared.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import io.github.samzhu.skillshub.SkillshubProperties;

class AiModelConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfig.class, AiModelConfig.class);

    @Test
    @DisplayName("AC-S171-1: Spring AI BOM uses 2.0.0-M6 without per-artifact pins")
    void springAiBomUsesM6WithoutPerArtifactPins() throws Exception {
        var build = Files.readString(Path.of("build.gradle.kts"));

        assertThat(build).contains("extra[\"springAiVersion\"] = \"2.0.0-M6\"");
        assertThat(build).doesNotContain("spring-ai-google-genai:");
        assertThat(build).doesNotContain("spring-ai-google-genai-embedding:");
        assertThat(build).doesNotContain("spring-ai-client-chat:");
    }

    @Test
    @DisplayName("AC-S171-4: quality judge chat client exists when api key is configured")
    void qualityJudgeChatClientExistsWhenApiKeyConfigured() {
        contextRunner.withPropertyValues("skillshub.genai.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatModel.class);
                    assertThat(context).hasBean("qualityJudgeChatClient");
                    assertThat(context).hasBean("scannerChatClient");
                    assertThat(context).hasSingleBean(EmbeddingModel.class);
                    assertThat(context.getBean("qualityJudgeChatClient")).isInstanceOf(ChatClient.class);
                });
    }

    @Test
    @DisplayName("AC-S171-4: quality judge fails fast when enabled without api key")
    void qualityJudgeFailsFastWhenEnabledWithoutApiKey() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("skillshub.genai.api-key");
        });
    }

    @Test
    @DisplayName("AC-S171-5: optional scanner and search chat clients are absent without api key")
    void optionalChatClientsAreAbsentWithoutApiKey() {
        contextRunner.withPropertyValues("skillshub.quality.judge.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanProvider(ChatModel.class).getIfAvailable()).isNull();
                    assertThat(context.getBeanProvider(ChatClient.class).stream()).isEmpty();
                    assertThat(context).hasSingleBean(EmbeddingModel.class);
                });
    }

    @Test
    @DisplayName("AC-S171-6: Spring AI auto-config chat model and chat client builder are disabled")
    void springAiAutoConfigIsDisabledInBaseApplicationYaml() throws Exception {
        var yaml = Files.readString(Path.of("src/main/resources/application.yaml"));

        assertThat(yaml).contains("chat: none");
        assertThat(yaml).contains("text: none");
        assertThat(yaml).contains("enabled: false");
        contextRunner.withPropertyValues("skillshub.quality.judge.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ChatClient.Builder.class);
                });
    }

    @Test
    @DisplayName("AC-S171-8: embedding model falls back to NoOp when api key is absent")
    void embeddingModelFallsBackToNoOpWhenApiKeyAbsent() {
        contextRunner.withPropertyValues("skillshub.quality.judge.enabled=false")
                .run(context -> {
                    var embeddingModel = context.getBean(EmbeddingModel.class);
                    assertThat(embeddingModel.embed("terraform")).hasSize(768).containsOnly(0.0f);
                    assertThat(context).doesNotHaveBean("googleGenAiTextEmbeddingModel");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SkillshubProperties.class)
    static class PropertiesConfig {
    }
}
