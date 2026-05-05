/**
 * skill::validation named interface — SKILL.md 驗證規則（SkillValidator + ValidationResult）。
 *
 * <p>S135a 加入此 named interface 以允許 score module（QualityScoreService）
 * 引用 SkillValidator.validate() 做 VALIDATION axis 品質評分。
 */
@org.springframework.modulith.NamedInterface("validation")
package io.github.samzhu.skillshub.skill.validation;
