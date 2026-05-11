package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;

import io.github.samzhu.skillshub.SkillshubProperties;

/**
 * S157: SearchConfig 單一 factory branching 行為驗證。
 *
 * <p>原 build-time {@code @ConditionalOnProperty} 已拿掉（per spec §3.2），bean 永遠註冊；
 * 真假 EmbeddingModel 由 factory body 依 api-key 是否存在 runtime branch。
 *
 * <p>純 JUnit 5 unit test，無 Spring context 啟動。
 */
class SearchConfigTest {

    private final SearchConfig config = new SearchConfig();

    @Test
    @DisplayName("api-key 為 null → 走 NoOp (768 維零向量)")
    void noApiKeyReturnsNoOp() {
        var props = propsWithApiKey(null);

        var em = config.embeddingModel(props);

        var vector = em.embed("test text");
        assertThat(vector).hasSize(768);
        assertThat(vector).containsOnly(0f);
    }

    @Test
    @DisplayName("api-key 為 blank → 走 NoOp")
    void blankApiKeyReturnsNoOp() {
        var props = propsWithApiKey("   ");

        var em = config.embeddingModel(props);

        var vector = em.embed("test text");
        assertThat(vector).hasSize(768);
        assertThat(vector).containsOnly(0f);
    }

    @Test
    @DisplayName("api-key 存在 → 走真實 GoogleGenAiTextEmbeddingModel")
    void realApiKeyReturnsGoogleGenAi() {
        var props = propsWithApiKey("AIzaTestKey");

        EmbeddingModel em = config.embeddingModel(props);

        assertThat(em).isInstanceOf(GoogleGenAiTextEmbeddingModel.class);
    }

    private static SkillshubProperties propsWithApiKey(String apiKey) {
        return new SkillshubProperties(
                new SkillshubProperties.Storage("skillshub-packages", "./storage-local"),
                new SkillshubProperties.Search("skill_embeddings"),
                new SkillshubProperties.GenAI("gemini-embedding-2", 768, apiKey),
                new SkillshubProperties.Scanner(new SkillshubProperties.Engines(
                        new SkillshubProperties.Engine(true),
                        new SkillshubProperties.Engine(true),
                        new SkillshubProperties.Engine(true),
                        new SkillshubProperties.Engine(false),
                        new SkillshubProperties.Engine(true))),
                new SkillshubProperties.Security(
                        new SkillshubProperties.OAuth(true, new SkillshubProperties.OAuth.Login(false)),
                        new SkillshubProperties.Lab("lab-user"),
                        new SkillshubProperties.Cors(java.util.List.of(), false),
                        new SkillshubProperties.Csrf(false)));
    }
}
