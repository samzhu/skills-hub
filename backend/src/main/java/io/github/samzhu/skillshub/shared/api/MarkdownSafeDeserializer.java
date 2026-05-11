package io.github.samzhu.skillshub.shared.api;

import java.io.IOException;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/**
 * S161b'' — Jackson custom deserializer：對「保留 markdown 安全 subset」欄位（如
 * {@code request.description}）用 OWASP {@link HtmlPolicyBuilder} 走 allowlist sanitize。
 *
 * <p>**為什麼不用 {@link PlainTextDeserializer}**：那個 regex 強制 strip 所有 tag — 對
 * description 這種多行 markdown 內容會破合法 {@code <p>}/{@code <strong>}/{@code <a>}
 * 等基本標籤。本 deserializer 走 allowlist 模式：在 policy 內列舉的 tag/attr 保留，
 * 其餘 silently strip。
 *
 * <p>**OWASP encode 副作用**（S161 Phase 1 棄用原因）：OWASP 對「非 ASCII」字符與
 * 「字符 {@code <}」會 encode 成 HTML entity（{@code &#xff01;} / {@code &lt;}）— 對純
 * 文字欄位破繁中。但 markdown 場景 acceptable：
 * <ul>
 *   <li>合法 markdown 含 entity（如 {@code &amp;}）本來就是合理 HTML</li>
 *   <li>frontend 後續若用 markdown renderer，entity 會被 decode 回字符</li>
 *   <li>儲存 escape 過的形式對 stored XSS 是縱深防禦（多一層 safe-by-default）</li>
 * </ul>
 *
 * <p>policy 涵蓋 markdown 常見元素：
 * <ul>
 *   <li>段落：{@code p}, {@code br}</li>
 *   <li>強調：{@code strong}, {@code em}</li>
 *   <li>清單：{@code ul}, {@code ol}, {@code li}</li>
 *   <li>程式碼：{@code code}, {@code pre}</li>
 *   <li>引用：{@code blockquote}</li>
 *   <li>連結：{@code a[href]} — 限 standard URL protocols（http/https/mailto），
 *       {@code javascript:} 自動擋（per S161 AC-6）</li>
 *   <li>標題：{@code h1}, {@code h2}, {@code h3}（h4-h6 屬罕用，未列入）</li>
 * </ul>
 *
 * <p>不在 policy 內的（自動 strip）：{@code script}/{@code style}/{@code iframe}/{@code object}/
 * {@code embed}/{@code form}/{@code input}/event handler attributes（{@code onclick} 等）/
 * inline {@code style} attribute 等所有可執行或可逃逸的元素與屬性。
 */
public class MarkdownSafeDeserializer extends JsonDeserializer<String> {

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "strong", "em",
                    "ul", "ol", "li",
                    "code", "pre", "blockquote",
                    "a", "h1", "h2", "h3")
            .allowAttributes("href").onElements("a")
            .allowStandardUrlProtocols()  // http/https/mailto；javascript: 被擋
            .toFactory();

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        var raw = p.getValueAsString();
        if (raw == null) {
            return null;
        }
        return POLICY.sanitize(raw);
    }
}
