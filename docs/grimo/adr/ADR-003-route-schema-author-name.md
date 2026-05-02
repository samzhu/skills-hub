# ADR-003: Route Schema Migration — `/skills/:id` UUID → `/skills/:author/:name` Canonical

> Status: **Accepted** (2026-05-02)
> Supersedes: PRD §P1-P6 implicit `/skills/:id` URL pattern referenced in既有 spec docs S001-S088
> Triggered by: Engineering Handoff (S096 META input) §9 Navigation Map specifies `/skills/:author/:name` as canonical
> Implementation: S096c (Routing schema + Risk tier 4-level)

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

採用 **dual-route 並行 schema**：

| Route | Status | Resolution |
|-------|--------|-----------|
| `/skills/:author/:name` | **Canonical** | 主要 frontend route；新分享連結都用此 |
| `/skills/:id` | **Permanent alias** | 不 redirect、不 deprecate；continue serving 給既有 caller |

兩個 route resolve 到同一個 `Skill` aggregate；backend 提供：
- 既有 `GET /api/v1/skills/{id}` 持續存活
- 新增 `GET /api/v1/skills/{author}/{name}` 等價語意

Frontend 兩個 React Route 都註冊到 `SkillDetailPage` component；component 內透過任一 identifier resolve 出 skill。

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
- **DB schema 不需動**: skill `name` 欄位本來就 UNIQUE per-org（per S041），加 `(author, name)` UNIQUE 即可（暫時不限 — 既有 author 對應 name 已唯一）

### Negative

- **dual-route 維護**: 兩個 backend endpoint 對同一 resource，需 keep behavior 一致；任何 skill resolve 邏輯改動需同步兩處
- **frontend 路由判斷**: SkillDetailPage 需 detect 路由 param 是 UUID 還是 author/name，做不同 fetch — 增 1 個分支
- **search result 連結 schema 統一**: 既有 search 結果 `<Link to={`/skills/${id}`}>` 改 `<Link to={`/skills/${author}/${name}`}>` — touchpoints widespread

### Neutral

- 既有 archived spec doc 內 `/skills/{id}` 引用**不更新**（archive 是歷史記錄；新 spec 用新 schema）
- 新建 spec doc 一律用 `/skills/:author/:name` canonical

## 4. Implementation Plan (S096c)

### 4.1 Backend

```java
// New endpoint
@GetMapping("/skills/{author}/{name}")
Skill getByAuthorAndName(@PathVariable String author, @PathVariable String name) {
  return queryService.findByAuthorAndName(author, name);
}

// Existing endpoint unchanged
@GetMapping("/skills/{id}")
Skill getById(@PathVariable String id) { ... }
```

新增 `SkillRepository.findByAuthorAndName(String author, String name)` — `WHERE LOWER(author) = LOWER(:author) AND LOWER(name) = LOWER(:name) LIMIT 1`.

### 4.2 Frontend

```tsx
<Routes>
  <Route path="/skills/:id" element={<SkillDetailPage />} />              // legacy alias
  <Route path="/skills/:author/:name" element={<SkillDetailPage />} />    // canonical
</Routes>
```

`SkillDetailPage` 透過 `useParams` 讀 `{id}` 或 `{author, name}`，分別 hit 對應 endpoint。

### 4.3 Data invariant

`(author, name)` 在 v1 設計上就 UNIQUE per-org（同一作者不發兩個同名 skill；name 有 `^[a-z0-9-]{1,64}$` regex）。新加 SQL `UNIQUE (LOWER(author), LOWER(name))` constraint 是強化（S096c implementation 確認）。

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

`/skills/:id` legacy alias 不需支援 sub-routes — 既有 caller 都是 detail page 入口；sub-routes 為新功能，全用 canonical schema.

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
