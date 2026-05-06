package io.github.samzhu.skillshub.skill.security;

import java.lang.invoke.MethodHandles;
import java.sql.Types;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.shared.security.AclPrincipalExpander;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.PermissionStrategy;

/**
 * Skill aggregate 的 row-level ACL 檢查 — {@code acl_entries ??| :patterns} SQL 比對（S016；spec §4.5）。
 *
 * <p>{@link io.github.samzhu.skillshub.shared.security.DelegatingPermissionEvaluator}
 * 透過 {@link #supports(String)} 路由到本 strategy；dispatcher 已展 user / role 兩命名空間，
 * 本 strategy 額外從 {@link CurrentUserProvider#current()} 取 groups 補上 group: patterns
 * （per spec §4.4 設計分工）。
 *
 * <h2>SQL 細節</h2>
 * <ul>
 *   <li>{@code ??|} 雙問號 escape 必要：pgJDBC PgPreparedStatement 在 Spring NamedParameterJdbcTemplate
 *       下層仍會 parse {@code ?} 為 placeholder（spec §2.4 Challenge #2 [Implementation note: T1 verified]）；
 *       pgJDBC 解碼 {@code ??} → {@code ?}，最終送 PostgreSQL 是 {@code ?|} operator</li>
 *   <li>{@code SqlParameterValue(Types.ARRAY, String[])} 必要：避免 NamedParameterJdbcTemplate 對
 *       {@link Iterable} / {@link java.util.Collection} 自動展成 IN-list，破壞 {@code ?|} 單一 ARRAY 語意
 *       （spec §2.4 Challenge #3）</li>
 *   <li>{@code EXISTS} sub-query 比 {@code COUNT(*) > 0} 快 — planner 可在第一個 row 命中後 short-circuit</li>
 * </ul>
 *
 * @see PermissionStrategy
 * @see io.github.samzhu.skillshub.shared.security.DelegatingPermissionEvaluator
 * @see <a href="https://www.postgresql.org/docs/16/datatype-json.html#JSON-INDEXING">PostgreSQL 16 JSON Indexing</a>
 */
@Component
public class SkillPermissionStrategy implements PermissionStrategy {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /** Caffeine cache name — S114b ACL read 去重（grant/revoke 後由 SkillAclProjectionListener evict）。 */
    static final String CACHE_NAME = "skill-acl";

    /**
     * acl_entries ?| :patterns — 任一 pattern 命中即 true。
     *
     * <p>{@code ??|} 寫法詳 class Javadoc 與 spec §2.4 #2 [Implementation note: T1 verified]。
     */
    private static final String SQL = """
            SELECT EXISTS (
              SELECT 1 FROM skills
               WHERE id = :skillId
                 AND acl_entries ??| :patterns
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentUserProvider currentUserProvider;
    private final AclPrincipalExpander expander;
    private final Cache cache;

    /**
     * @param dataSource          專屬 DataSource — 不複用既有的 {@link NamedParameterJdbcTemplate} bean，
     *                            因 strategy 邏輯與既有 query service 互無依賴；獨立 instance 簡化 module
     *                            邊界（{@code skill :: security} 不需 import {@code skill :: query}）。
     * @param currentUserProvider 取當前 user 的 groups（dispatcher 不展 group，由本 strategy 補）
     * @param expander            把 groups 展開成 {@code group:<name>:<perm>} patterns
     * @param cacheManager        Caffeine CacheManager（S114b ACL read 去重）
     */
    public SkillPermissionStrategy(DataSource dataSource,
                                   CurrentUserProvider currentUserProvider,
                                   AclPrincipalExpander expander,
                                   CacheManager cacheManager) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.currentUserProvider = currentUserProvider;
        this.expander = expander;
        this.cache = cacheManager.getCache(CACHE_NAME);
    }

    @Override
    public boolean supports(@Nullable String targetType) {
        return "Skill".equals(targetType);
    }

    /**
     * 對指定 skillId 檢查當前 user 是否有對應 permission。
     *
     * <p>流程：
     * <ol>
     *   <li>dispatcher 已給的 user / role patterns 直接收入 fullPatterns</li>
     *   <li>從 {@link CurrentUserProvider} 取 groups，經 {@link AclPrincipalExpander#expandGroups} 展成
     *       {@code group:<name>:<permission>} 補上</li>
     *   <li>SQL {@code EXISTS} 配 {@code ??|} 任一命中即 true；找不到 row（不存在的 skillId）也 false（fail-secure）</li>
     * </ol>
     *
     * @param principals       dispatcher 給的 user / role patterns
     * @param targetIdOrObject 通常是 skillId String（{@code @PreAuthorize("hasPermission(#id, 'Skill', ...)")} path）；
     *                         {@code @PostAuthorize("hasPermission(returnObject, ...)")} path 暫不啟用
     * @param permission       SpEL 第三參數，如 {@code "read"} / {@code "write"} / {@code "delete"} /
     *                         {@code "suspend"} / {@code "reactivate"}
     */
    @Override
    public boolean hasPermission(Set<String> principals,
                                 @Nullable Object targetIdOrObject,
                                 String permission) {
        if (targetIdOrObject == null) {
            return false;
        }
        var skillId = targetIdOrObject.toString();

        // 補 group: patterns — dispatcher 不知 groups（避免循環依賴 CurrentUserProvider）；strategy 端補。
        // fullPatterns 必須先展開才能作為 cache key（group patterns 在此決定，非 principals 參數本身）。
        var fullPatterns = new HashSet<>(principals);
        fullPatterns.addAll(expander.expandGroups(currentUserProvider.current().groups(), permission));

        // Cache key = skillId + sorted fullPatterns + permission（TreeSet 保證排序一致性）
        var cacheKey = skillId + ":" + new TreeSet<>(fullPatterns) + ":" + permission;
        if (cache != null) {
            var cached = cache.get(cacheKey, Boolean.class);
            if (cached != null) {
                return cached;
            }
        }

        var patternsArray = fullPatterns.toArray(new String[0]);
        var params = new MapSqlParameterSource()
                .addValue("skillId", skillId)
                // SqlParameterValue(Types.ARRAY, ...) 強制走 ps.setArray() — 避開 NamedParameterJdbcTemplate
                // 對 Iterable<?> 的 IN-list 自動展開（會把 String[] 拆成 ?,?,? 破壞 ?| 語意）
                .addValue("patterns", new SqlParameterValue(Types.ARRAY, patternsArray));

        var result = Boolean.TRUE.equals(jdbc.queryForObject(SQL, params, Boolean.class));
        if (cache != null) {
            cache.put(cacheKey, result);
        }
        return result;
    }
}
