/**
 * Community module — Request Board (S096g2) + Collections (S096f2) aggregates。
 *
 * <p>S096g2 ship 時正式註冊 Modulith module（既有 RequestController stub 在
 * S096g1 ship 時尚無 ApplicationModule 標註）。Community 兩 aggregate 都走
 * ADR-002 canonical pattern（Spring Data JDBC 充血聚合 + Modulith Outbox）。
 *
 * <p>Cross-module SPI：
 * <ul>
 *   <li>{@code skill::domain} — 讀 Skill aggregate 驗 PUBLISHED status (fulfill 用)</li>
 *   <li>{@code skill::query} — 預留 read model 投影需求</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared :: events", "shared :: api", "shared :: security",
                           "skill :: domain", "skill :: query"}
)
package io.github.samzhu.skillshub.community;
