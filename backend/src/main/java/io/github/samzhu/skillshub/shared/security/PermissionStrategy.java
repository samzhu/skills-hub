package io.github.samzhu.skillshub.shared.security;

import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Aggregate-level permission strategy — {@link DelegatingPermissionEvaluator}
 * 透過 {@link #supports(String)} 路由到對應 strategy（S016）。
 *
 * <p>每個業務 aggregate（Skill、Workspace、WarRoom）一個 {@code @Component} 實作；
 * Spring DI 自動收齊（{@code List<PermissionStrategy>} 注入 dispatcher）。
 * 新增 aggregate 不需修改 {@link DelegatingPermissionEvaluator}（Open/Closed Principle）。
 *
 * <p>對應 Spring Security 7 {@link org.springframework.security.access.PermissionEvaluator}
 * SpEL 路徑 — {@code @PreAuthorize("hasPermission(#id, 'Skill', 'read')")}
 * 第二參數即 {@code targetType}，匹配 {@link #supports(String)}。
 *
 * @see DelegatingPermissionEvaluator
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/authorization/acls.html">Spring Security ACLs Reference</a>
 */
public interface PermissionStrategy {

    /**
     * 是否處理此 targetType — 對應 {@code @PreAuthorize} SpEL 第二參數。
     *
     * @param targetType 例如 {@code "Skill"}、{@code "Workspace"}
     */
    boolean supports(String targetType);

    /**
     * 實際 ACL 檢查 — 由 dispatcher 在 {@link #supports(String)} 為 true 時呼叫。
     *
     * @param principals       已展開的 patterns（user / role 兩類；group 由各 strategy
     *                         自行透過 {@link CurrentUserProvider} 補上 — 設計理由詳
     *                         {@link DelegatingPermissionEvaluator} 註釋）
     * @param targetIdOrObject {@code @PreAuthorize("hasPermission(#id, ...)")} 傳入的 id（String）
     *                         或 {@code @PostAuthorize("hasPermission(returnObject, ...)")} 傳入的 domain object；
     *                         null 由 dispatcher 攔下不會傳入此處
     * @param permission       SpEL 第三參數，如 {@code "read"} / {@code "write"} /
     *                         {@code "delete"} / {@code "suspend"} / {@code "reactivate"}
     */
    boolean hasPermission(Set<String> principals,
                          @Nullable Object targetIdOrObject,
                          String permission);
}
