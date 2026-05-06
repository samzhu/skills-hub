# S096f3 — Collections Risk Filter Polish

**Status:** ✅ v4.12.0
**Size:** XS(3-4 pt)
**Depends on:** S096f2 ✅
**Target version:** v4.12.0

---

## §1 Goal

`CollectionsPage` 明確在 S096f2 spec 內 defer 了 risk filter（comment："Risk filter defer 至 S096f3 polish"）。本 spec 補上：讓使用者在 `/collections` 頁面按集合的最高風險等級篩選，對齊 PRD §P7 SBE Scenario 3「分類篩選」。

**場景：** 企業 security admin 只想看「不含 HIGH risk 技能」的集合 → 選 NONE/LOW/MEDIUM filter → 只顯示 maxRiskLevel 符合的集合。

**不是：** 後端分頁 filter（client-side 即可）；Collection detail 頁修改；Category filter 重做。

---

## §2 Approach

### 2.1 maxRiskLevel 計算策略

Collection 本身無 `risk_level` 欄位。maxRiskLevel = 集合內所有 skill 的最高風險等級（severity 排序：HIGH > MEDIUM > LOW > NONE > null）。

**Batch SQL**（在 `CollectionQueryController.list()` 內用 `NamedParameterJdbcTemplate` 批次查詢，避免 N+1）：

```sql
SELECT cs.collection_id,
       CASE MAX(CASE s.risk_level
               WHEN 'HIGH'   THEN 4
               WHEN 'MEDIUM' THEN 3
               WHEN 'LOW'    THEN 2
               WHEN 'NONE'   THEN 1
               ELSE 0 END)
            WHEN 4 THEN 'HIGH'
            WHEN 3 THEN 'MEDIUM'
            WHEN 2 THEN 'LOW'
            WHEN 1 THEN 'NONE'
            ELSE NULL END AS max_risk_level
FROM collection_skills cs
JOIN skills s ON s.id = cs.skill_id
WHERE cs.collection_id IN (:ids)
GROUP BY cs.collection_id
```

- 全部 skill 的 `risk_level` 皆 null（尚未掃描）→ max_risk_level = null（不篩掉）
- 空集合（collection_skills 無 row）→ 不出現在 result → map.getOrDefault = null

### 2.2 後端 DTO 異動

`CollectionQueryController.CollectionSummary` record 加 `String maxRiskLevel` field（nullable）。

`CollectionQueryController.list()` endpoint：
1. 呼叫 `service.list(category)` 拿 `List<Collection>`
2. 取所有 collectionId，一次 batch SQL 拿 `Map<String, String>`（collectionId → maxRiskLevel）
3. map 每個 Collection → `CollectionSummary.from(c, maxRiskLevels.get(c.getId()))`

`CollectionSummary.from()` 新增第二個參數 `String maxRiskLevel`。

### 2.3 前端元件泛化

`RiskFilterSidebar.skills: Skill[]` 改為 `items: Array<{ riskLevel: RiskLevel | null }>`：
- `Skill` 有 `riskLevel`，`SkillCollection`（加 `maxRiskLevel` 後 map 成 `{ riskLevel: maxRiskLevel }`）皆相容
- `HomePage.tsx` caller 更新：`skills={...}` → `items={skillsPage?.content ?? []}`（`Skill` struct 相容 `{ riskLevel }`）

### 2.4 CollectionsPage filter UI

`CollectionsPage` 加左側 `RiskFilterSidebar`（desktop 用 `aside`，對齊 `HomePage` 佈局）：
- state: `const [riskFilter, setRiskFilter] = useState<Set<RiskLevel>>(new Set())`
- filter logic: `riskFilter.size === 0 ? collections : collections.filter(c => riskFilter.has(c.maxRiskLevel as RiskLevel))`
- `maxRiskLevel` null 的集合：`riskFilter.size > 0` 時不顯示（尚未掃描 = 不確定 → 保守 filter out）

### 2.5 SkillCollection type 異動

```typescript
export interface SkillCollection {
  // ...existing fields...
  maxRiskLevel: RiskLevel | null   // 集合內最高 risk level；null = 尚未掃描
}
```

### 2.6 Trim / Defer

- **Defer to S096f3+：** server-side filter by maxRiskLevel（加 `?maxRiskLevel=HIGH` query param）— client-side count 小（MVP 集合數少），不值得後端 filter
- **Defer：** Collections 頁 category sidebar（PRD 有提但不在 risk filter scope）
- **Core（本 tick）：** 後端加 `maxRiskLevel` + 前端 `RiskFilterSidebar` 泛化 + `CollectionsPage` filter

---

## §3 Acceptance Criteria

**AC-1 — maxRiskLevel 出現在 API response**
```
Given: 集合 A 含 skill X (HIGH) + skill Y (LOW)
When:  GET /api/v1/collections
Then:  CollectionSummary.maxRiskLevel = "HIGH"
```

**AC-2 — 篩選後只顯示對應集合**
```
Given: 集合 A (maxRiskLevel=HIGH)、集合 B (maxRiskLevel=LOW)
When:  User 在 /collections 選「低風險」filter
Then:  只顯示集合 B；集合 A 不出現
       「全部」count = 2；「低風險」count = 1
```

**AC-3 — null maxRiskLevel 集合 filter 時不顯示**
```
Given: 集合 C 的所有 skill risk_level 皆 null（尚未掃描）
When:  User 選任一 risk level filter
Then:  集合 C 不出現（不確定 = 保守 exclude）
       選「全部」時集合 C 仍顯示
```

**AC-4 — RiskFilterSidebar 泛化後 HomePage 不 break**
```
Given: HomePage 的 RiskFilterSidebar 已改用 items prop
When:  瀏覽 / 頁
Then:  risk filter 功能與 S096f3 前完全一致；不 regression
```

---

## §4 File Plan

| File | Action |
|------|--------|
| `backend/.../community/CollectionQueryController.java` | `CollectionSummary` 加 `maxRiskLevel`；`list()` 注入 `NamedParameterJdbcTemplate`，batch SQL 計算 maxRiskLevel；`from()` 加第二參數 |
| `frontend/src/api/skills.ts` | `SkillCollection` 加 `maxRiskLevel: RiskLevel \| null` |
| `frontend/src/components/RiskFilterSidebar.tsx` | `skills: Skill[]` → `items: Array<{ riskLevel: RiskLevel \| null }>` |
| `frontend/src/components/RiskFilterSidebar.test.tsx` | 更新 prop 名稱 |
| `frontend/src/pages/HomePage.tsx` | `skills={...}` → `items={skillsPage?.content ?? []}` |
| `frontend/src/pages/CollectionsPage.tsx` | 加 aside + `RiskFilterSidebar` + filter state |
| `frontend/src/pages/CollectionsPage.test.tsx` | 驗 AC-2/AC-3 filter 行為 |

---

## §5 Test Plan

- **AC-1 integration（backend）**：`CollectionQueryControllerTest` — 建 skill + collection，呼叫 `GET /collections`，驗 `maxRiskLevel` 正確
- **AC-2/3 unit（frontend）**：`CollectionsPage.test.tsx` — mock collections with/without maxRiskLevel；filter state → render count
- **AC-4 regression**：`RiskFilterSidebar.test.tsx` — 改 `items` prop 後現有測試 pass；`HomePage.test.tsx` compile + pass
- **Regression**：`cd frontend && npm test -- --testPathPattern="Collections|RiskFilter|Home"`

---

## §6 Verification

- `./gradlew compileJava` → BUILD SUCCESSFUL
- `npm test` → 49 files, 236 tests, 0 failures
- AC-2/AC-3 frontend unit tests 新增並 pass（`CollectionsPage.test.tsx`）
- AC-4 regression: `RiskFilterSidebar.test.tsx` 5 tests pass；`HomePage.test.tsx` 5 tests pass

---

## §7 Result

**2026-05-07 v4.12.0 shipped.**

- Backend `CollectionSummary` 加 `maxRiskLevel: String | null`，`list()` 注入 `NamedParameterJdbcTemplate` 批次 CASE/MAX SQL 計算，無 N+1
- Frontend `SkillCollection` interface 加 `maxRiskLevel: RiskLevel | null`
- `RiskFilterSidebar` prop `skills: Skill[]` → `items: Array<{ riskLevel: RiskLevel | null }>`，向下相容 Skill struct
- `CollectionsPage` 加 aside + `RiskFilterSidebar` + `useState<Set<RiskLevel>>`；null maxRiskLevel filter active 時 exclude（保守策略）
- 全 frontend suite 236 tests green；backend 編譯通過
