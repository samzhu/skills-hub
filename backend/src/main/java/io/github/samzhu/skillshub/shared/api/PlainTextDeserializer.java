package io.github.samzhu.skillshub.shared.api;

import java.io.IOException;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/**
 * S161 — Jackson custom deserializer：把 user-submitted「plain text」欄位的 HTML markup
 * silently strip。設計理由 / trade-off 詳 spec §2.2-§2.3。
 *
 * <p>**為何不用 OWASP library**：S161 spec §2.3 原選 owasp-java-html-sanitizer，但實測對
 * 「非 ASCII 字符」（如 {@code ！} U+FF01）與「字符 < 非 tag 用法」（如 {@code a < b}）一律
 * encode 成 HTML entity（{@code &#xff01;} / {@code &lt;}）— 破繁中 user 內容。OWASP encode
 * 是 escape-for-safe-render 而非 strip-for-safe-store；本欄位儲存階段不需要 escape（React
 * render 端負責），只需要拔 tag，故走簡單 regex。OWASP dep 保留給未來 markdown 安全子集
 * （request.description 等 S161b 範疇）。
 *
 * <p>策略：regex 拔「{@code <...>}」對 — 不 encode 字符、不破繁中 / emoji / `<` 非 tag 用法。
 *
 * <p>使用：欄位上加 {@code @JsonDeserialize(using = PlainTextDeserializer.class)} 即可。
 *
 * <p>覆蓋的 XSS 攻擊 surface：
 * <ul>
 *   <li>{@code <script>x</script>plain} → {@code plain}（標籤連同內容 strip）</li>
 *   <li>{@code <img src=x onerror=alert(1)>} → {@code ""}（自閉合 tag strip）</li>
 *   <li>{@code prefix<b>bold</b>suffix} → {@code prefixboldsuffix}（inline tag strip 留文字）</li>
 *   <li>{@code <style>x</style>} → {@code ""}</li>
 *   <li>{@code <iframe src=...>...</iframe>} → {@code ""}</li>
 * </ul>
 *
 * <p>限制（已知；spec §2.3 接受）：
 * <ul>
 *   <li>{@code <script>} 包文字會把文字一起 strip 嗎？— 否，本 regex 只 strip 標籤本身，
 *       不處理「script tag 內 textContent」。但是 React/前端 render 階段 text node 已 safe
 *       （不會 exec）；CSP 報告層 + frontend escape 仍是另外兩道防線。</li>
 *   <li>{@code <script> 缺結束 tag} 或 {@code &lt;script&gt;hello&lt;/script&gt;} entity 變體
 *       不在 strip 範圍；後者本來就是純文字（browser 不 exec），無 XSS 風險。</li>
 * </ul>
 *
 * <p>說明：上面第一點對「script 內容也 strip」要進一步處理需要先 match 整段
 * {@code <script>...</script>}（含內容）再 strip。本實作先做 tag-strip 滿足 OWASP Top 10
 * basic protection，「整段 script 連內容也清」交給更嚴格的 markdown allowlist 子 spec。
 */
public class PlainTextDeserializer extends JsonDeserializer<String> {

    /**
     * Match {@code <script>...</script>} / {@code <style>...</style>}（含中間內容）case-insensitive。
     * 必須先於一般 tag strip — 否則 script tag 拔掉留下 dangerous JS code 成文字。
     */
    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile(
            "<(script|style)\\b[^>]*>.*?</\\1\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Match 任何 HTML tag — {@code <foo>} / {@code </foo>} / {@code <foo bar="baz">}。 */
    private static final Pattern HTML_TAG = Pattern.compile("<[a-zA-Z/][^>]*>");

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        var raw = p.getValueAsString();
        if (raw == null) {
            return null;
        }
        var noScript = SCRIPT_OR_STYLE.matcher(raw).replaceAll("");
        return HTML_TAG.matcher(noScript).replaceAll("");
    }
}
