/**
 * Community module — Request Board (S096g2) + Collections (S096f2) aggregates。
 *
 * <p>S096g2 ship 時正式註冊 Modulith module（既有 RequestController stub 在
 * S096g1 ship 時尚無 ApplicationModule 標註）；S096f2-T01 補 {@code displayName}
 * 對齊 audit / notification / analytics 既驗 module metadata 慣例。Community 兩
 * aggregate 都走 ADR-002 canonical pattern（Spring Data JDBC 充血聚合 + Modulith Outbox）。
 *
 * <p>Cross-module SPI：
 * <ul>
 *   <li>{@code skill::domain} — 讀 Skill aggregate 驗 PUBLISHED status
 *       （Request.fulfill / Collection.create 預檢 skillIds 全 PUBLISHED）</li>
 *   <li>{@code skill::query} — 預留 read model 投影需求</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Community",
    allowedDependencies = {"shared :: events", "shared :: api", "shared :: security",
                           "skill :: domain", "skill :: query"}
)
package io.github.samzhu.skillshub.community;
