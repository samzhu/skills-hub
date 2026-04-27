/**
 * shared::persistence named interface — Spring Data JDBC + PostgreSQL 共用配置。
 *
 * <p>本 package 內容：
 * <ul>
 *   <li>{@link io.github.samzhu.skillshub.shared.persistence.JdbcConfiguration}
 *       — 註冊 {@code Map<String, Object> ↔ JSONB} 雙向 Converter，供 Event Store
 *       與所有 read model 的 JSONB 欄位使用。</li>
 * </ul>
 *
 * <p>所有 Spring Modulith module（skill / security / analytics）透過 Spring DI
 * 自動繼承本 named interface 註冊的 Converter，不需顯式 import；
 * Modulith 邊界透過 {@code @NamedInterface("persistence")} 暴露為跨模組 named interface。
 *
 * <p>S014 引入；後續 spec（S016 ACL）會在本 package 加 ACL string Converter（如需）。
 */
@org.springframework.modulith.NamedInterface("persistence")
package io.github.samzhu.skillshub.shared.persistence;
