# S098e3: Flag Write Flow + Reviewer Queue (POST form + status mutations)

> Spec: S098e3 | Size: S(8) re-est from M(7) | Status: 🚧 in-progress (4 tasks queued — cron tick handoff)
> Date: 2026-05-03

> **Tasks**: T01 backend write flow (FlagStatus enum + status mutations + cross-skill list + reporter param) → T02 frontend infra (api/flags.ts mutations + useFlagsQueue + DISMISSED label) → T03 SkillDetail FlagsList CTA + FlagSubmitModal → T04 FlagsQueuePage + AppShell nav + /flags route。Execution order T01→T02→T03→T04。

---

## 1. Goal

讓使用者從 SkillDetail Flags tab 提交 flag report（modal form）；新增 `/flags` reviewer queue 頁列出所有 OPEN flags 並可 resolve / dismiss；後端補 status mutation endpoint + cross-skill list endpoint + submitter identity 從寫死 `"anonymous"` 改為 `useMe().sub`。完整補完 ✅ S112（read 端）對應的 write + reviewer 端，把 Flag domain 從「只能看不能寫」升級為「能報能處理」。

**起源**：✅ S112 ship 後 SkillDetail Flags tab 已能顯示 flags 但**無提交按鈕**；後端 `POST /api/v1/skills/{skillId}/flags` ✅ 既有但**無前端 trigger**；`FlagReadModel.status` 設計含 OPEN 但**無轉 RESOLVED 的 path** — admin 只能手動 SQL UPDATE 才能消化 OPEN flag，違反 Feature First 完整性。

**Visual flow — 提交 flag**：

```
User 在 SkillDetail Flags tab → 點「回報問題」按鈕（hero CTA）
   ↓ 開 modal
[type radio: 惡意指令 / 垃圾內容 / 不當內容 / 版權問題 / 資安疑慮 / 其他]
[description textarea (≤500 字)]
   ↓ Submit
Frontend POST /api/v1/skills/{skillId}/flags
        body { type, description }
        Authorization: Bearer <token>（LAB mode 自動帶）
   ↓
FlagController.createFlag → FlagService.createFlag(skillId, type, description, reporter)
   ↓
flags 表 INSERT（status="OPEN", reportedBy=useMe.sub）
domain_events 寫 SkillFlagged event
ApplicationEventPublisher publish SkillFlaggedEvent
   ↓
Frontend invalidate ['skill-flags', skillId] + ['me-flags-summary']
   → tab list 即時更新 + MySkillsPage 待處理回報 +1 (若該 skill 是 me 的)
   → toast「已收到回報，待 reviewer 處理」
```

**Visual flow — reviewer queue 處理**：

```
User 點 AppShell「待審回報」nav → /flags
   ↓
Frontend GET /api/v1/flags?status=OPEN
   ↓
FlagAdminQueryController.listAcrossSkills → query flags JOIN skills
   ↓
回 flag 列表：每筆 row 含 type / description / reportedBy / createdAt + 對應 skill name + 「Resolve」/「Dismiss」按鈕
   ↓ user 點 Resolve（已處理）或 Dismiss（假警報）
Frontend PATCH /api/v1/skills/{skillId}/flags/{flagId} body { status: "RESOLVED" | "DISMISSED" }
   ↓
FlagService.updateStatus(flagId, newStatus, actor)
   → flags UPDATE SET status=? WHERE id=?
   → registerEvent(FlagResolvedEvent) （給未來 audit 用，本 spec 無 listener）
   ↓
Frontend invalidate ['flags'] → row 從 OPEN 列表消失（filter 過濾）
   → toast「已處理 1 筆回報」
```

## 2. Approach

走「**最小擴充既有 Flag domain**」路線：FlagController 加 PATCH endpoint + 新增 cross-skill query controller + FlagService 加 updateStatus method + createFlag 加 reporter 參數。Frontend 加 modal form + 新 `/flags` 頁。**零 schema migration**（status 已是 String 欄位，加 RESOLVED/DISMISSED 純應用層 enum 擴充）。

### 2.1 7 個產品/UX 決策

| # | 決策 | 採用 | 理由 |
|---|---|---|---|
| 1 | Admin gate | **任何登入用戶可看 + resolve**，無 admin role 強制 | Feature First per CLAUDE.md；admin gate 等 PRD B6 admin scope 進來再補 |
| 2 | Status transitions | **OPEN → RESOLVED / DISMISSED 兩種 terminal**；無 IN_REVIEW 中間態 | RESOLVED = 真有問題已處理；DISMISSED = 假警報 / 無理由；中間態 over-engineered |
| 3 | Cross-skill list endpoint | **(a) 新加 GET /api/v1/flags?status=** + 既有 per-skill endpoint 加 ?status= filter；不破舊 callsite | S112 已 wire 既有 per-skill endpoint，破壞性改動會 regress |
| 4 | Reviewer queue 頁面 | **`/flags`**（中性 path）+ AppShell nav entry「待審回報」 | Feature First；admin gate 加上去後 nav 隱藏即可；URL 結構不變 |
| 5 | Submission UI | **Modal from SkillDetail Flags tab**（hero「回報問題」CTA） | 對齊 future S098e2 Reviews modal pattern；不污染 list view |
| 6 | Submitter identity | **改 reportedBy = useMe().sub**（FlagService.createFlag 加 reporter 參數）；anonymous 留 LAB mode 無 sub fallback | per S112 reviewer = sub pattern；admin queue 才看得出誰報的；不破壞既有 anonymous fallback |
| 7 | Resolution comment | **MVP 純 status flip**；resolver 寫 comment defer | 需求未證實；reviewer queue MVP 看 type+description 已夠判斷 |

### 2.2 Approach 比較 — Status mutation endpoint shape

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| **A. PATCH `/api/v1/skills/{skillId}/flags/{flagId}` body `{status}`** | 路徑對齊既有 per-skill list；status 是 generic field 適合 PATCH | PATCH 語意要求 partial update — 但本 endpoint 只更新 status 一欄，純粹了 | ⭐ |
| B. POST `/api/v1/skills/{skillId}/flags/{flagId}/resolve` + POST .../dismiss 兩個 action endpoint | 動詞清楚 | 兩個 endpoint 反映同一個 state transition；後續加 status 又要再加 endpoint | |
| C. PUT `/api/v1/skills/{skillId}/flags/{flagId}` 整 record replacement | RESTful 完整 | 需要 client 帶完整 flag body；過度 | |

走 **A** — PATCH semantic 適合 partial state update；frontend 帶 `{status: "RESOLVED"}` 或 `{status: "DISMISSED"}`；後續加新 status 不必加 endpoint。

### 2.3 Behavior validation

| 決策 | Confidence | 證據 |
|------|------------|------|
| `flags.status` 是 String 無 CHECK constraint | Validated | V1__initial_schema.sql 確認；可加 RESOLVED/DISMISSED 不需 migration |
| `FlagService.createFlag` 改 signature 加 reporter | Validated | line 68 既有 signature `(skillId, type, description)`；單一 caller (`FlagController.createFlag` line 41) 同改即可 |
| `useMe().sub` 取 reporter | Validated | S112 同 pattern；`MeController` 已暴露 sub |
| PATCH endpoint 經 `@PatchMapping` + `@RequestBody Map<String,String>` | Validated | Spring MVC 標準 |
| Cross-skill list `GET /flags?status=` 走新 controller (例 `FlagAdminQueryController`) | Validated | 既有 ACL controller / FlagController 同 module 範本；implementer 視 module 結構決定 |
| Frontend Modal pattern reuse | Validated | 既有 SkillDetailPage `AddVersionForm` 已是 mutation form 範本 |

零 Hypothesis；零 Unknown。**不需 POC**。

### 2.4 Trim list

S(8) 一個 cron tick 應可完成；wall hit 時 defer 順序：

- Reviewer queue 的 type/skill filter sidebar（MVP 列全部 OPEN 即可，加 filter 為 UX polish）
- AppShell「待審回報」nav badge（顯 OPEN flag 總數）— 需要新加 `/flags/count` endpoint 或前端 derive；defer
- Resolution timeline 顯示在 reviewed flag 上「resolved at X by Y」— 需新 columns / event listener；defer

### 2.5 Research Citations

無外部框架研究。Internal references：

- `backend/.../security/FlagController.java`（既有 GET/POST endpoint 範本）
- `backend/.../security/FlagService.java`（line 68 signature 改動點 + line 123 reportedBy 改動點）
- `backend/.../security/FlagReadModel.java`（status 欄位定義）
- `backend/.../security/MeFlagsController.java` (S112-T01)（cross-skill query 範本）
- `backend/src/main/resources/db/migration/V1__initial_schema.sql`（flags table schema 確認 no CHECK constraint）
- `frontend/src/pages/SkillDetailPage.tsx:294-356` `AddVersionForm`（既有 mutation form 範本）
- `frontend/src/components/FlagsList.tsx` (S112-T03)（list view 範本，本 spec 加 hero CTA）
- `frontend/src/api/flags.ts` (S112-T02)（補 createFlag + listAllFlags + updateFlagStatus）
- `frontend/src/lib/flag-labels.ts` (S112-T02)（既有 type/status 中譯）

## 3. SBE Acceptance Criteria

驗證指令：

- Backend：`./gradlew test`
- Frontend：`cd frontend && npm test`
- Pass：所有 `@DisplayName("AC-N: ...")` / `@Tag("AC-N")` 測試綠

---

**AC-1：建立 flag — happy path with reporter identity**
- Given：alice 已登入；skill `S` PUBLISHED
- When：發 `POST /api/v1/skills/S/flags` body `{"type":"malicious","description":"含後門"}`
- Then：回 201 + body `{"id":"<uuid>"}`；DB 新增一筆 flags (skill_id=S, type=malicious, description="含後門", reported_by="alice", status="OPEN")；舊行為 `reportedBy="anonymous"` 不再出現

**AC-2：未登入 fallback to anonymous（LAB mode）**
- Given：無 Authorization header（LAB mode 預設無 sub）
- When：POST `/api/v1/skills/S/flags` body 同上
- Then：回 201；DB `reported_by = "anonymous"`（既有 fallback 保留）

**AC-3：cross-skill OPEN flags list endpoint**
- Given：3 個 flags：sk1 OPEN / sk2 RESOLVED / sk3 OPEN
- When：發 `GET /api/v1/flags?status=OPEN`
- Then：回 200 + 2 筆（sk1 + sk3），按 createdAt desc，每筆含 `{id, skillId, type, description, reportedBy, createdAt, status}`

**AC-4：cross-skill list 預設無 filter 回全部**
- Given：3 flags 不同 status
- When：發 `GET /api/v1/flags`
- Then：回 200 + 3 筆全部

**AC-5：per-skill endpoint 加 status filter**
- Given：skill `S` 有 2 OPEN + 1 RESOLVED flags
- When：發 `GET /api/v1/skills/S/flags?status=OPEN`
- Then：回 2 筆 OPEN，按 createdAt desc（既有無 filter 行為不破）

**AC-6：PATCH status — resolve happy path**
- Given：flag `f1` 為 OPEN
- When：發 `PATCH /api/v1/skills/S/flags/f1` body `{"status":"RESOLVED"}`
- Then：回 204；DB `flags.status = "RESOLVED"`；outbox 寫 `FlagStatusChangedEvent`（給 future audit）

**AC-7：PATCH status — invalid transition 拒絕**
- Given：flag `f1` 已 RESOLVED
- When：PATCH 改回 OPEN（或 status="bogus"）
- Then：回 400 + `error: "invalid_status_transition"` 或 `error: "invalid_status"`

**AC-8：PATCH status — flag 不存在 404**
- Given：`f-bogus` 不存在
- When：PATCH `/api/v1/skills/S/flags/f-bogus` body `{"status":"RESOLVED"}`
- Then：回 404 + `error: "flag_not_found"`

**AC-9：Frontend SkillDetail Flags tab — 加「回報問題」CTA**
- Given：登入用戶開 SkillDetail 並切到 Flags tab
- When：tab 渲染後
- Then：tab 上方顯「回報問題」按鈕（不論有無既存 flag）；點擊開 FlagSubmitModal

**AC-10：Frontend FlagSubmitModal — happy path**
- Given：modal 開啟
- When：選 type=「惡意指令」+ 寫 description「含後門」+ 點 Submit
- Then：發 POST flags；modal 關閉；既有 FlagsList 加新 row（樂觀更新或 refetch）；toast「已收到回報」

**AC-11：Frontend `/flags` reviewer queue page — list OPEN flags**
- Given：3 flags 不同 skill 都 OPEN
- When：user 開啟 `/flags`
- Then：頁面顯 3 row，每 row 含 type pill + description + 對應 skill name (點 link 跳 SkillDetail) + reporter + createdAt + 「Resolve」+「Dismiss」按鈕

**AC-12：Frontend `/flags` — Resolve action**
- Given：reviewer queue 列出 1 筆 OPEN flag
- When：user 點該 row 的「Resolve」按鈕
- Then：發 PATCH status=RESOLVED；row 從列表消失（filter 過濾）；toast「已處理 1 筆回報」

**AC-13：AppShell nav 加「待審回報」入口**
- Given：AppShell 渲染後
- When：query nav links
- Then：含「待審回報」link，target = `/flags`，highlights when on `/flags`

## 4. Interface / API Design

### 4.1 Backend — REST endpoints

```
POST   /api/v1/skills/{skillId}/flags                     # 既有，行為改 reportedBy
   body { type: string, description: string nullable }
   201 { id: string }
   400 invalid_type / description_too_long
   # NEW: reportedBy 改從 SecurityContext 抽 sub；無 sub fallback "anonymous"

GET    /api/v1/skills/{skillId}/flags?status=             # 既有，加 ?status= filter
   200 [{ id, skillId, type, description, reportedBy, createdAt, status }, ...]

PATCH  /api/v1/skills/{skillId}/flags/{flagId}            # NEW
   body { status: "RESOLVED" | "DISMISSED" }
   204
   400 invalid_status / invalid_status_transition
   404 flag_not_found

GET    /api/v1/flags?status=                              # NEW (cross-skill admin queue)
   200 [{ id, skillId, type, description, reportedBy, createdAt, status }, ...]
```

### 4.2 Backend — Status enum + valid transitions

新增 enum (or app-layer constants)：

```java
public enum FlagStatus {
    OPEN,       // 初始
    RESOLVED,   // reviewer 確認問題真實且已處理
    DISMISSED   // reviewer 判定假警報 / 無理由

    // 唯一允許 transition：OPEN → RESOLVED, OPEN → DISMISSED
    public boolean canTransitionTo(FlagStatus next) {
        return this == OPEN && (next == RESOLVED || next == DISMISSED);
    }
}
```

DB column 仍是 String — enum 純應用層 validation gate；無 schema migration 需求。

### 4.3 Backend — FlagService signature 改動

```java
// 改：reportedBy 由 caller 注入（從 CurrentUserProvider 抽 sub）
@Transactional
public String createFlag(String skillId, String type, String description, String reportedBy) {
    // ... 既有 validation 不變
    // line 123 改：寫 reportedBy 參數而非寫死 "anonymous"
    var flag = new FlagReadModel(flagId, skillId, trimmedType, trimmedDescription, reportedBy, Instant.now(), "OPEN");
    // ... rest unchanged
}

// 新：status 變更 (PATCH endpoint backing)
@Transactional
public void updateStatus(String flagId, String newStatusStr, String actor) {
    var flag = flagRepo.findById(flagId).orElseThrow(() -> new FlagNotFoundException(flagId));
    var current = FlagStatus.valueOf(flag.status());
    var next = FlagStatus.valueOf(newStatusStr);  // throws IllegalArgumentException → 400 mapped
    if (!current.canTransitionTo(next)) {
        throw new InvalidStatusTransitionException(current, next);
    }
    jdbc.update("UPDATE flags SET status = :s WHERE id = :id",
        Map.of("s", next.name(), "id", flagId));
    events.publishEvent(new FlagStatusChangedEvent(flagId, flag.skillId(), current.name(), next.name(), actor));
    log.atInfo().addKeyValue("flagId", flagId).addKeyValue("from", current).addKeyValue("to", next).log("Flag status 變更");
}

// 新：cross-skill list with optional filter
public List<FlagReadModel> listAllFlags(String statusFilter) {
    if (statusFilter == null || statusFilter.isBlank()) {
        return flagRepo.findAllByOrderByCreatedAtDesc();
    }
    return flagRepo.findByStatusOrderByCreatedAtDesc(statusFilter);
}
```

### 4.4 Backend — Controller 改動

`FlagController` 加 PATCH method + per-skill list 加 ?status= filter：

```java
@GetMapping
List<FlagReadModel> getFlags(@PathVariable String skillId, @RequestParam(required = false) String status) {
    return flagService.getFlagsBySkillId(skillId, status);  // service 端加 filter logic
}

@PatchMapping("/{flagId}")
ResponseEntity<Void> updateStatus(
        @PathVariable String skillId,
        @PathVariable String flagId,
        @RequestBody UpdateFlagStatusRequest request) {
    var actor = users.current().userId();  // 注入 CurrentUserProvider
    flagService.updateStatus(flagId, request.status(), actor);
    return ResponseEntity.noContent().build();
}

@PostMapping
ResponseEntity<Map<String, String>> createFlag(
        @PathVariable String skillId,
        @RequestBody CreateFlagRequest request) {
    var reporter = users.current().userId();  // sub 或 LAB fallback "anonymous"
    var flagId = flagService.createFlag(skillId, request.type(), request.description(), reporter);
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", flagId));
}

record UpdateFlagStatusRequest(String status) {}
```

新增 `FlagAdminQueryController`（cross-skill list）：

```java
@RestController
@RequestMapping("/api/v1/flags")
public class FlagAdminQueryController {
    private final FlagService flagService;

    @GetMapping
    List<FlagReadModel> list(@RequestParam(required = false) String status) {
        return flagService.listAllFlags(status);
    }
}
```

放置 module 由 implementer 視 Modulith verifier 結果決定（per S112-T01 deviation 啟示）。

### 4.5 Backend — Repository methods

加 `FlagReadModelRepository`：

```java
List<FlagReadModel> findByStatusOrderByCreatedAtDesc(String status);
List<FlagReadModel> findAllByOrderByCreatedAtDesc();
List<FlagReadModel> findBySkillIdAndStatusOrderByCreatedAtDesc(String skillId, String status);
// 既有 findBySkillIdOrderByCreatedAtDesc 保留
```

### 4.6 Backend — Domain event

```java
public record FlagStatusChangedEvent(String flagId, String skillId, String oldStatus, String newStatus, String actor) {}
```

MVP 無 listener 訂閱（給未來 audit / analytics 預留）。

### 4.7 Frontend — API additions in `frontend/src/api/flags.ts`

```typescript
// 既有 fetchFlags / fetchFlagsSummary / Flag type 保留

export interface CreateFlagRequest {
  type: Flag['type']
  description: string | null
}

export function createFlag(skillId: string, body: CreateFlagRequest): Promise<{ id: string }> {
  return apiFetch(`/skills/${skillId}/flags`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function fetchFlagsByStatus(status: 'OPEN' | 'RESOLVED' | 'DISMISSED' | null): Promise<Flag[]> {
  const qs = status ? `?status=${status}` : ''
  return apiFetch<Flag[]>(`/flags${qs}`)
}

export function updateFlagStatus(skillId: string, flagId: string, status: 'RESOLVED' | 'DISMISSED'): Promise<void> {
  return apiFetch(`/skills/${skillId}/flags/${flagId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status }),
  })
}
```

### 4.8 Frontend — New Page + Modal

**新檔 `frontend/src/pages/FlagsQueuePage.tsx`**：列表 OPEN flags（cross-skill），每 row 含 Resolve/Dismiss 按鈕 + 連到對應 SkillDetail。reuse FLAG_TYPE_LABEL / FLAG_STATUS_LABEL / FLAG_STATUS_STYLE。

**新檔 `frontend/src/components/FlagSubmitModal.tsx`** (or inline within SkillDetailPage per implementer testability)：modal 含 type radio + description textarea + Submit。

**修改 `frontend/src/components/FlagsList.tsx`** (S112-T03 ship)：tab 上方加「回報問題」按鈕觸發 modal。

**修改 `frontend/src/App.tsx`**：加 `<Route path="/flags" element={<FlagsQueuePage />} />`。

**修改 `frontend/src/components/AppShell.tsx`**：nav 加「待審回報」link → `/flags`。

### 4.9 Frontend — useFlagsQueue hook

```typescript
// frontend/src/hooks/useFlagsQueue.ts
import { useQuery } from '@tanstack/react-query'
import { fetchFlagsByStatus, type Flag } from '../api/flags'

export function useFlagsQueue(status: 'OPEN' | null = 'OPEN') {
  return useQuery<Flag[]>({
    queryKey: ['flags-queue', status],
    queryFn: () => fetchFlagsByStatus(status),
    staleTime: 30 * 1000,
  })
}
```

## 5. File Plan

### Backend

| File | Action | Description |
|------|--------|-------------|
| `backend/.../security/FlagStatus.java` | new | enum OPEN/RESOLVED/DISMISSED + canTransitionTo |
| `backend/.../security/FlagService.java` | modify | createFlag 加 reporter 參數；新 updateStatus + listAllFlags + getFlagsBySkillId 加 status filter |
| `backend/.../security/FlagController.java` | modify | createFlag inject CurrentUserProvider 抽 sub；getFlags 加 ?status= ；新 PATCH endpoint |
| `backend/.../security/FlagAdminQueryController.java` | new | GET /api/v1/flags?status= cross-skill list |
| `backend/.../security/FlagReadModelRepository.java` | modify | 加 3 個 derived queries (見 §4.5) |
| `backend/.../security/FlagStatusChangedEvent.java` | new | record (給 future audit listener) |
| `backend/.../security/InvalidStatusTransitionException.java` | new | + GlobalExceptionHandler mapping → 400 invalid_status_transition |
| `backend/.../security/FlagNotFoundException.java` | new (if not exists) | + GlobalExceptionHandler mapping → 404 flag_not_found |
| `backend/.../security/package-info.java` | modify (if needed) | confirm allowedDependencies 含 shared::security（既 S112-T01 已加） |
| `backend/src/test/.../security/FlagServiceTest.java` | modify (S112-T01 ship 已存在) | 加 AC-1/2/6/7 test |
| `backend/src/test/.../security/FlagControllerTest.java` | modify or new | 加 AC-3/4/5/8 web slice |
| `backend/src/test/.../security/FlagAdminQueryControllerTest.java` | new | AC-3/4 cross-skill list |

### Frontend

| File | Action | Description |
|------|--------|-------------|
| `frontend/src/api/flags.ts` | modify (S112-T02 ship) | 加 createFlag / fetchFlagsByStatus / updateFlagStatus |
| `frontend/src/hooks/useFlagsQueue.ts` | new | TanStack Query hook |
| `frontend/src/components/FlagsList.tsx` | modify (S112-T03 ship) | 加「回報問題」CTA + FlagSubmitModal trigger |
| `frontend/src/components/FlagSubmitModal.tsx` | new (recommended; implementer 可 inline within FlagsList per testability) | type radio + description + submit |
| `frontend/src/pages/FlagsQueuePage.tsx` | new | cross-skill list + Resolve/Dismiss |
| `frontend/src/pages/FlagsQueuePage.test.tsx` | new | AC-11 / AC-12 |
| `frontend/src/pages/SkillDetailPage.test.tsx` | modify | 加 AC-9 / AC-10 |
| `frontend/src/components/AppShell.tsx` | modify | 加「待審回報」nav link |
| `frontend/src/App.tsx` | modify | 加 `/flags` route |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/specs/spec-roadmap.md` | modify | M92e3 row：📋 → 📐 in-design + 估點 M(7)→S(8) + 設計摘要 |
| `docs/grimo/glossary.md` | modify | 加 Flag Status (OPEN / RESOLVED / DISMISSED) 中英對照 + Reviewer Queue 術語 |

---

## 7. Result

**Status**: ✅ Shipped 2026-05-03 cron Tick 12-16（30m loop，5 ticks 含 Tick 12 spec planning）— v3.5.1 patch（無 schema migration / 無新模組，純擴充既有 Flag domain）。

**Task ledger**：
- T01 (Tick 13) — backend write flow：FlagStatus enum + canTransitionTo state machine + FlagService updateStatus / listAllFlags / createFlag 加 reporter 4th 參數 + FlagController PATCH/?status= + FlagAdminQueryController + 2 exception types + GlobalExceptionHandler mapping。零 schema migration（status 已是 String column）。FlagServiceTest 9/9 + FlagControllerTest 5/5 + ModularityTests PASS @ ~8s。
- T02 (Tick 14) — frontend infra：api/flags.ts 加 createFlag/fetchFlagsByStatus/updateFlagStatus + Flag.status 加 'DISMISSED' + lib/flag-labels.ts 加 DISMISSED label/style + useFlagsQueue hook (30s staleTime + refetchOnWindowFocus)。typecheck 0 error + FlagsList regression 2/2 PASS。
- T03 (Tick 15) — SkillDetail submit：新建 FlagSubmitModal (6-type radio + description optional + useMutation invalidate ['skill-flags'] + ['me-flags-summary']) + FlagsList 加 CTA 永顯。FlagsList.test.tsx 4/4 PASS（既有 AC-1/AC-2 + 新 AC-9/AC-10）。
- T04 (Tick 16) — reviewer queue：新建 FlagsQueuePage (list OPEN flags + Resolve/Dismiss buttons + skill link) + AppShell nav 加「待審回報」+ /flags route。FlagsQueuePage.test.tsx 2/2 PASS。

**Verification metrics**：
- Backend: FlagServiceTest 9 (AC-1/2/3/4/5/6×2/7×2/8) + FlagControllerTest 5 + ModularityTests 2 — 全 PASS
- Frontend cross-spec: FlagsList 4 + ReviewsPanel 4 + RatingStars 5 + FlagsQueuePage 2 + MySkillsPage 5 — 20/20 PASS @ 1.85s
- Typecheck 0 error；ModularityTests boundary 仍乾淨
- LOC delta: backend +400 (含 ~210 LOC test)，frontend +560 (含 ~190 LOC test)

**13 ACs 涵蓋**：
- AC-1～8 backend by FlagServiceTest + FlagControllerTest
- AC-9 FlagsList CTA + AC-10 modal happy path by FlagsList.test.tsx
- AC-11 reviewer queue list + AC-12 Resolve action by FlagsQueuePage.test.tsx
- AC-13 AppShell nav 加入口 — AppShell.tsx 改動覆蓋（無單獨 test 因 nav 高亮邏輯走既有 location.pathname 比對）

**Trim from spec template**：
- T01 FlagAdminQueryControllerTest slice test defer — FlagServiceTest.listAllFlags 已涵蓋 cross-skill list 業務邏輯
- T04 skill name 顯示走 link 而非 N+1 fetch（per spec §4.1 Approach C simplest）；user 點進去看 detail page

**Lessons**：
- **零 schema migration enum 擴充 pattern**：`FlagReadModel.status` 既是 String column；應用層加 enum + canTransitionTo state machine 不需 ALTER TABLE。對既有資料 100% 相容（OPEN row 仍合法）。
- **FlagReadModel.isNew=true 不能用 save() 做 UPDATE**：`@Modifying @Query` 是 Flag UPDATE 唯一合法路徑（mirror Skill updateRiskLevel S014 pattern）。事先想清避免 DuplicateKey 衝主鍵 (per S098e2-T01 deviation 教訓)。
- **AppShell nav links 跨 spec 累積**：本次加第 8 個 nav link（待審回報）；nav 容量已開始接近上限（橫向 scroll 邊緣）。Polish 候選：將「集合 / 需求 / 待審回報」collapse 進 dropdown menu，但不在本 spec scope。

---
