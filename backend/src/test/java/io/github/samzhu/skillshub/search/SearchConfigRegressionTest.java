package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;

import io.github.samzhu.skillshub.security.scan.ScannerAiConfig;

/**
 * S157 regression guards — reflection-based assertions 防未來 PR 把 build-time condition 加回去
 * 或移除 AOT hint，重蹈 native image bake-out 覆轍。
 *
 * <p>Mirror S168 pattern：fix-pattern 的 invariant 寫成 reflection-asserted test，
 * 比 prose comment 抓得到 regression。
 */
class SearchConfigRegressionTest {

    @Test
    @DisplayName("AC-S171-3: Google provider classes only appear in AiModelConfig")
    void googleProviderClassesOnlyAppearInAiModelConfig() throws Exception {
        var main = Path.of("src/main/java");
        var hits = Files.walk(main)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    try {
                        var source = Files.readString(path);
                        return source.contains("com.google.genai.Client")
                                || source.contains("GoogleGenAiChatModel")
                                || source.contains("GoogleGenAiChatOptions")
                                || source.contains("GoogleGenAiTextEmbeddingModel")
                                || source.contains("GoogleGenAiEmbeddingConnectionDetails")
                                || source.contains("GoogleGenAiTextEmbeddingOptions");
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })
                .map(path -> main.relativize(path).toString())
                .toList();

        assertThat(hits).containsExactly("io/github/samzhu/skillshub/shared/ai/AiModelConfig.java");
    }

    @Test
    @DisplayName("AC-S171-8: search runtime classes only depend on EmbeddingModel")
    void searchRuntimeClassesOnlyDependOnEmbeddingModel() throws Exception {
        for (String file : java.util.List.of(
                "src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java",
                "src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java")) {
            var source = Files.readString(Path.of(file));
            assertThat(source).contains("org.springframework.ai.embedding.EmbeddingModel");
            assertThat(source).doesNotContain("GoogleGenAiTextEmbeddingModel");
            assertThat(source).doesNotContain("GoogleGenAiEmbeddingConnectionDetails");
            assertThat(source).doesNotContain("GoogleGenAiTextEmbeddingOptions");
        }
    }

    @Test
    @DisplayName("AC-5: SearchConfig.embeddingModel 上不含 @ConditionalOnProperty(api-key)")
    @Tag("AC-5")
    void searchConfigEmbeddingModelHasNoBuildTimeConditionalOnProperty() throws Exception {
        var method = SearchConfig.class.getDeclaredMethod("embeddingModel",
                io.github.samzhu.skillshub.SkillshubProperties.class);

        var cond = method.getAnnotation(ConditionalOnProperty.class);

        assertThat(cond).as(
                "SearchConfig.embeddingModel must NOT carry @ConditionalOnProperty — "
                        + "Spring AOT bake-out 會把 bean 從 baked context 排除（per S157 spec §2.3）")
                .isNull();
    }

    @Test
    @DisplayName("AC-6: ScannerAiConfig.scannerChatClient 上不含 @Conditional 系列 build-time gate")
    @Tag("AC-6")
    void scannerAiConfigChatClientHasNoBuildTimeConditional() throws Exception {
        var method = ScannerAiConfig.class.getDeclaredMethod("scannerChatClient",
                io.github.samzhu.skillshub.SkillshubProperties.class);

        for (Annotation a : method.getAnnotations()) {
            var type = a.annotationType();
            assertThat(type).as(
                    "ScannerAiConfig.scannerChatClient must NOT carry any build-time conditional "
                            + "(@ConditionalOnProperty / @ConditionalOnExpression / @Conditional) — "
                            + "per S157 spec §2.4 EngineEnabled / ApiKeyPresent 都會 bake-out")
                    .isNotEqualTo(ConditionalOnProperty.class)
                    .isNotEqualTo(ConditionalOnExpression.class)
                    .isNotEqualTo(Conditional.class);
        }
    }

}
