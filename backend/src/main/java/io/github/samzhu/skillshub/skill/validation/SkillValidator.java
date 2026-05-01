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

	/** S018 AC-14：name 必須符合 lowercase + digits + hyphen，1-64 字元（agentskills.io spec）。 */
	private static final Pattern NAME_REGEX = Pattern.compile("^[a-z0-9-]{1,64}$");

	/** S018 AC-14：description 上限 1024 字元。 */
	private static final int DESCRIPTION_MAX = 1024;

	/** S018 AC-14：compatibility 上限 500 字元。 */
	private static final int COMPATIBILITY_MAX = 500;

	/**
	 * S018 AC-14：allowed-tools 各 token 的合法語法：
	 * - 單純名稱（{@code Edit} / {@code Read} / {@code Bash}）
	 * - 帶 args 形式（{@code Bash(git:*)} / {@code Bash(npm:test)}）
	 * 拒收 shell 控制字元（{@code ; & | $ ` > <}）+ 路徑（{@code /} 除括號內）+ 空字元等。
	 */
	private static final Pattern ALLOWED_TOOL_TOKEN_REGEX =
			Pattern.compile("^[A-Z][a-zA-Z0-9_]{0,30}(\\([a-zA-Z0-9_:.* /,-]{1,200}\\))?$");

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

		// S018 AC-14：嚴格化驗證 — 必填欄位有值時才檢查格式（避免 null 造成 NullPointerException）
		validateFieldConstraints(parsed, errors);

		if (!errors.isEmpty()) {
			// 驗證失敗：回傳部分解析結果供錯誤診斷，metadata 以不可變 Map 封裝
			return new ValidationResult(false, Collections.unmodifiableMap(new LinkedHashMap<>(parsed)), List.copyOf(errors));
		}

		// 驗證通過：以 LinkedHashMap 保留欄位宣告順序，再封裝為不可變 Map 回傳
		return new ValidationResult(true, Collections.unmodifiableMap(new LinkedHashMap<>(parsed)), List.of());
	}

	/**
	 * S018 AC-14：嚴格化檢查 — name regex / description-length / compatibility-length /
	 * allowed-tools-syntax。違規累積至 errors list，不短路（讓 caller 一次看到所有違規）。
	 */
	private void validateFieldConstraints(Map<String, Object> parsed, List<String> errors) {
		// name：lowercase + digits + hyphen，1-64 字元
		var name = parsed.get("name");
		if (name != null && !NAME_REGEX.matcher(name.toString()).matches()) {
			errors.add("Field 'name' fails regex ^[a-z0-9-]{1,64}$ (got: " + name + ")");
		}

		// description：≤ 1024 字元
		var description = parsed.get("description");
		if (description != null && description.toString().length() > DESCRIPTION_MAX) {
			errors.add("Field 'description' exceeds " + DESCRIPTION_MAX + " characters");
		}

		// compatibility：≤ 500 字元（optional）
		var compatibility = parsed.get("compatibility");
		if (compatibility != null && compatibility.toString().length() > COMPATIBILITY_MAX) {
			errors.add("Field 'compatibility' exceeds " + COMPATIBILITY_MAX + " characters");
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
