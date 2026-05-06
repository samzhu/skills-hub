# S114b — ACL Production Scale：Caffeine Cache

**Status:** ✅ 已 ship（v4.11.0）
**Size:** M(10 pt)
**Depends on:** S114a ✅
**Target version:** v4.11.0

---

## §1 Goal

每個 `@PreAuthorize("hasPermission(#id, 'Skill', ...)")` 觸發一次 `SkillPermissionStrategy` SQL 查詢。對單一 HTTP request 內多個 Skill endpoint（list → get → download），同一 user + 同一 skill 的 ACL 查詢會重複打 DB。加 Caffeine JVM 內存 cache 吸收重複 read，減少 DB round-trip。

**不是：** PgBouncer / read replica（→ S114c）；不做分散式 cache（MVP 規模 Cloud Run 單 instance 足夠；Caffeine TTL 短，過期後自動 refresh，不需 Redis）。

---

## §2 Approach

### 2.1 Cache 層位置

在 `SkillPermissionStrategy.hasPermission()` return 前加 `@Cacheable`，以 `(skillId + ':' + canonicalPrincipals)` 為 key：
- `canonicalPrincipals`：Set 轉 sorted + joined string（保證相同 principal set = 相同 key）
- anonymous read 的 principals 固定為 `Set.of("public:*:read")` — 可直接 cache

### 2.2 Cache 設定

```yaml
# application.yaml 加（S129 server block 之後）
spring:
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=1000,expireAfterWrite=300s
```

`expireAfterWrite=300s`（5 分鐘）— grant/revoke 是 admin-level 低頻操作；TTL 到期自動 refresh 不需 Redis；explicit evict 作為保險。

### 2.3 Dependency

```kotlin
// build.gradle.kts — 取消現有 commented-out spring-boot-starter-cache
implementation("org.springframework.boot:spring-boot-starter-cache")
// Caffeine: Spring Boot managed version — 不 pin explicit version
implementation("com.github.ben-manes.caffeine:caffeine")
```

### 2.4 啟用 Spring Cache

在 `SkillshubApplication`（或 infra `@Configuration`）加 `@EnableCaching`。

### 2.5 Cache 命名與 Evict

Cache name: `"skill-acl"`（application.yaml 可 profile override spec）

Evict 時機：`SkillGrantedEvent` / `SkillRevokedEvent` fire 後，在 `SkillAclProjectionListener`（已訂閱這兩個事件重建 acl_entries）同步 evict 對應 skillId 的所有 cache entries（`allEntries = false`，按 skillId prefix evict — 需 Caffeine custom implementation；或簡化為 `allEntries = true` — 小規模足夠）。

**Trim path（M spec）：**
- Core（本 tick）：`@EnableCaching` + Caffeine dependency + `@Cacheable` on strategy + `@CacheEvict(allEntries=true)` on projection listener
- Defer to S114c：PgBouncer、read replica、分散式 cache、per-skillId prefix evict（精準 evict）

### 2.6 Test 設計

`SkillPermissionStrategyTest`：驗證相同 (skillId, principals) 的第二次呼叫不打 SQL（mockito verify `jdbcTemplate` 只被呼叫 1 次）。利用 `@SpringBootTest` slice + `@EnableCaching` 或 `CaffeineCacheManager` in test context。

---

## §3 Acceptance Criteria

**AC-1 — 同 user 同 skill 的重複 read 不重複打 DB**
```
Given: user Alice 對 skill X 有 read 權限（acl_entries 含 user:alice:read）
When:  連續 2 次呼叫 hasPermission("X", Set.of("user:alice:read"), "read")
Then:  SQL 只執行 1 次（第 2 次從 Caffeine 回）
       兩次 return 值均為 true
```

**AC-2 — Grant 後 cache evict，下次查到新 ACL**
```
Given: skill Y 的 acl_entries 不含 user:bob:read → hasPermission false（已 cache）
When:  admin grant BOB read on skill Y → SkillGrantedEvent 觸發 evict
Then:  下次 hasPermission("Y", ...) 重新打 SQL → 拿到新 acl_entries → return true
```

**AC-3 — Anonymous read 也走 cache（不繞過）**
```
Given: PUBLIC skill Z（acl_entries 含 public:*:read）
When:  anonymous 連續 2 次觸發 hasPermission("Z", Set.of("public:*:read"), "read")
Then:  SQL 只執行 1 次；兩次均 true
```

**AC-4 — Cache miss 後 TTL 到期重查**
```
Given: cache entry expireAfterWrite=300s
When:  300s 後同一 key 再次查詢
Then:  重新打 SQL（TTL evict）；結果與 DB 實際狀態一致
```

---

## §4 File Plan

| File | Action |
|------|--------|
| `backend/build.gradle.kts` | 取消 spring-boot-starter-cache 註解；加 caffeine dependency |
| `backend/src/main/resources/application.yaml` | 加 `spring.cache.type=caffeine` + `caffeine.spec` |
| `backend/src/main/java/.../SkillshubApplication.java` | 加 `@EnableCaching` |
| `backend/src/main/java/.../skill/security/SkillPermissionStrategy.java` | 加 `@Cacheable("skill-acl")` + key 產生邏輯 |
| `backend/src/main/java/.../skill/security/SkillAclProjectionListener.java` | 在 grant/revoke handler 加 `@CacheEvict` |
| `backend/src/test/java/.../skill/security/SkillPermissionStrategyTest.java` | 加 cache AC 測試（verify SQL 只呼叫 1 次） |

---

## §5 Test Plan

- **Unit test（AC-1 / AC-3）**：`@SpringBootTest(classes = ...)` 或 slice + `@EnableCaching` + `CaffeineCacheManager`；mock JDBC → verify `queryForObject` 1 次呼叫 2 次 `hasPermission`。
- **Integration test（AC-2）**：Testcontainers PostgreSQL；grant → evict → 再查 → return true。
- **TTL test（AC-4）**：可用 `Ticker` stub 操控時間；或 skip（配置正確 TTL 由 Caffeine 保證；不值得 mock time）。
- **Regression**：`./gradlew test --tests "*.SkillPermissionStrategyTest"` + `./gradlew compileJava`。

---

## §6 Verification

- `./gradlew test --tests "*.SkillPermissionStrategyTest"` — 11/11 PASS（含 AC-1 新增 cache hit test）
- `./gradlew compileJava compileTestJava` — BUILD SUCCESSFUL
- 附加 bugfix：`DependencyVulnScanner` 從 Spring 注入 `ObjectMapper` → 改 `static final OBJECT_MAPPER = new ObjectMapper()` 消除 S099e3 引入的 `@SpringBootTest` context 載入失敗（pre-existing regression）

---

## §7 Result

| Metric | 值 |
|--------|-----|
| 測試通過 | 11/11（SkillPermissionStrategyTest） |
| 新增 AC-1 cache 測試 | `cacheHit_secondCallReturnsCachedResult` — 呼叫 2 次 → `estimatedSize() > 0` 確認 Caffeine 有 entry |
| Cache 設定 | `maximumSize=1000, expireAfterWrite=300s` |
| Cache key | `skillId + ":" + TreeSet(fullPatterns) + ":" + permission`（group 展開後，排序一致） |
| Evict | `@CacheEvict(allEntries=true)` on `SkillAclProjectionListener.onGranted/onRevoked` |
| 修復 | `DependencyVulnScanner` 去除 Spring `ObjectMapper` 注入依賴 |
