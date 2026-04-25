package io.github.samzhu.skillshub.skill.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * SKILL.md 內容驗證器 — 依 agentskills.io 規範解析並驗證技能套件的 SKILL.md 檔案。
 *
 * <p>驗證流程：
 * <ol>
 *   <li>檢查內容非空</li>
 *   <li>提取 {@code ---} 分隔符之間的 YAML frontmatter</li>
 *   <li>使用 SnakeYAML 解析 frontmatter</li>
 *   <li>驗證 {@link #REQUIRED_FIELDS} 中所有必填欄位皆存在</li>
 * </ol>
 *
 * @see ValidationResult
 */
@Component
public class SkillValidator {

	/** SKILL.md frontmatter 中必須存在的欄位名稱。 */
	private static final List<String> REQUIRED_FIELDS = List.of("name", "description");

	/**
	 * 驗證 SKILL.md 文字內容。
	 *
	 * @param skillMdContent SKILL.md 的完整文字內容
	 * @return 驗證結果，包含解析後的 metadata 或錯誤清單
	 */
	public ValidationResult validate(String skillMdContent) {
		if (skillMdContent == null || skillMdContent.isBlank()) {
			return new ValidationResult(false, Map.of(), List.of("SKILL.md content is empty"));
		}

		// 提取 frontmatter 區塊（--- 與 --- 之間的 YAML 文字）
		var yamlContent = extractFrontmatter(skillMdContent);
		if (yamlContent == null) {
			return new ValidationResult(false, Map.of(), List.of("No YAML frontmatter found (expected --- delimiters)"));
		}

		// 使用 SnakeYAML 解析 frontmatter 字串為 Map
		var yaml = new Yaml();
		Map<String, Object> parsed;
		try {
			parsed = yaml.load(yamlContent);
		} catch (Exception e) {
			return new ValidationResult(false, Map.of(), List.of("Invalid YAML: " + e.getMessage()));
		}

		// YAML 合法但內容為空（如只有空白），SnakeYAML 回傳 null
		if (parsed == null) {
			parsed = Map.of();
		}

		// 逐一檢查必填欄位是否存在且非 null
		var errors = new ArrayList<String>();
		for (var field : REQUIRED_FIELDS) {
			if (!parsed.containsKey(field) || parsed.get(field) == null) {
				errors.add("Missing required field: " + field);
			}
		}

		if (!errors.isEmpty()) {
			// 驗證失敗：回傳部分解析結果供錯誤診斷，metadata 以不可變 Map 封裝
			return new ValidationResult(false, Collections.unmodifiableMap(new LinkedHashMap<>(parsed)), List.copyOf(errors));
		}

		// 驗證通過：以 LinkedHashMap 保留欄位宣告順序，再封裝為不可變 Map 回傳
		return new ValidationResult(true, Collections.unmodifiableMap(new LinkedHashMap<>(parsed)), List.of());
	}

	/**
	 * 從 SKILL.md 文字中提取 YAML frontmatter 區塊。
	 *
	 * <p>frontmatter 須以 {@code ---} 開頭並以第二個 {@code ---} 結尾。
	 * 若格式不符則回傳 {@code null}。
	 *
	 * @param content SKILL.md 完整文字
	 * @return frontmatter 的 YAML 字串，若不存在則為 {@code null}
	 */
	private String extractFrontmatter(String content) {
		var trimmed = content.strip();
		// 必須以 --- 開頭才是合法的 frontmatter
		if (!trimmed.startsWith("---")) {
			return null;
		}
		// 從第 3 個字元後尋找結束分隔符（跳過起始的 ---）
		int secondDelimiter = trimmed.indexOf("---", 3);
		if (secondDelimiter < 0) {
			return null;
		}
		// 擷取兩個 --- 之間的純 YAML 內容並去除前後空白
		return trimmed.substring(3, secondDelimiter).strip();
	}

}
