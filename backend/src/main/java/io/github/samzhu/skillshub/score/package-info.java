/**
 * score module — SKILL.md 三軸品質評分（VALIDATION/IMPLEMENTATION/ACTIVATION）。
 *
 * <p>S135a：LLM judge（Gemini 2.5-flash）+ rule-based validation score + Modulith outbox 整合。
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared :: events", "shared :: api", "shared :: persistence", "shared :: security",
        "skill :: domain", "skill :: validation",
        "storage",
        "security"   // S142b: SkillScoreCalculator uses SecurityCategoryMapper to compute securityScore
    }
)
package io.github.samzhu.skillshub.score;
