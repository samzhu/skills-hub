/**
 * security.scan — 多引擎安全掃描的 finding domain types。
 *
 * <p>S148d: 宣告為 {@code @NamedInterface("scan")} 讓其他 module（如 score）可顯式
 * 在 {@code @ApplicationModule.allowedDependencies} 列表加 {@code "security :: scan"} 引用，
 * 不再被 Modulith 視為 internal sub-package。
 *
 * <p>Modulith 預設只允許 module 頂層 package 對外曝露；sub-package 預設 internal。
 * SecurityFinding 雖位於 sub-package，但被 score / skill domain event 等多 module 用作
 * shared finding shape — 顯式 NamedInterface 表達設計意圖，避免後續他人誤以為 internal。
 */
@org.springframework.modulith.NamedInterface("scan")
package io.github.samzhu.skillshub.security.scan;
