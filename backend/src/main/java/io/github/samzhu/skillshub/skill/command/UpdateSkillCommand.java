package io.github.samzhu.skillshub.skill.command;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * S163 — PUT /api/v1/skills/{id} 請求 body。
 *
 * <p>**僅** description / category 可改。name / version 不在 DTO surface，Jackson
 * 預設 ignore unknown fields → 送 name / version 進來自動被丟掉，aggregate 內部
 * 對應欄位不變。AC-3「name and version are immutable」由「DTO 缺面」自然滿足。
 *
 * <p>兩個欄位都允許 null — null 表示本次不動該欄位（partial update via PUT；
 * 雖然 REST 嚴格上 PUT = full replace，本平台採實用主義；frontend EditSkillModal
 * 永遠帶兩個欄位故無歧義）。
 *
 * @param description 新描述（如為 null 則不動）；trim 後存
 * @param category    新分類（如為 null 則不動）；trim 後存
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateSkillCommand(String description, String category) {}
