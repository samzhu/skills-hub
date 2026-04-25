package io.github.samzhu.skillshub.security;

/**
 * 單筆風險發現記錄，描述掃描過程中偵測到的一個可疑匹配項目。
 *
 * @param type    風險類型代碼，例如 {@code DANGEROUS_COMMAND}、{@code SENSITIVE_PATH}、{@code EXTERNAL_URL}
 * @param message 人類可讀的風險說明訊息
 * @param file    發現風險的檔案名稱（相對於 Skill 套件根目錄）
 * @param line    發現風險的行號（從 1 起算）
 * @param pattern 觸發此發現的正規表示式或匹配字串
 */
public record RiskFinding(
		String type,
		String message,
		String file,
		int line,
		String pattern
) {}
