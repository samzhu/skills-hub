# S174: Skill Detail Anonymous Not-Found UX

> 規格：S174 | 大小：XS(8) | 狀態：Approved-for-Dev
> 日期：2026-05-17
> 對應：spec-roadmap row S174 / S153 / S122

---

## 1. 目標

使用者開 `/skills/00000000-0000-0000-0000-000000000000` 時，畫面要顯示「找不到此技能」，不要顯示「載入技能時發生錯誤」或 retry 提示。

S153 已讓前端把 400 / 403 / 404 都當成「找不到此技能」，但 production Chrome Round 68 又看到 missing UUID 走 anonymous 401。這次補上 401 的 UX 防線，並把後端 `GET /api/v1/skills/{id}` 的 missing row 行為改回 404，讓 API 跟 UI 都對齊使用者看到的結果。

相依說明：

| Spec | 狀態 | 對 S174 的意義 |
|---|---|---|
| S153 | shipped v4.24.0 | 既有前端錯誤態：400 / 403 / 404 顯「找不到此技能」，5xx 顯 retry。 |
| S122 | shipped v3.8.5 | 單筆 skill read 加 `@PreAuthorize("hasPermission(#id, 'Skill', 'read')")`，保護 private skill。 |

## 2. 研究與設計

### 2.1 查到的事實

| 來源 | 查到什麼 | 對設計的影響 |
|------|----------|--------------|
| `docs/grimo/specs/spec-roadmap.md` row S174 | Round 68 production Chrome 發現 `/skills/{missing-id}` 顯 generic retry，原因是 API 在 404 前先回 401。 | S174 目標不是重做 detail page，而是補 missing UUID + anonymous 401 的錯誤分類。 |
| `frontend/src/pages/SkillDetailPage.tsx` | `isUnviewable` 只包含 `[400, 403, 404]`；401 會走「載入技能時發生錯誤」和 retry hint。 | 前端要把 401 也視為 detail page 不可見狀態。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryController.java` | `getById(@PathVariable UUID id)` 目前有 `@PreAuthorize("hasPermission(#id, 'Skill', 'read')")`。 | method 進入前先跑 permission check；missing UUID 不會先進 `queryService.findById()`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillPermissionStrategy.java` | read SQL 用 `SELECT EXISTS (...) WHERE id = :skillId AND (...)`；row 不存在時回 `false`。 | missing row 對 anonymous 會被 security layer 判成 deny，而不是 resource layer 的 404。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java` | `findById` 在 repository empty 時 throw `NoSuchElementException("Skill not found: " + id)`。 | 如果 controller method 先執行，`GlobalExceptionHandler` 會把 missing UUID 轉成 404 `NOT_FOUND`。 |
| `SkillQueryController.getByAuthorAndName` | 已採 `@PostAuthorize("hasPermission(returnObject.id, 'Skill', 'read')")`，先 resolve 物件，不存在時 404，存在但無權時才 deny。 | `getById` 可沿用同一 pattern，不新增 security abstraction。 |

### 2.2 架構設計

S174 採兩層補強：

```text
使用者開 /skills/{uuid}
  -> frontend GET /api/v1/skills/{uuid}
  -> backend controller 先呼叫 queryService.findById(uuid)
       row 不存在 -> NoSuchElementException -> 404 NOT_FOUND
       row 存在 -> 回 Skill 物件
  -> @PostAuthorize 檢查 returnObject.id
       public / granted -> 200
       private + anonymous -> 401
       private + logged-in no grant -> 403
  -> frontend 400 / 401 / 403 / 404 都顯「找不到此技能」
```

後端改動只限 `GET /api/v1/skills/{id}`。`versions`、`bundle-info`、download 等子資源保留 `@PreAuthorize`，因為它們不是 detail page 首要資料來源，而且子資源是否存在通常要依 skill read 權限保護。

### 2.3 做法比較

| 做法 | 採用 | 理由 |
|----------|--------|-----------|
| A. 前端只把 401 加入 `isUnviewable` | no | 可以修畫面，但 API 對「不存在 UUID」仍回 401；S174 roadmap 明確指出 API 先回 401 是 root cause。 |
| B. `getById` 改 `@PostAuthorize` + 前端包含 401 | yes | `getByAuthorAndName` 已用相同 pattern；missing row 先走 404，private row 仍由權限 gate 擋住；前端也能處理既有 production 401。 |
| C. `SkillPermissionStrategy` 查不到 row 時丟 exception | no | permission evaluator 的職責是回 true/false；在 security layer 丟 resource exception 會讓所有 `hasPermission` call site 混入 resource semantics。 |

### 2.4 低保真 UI 草圖

這不是 final pixel，不新增設計系統，也不加入無關視覺裝飾；只固定錯誤態內容與互動。

```text
/skills/00000000-0000-0000-0000-000000000000

┌──────────────────────────────────────────────┐
│ Skills Hub nav                               │
├──────────────────────────────────────────────┤
│                                              │
│              找不到此技能                    │
│              返回列表                        │
│                                              │
└──────────────────────────────────────────────┘

不顯示：
- 請稍後重試或重新整理頁面
- 技能內容 tab
- 下載 / 分享 / 編輯動作
```

### 2.5 Task 邊界提示

| Task 候選 | Class / file | 來源 | 正向情境 | 反向情境 | POC |
|-----------|--------------|------|----------|----------|-----|
| T01 | `SkillDetailPage.tsx` + `SkillDetailPage.test.tsx` | S153 existing error mapping | API 401 時顯「找不到此技能」且無 retry hint | API 500 仍顯「載入技能時發生錯誤」與 retry hint | not required |
| T02 | `SkillQueryController.java` + controller test | `getByAuthorAndName` existing `@PostAuthorize` pattern | anonymous GET missing UUID 回 404 `NOT_FOUND` | anonymous GET private existing skill 仍回 401 | not required |

### 2.6 估算

| 維度 | 分數 | 理由 |
|---|---:|---|
| 技術風險 | 1 | 沿用現有 `@PostAuthorize` pattern，不引入新 API。 |
| 不確定性 | 1 | roadmap、S153、S122 和現有 code 已說明問題。 |
| 依賴 | 2 | 依賴 S153 前端錯誤態與 S122 read ACL gate 既有行為。 |
| 範疇 | 1 | 兩個 production files：一個前端頁面、一個 backend controller。 |
| 測試 | 2 | 需要 Vitest + WebMvc slice，無新容器或 E2E fixture。 |
| 可逆性 | 1 | 可一 commit revert；不改 schema、不改 public response body shape。 |
| **Total** | **8 / XS** | 可直接進 task planning。 |

## 3. 驗收條件（SBE）

驗證命令：

執行：`cd frontend && npm test -- SkillDetailPage && cd ../backend && ./gradlew test --tests "*SkillQueryControllerApiContractTest"`

通過條件：所有帶 `S174` 的 frontend/backend 測試都是綠燈，且既有 S153 500 retry test 仍通過。

| AC | 優先級 | 驗證方式 | 標題 |
|----|----------|--------|-------|
| AC-S174-1 | 必做 | Test | anonymous detail API missing UUID 回 404 |
| AC-S174-2 | 必做 | Test | private existing skill anonymous 仍回 401 |
| AC-S174-3 | 必做 | Test | detail page 收 401 顯「找不到此技能」 |
| AC-S174-4 | 必做 | Test | 500 / network error 仍顯 retry |

**AC-S174-1: anonymous detail API missing UUID 回 404**
- Given（前提）資料庫沒有 id `00000000-0000-0000-0000-000000000000` 的 skill
- When（動作）anonymous request `GET /api/v1/skills/00000000-0000-0000-0000-000000000000`
- Then（結果）HTTP status 是 404
- And（而且）response body 是 `{"error":"NOT_FOUND","message":"Skill not found: 00000000-0000-0000-0000-000000000000",...}`

**AC-S174-2: private existing skill anonymous 仍回 401**
- Given（前提）資料庫有一筆 private skill，`skills.is_public=false`，且沒有 public grant
- When（動作）anonymous request `GET /api/v1/skills/{private-id}`
- Then（結果）HTTP status 仍是 401
- And（而且）response 不輸出該 private skill 的 name、description、author、version、viewerPermissions

**AC-S174-3: detail page 收 401 顯「找不到此技能」**
- Given（前提）使用者開 `/skills/00000000-0000-0000-0000-000000000000`，frontend fetch mock 回 401
- When（動作）`SkillDetailPage` 進入 error state
- Then（結果）畫面顯示「找不到此技能」
- And（而且）不顯示「請稍後重試或重新整理頁面」
- And（而且）「返回列表」連到 `/browse`

**AC-S174-4: 500 / network error 仍顯 retry**
- Given（前提）使用者開任一 `/skills/{id}`，frontend fetch mock 回 500
- When（動作）`SkillDetailPage` 進入 error state
- Then（結果）畫面顯示「載入技能時發生錯誤」
- And（而且）顯示「請稍後重試或重新整理頁面」

### 非功能需求檢查

| 分類 | 對應驗收 | 說明 |
|---|---|---|
| Performance | N/A | 只調整錯誤分流；成功路徑仍是單次 repository read + permission check。 |
| Security | AC-S174-2 | private existing skill anonymous 仍不能拿到 JSON body。 |
| Reliability | AC-S174-1, AC-S174-4 | 永久不存在回 404；真的 5xx 才提示 retry。 |
| Usability | AC-S174-3, AC-S174-4 | 使用者不會被 missing URL 誤導去 refresh，但 server error 仍有 retry 指引。 |
| Maintainability | AC-S174-1 | backend 沿用 `@PostAuthorize` 既有 pattern，不新增平行授權邏輯。 |

## 4. 介面與 API 設計

### 4.1 Backend controller

```java
@GetMapping("/skills/{id}")
@PostAuthorize("hasPermission(returnObject.id, 'Skill', 'read')")
Skill getById(@PathVariable UUID id) {
    return queryService.findById(id.toString());
}
```

行為表：

| DB row | Viewer | `queryService.findById` | `@PostAuthorize` | HTTP |
|---|---|---|---|---|
| missing | anonymous | throws `NoSuchElementException` | 不執行 | 404 |
| private | anonymous | returns `Skill` | false | 401 |
| private | logged-in no grant | returns `Skill` | false | 403 |
| public | anonymous | returns `Skill` | true | 200 |

### 4.2 Frontend error mapping

```tsx
const isUnviewable =
  ApiError.is(error) && [400, 401, 403, 404].includes(error.status)
```

401 在 detail page 視為「不可見」，顯示「找不到此技能」。這不改全域 `ApiError` 或其他頁面；只有 skill detail 的 user-facing route 採 security-by-obscurity copy。

## 5. 檔案規劃

| 檔案 | 動作 | 說明 |
|------|--------|-------------|
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryController.java` | modify | `getById` 從 `@PreAuthorize` 改 `@PostAuthorize`；Javadoc 改為 missing=404、private deny=401/403。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillQueryControllerApiContractTest.java` | modify | 新增 `AC-S174-1` / `AC-S174-2` MockMvc tests；確認 missing 404 與 private anonymous 401。 |
| `frontend/src/pages/SkillDetailPage.tsx` | modify | `isUnviewable` status list 加 401；註解補 S174。 |
| `frontend/src/pages/SkillDetailPage.test.tsx` | modify | 新增 401 case；保留 500 retry case。 |
| `docs/grimo/specs/spec-roadmap.md` | modify | S174 row 改成 `Approved-for-Dev` / next `$planning-tasks S174`。 |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
## 6. Task Plan

POC：not required — S174 只改既有 React error mapping 與既有 Spring Method Security annotation pattern；沒有新 dependency、SDK、schema、外部服務或待驗證框架能力。

| 順序 | Task | AC | 狀態 | 驗證 |
|---:|---|---|---|---|
| 1 | `2026-05-17-S174-T01-frontend-401-not-found.md` | AC-S174-3, AC-S174-4 | PASS | `cd frontend && npm test -- SkillDetailPage` |
| 2 | `2026-05-17-S174-T02-backend-missing-uuid-404.md` | AC-S174-1, AC-S174-2 | PASS | `cd backend && ./gradlew test --tests "*SkillQueryControllerApiContractTest"` |

E2E artifact verification：not required for planning — S174 的 AC 都是 API status/body 或 single-page error state，沒有新增 browser-only fixture、runtime wiring、schema 初始化或跨程序行為。Phase 4 若 task 全 PASS，記錄 no E2E seam rationale 即可。
