package io.github.samzhu.skillshub.security.scan;

/**
 * 掃描注意事項（informational）— 不上升為 finding 但值得讓使用者知曉的事項。
 *
 * <p>典型用途：
 * <ul>
 *   <li>MetadataValidator：frontmatter 格式違規（lowercase-hyphen、長度上限）— 不影響執行但違反 spec</li>
 *   <li>LlmJudge：LLM 對整體判斷的 reasoning 摘要</li>
 *   <li>引擎執行失敗的降級訊息</li>
 * </ul>
 *
 * <p>對應 SARIF 2.1.0 {@code invocations[].toolExecutionNotifications[]}，
 * 而非 {@code results[]}（per spec §2.3 決策 #4 與 R1 引用）。
 *
 * @param source  發出 notice 的來源（通常為 analyzer name）
 * @param message 人類可讀訊息
 *
 * @see SecurityFinding
 */
public record ScanNotice(String source, String message) {}
