# ADR-003: Route Schema Migration — `/skills/:id` UUID + `/skills/:author/:name`

> Status: **Amended by S176** (2026-05-15)
> Supersedes: PRD §P1-P6 implicit `/skills/:id` URL pattern referenced in既有 spec docs S001-S088
> Triggered by: Engineering Handoff (S096 META input) §9 Navigation Map specifies `/skills/:author/:name` as canonical
> Implementation: S096c (Routing schema + Risk tier 4-level)
> Amendment: S176 allows duplicate platform display names, so `/skills/:id` is the canonical identity and `/skills/:author/:name` is a deterministic legacy alias.

---

## 1. Context

當前 Skills Hub frontend 使用 `/skills/:id`（UUID）作為 skill 詳情頁路由，例如 `/skills/d258465b-b7b1-40d5-b7c0-969a7af8dcc6`。

問題：

1. **不可讀** — UUID 在書籤、Slack 分享、文件引用都對人類無意義
2. **不可記憶** — user 找特定 skill 需透過搜尋介面才能 navigate
3. **與 agentskills.io 慣例不對齊** — npm (`@scope/package`)、GitHub (`org/repo`)、Docker Hub (`org/image`) 全用 `:owner/:name` schema
4. **Engineering Handoff §9** 明確要求 `/skills/:author/:name` 為 canonical route

但 UUID-based 既有 frontend / 內部 API caller / 既有 bookmark 已部署。

## 2. Decision

採用 **dual-route 並行 schema**。S096c 原始決策把 author/name 視為 canonical；S176 ship 後因平台 `skills.name` 改成人類顯示名稱且允許同作者重名，canonical identity 改回 UUID id：

| Route | Status | Resolution |
|-------|--------|-----------|
| `/skills/:id` | **Canonical identity** | 唯一定位 skill；新功能與可審計引用優先使用此 route |
| `/skills/:author/:name` | **Legacy deterministic alias** | 不 redirect、不 deprecate；同作者同名時回 `created_at DESC, id DESC` 最新 row |

兩個 route resolve 到同一個 `Skill` aggregate；backend 提供：
- 既有 `GET /api/v1/skills/{id}` 持續存活
- `GET /api/v1/skills/{author}/{name}` 保留為 deterministic alias，不再保證唯一語意

Frontend 兩個 React Route 都註冊到 `SkillDetailPage` component；component 內透過任一 identifier resolve 出 skill。新入口若需要不可歧義引用，應用 `/skills/:id`。

### 2.1 為什麼不 hard migrate（302/301 redirect）

**捨棄 hard migrate**（Q2 grill option b）的理由：
- 既有書籤觸發 redirect 雖然 UX 可接受，但每個 redirect 多一個 round-trip
- API caller 對 redirect 處理可能 break（curl `-L` 才跟、cli tool 可能不跟）
- Skills Hub 是企業內部 tool，rebuild bookmark cost 不為 0

並行 dual-route 維護成本低（只是多一個 controller method + 一個 React Route），長期保留更安全。

### 2.2 為什麼不 drop UUID（Q2 option c）

完全移除 `:id` 路由是 BC break，影響：
- 既有 spec doc S001-S088 內所有 `/skills/{id}` 引用
- 既有 internal tool / CLI / Slack bot 使用 UUID lookup
- ID 在 logs / domain_events / 監控系統都是 UUID — UI 點擊無法直跳會增 ops 摩擦

## 3. Consequences

### Positive

- **Human-readable URL**: `/skills/platform-team/k8s-deployment` 比 `/skills/abc-123-...` 易於分享
- **對齊 industry convention**: GitHub/npm/Docker Hub 同 pattern，user 直覺
- **Backwards compatible**: 既有 caller / bookmark 不破
- **Backwards compatible alias**: S176 移除 `skills.name` unique 後，author/name route 仍可服務舊連結，但只能承諾 deterministic latest-row 行為。

### Negative

- **dual-route 維護**: 兩個 backend endpoint 對同一 resource，需 keep behavior 一致；任何 skill resolve 邏輯改動需同步兩處
- **frontend 路由判斷**: SkillDetailPage 需 detect 路由 param 是 UUID 還是 author/name，做不同 fetch — 增 1 個分支
- **author/name 不再唯一**: 同作者同名時 URL 無法指向舊 row；需要唯一定位時必須用 `/skills/:id`

### Neutral

- 既有 archived spec doc 內 `/skills/{id}` 引用**不更新**（archive 是歷史記錄；新 spec 用新 schema）
- 新建 spec doc 一律用 `/skills/:id` 表達 canonical identity；只有在測 legacy alias 行為時才用 `/skills/:author/:name`

## 4. Implementation Plan (S096c)

### 4.1 Backend

```java
// Legacy alias endpoint
@GetMapping("/skills/{author}/{name}")
Skill getByAuthorAndName(@PathVariable String author, @PathVariable String name) {
  return queryService.findByAuthorAndName(author, name);
}

// Existing endpoint unchanged
@GetMapping("/skills/{id}")
Skill getById(@PathVariable String id) { ... }
```

新增 `SkillRepository.findByAuthorAndName(String author, String name)` — S176 後使用 `WHERE ... ORDER BY created_at DESC, id DESC LIMIT 1`，讓 legacy alias deterministic。

### 4.2 Frontend

```tsx
<Routes>
  <Route path="/skills/:id" element={<SkillDetailPage />} />              // canonical identity
  <Route path="/skills/:author/:name" element={<SkillDetailPage />} />    // legacy deterministic alias
</Routes>
```

`SkillDetailPage` 透過 `useParams` 讀 `{id}` 或 `{author, name}`，分別 hit 對應 endpoint。

### 4.3 Data invariant

S176 後 `(author, name)` 不再唯一：`skills.name` 是平台顯示名稱，允許空白、大小寫、中文與重名；`SKILL.md` frontmatter `name` 的 agentskills.io regex 只套用在 package metadata。

## 5. Sub-routes

`/skills/:author/:name` 後續 sub-routes（per Engineering Handoff §9）：

```
/skills/:author/:name?tab=overview   (default)
/skills/:author/:name?tab=risk
/skills/:author/:name?tab=versions
/skills/:author/:name?tab=reviews
/skills/:author/:name?tab=flags
/skills/:author/:name/diff?from=v2.0.1&to=v2.1.0
```

S176 後 sub-route 若需要唯一定位，應優先設計在 `/skills/:id` 上；author/name sub-route 只能繼承 legacy alias 的 deterministic latest-row 語意。

## 6. Glossary impact

新增 glossary terms（per S096a）：
- Author / Publisher（既有；釐清在 URL schema 角色）
- Skill name uniqueness（per-org `(author, name)` 組合 UNIQUE）

## 7. Verification

S096c §3 AC 將實際驗證：
- AC: `GET /api/v1/skills/{id}` 既有 caller 持續 200
- AC: `GET /api/v1/skills/{author}/{name}` 新 caller 200，回相同 aggregate
- AC: 不存在 `(author, name)` 組合 → 404 NOT_FOUND
- AC: frontend 兩個 route 都 render SkillDetailPage 正常

## 8. Open Questions (deferred to S096c)

1. **既有 search results / SkillCard `<Link>`** 是否一併改 canonical schema？或保持 UUID 不變直到 S096d 整體 page refresh 時統一處理？
   → 推薦 S096c 只動 routing + endpoint；UUID-based `<Link>` 由 S096d existing pages refresh 統一更新（避免 S096c scope creep）
2. **(author, name) UNIQUE constraint** 是否需 Flyway migration 加上（DB 強化）？
   → S096c 評估；若既有資料無衝突直接加 constraint，否則先檢查 cleanup
