package io.github.samzhu.skillshub.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * S161 AC-1 / AC-2：{@link PlainTextDeserializer} XSS strip 行為驗證。
 *
 * <p>純單元測試 — 用 Jackson {@link ObjectMapper} 走 deserialize 路徑，避開 Spring context；
 * 任何欄位加 {@code @JsonDeserialize(using = PlainTextDeserializer.class)} 行為等同此處。
 */
class PlainTextDeserializerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record SampleDto(@JsonDeserialize(using = PlainTextDeserializer.class) String content) {}

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: <script>alert(1)</script>hello → hello（script tag + 內容皆 strip）")
    void scriptTagStripped() throws Exception {
        var dto = MAPPER.readValue(
                "{\"content\":\"<script>alert(1)</script>hello\"}",
                SampleDto.class);

        assertThat(dto.content()).isEqualTo("hello");
        assertThat(dto.content()).doesNotContain("<script");
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: <img src=x onerror=alert(1)> → 空字串（img tag 全 strip 含 onerror handler）")
    void imgOnErrorStripped() throws Exception {
        var dto = MAPPER.readValue(
                "{\"content\":\"<img src=x onerror=alert(1)>\"}",
                SampleDto.class);

        assertThat(dto.content()).doesNotContain("onerror");
        assertThat(dto.content()).doesNotContain("<img");
    }

    @Test
    @DisplayName("純文字 → 完全不動（繁中 / emoji / 全形標點不 encode）")
    void plainTextPreserved() throws Exception {
        var dto = MAPPER.readValue(
                "{\"content\":\"很棒的技能！效率提升 50%\"}",
                SampleDto.class);

        // 對比 OWASP library：會把「！」encode 成「&#xff01;」破中文 UX；本實作走 regex 不 encode
        assertThat(dto.content()).isEqualTo("很棒的技能！效率提升 50%");
    }

    @Test
    @DisplayName("字符 < 後接 space/digit（非 tag 用法）保留：a < b and 3 < 5")
    void lessThanCharacterPreserved() throws Exception {
        var dto = MAPPER.readValue(
                "{\"content\":\"a < b and 3 < 5\"}",
                SampleDto.class);

        // regex 只 match <[a-zA-Z/]，後接 space 不算 tag start，保留
        assertThat(dto.content()).isEqualTo("a < b and 3 < 5");
    }

    @Test
    @DisplayName("script tag 連內容一起 strip（防 tag-strip 留下 dangerous JS 成文字）")
    void scriptTagAndContentStripped() throws Exception {
        var dto = MAPPER.readValue(
                "{\"content\":\"before<script>alert('xss')</script>after\"}",
                SampleDto.class);

        // SCRIPT_OR_STYLE pattern 先 match 整段含內容；剩 before+after
        assertThat(dto.content()).isEqualTo("beforeafter");
        assertThat(dto.content()).doesNotContain("alert");
    }

    @Test
    @DisplayName("style tag 連內容一起 strip")
    void styleTagAndContentStripped() throws Exception {
        var dto = MAPPER.readValue(
                "{\"content\":\"hi<style>body{display:none}</style>bye\"}",
                SampleDto.class);

        assertThat(dto.content()).isEqualTo("hibye");
    }

    @Test
    @DisplayName("null content → null（不轉空字串）")
    void nullStaysNull() throws Exception {
        var dto = MAPPER.readValue("{\"content\":null}", SampleDto.class);

        assertThat(dto.content()).isNull();
    }

    @Test
    @DisplayName("混合 HTML + 純文字 → 只 strip HTML 留純文字")
    void mixedStripsHtmlKeepsText() throws Exception {
        var dto = MAPPER.readValue(
                "{\"content\":\"prefix<b>bold</b>suffix\"}",
                SampleDto.class);

        assertThat(dto.content()).isEqualTo("prefixboldsuffix");
        assertThat(dto.content()).doesNotContain("<b>");
    }
}
