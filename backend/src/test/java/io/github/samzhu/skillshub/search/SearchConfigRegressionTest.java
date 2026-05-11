package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
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

    @Test
    @DisplayName("AC-7: SearchNativeConfig 含 @RegisterReflectionForBinding(LlmIntentOutput.class)")
    @Tag("AC-7")
    void searchNativeConfigRegistersLlmIntentOutputForBinding() {
        var hint = SearchNativeConfig.class.getAnnotation(RegisterReflectionForBinding.class);

        assertThat(hint).as(
                "SearchNativeConfig must declare @RegisterReflectionForBinding — "
                        + "BeanOutputConverter 需要 AOT hint 才能 reflect record components (S148 family)")
                .isNotNull();
        // @RegisterReflectionForBinding 用 @AliasFor 把 value() / classes() 互通。raw reflection
        // 不走 Spring AnnotatedElementUtils merge，所以 array form `@A({X.class})` 會把資料寫進
        // value()，classes() 仍回 default empty。檢查兩個 array 任一含目標 class 即可。
        var both = new java.util.ArrayList<Class<?>>();
        java.util.Collections.addAll(both, hint.value());
        java.util.Collections.addAll(both, hint.classes());
        assertThat(both).contains(SearchIntentService.LlmIntentOutput.class);
    }
}
