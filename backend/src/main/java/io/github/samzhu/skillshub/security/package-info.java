/**
 * Security module — 多引擎安全掃描 + Flag service。
 *
 * <p>S014：ScanOrchestrator 從 MongoTemplate 改 Spring Data JDBC repository（@Modifying @Query）
 * 寫 risk_level / risk_assessment。新增允許依賴 {@code skill :: query} —
 * ScanOrchestrator 直接呼叫 SkillReadModelRepository.updateRiskLevel +
 * SkillVersionReadModelRepository.updateRiskAssessment（per S005 §7：避免發第二個
 * application event 造成循環依賴；保持「scanner 是 read model 的協作者，不是業務上游」設計）。
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared :: events", "shared :: api", "shared :: persistence",
                           "skill :: domain", "skill :: query", "storage"}
)
package io.github.samzhu.skillshub.security;
