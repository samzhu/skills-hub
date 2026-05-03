# S112: Flag Wiring Full-Stack

> Spec: S112 | Size: S(7) | Status: ⏳ Plan
> Date: 2026-05-03

---

## 1. Goal

把既有 `GET /api/v1/skills/{skillId}/flags` 後端 endpoint 串到 SkillDetail Flags tab UI；新增 `GET /api/v1/me/flags-summary` 端點供 MySkillsPage「待處理回報」MetricCard 消費；同時移除 MySkillsPage 寫死的「平均評分」MetricCard（等 S101a Quality Score 才有真實資料）。

**起源**：2026-05-03 page audit 發現後端 `FlagController` 已完整實作（S058 / S072 / S075 等多輪 ship），但前端兩個 surface 該用未用：

1. `SkillDetailPage.tsx:222-227` Flags tab 永遠 render `<EmptyState>`，沒呼叫 endpoint
2. `MySkillsPage.tsx:97-98` 兩張 MetricCard 寫死值（`value="—"` / `value={0}`），看似真資料但不是

**Roadmap 對位**：
- 與 📋 S098e2 Reviews（新建 Review aggregate）**不衝突** — 本 spec 不碰 Reviews tab
- 與 📋 S098e3 Flag 回報流程（reviewer queue + POST 表單）**不重複** — 本 spec 只接 GET 側

## 2. Approach

**Backend：擴 `MeController` 加 `/flags-summary` 子 endpoint，回 `{openCount: number}`**

| Approach | Chosen | Rationale |
|----------|--------|-----------|
| A: 純前端 N+1 fetch（`useQueries`） | no | MVP 簡單但 author 有 200 個 skill 時會 200 並發；後續若加 byType breakdown 還是要 backend |
| B: 新 `GET /api/v1/me/flags-summary` aggregate query | **yes** | 1 個 SQL COUNT 取代 N 次 fetch；endpoint shape 留有 evolution room（未來加 byType） |

**Endpoint placement**：擴既有 `MeController`（`io.github.samzhu.skillshub.shared.security.MeController`）— path `/api/v1/me/flags-summary` 自然延伸；`CurrentUserProvider` 抽 user 模式現成（line 53-67）；不另開 controller 避免 path 尷尬。

**Response shape**：`{openCount: number}` 最簡。byType breakdown 留給 S098e3 真要用時再 evolve（YAGNI）。

**Frontend：兩個新 hook，分別接兩個 endpoint**

| Hook | Endpoint | Used by |
|------|----------|---------|
| `useFlags(skillId)` | `GET /skills/{id}/flags` | SkillDetailPage Flags tab |
| `useFlagsSummary()` | `GET /me/flags-summary` | MySkillsPage 待處理回報 MetricCard |

**Flags tab 列表 UI**：顯示全部 + status pill；目前 `FlagService` 從未 update status（`createFlag` 寫死 `"OPEN"`），所以實際只會看到 OPEN — 但 UI 結構預留 status pill，未來 S098e3 加 RESOLVED 狀態時自然繼承不必改 UI。

**MySkillsPage「平均評分」MetricCard**：直接移除整張卡片，4-card grid 變 3-card（下載總數 / 技能總數 / 待處理回報）。Layout `lg:grid-cols-4` → `lg:grid-cols-3`。等 S101a Quality Score ship 才補回。

### 2.1 Behavior validation

| 決策 | Confidence | 證據 |
|------|------------|------|
| `FlagController` GET endpoint shape 已知 | Validated | 讀 `FlagController.java:51-54` + `FlagReadModel.java:30-38` |
| `FlagReadModel.status` 為 String，初始 "OPEN"，目前無 update path | Validated | grep `status =` in security/ → 只 `FlagService.java:123` 寫入 OPEN |
| `MeController` 用 `CurrentUserProvider` 取 userId | Validated | 讀 `MeController.java:67` `users.current().userId()` |
| TanStack Query `useQuery` pattern | Validated | 既有 `useSkillStats.ts` 範本（hooks/useSkillStats.ts:8-16） |
| Spring Data JDBC COUNT query 走 `NamedParameterJdbcTemplate` | Validated | 既有 `FlagService.java:96-98` 用同 template |

零 Hypothesis / Unknown — 純既有 pattern reuse。

### 2.2 Trim list

無需 trim（S 估點，1 tick 內可完成）。若 wall hit 可 defer：
- Flag type / status 中譯 i18n 表（暫用 raw type code 顯示，後續 polish）

### 2.3 Research Citations

無外部框架研究 — 全部使用既有專案內 pattern。Internal references：
- `FlagController.java` / `FlagReadModel.java` / `FlagService.java`（後端 endpoint shape）
- `MeController.java` / `CurrentUserProvider.java`（user 抽取 pattern）
- `SkillDetailPage.tsx:213-227`（Flags tab 現況）
- `MySkillsPage.tsx:86-99`（4 MetricCards 現況）
- `useSkillStats.ts`（hook pattern 範本）
- `api/skills.ts:158-160`（TanStack-friendly fetch function pattern）

## 3. SBE Acceptance Criteria

驗證指令（per qa-strategy.md）：
- Backend：`./gradlew test`
- Frontend：`cd frontend && npm test`
- Pass：所有 `@DisplayName("AC-N: ...")` / `@Tag("AC-N")` 測試綠

---

**AC-1：SkillDetail Flags tab — 0 flags 顯示 EmptyState**
- Given：某 skill 無任何 flag
- When：user 開啟 `/skills/:id` 並切到「回報」tab
- Then：tab 內顯示既有 `EmptyState`（tone="clear"，文案「目前沒有任何回報」）

**AC-2：SkillDetail Flags tab — >0 flags 顯示 list**
- Given：某 skill 有 2 筆 flag（type="malicious" 與 type="spam"）
- When：user 開啟該 skill detail 並切到 Flags tab
- Then：顯示 2 row，每 row 含 `type` pill + `description` + `createdAt`（zh-TW 格式）+ `status` pill；最新 createdAt 排最上（後端已 ORDER BY desc）

**AC-3：MySkillsPage 待處理回報 MetricCard 顯示後端 openCount**
- Given：user A 有 3 個 PUBLISHED skill；其中 2 個 skill 各被 flag 1 次（共 2 個 OPEN flag）
- When：user A 開啟 `/my-skills`
- Then：「待處理回報」MetricCard `value` 顯示 `2`（`subtitle` 改為「未處理 OPEN 狀態」）

**AC-4：MySkillsPage 移除「平均評分」MetricCard**
- Given：MySkillsPage 渲染後
- When：query DOM 找 `label="平均評分"` 的 MetricCard
- Then：找不到該卡；4-card grid 變 3-card（技能總數 / 下載總數 / 待處理回報）

**AC-5：後端 `GET /api/v1/me/flags-summary` 回正確 shape**
- Given：current user `sub="alice"`，alice 有 1 個 PUBLISHED skill 且該 skill 有 1 個 OPEN flag
- When：發 `GET /api/v1/me/flags-summary`
- Then：回 200 + body `{"openCount": 1}`；Content-Type `application/json`

**AC-6：後端 endpoint 只統計 current user 的 PUBLISHED skills 的 OPEN flags**
- Given：alice 有 1 個 PUBLISHED skill 含 1 個 OPEN flag；bob 有 1 個 PUBLISHED skill 含 5 個 OPEN flags；alice 也有 1 個 DRAFT skill 含 3 個 OPEN flags
- When：以 alice 身份發 `GET /api/v1/me/flags-summary`
- Then：回 `{"openCount": 1}` — 不含 bob 的 flags（user 隔離），不含 alice DRAFT skill 的 flags（status filter）

**AC-7：無 PUBLISHED skill 時回 0**
- Given：alice 無任何 PUBLISHED skill（只有 DRAFT 或 0 skill）
- When：alice 發 `GET /api/v1/me/flags-summary`
- Then：回 `{"openCount": 0}`（不丟 error）

## 4. Interface / API Design

### 4.1 Backend — 擴 `MeController`

```java
// MeController.java — 新增方法
@GetMapping("/flags-summary")
Map<String, Object> flagsSummary() {
    var userId = users.current().userId();
    long openCount = flagService.countOpenFlagsForAuthor(userId);
    return Map.of("openCount", openCount);
}
```

`MeController` constructor 注入 `FlagService`（既有 bean）：

```java
private final CurrentUserProvider users;
private final FlagService flagService;  // NEW
```

### 4.2 Backend — 擴 `FlagService` 加 query method

```java
// FlagService.java — 新增方法
/**
 * 統計指定 author 名下所有 PUBLISHED skill 的 OPEN flag 總數。
 * 用於 MySkillsPage 「待處理回報」MetricCard。
 */
public long countOpenFlagsForAuthor(String author) {
    var sql = """
        SELECT COUNT(*) FROM flags f
        WHERE f.status = 'OPEN'
          AND f.skill_id IN (
              SELECT id FROM skills
              WHERE author = :author AND status = 'PUBLISHED'
          )
        """;
    var params = Map.of("author", author);
    Long count = jdbc.queryForObject(sql, params, Long.class);
    return count == null ? 0L : count;
}
```

走 `NamedParameterJdbcTemplate`（既有 dependency）— 不引新 Repository method 是因為 query 跨 `flags` + `skills` 兩表，derived query 不適用。

### 4.3 Frontend — 新檔 `frontend/src/api/flags.ts`

```typescript
import { apiFetch } from './client'

/**
 * 後端 FlagReadModel 對應前端 type。
 * Flag type 白名單見 FlagService.ALLOWED_TYPES（S072）。
 */
export interface Flag {
  id: string
  skillId: string
  type: 'malicious' | 'spam' | 'inappropriate' | 'copyright' | 'security' | 'other'
  description: string | null
  reportedBy: string
  createdAt: string
  status: 'OPEN' | 'RESOLVED'  // RESOLVED 預留，目前後端只寫 OPEN
}

export function fetchFlags(skillId: string): Promise<Flag[]> {
  return apiFetch<Flag[]>(`/skills/${skillId}/flags`)
}

export interface FlagsSummary {
  openCount: number
}

export function fetchFlagsSummary(): Promise<FlagsSummary> {
  return apiFetch<FlagsSummary>('/me/flags-summary')
}
```

### 4.4 Frontend — 新檔 `frontend/src/hooks/useFlags.ts` / `useFlagsSummary.ts`

```typescript
// useFlags.ts
import { useQuery } from '@tanstack/react-query'
import { fetchFlags, type Flag } from '../api/flags'

export function useFlags(skillId: string | undefined) {
  return useQuery<Flag[]>({
    queryKey: ['skill-flags', skillId],
    queryFn: () => fetchFlags(skillId!),
    enabled: !!skillId,
    staleTime: 60 * 1000,
    refetchOnWindowFocus: false,
  })
}

// useFlagsSummary.ts
export function useFlagsSummary(enabled: boolean = true) {
  return useQuery<FlagsSummary>({
    queryKey: ['me-flags-summary'],
    queryFn: fetchFlagsSummary,
    enabled,
    staleTime: 60 * 1000,
  })
}
```

### 4.5 Frontend — 中譯表（共用 const，放 `frontend/src/lib/flag-labels.ts`）

```typescript
export const FLAG_TYPE_LABEL: Record<Flag['type'], string> = {
  malicious: '惡意指令',
  spam: '垃圾內容',
  inappropriate: '不當內容',
  copyright: '版權問題',
  security: '資安疑慮',
  other: '其他',
}

export const FLAG_STATUS_LABEL: Record<Flag['status'], string> = {
  OPEN: '待處理',
  RESOLVED: '已處理',
}

// 對齊 STATUS_PILL_STYLE pattern (SkillDetailPage.tsx:33-37)
export const FLAG_STATUS_STYLE: Record<Flag['status'], { backgroundColor: string; color: string }> = {
  OPEN:     { backgroundColor: 'rgba(239,159,39,0.14)', color: '#FAC775' },  // warning-soft
  RESOLVED: { backgroundColor: 'rgba(29,158,117,0.14)', color: '#6FD8B0' },  // success-soft
}
```

### 4.6 Frontend — `SkillDetailPage` Flags tab 改寫

```tsx
// 替換 line 220-227
<TabsContent value="flags" className="mt-4">
  <FlagsList skillId={id ?? ''} />
</TabsContent>

// 同檔內新增 component
function FlagsList({ skillId }: { skillId: string }) {
  const { data: flags, isLoading } = useFlags(skillId)
  if (isLoading) return <div className="py-8 text-sm text-muted-foreground">載入中...</div>
  if (!flags || flags.length === 0) {
    return (
      <EmptyState
        tone="clear"
        headline="目前沒有任何回報"
        sub="若你發現此技能含惡意指令、誤導 description 或其他問題，回報功能即將推出，可送至審核佇列由 reviewer 處理。"
      />
    )
  }
  return (
    <div className="space-y-2">
      {flags.map((f) => <FlagRow key={f.id} flag={f} />)}
    </div>
  )
}
```

### 4.7 Frontend — `MySkillsPage` MetricCards 改寫

```tsx
// 替換 line 86-99
const { data: flagsSummary } = useFlagsSummary(total > 0)  // 0 skill 不必查

<div className="mb-6 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
  {/* 移除「平均評分」MetricCard — 等 S101a Quality Score */}
  <MetricCard
    label="技能總數"
    value={total}
    subtitle={`已發布 ${published} · 草稿 ${drafts} · 已停用 ${suspended}`}
  />
  <MetricCard
    label="下載總數"
    value={totalDownloads.toLocaleString()}
    subtitle="累積下載"
  />
  <MetricCard
    label="待處理回報"
    value={flagsSummary?.openCount ?? 0}
    subtitle="未處理 OPEN 狀態"
  />
</div>
```

## 5. File Plan

| File | Action | Description |
|------|--------|-------------|
| `backend/.../shared/security/MeController.java` | modify | 加 `flagsSummary()` method + constructor 注入 `FlagService` |
| `backend/.../security/FlagService.java` | modify | 加 `countOpenFlagsForAuthor(String)` method |
| `backend/.../shared/security/MeControllerTest.java` | modify | 加 AC-5 / AC-6 / AC-7 測試（若無此檔則 new） |
| `backend/.../security/FlagServiceTest.java` | modify | 加 `countOpenFlagsForAuthor` 行為測試（含跨 author / 跨 status filter） |
| `frontend/src/api/flags.ts` | new | `Flag` type + `fetchFlags` + `FlagsSummary` + `fetchFlagsSummary` |
| `frontend/src/hooks/useFlags.ts` | new | TanStack Query hook for skill flags |
| `frontend/src/hooks/useFlagsSummary.ts` | new | TanStack Query hook for me flags summary |
| `frontend/src/lib/flag-labels.ts` | new | type / status 中譯表 + status pill 樣式 |
| `frontend/src/pages/SkillDetailPage.tsx` | modify | Flags tab `EmptyState` → `FlagsList`；同檔加 `FlagsList` + `FlagRow` 內部 component |
| `frontend/src/pages/MySkillsPage.tsx` | modify | 移除「平均評分」MetricCard；接 `useFlagsSummary` 寫真實 openCount；4-col grid → 3-col |
| `frontend/src/pages/SkillDetailPage.test.tsx` | modify | 加 AC-1 / AC-2 測試 |
| `frontend/src/pages/MySkillsPage.test.tsx` | modify | 加 AC-3 / AC-4 測試 |
| `docs/grimo/specs/spec-roadmap.md` | modify | 新增 S112 row（Phase 5 區塊） |
| `docs/grimo/glossary.md` | modify | 加 Flag 相關 type / status 中英對照（若漏） |

---

## 6. Task Plan

POC: not required — 全部使用既有專案內 pattern（Spring Data JDBC + NamedParameterJdbcTemplate / TanStack Query / EmptyState / MetricCard 都是 ship 過的 component），零新框架。

| # | Task | AC | Status | Depends |
|---|------|-----|--------|---------|
| T01 | Backend `/me/flags-summary` endpoint + service + tests | AC-5 / AC-6 / AC-7 | pending | none |
| T02 | Frontend infra — `api/flags.ts` + `lib/flag-labels.ts` | infra | pending | none |
| T03 | SkillDetail Flags tab — `useFlags` hook + page wiring + tests | AC-1 / AC-2 | pending | T02 |
| T04 | MySkillsPage MetricCards rework — `useFlagsSummary` hook + page edit + tests | AC-3 / AC-4 | pending | T01 + T02 |

**Execution order**：T01 → T02 → T03 → T04
（T01 與 T02 互不依賴，可並行；保險走順序）

**Task file 位置**：`docs/grimo/tasks/2026-05-03-S112-T0[1-4]-*.md`（各 task 含 BDD + implementation outline + target files + verify cmd）

**E2E gate**：本 spec 純 wire 既有 endpoint 到既有 UI，無新 infra / credential / subprocess。Backend 走 Testcontainers（真 PostgreSQL），frontend 走標準 vitest mock — Mode B Chrome MCP smoke 由獨立 cron tick 補。**不額外加 dedicated E2E task**。

---

<!-- Section 7 added by /planning-tasks after all tasks PASS -->
