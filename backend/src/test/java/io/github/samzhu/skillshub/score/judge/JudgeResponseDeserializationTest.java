package io.github.samzhu.skillshub.score.judge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

/**
 * S148: 驗證 Jackson 對 {@link JudgeResponse} record 的反序列化合約。
 *
 * <p>JVM 反射在這裡一定能跑；本測試的真實價值是在 GraalVM native image build 時
 * 觸發 reachability 分析 — Spring AOT processor 觀察到測試讀 {@code JudgeResponse} fields
 * 的反射路徑，會自動產生對應 metadata 進 native-image。即使 {@link ScoreNativeConfig}
 * 的 {@code @RegisterReflectionForBinding} 失效或被誤刪，test 也會在 native build phase 抓出來。
 */
class JudgeResponseDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("AC-3: 完整 JSON 反序列化為 JudgeResponse — 含巢狀 DimensionScore")
    void deserializeFullJson() throws JsonProcessingException {
        String json = """
                {
                  "scores": [
                    {"dimension": "Conciseness", "score": 3, "reasoning": "Tight and focused"},
                    {"dimension": "Clarity",     "score": 2, "reasoning": "Mostly clear"},
                    {"dimension": "Coverage",    "score": 3, "reasoning": "Comprehensive"},
                    {"dimension": "Examples",    "score": 1, "reasoning": "Few examples"}
                  ],
                  "verdict": "Solid skill with room to grow"
                }
                """;

        JudgeResponse response = mapper.readValue(json, JudgeResponse.class);

        assertThat(response.scores()).hasSize(4);
        assertThat(response.verdict()).isEqualTo("Solid skill with room to grow");

        JudgeResponse.DimensionScore first = response.scores().get(0);
        assertThat(first.dimension()).isEqualTo("Conciseness");
        assertThat(first.score()).isEqualTo(3);
        assertThat(first.reasoning()).isEqualTo("Tight and focused");
    }

    @Test
    @DisplayName("AC-3: 空 scores 陣列也能正常反序列化")
    void deserializeEmptyScores() throws JsonProcessingException {
        String json = """
                {"scores": [], "verdict": "n/a"}
                """;

        JudgeResponse response = mapper.readValue(json, JudgeResponse.class);

        assertThat(response.scores()).isEmpty();
        assertThat(response.verdict()).isEqualTo("n/a");
    }

    @Test
    @DisplayName("AC-3: 缺必要欄位 → graceful Jackson 例外（不 crash JVM）")
    void deserializeMissingScoresField() {
        String json = """
                {"verdict": "missing scores field"}
                """;

        JudgeResponse response;
        try {
            response = mapper.readValue(json, JudgeResponse.class);
            assertThat(response.scores()).isNull();
            assertThat(response.verdict()).isEqualTo("missing scores field");
        } catch (JsonProcessingException e) {
            assertThat(e).isInstanceOf(MismatchedInputException.class);
        }
    }

    @Test
    @DisplayName("AC-3: 完全空白 JSON → record 兩個欄位皆 null（Jackson 預設不 fail）")
    void deserializeEmptyObject() throws JsonProcessingException {
        JudgeResponse response = mapper.readValue("{}", JudgeResponse.class);

        assertThat(response.scores()).isNull();
        assertThat(response.verdict()).isNull();
    }
}
