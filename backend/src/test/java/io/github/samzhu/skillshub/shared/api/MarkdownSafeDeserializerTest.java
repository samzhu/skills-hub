package io.github.samzhu.skillshub.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * S161b'' — {@link MarkdownSafeDeserializer} 行為驗證。
 *
 * <p>對應 S161 AC-5（markdown safe subset 保留）+ AC-6（javascript: URL 擋）。
 * 純 Jackson roundtrip + OWASP policy run，無 Spring context。
 */
class MarkdownSafeDeserializerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record SampleDto(@JsonDeserialize(using = MarkdownSafeDeserializer.class) String description) {}

    private static String sanitize(String raw) throws Exception {
        var dto = MAPPER.readValue(
                MAPPER.writeValueAsString(java.util.Map.of("description", raw)),
                SampleDto.class);
        return dto.description();
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: 合法 markdown tag 保留（<p> + <strong> + <em>）")
    void preservesParagraphAndEmphasis() throws Exception {
        var out = sanitize("<p>hello <strong>bold</strong> and <em>italic</em></p>");

        assertThat(out).contains("<p>hello");
        assertThat(out).contains("<strong>bold</strong>");
        assertThat(out).contains("<em>italic</em>");
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: 列表元素保留（<ul>/<ol>/<li>）")
    void preservesLists() throws Exception {
        var out = sanitize("<ul><li>one</li><li>two</li></ul>");

        assertThat(out).contains("<ul>");
        assertThat(out).contains("<li>one</li>");
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: code / pre / blockquote 保留")
    void preservesCodeAndQuotes() throws Exception {
        var out = sanitize("<pre><code>git status</code></pre><blockquote>tip</blockquote>");

        assertThat(out).contains("<pre>");
        assertThat(out).contains("<code>");
        assertThat(out).contains("<blockquote>");
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: 標題 h1/h2/h3 保留；h4 不在 policy → strip 但保留文字內容")
    void preservesH1H2H3() throws Exception {
        var out = sanitize("<h1>Title</h1><h2>Sub</h2><h3>Mini</h3><h4>strip me</h4>");

        assertThat(out).contains("<h1>Title</h1>");
        assertThat(out).contains("<h2>Sub</h2>");
        assertThat(out).contains("<h3>Mini</h3>");
        // h4 tag 移除但內容文字 OWASP 預設保留
        assertThat(out).doesNotContain("<h4>");
        assertThat(out).contains("strip me");
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: <a href=\"https://...\"> 保留")
    void preservesSafeLink() throws Exception {
        var out = sanitize("<a href=\"https://example.com\">link</a>");

        assertThat(out).contains("href=\"https://example.com\"");
        assertThat(out).contains(">link</a>");
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6: <a href=\"javascript:alert(1)\"> → href 被擋（OWASP allowStandardUrlProtocols）")
    void blocksJavascriptUrl() throws Exception {
        var out = sanitize("<a href=\"javascript:alert(1)\">click</a>");

        assertThat(out).doesNotContain("javascript:");
        assertThat(out).doesNotContain("alert");
        // OWASP 對 disallowed protocol 的 a tag：href attr 被 drop，留 tag + 文字（or strip 整個 a）
        assertThat(out).contains("click");
    }

    @Test
    @DisplayName("<script> + 內容 strip — 防 markdown 場景下 stored XSS")
    void stripsScriptTagAndContent() throws Exception {
        var out = sanitize("<p>safe</p><script>alert('xss')</script>");

        assertThat(out).doesNotContain("<script");
        assertThat(out).doesNotContain("alert");
        assertThat(out).contains("<p>safe</p>");
    }

    @Test
    @DisplayName("<iframe> / <object> / <embed> 全 strip — 不在 allowlist")
    void stripsDangerousEmbedTags() throws Exception {
        var out = sanitize("<iframe src=\"//evil\"></iframe><object data=\"//evil\"></object>safe");

        assertThat(out).doesNotContain("<iframe");
        assertThat(out).doesNotContain("<object");
        assertThat(out).contains("safe");
    }

    @Test
    @DisplayName("inline event handler（onclick / onerror）attribute strip")
    void stripsEventHandlerAttributes() throws Exception {
        var out = sanitize("<p onclick=\"alert(1)\">click me</p>");

        assertThat(out).contains("<p>click me</p>");
        assertThat(out).doesNotContain("onclick");
    }

    @Test
    @DisplayName("inline style attribute strip — CSS expression / url() 注入面")
    void stripsInlineStyleAttribute() throws Exception {
        var out = sanitize("<p style=\"background:url(javascript:alert(1))\">x</p>");

        assertThat(out).contains("<p>x</p>");
        assertThat(out).doesNotContain("style=");
    }

    @Test
    @DisplayName("null 維持 null（不轉空字串）")
    void nullStaysNull() throws Exception {
        var dto = MAPPER.readValue("{\"description\":null}", SampleDto.class);
        assertThat(dto.description()).isNull();
    }
}
