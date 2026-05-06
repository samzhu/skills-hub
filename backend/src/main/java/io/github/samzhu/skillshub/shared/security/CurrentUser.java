package io.github.samzhu.skillshub.shared.security;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * 當前請求的使用者識別 — 從 {@link org.springframework.security.core.context.SecurityContext}
 * 抽出的最小欄位集合（S012；S016 加 {@code groups}；S114a 加 {@code companyId}）。
 *
 * <p>OAuth 模式下 {@code userId} 來自 JWT {@code sub} claim、{@code roles} 來自 JWT
 * {@code roles} claim、{@code groups} 來自 OIDC {@code groups} claim；LAB 模式下
 * {@code userId} / {@code roles} 由 {@link LabSecurityFilter} 注入，{@code groups}
 * 預設為空 list（無 claim 來源；fail-secure）。
 *
 * <p>未來 audit 欄位（{@code createdBy} / {@code updatedBy}）統一從
 * {@link CurrentUserProvider#userId()} 取值，避免散在各 controller / service 自行
 * 處理 JWT vs LAB 兩種型別差異。
 *
 * <p>S016 ACL 用 {@code groups} 展開 {@code group:<name>:<perm>} patterns 比對
 * row-level {@code acl_entries}（per {@link AclPrincipalExpander}）。
 *
 * <p>S114a：{@code companyId} 來自 JWT {@code company_id} claim；非 null 時展開
 * {@code company:<companyId>:<perm>} pattern 讓 company-scoped skill 對全公司開放。
 *
 * @param userId    JWT {@code sub} 或 LAB 模式預設值（如 {@code "lab-user"}）
 * @param roles     角色清單；已剝去 Spring Security 的 {@code ROLE_} 前綴，回到業務語意值
 * @param groups    OIDC {@code groups} claim 原樣抽出；LAB / fallback 為 {@link List#of()}
 * @param companyId JWT {@code company_id} claim；null 表示 user 無 company context
 */
public record CurrentUser(String userId, List<String> roles, List<String> groups,
                          @Nullable String companyId) {}
