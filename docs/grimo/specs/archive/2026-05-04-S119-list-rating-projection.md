# S119: List endpoint rating projection (Bug AR fix — user-visible)

> Spec: S119 | Size: XS(2) | Status: ✅ Shipped (v3.10.2)
> Date: 2026-05-04
> Source: Mode B Round 36 (2026-05-03) finding (MEDIUM, user-visible) — backlog 候選

---

## 1. Goal

修補 `SkillQueryService.search()` raw JDBC SELECT clause 缺 `average_rating, review_count` 兩 column 致 list endpoint 永遠回 `averageRating=0, reviewCount=0` — frontend SkillCard 顯 rating 星星永遠 0（user-visible bug）。Single GET findById 走 Spring Data JDBC auto-load 已含真值；本 fix 補齊 list path 一致性，讓 LAB 封測員工瀏覽 skills list 看到真實評分數據。

**起源**：Mode B Round 36 (2026-05-03) finding **Bug AR**。S098e2 ship review averageRating projection 後此 list endpoint 漏 update SELECT；3 backlog 中本 spec MEDIUM severity 影響員工 LAB 視覺體驗。

**非目標**（本 spec 不做）：
- 改 review controller / aggregate（與本 fix 正交）
- frontend SkillCard 顯示邏輯（已 implement，純等 backend 給真值）

## 2. Approach

走 **option A — backward-compat overload pattern + SQL SELECT 補 2 column**（per S116 既驗 100x 成本節省 vs 全 callsite migration）：

### 2.1 兩條改動 path

1. **`Skill.fromRow` 加 15-arg overload**（`...List<String> aclEntries, Long version, double averageRating, long reviewCount`）— 13-arg 既有 caller 透過新 backward-compat overload delegate to 15-arg with `(0.0, 0L)` defaults，**0 callsite migration**
2. **`SkillQueryService.search()` SQL** SELECT 加 `average_rating, review_count` 兩 column；`mapSkillRow` 走 15-arg `fromRow` 餵真值

### 2.2 Approach 比較

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| **A. Backward-compat overload + SELECT 補 column** | 0 callsite migration（既有 12+ caller 不動）；對齊 S116 既驗 pattern；最小 diff | 兩個 method overload 同 codebase（小型 cost） | ⭐ |
| B. 強制 15-arg + 全 callsite migration | 唯一 method signature | 12+ test/production caller 都要 migrate；高 churn 成本 | |
| C. 把 averageRating/reviewCount 改非 @ReadOnlyProperty 走 Spring Data JDBC 自動 binding | 不改 fromRow 簽章 | aggregate full-row UPDATE 會覆蓋並發 projection update（per S077/AK regression 教訓）；會破 invariant | |

走 **A**。

### 2.3 Trim list

XS=2 範圍緊；無可進一步 trim。

## 3. SBE Acceptance Criteria

驗證指令：`./gradlew test --tests "*SkillSearchTest" -x npmBuild`

**AC-S119-1：list endpoint 回 averageRating + reviewCount projection 真值**
- Given：skill seeded acl_entries=[*:read]；後續 raw SQL UPDATE 寫 `average_rating=4.5, review_count=2`（mirror SkillRatingService.refresh 路徑）
- When：`queryService.search("rated-skill-x", null, null, page)`
- Then：page.content[0].averageRating=4.5；reviewCount=2L

**AC-S119-2 (regression)：既有 SkillSearchTest 13 ACs PASS**
- 13-arg fromRow overload 走 backward-compat delegate；既有 fixture 行為不變

**AC-S119-3 (manual smoke)：dev backend list endpoint 回 rating**
- DB seed `UPDATE skills SET average_rating=4.5, review_count=2 WHERE id=...`
- `GET /api/v1/skills?keyword=...` → `content[0].averageRating=4.5, reviewCount=2`
- `GET /api/v1/skills/{id}` 同樣回 4.5/2（一致性確認）

## 4. File Plan

### Backend (production)

| File | Action | Description |
|------|--------|-------------|
| `backend/.../skill/domain/Skill.java` | modify | (1) 加 15-arg `fromRow` overload 含 `averageRating, reviewCount`；(2) 既有 13-arg `fromRow` 改為 backward-compat delegate to 15-arg with `(0.0, 0L)` defaults |
| `backend/.../skill/query/SkillQueryService.java` | modify | (1) `search()` SQL SELECT 加 `average_rating, review_count`；(2) `mapSkillRow` 加 `rs.getDouble("average_rating") + rs.getLong("review_count")` 走 15-arg fromRow |

### Backend (tests)

| File | Action | Description |
|------|--------|-------------|
| `backend/src/test/.../skill/query/SkillSearchTest.java` | modify | (1) imports JdbcTemplate；(2) `@Autowired JdbcTemplate jdbcTemplate`；(3) 加 AC-S119-1（list endpoint 回 rating projection — 走 raw SQL UPDATE 模擬 projection 寫入路徑） |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/CHANGELOG.md` | append | v3.10.2 entry — S119 ship + verify metric |
| `docs/grimo/specs/spec-roadmap.md` | modify | M114 row → ✅ + version v3.10.2 + 一行 highlight |
| `docs/grimo/specs/archive/2026-05-04-S119-list-rating-projection.md` | new | 本 spec |

## 5. Test Plan

### 5.1 Targeted slice test

- `SkillSearchTest`（既有 13 + 新加 1 = 14 ACs）：
  - 13 既驗 ACs 走 13-arg backward-compat overload，行為不變
  - 新 AC-S119-1：raw SQL UPDATE 模擬 projection 寫入後 list endpoint 回真值

### 5.2 E2E manual smoke (dev backend)

對應 §3 AC-S119-3 — 走 dev backend OAuth=true mode：
- DB 直接 UPDATE 模擬 projection
- list endpoint 回 4.5/2 ✓
- single GET 一致 ✓

## 6. Verification

### 6.1 Targeted slice test

```
./gradlew test --tests "*SkillSearchTest" -x npmBuild
```

`SkillSearchTest`：**14/14 PASS @ 9.4s**（既有 13 + S119-1 新加；0 failures / 0 errors）— 13-arg backward-compat overload 不 regression；新 AC-S119-1 raw SQL UPDATE seed pattern 配 @ReadOnlyProperty 字段成功。

### 6.2 E2E manual smoke (PASS)

```bash
# DB seed
UPDATE skills SET average_rating=4.5, review_count=2 WHERE id='6906efd1-...';

# list endpoint (S119 fix verify)
GET /api/v1/skills?keyword=public
→ content[0].averageRating=4.5, reviewCount=2 ✓ (before fix=0/0)

# single GET 一致性
GET /api/v1/skills/{id}
→ averageRating=4.5, reviewCount=2 ✓
```

### 6.3 ModularityTests

未額外執行（本 spec 純 SQL SELECT clause + factory overload 改動；module boundary 不變）。

## 7. Result

### Shipped

- `Skill.java`：15-arg `fromRow` overload + 13-arg backward-compat delegate
- `SkillQueryService.java`：search() SQL SELECT 補 2 column；mapSkillRow 15-arg
- `SkillSearchTest`：14 ACs（既有 13 + S119-1 新加）

### Verify metric

- E2E manual smoke：list endpoint 從 0/0 → 4.5/2（projection 真值正確流通）；single GET 一致
- SkillSearchTest：（待跑完填入）

### Trim defer

- **無** — XS=2 範圍緊，single-tick 完整 ship

### LAB 封測 impact

- LAB 員工瀏覽 skills list 看到 SkillCard rating 星星顯示真實評分（before fix 永遠 0）
- 對齊 single GET 既驗行為，list / single 數據一致

### Lessons / Pattern reuse

- **第 12 次 single-tick XS/S spec ship**（per session lessons learned）
- **第 3 次 backward-compat overload pattern**（S116 ctor delegate / S119 fromRow overload）— 100x 成本節省 vs 全 callsite migration
- **@ReadOnlyProperty 字段 test seed**：必須走 raw SQL UPDATE 而非 aggregate save（per S077/AK regression 教訓 — aggregate save 會 skip @ReadOnlyProperty 字段）；對齊 SkillRatingService.refresh 既驗 path
- 修補 Round 36 backlog 第 3/3（S117 / S118 仍 backlog）— Round 36 finding chain 1/3 closed
