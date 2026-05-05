package io.github.samzhu.skillshub.skill.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * SKILL.md 內容驗證器 — 依 agentskills.io 規範解析並驗證技能套件的 SKILL.md 檔案。
 *
 * <p>驗證流程：
 * <ol>
 *   <li>行數上限檢查（500 行）</li>
 *   <li>提取 {@code ---} 分隔符之間的 YAML frontmatter</li>
 *   <li>使用 SnakeYAML 解析 frontmatter</li>
 *   <li>驗證 {@link #REQUIRED_FIELDS} 中所有必填欄位皆存在</li>
 *   <li>S135a：嚴格化欄位約束 + body 存在性（hard errors）</li>
 *   <li>S135a：body 品質建議（soft warnings，不擋 publish）</li>
 * </ol>
 *
 * @see ValidationResult
 */
@Component
public class SkillValidator {

	/** SKILL.md frontmatter 中必須存在的欄位名稱。 */
	private static final List<String> REQUIRED_FIELDS = List.of("name", "description");

	/**
	 * S135a AC-S135a-5：name 須以 [a-z0-9] 開頭和結尾，中間可含 hyphen，1-64 字元。
	 * leading/trailing hyphen 與 consecutive hyphen 由 validateFieldConstraints 先行攔截，
	 * 此 regex 作為最終 fallback（捕捉大寫、特殊字元等其他違規）。
	 */
	private static final Pattern NAME_REGEX = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$");

	/** S018 AC-14：description 上限 1024 字元。 */
	private static final int DESCRIPTION_MAX = 1024;

	/** S018 AC-14：compatibility 上限 500 字元。 */
	private static final int COMPATIBILITY_MAX = 500;

	/** S135a AC-S135a-5：SKILL.md 行數上限。 */
	private static final int SKILL_MD_MAX_LINES = 500;

	/**
	 * S018 AC-14：allowed-tools 各 token 的合法語法：
	 * - 單純名稱（{@code Edit} / {@code Read} / {@code Bash}）
	 * - 帶 args 形式（{@code Bash(git:*)} / {@code Bash(npm:test)}）
	 * 拒收 shell 控制字元（{@code ; & | $ ` > <}）+ 路徑（{@code /} 除括號內）+ 空字元等。
	 */
	private static final Pattern ALLOWED_TOOL_TOKEN_REGEX =
			Pattern.compile("^[A-Z][a-zA-Z0-9_]{0,30}(\\([a-zA-Z0-9_:.* /,-]{1,200}\\))?$");

	/** S135a：body 中 numbered list 偵測 pattern（e.g., "1." / "2."）。 */
	private static final Pattern NUMBERED_LIST = Pattern.compile("(?m)^\\s*\\d+\\.");

	/** S135a：code fence 偵測 pattern。 */
	private static final Pattern CODE_FENCE = Pattern.compile("```");

	/**
	 * 驗證 SKILL.md 文字內容。
	 *
	 * @param skillMdContent SKILL.md 的完整文字內容
	 * @return 驗證結果，包含解析後的 metadata、錯誤清單、及非阻斷性品質建議
	 */
	public ValidationResult validate(String skillMdContent) {
		if (skillMdContent == null || skillMdContent.isBlank()) {
			return ValidationResult.of(false, Map.of(), List.of("SKILL.md content is empty"));
		}

		// S135a AC-S135a-5：行數上限 500
		var lineCount = skillMdContent.split("\n", -1).length;
		if (lineCount > SKILL_MD_MAX_LINES) {
			return ValidationResult.of(false, Map.of(),
					List.of("skill_md_line_count: SKILL.md has " + lineCount + " lines (max " + SKILL_MD_MAX_LINES + ")"));
		}

		// 提取 frontmatter 區塊（--- 與 --- 之間的 YAML 文字）
		var yamlContent = extractFrontmatter(skillMdContent);
		if (yamlContent == null) {
			return ValidationResult.of(false, Map.of(), List.of("No YAML frontmatter found (expected --- delimiters)"));
		}

		// 使用 SnakeYAML 解析 frontmatter 字串為 Map
		var yaml = new Yaml();
		Map<String, Object> parsed;
		try {
			parsed = yaml.load(yamlContent);
		} catch (Exception e) {
			return ValidationResult.of(false, Map.of(), List.of("Invalid YAML: " + e.getMessage()));
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

		// S018 AC-14 + S135a AC-S135a-5：嚴格化欄位約束
		validateFieldConstraints(parsed, errors);

		// S135a AC-S135a-5：body 存在性（frontmatter 後需有非空 body）
		var body = extractBody(skillMdContent);
		if (body.isBlank()) {
			errors.add("body_present: SKILL.md has no body content after frontmatter");
		}

		if (!errors.isEmpty()) {
			return new ValidationResult(false, Collections.unmodifiableMap(new LinkedHashMap<>(parsed)),
					List.copyOf(errors), List.of());
		}

		// S135a AC-S135a-6：body 品質建議（非阻斷性 warnings）
		var warnings = new ArrayList<String>();
		validateBodyWarnings(body, warnings);

		return new ValidationResult(true, Collections.unmodifiableMap(new LinkedHashMap<>(parsed)),
				List.of(), List.copyOf(warnings));
	}

	/**
	 * S018 AC-14 + S135a AC-S135a-5：嚴格化檢查 — name / description / compatibility /
	 * allowed-tools / metadata 值型別。違規累積至 errors list，不短路。
	 */
	private void validateFieldConstraints(Map<String, Object> parsed, List<String> errors) {
		// name：leading/trailing hyphen → 特定訊息；consecutive hyphen → 特定訊息；其他 → regex 訊息
		var name = parsed.get("name");
		if (name != null) {
			var nameStr = name.toString();
			if (nameStr.startsWith("-") || nameStr.endsWith("-")) {
				errors.add("name: must not start or end with hyphen");
			} else if (nameStr.contains("--")) {
				errors.add("name: consecutive hyphens not allowed");
			} else if (!NAME_REGEX.matcher(nameStr).matches()) {
				errors.add("Field 'name' fails regex ^[a-z0-9-]{1,64}$ (got: " + nameStr + ")");
			}
		}

		// description：≤ 1024 字元；非空白
		var description = parsed.get("description");
		if (description != null) {
			if (description.toString().isBlank()) {
				errors.add("description: must not be blank");
			} else if (description.toString().length() > DESCRIPTION_MAX) {
				errors.add("Field 'description' exceeds " + DESCRIPTION_MAX + " characters");
			}
		}

		// compatibility：≤ 500 字元；若提供則不能為空白（optional）
		var compatibility = parsed.get("compatibility");
		if (compatibility != null) {
			if (compatibility.toString().isBlank()) {
				errors.add("compatibility: must not be blank if provided");
			} else if (compatibility.toString().length() > COMPATIBILITY_MAX) {
				errors.add("Field 'compatibility' exceeds " + COMPATIBILITY_MAX + " characters");
			}
		}

		// S073: allowed-tools 支援兩種 frontmatter 形狀（與 canonical agentskills.io spec 對齊）：
		//   1. YAML list（block 或 flow seq）— Anthropic 自家 SKILL.md 慣用形狀
		//   2. 空白分隔 string — 既有測試 fixture 與 v2.50.0 之前唯一支援形狀（向後相容）
		// 不能 fallback 到 toString()：ArrayList.toString() 為 "[a, b]"，會被誤切成 `[a,` / `b]` 全部不過 regex。
		var allowedTools = parsed.get("allowed-tools");
		if (allowedTools != null) {
			List<String> tokens;
			if (allowedTools instanceof List<?> list) {
				tokens = list.stream().map(String::valueOf).toList();
			} else {
				var s = allowedTools.toString().trim();
				tokens = s.isBlank() ? List.of() : List.of(s.split("\\s+"));
			}
			for (var token : tokens) {
				if (token.isBlank()) continue;
				if (!ALLOWED_TOOL_TOKEN_REGEX.matcher(token).matches()) {
					errors.add("Field 'allowed-tools' contains invalid token: " + token);
					break;   // 一個違規足以拒收，不重複報相同 root cause
				}
			}
		}

		// S135a AC-S135a-5：metadata 子物件各 value 須為 string
		validateMetadataFieldTypes(parsed, errors);
	}

	/**
	 * S135a AC-S135a-5：metadata 子物件各 value 必須是 string；int / boolean / nested map 拒收。
	 */
	private void validateMetadataFieldTypes(Map<String, Object> parsed, List<String> errors) {
		var metadata = parsed.get("metadata");
		if (metadata instanceof Map<?, ?> metaMap) {
			for (var entry : metaMap.entrySet()) {
				if (!(entry.getValue() instanceof String)) {
					errors.add("metadata: key '" + entry.getKey() + "' value must be a string");
				}
			}
		}
	}

	/**
	 * S135a AC-S135a-6：body 品質建議（不阻斷 publish）。
	 * 偵測三類缺漏並加入 warnings。
	 */
	private void validateBodyWarnings(String body, List<String> warnings) {
		if (!body.contains("## Example") && !CODE_FENCE.matcher(body).find()) {
			warnings.add("body_examples: no example heading or code fence detected");
		}
		if (!NUMBERED_LIST.matcher(body).find() && !body.contains("## Steps")) {
			warnings.add("body_steps: no step-by-step structure detected");
		}
		var bodyLower = body.toLowerCase();
		if (!bodyLower.contains("output") && !bodyLower.contains("format") && !CODE_FENCE.matcher(body).find()) {
			warnings.add("body_output_format: no output format guidance detected");
		}
	}

	/**
	 * 從 SKILL.md 文字中提取 YAML frontmatter 區塊。
	 *
	 * <p>frontmatter 須以 {@code ---} 開頭並以第二個 {@code ---} 結尾。
	 * 若格式不符則回傳 {@code null}。
	 */
	private String extractFrontmatter(String content) {
		var trimmed = content.strip();
		if (!trimmed.startsWith("---")) {
			return null;
		}
		int secondDelimiter = trimmed.indexOf("---", 3);
		if (secondDelimiter < 0) {
			return null;
		}
		return trimmed.substring(3, secondDelimiter).strip();
	}

	/**
	 * 從 SKILL.md 文字中提取 frontmatter 之後的 body 內容。
	 * 若無 frontmatter 則回傳空字串。
	 */
	private String extractBody(String content) {
		var trimmed = content.strip();
		if (!trimmed.startsWith("---")) {
			return "";
		}
		int secondDelimiter = trimmed.indexOf("---", 3);
		if (secondDelimiter < 0) {
			return "";
		}
		return trimmed.substring(secondDelimiter + 3).strip();
	}

}
