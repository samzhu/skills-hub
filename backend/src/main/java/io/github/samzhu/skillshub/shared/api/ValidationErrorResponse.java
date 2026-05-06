package io.github.samzhu.skillshub.shared.api;

import java.time.Instant;
import java.util.List;

import io.github.samzhu.skillshub.skill.validation.ValidationFinding;

/**
 * S098b3-2 — 結構化驗證錯誤回應。
 *
 * <p>擴展 {@link ErrorResponse} 的語意（非繼承），加入 {@code findings} 陣列，
 * 讓前端逐項渲染 SKILL.md 驗證錯誤 / warning ErrRow，不再顯示 flat concatenated msg。
 * {@link ErrorResponse} 本體不動（其他 28 個 handler 不受影響）。
 */
public record ValidationErrorResponse(
        String error,
        String message,
        Instant timestamp,
        List<ValidationFinding> findings
) {}
