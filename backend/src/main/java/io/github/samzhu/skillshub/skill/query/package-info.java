/**
 * skill::query named interface — CQRS 讀取側 read models + repositories + query service。
 *
 * <p>S014 加入此 named interface 以允許 security module（ScanOrchestrator）跨模組
 * 引用 {@link io.github.samzhu.skillshub.skill.query.SkillReadModelRepository#updateRiskLevel}
 * 與 {@link io.github.samzhu.skillshub.skill.query.SkillVersionReadModelRepository#updateRiskAssessment}
 * （per S005 §7：避免發第二個 application event 造成循環依賴）。
 */
@org.springframework.modulith.NamedInterface("query")
package io.github.samzhu.skillshub.skill.query;
