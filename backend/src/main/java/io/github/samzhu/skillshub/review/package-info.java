/**
 * Review module — 社群評論（rating + content）aggregate + REST endpoints。
 *
 * <p>S098e2 ship — ADR-002 canonical pattern：Spring Data JDBC 充血聚合 +
 * Modulith Outbox（同 TX）+ AFTER_COMMIT projection 在 T02 補。
 *
 * <p>Endpoint：
 * <ul>
 *   <li>{@code POST /api/v1/skills/{skillId}/reviews} — 建立 review</li>
 *   <li>{@code DELETE /api/v1/skills/{skillId}/reviews/{reviewId}} — 刪自己 review</li>
 *   <li>{@code GET /api/v1/skills/{skillId}/reviews} — 列表（time desc）</li>
 * </ul>
 *
 * <p>Modulith allowedDependencies：{@code shared :: events / api / security} +
 * {@code skill :: domain}（讀 Skill aggregate 驗 PUBLISHED status if 需要；T01 暫
 * 不檢 skill 狀態，純信任 caller — 對齊 Flag 既有寬鬆 pattern）。
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared :: events", "shared :: api", "shared :: security",
                           "skill :: domain"}
)
package io.github.samzhu.skillshub.review;
