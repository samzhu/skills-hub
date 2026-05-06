package io.github.samzhu.skillshub.skill.validation;

/**
 * S098b3-2 — 一個結構化 validation finding，對應 PublishFailedPage ErrRow UI。
 *
 * <p>{@code section} V1 只有 {@code "skill_md"}；未來 bundle_structure / risk_scan 由後續 spec 加。
 * {@code hint} nullable — V1 先留 null，讓 title 清楚即可。
 */
public record ValidationFinding(
        String section,
        String severity,
        String title,
        String hint
) {}
