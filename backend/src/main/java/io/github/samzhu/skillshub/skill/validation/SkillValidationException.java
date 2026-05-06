package io.github.samzhu.skillshub.skill.validation;

import java.util.List;

/**
 * S098b3-2 — 取代 SKILL.md 驗證失敗的 {@link IllegalArgumentException}，攜帶結構化 findings。
 *
 * <p>由 {@link io.github.samzhu.skillshub.skill.command.SkillCommandService} 拋出；
 * {@link io.github.samzhu.skillshub.shared.api.GlobalExceptionHandler} 專屬 handler 捕捉。
 */
public class SkillValidationException extends RuntimeException {

    private final List<ValidationFinding> findings;

    public SkillValidationException(String message, List<ValidationFinding> findings) {
        super(message);
        this.findings = List.copyOf(findings);
    }

    public List<ValidationFinding> findings() {
        return findings;
    }
}
