package io.github.samzhu.skillshub.skill.command;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * S163 — PUT /api/v1/skills/{id} 請求 body。
 *
 * <p>S187 起這條 API **僅**允許 category。description snapshot 只能由新版 SKILL.md
 * frontmatter.description 更新；controller 看到 request body 含 description 會先回 400。
 *
 * <p>name / version 不在 DTO surface，Jackson
 * 預設 ignore unknown fields → 送 name / version 進來自動被丟掉，aggregate 內部
 * 對應欄位不變。AC-3「name and version are immutable」由「DTO 缺面」自然滿足。
 *
 * <p>category 允許 null — null 表示本次不動該欄位（partial update via PUT；
 * 雖然 REST 嚴格上 PUT = full replace，本平台採實用主義）。
 *
 * @param category 新分類（如為 null 則不動）；trim 後存
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateSkillCommand(String category) {}
