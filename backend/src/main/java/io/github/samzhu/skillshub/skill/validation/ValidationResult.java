package io.github.samzhu.skillshub.skill.validation;

import java.util.List;
import java.util.Map;

/**
 * SKILL.md 驗證結果 — 封裝 {@link SkillValidator#validate} 的執行結果。
 *
 * <p>驗證通過時 {@code valid} 為 {@code true}、{@code errors} 為空集合；
 * 驗證失敗時 {@code valid} 為 {@code false}，{@code errors} 含具體錯誤訊息，
 * {@code metadata} 為空 Map（YAML 解析失敗）或解析成功的部分資料。
 *
 * @param valid    是否通過所有必填欄位驗證
 * @param metadata 從 SKILL.md YAML frontmatter 解析出的 metadata 鍵值對（不可變）
 * @param errors   驗證失敗的錯誤訊息清單（通過時為空，不可變）
 */
public record ValidationResult(
		boolean valid,
		Map<String, Object> metadata,
		List<String> errors
) {}
