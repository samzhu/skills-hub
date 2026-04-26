/**
 * Cross-cutting OAuth2 Resource Server / JWT 驗證設定與 demo endpoints。
 *
 * <p>本子 package 屬於 {@code shared} module 的一部分，不另開獨立 module。
 * 內含：
 * <ul>
 *   <li>{@link io.github.samzhu.skillshub.shared.security.SecurityConfig}
 *       — SecurityFilterChain 與 JwtAuthenticationConverter bean</li>
 *   <li>{@link io.github.samzhu.skillshub.shared.security.MeController}
 *       — {@code /api/v1/me}：回傳目前 JWT 解出的 claim 集合</li>
 *   <li>{@link io.github.samzhu.skillshub.shared.security.AdminController}
 *       — {@code /api/v1/admin/echo}：示範 {@code @PreAuthorize("hasRole('admin')")}</li>
 * </ul>
 *
 * <p>對應 spec：S011 — 開發環境 OAuth Mock 整合。
 */
@org.springframework.modulith.NamedInterface("security")
package io.github.samzhu.skillshub.shared.security;
