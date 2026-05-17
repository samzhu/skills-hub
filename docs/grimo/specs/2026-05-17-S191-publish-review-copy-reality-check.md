# S191 - 發佈結果與上架語意整理

> Status: 📐 in-design  
> Owner: Codex  
> Date: 2026-05-17  
> Scope: Frontend UI copy, frontend tests, product docs alignment, backend source inspection only  
> Non-goal: 不新增人工上架審核、審核佇列、approve/reject、PENDING_REVIEW 狀態

## 1. User Story & Problem

`frontend/src/pages/PublishReviewPage.tsx` 現在中風險技能會顯示「低風險自動上架完成」；同一批上架頁與文件還寫著 HIGH 風險會進入「人工審核佇列」。實際後端沒有這條上架審核流程：

- `backend/src/main/java/io/github/samzhu/skillshub/skill/application/SkillCommandService.java` 上傳成功時呼叫 `skill.recordVersionPublished(...)`，技能在上傳流程就變成 `PUBLISHED`。
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/ScanOrchestrator.java` 掃描完成後只寫 `skills.risk_level` 與 `skill_versions.risk_assessment`。
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillStatus.java` 只有 `DRAFT`、`PUBLISHED`、`SUSPENDED`，沒有 `PENDING_REVIEW`。

使用者完成上傳和掃描後，頁面應該只說明「掃描結果 + 發佈狀態 + 風險提示」。人工上架審核現在不做，所以 UI、文件、測試與型別註解不能再承諾審核佇列、24 小時通知、reviewer approve/reject。

### Current Evidence

| Area | Evidence | Issue |
| --- | --- | --- |
| Publish result UI | `frontend/src/pages/PublishReviewPage.tsx` | MEDIUM 被寫成「低風險自動上架完成」 |
| High risk fail UI | `frontend/src/pages/PublishFailedPage.tsx` | State B 文案說「已送審」、「審核佇列」、「24 小時」 |
| Docs UI | `frontend/src/pages/docs/RiskTiersPage.tsx`, `UploadValidatePage.tsx`, `OverviewPage.tsx`, `YourFirstSkillPage.tsx`, `LandingPage.tsx` | 多處宣稱 HIGH 進人工審核或 reviewer approve/reject |
| Product docs | `docs/grimo/PRD.md`, `docs/grimo/glossary.md` | PRD 同時有「HIGH 待審核」與 Out of Scope「人工審核流程」；glossary 的 Verified 容易被理解為人工審核 |
| Backend state | `SkillStatus`, `SkillCommandService`, `ScanOrchestrator` | 目前只有發佈 + 掃描風險，沒有上架審核狀態機 |

## 2. Solution Design

### 2.1 Product Decision

S191 先把產品現況寫清楚：

1. 上傳成功代表系統已建立 skill 與 version，並進入 `PUBLISHED`。
2. 掃描完成後，頁面顯示 `NONE / LOW / MEDIUM / HIGH` 風險。
3. `MEDIUM` 仍是已發佈，但 UI 必須用中風險提示，不得顯示低風險。
4. `HIGH` 不再說「送審」或「等待 reviewer」，改成「高風險掃描完成；目前沒有人工審核流程，請查看詳情或修正後重新上傳」。
5. 社群檢舉的 `FlagsQueuePage` 與 reviewer 文案保留，因為那是「已上架後的檢舉處理」，不是「上架前人工審核」。

### 2.2 UI Copy Rules

| Risk | Publish Review Result Copy | Allowed Status Meaning |
| --- | --- | --- |
| `NONE` | 未偵測到安全風險，發佈完成 | 已發佈 |
| `LOW` | 低風險，發佈完成 | 已發佈 |
| `MEDIUM` | 中風險，發佈完成；使用者應查看安全報告 | 已發佈但有警示 |
| `HIGH` | 高風險掃描完成；不承諾人工審核 | 高風險警示頁，不提審核佇列 |

Status 欄位改用中文顯示：

| Backend Status | UI Label |
| --- | --- |
| `DRAFT` | 草稿 |
| `PUBLISHED` | 已發佈 |
| `SUSPENDED` | 已停用 |

### 2.3 Low-Fi UI Sketches

#### PublishReviewPage - MEDIUM

```text
發布完成
技能已上傳 — 檢視結果

[成功] 「產生字幕檔」 v1 已成功發佈

產生字幕檔  [中風險]
...
分類    video
版本    v1
狀態    已發佈

[提示] 中風險：發佈完成，請查看安全報告與檔案內容後再分享或安裝。
[查看技能詳情] [發佈下一個技能]
```

#### PublishFailedPage - HIGH State B

```text
高風險掃描完成
掃描偵測到 HIGH 級風險。平台目前不提供人工上架審核通知；請查看技能詳情中的安全報告，或修正套件後重新上傳。

[查看技能詳情] [重新上傳] [回到瀏覽]
```

### 2.4 Frontend Files

Implementation should update these files only as needed:

- `frontend/src/pages/PublishReviewPage.tsx`
  - Replace reviewer / queue wording.
  - Add risk-specific message for `NONE / LOW / MEDIUM / HIGH`.
  - Display status with zh-TW label instead of raw enum.
- `frontend/src/pages/PublishReviewPage.test.tsx`
  - Add tests for MEDIUM copy.
- `frontend/src/pages/PublishFailedPage.tsx`
  - Rewrite State B from「已送審」to high-risk scan warning.
  - Remove `reviewer`, `審核佇列`, `24 小時`, `approve`, `reject`.
  - Add or keep CTA that lets user view detail when `id` exists.
- `frontend/src/pages/PublishFailedPage.test.tsx`
  - Update State B expectations.
  - Add negative assertion for removed review terms.
- `frontend/src/pages/docs/RiskTiersPage.tsx`
- `frontend/src/pages/docs/UploadValidatePage.tsx`
- `frontend/src/pages/docs/OverviewPage.tsx`
- `frontend/src/pages/docs/YourFirstSkillPage.tsx`
- `frontend/src/pages/LandingPage.tsx`
  - Remove publication-review claims.
  - Keep risk classification and community flag concepts.
- `frontend/src/types/skill.ts`
  - Update comments so `RiskLevel` / `SkillStatus` do not imply an unimplemented publication review workflow.

### 2.5 Product Docs

Implementation should align current product docs, not rewrite historical records:

- `docs/grimo/PRD.md`
  - Clarify current MVP: scan result is a risk signal; manual publication review remains backlog/out-of-scope.
  - Remove or qualify P2/P3 lines that say scripts/HIGH enter manual review during current MVP.
- `docs/grimo/glossary.md`
  - Clarify `Verified` means validation + automated scan completed, not human approval.
- Do not edit archived shipped specs or `docs/grimo/CHANGELOG.md` for history cleanup.

### 2.6 Backend Inspection Result

No backend behavior change in S191.

The implementation must not add:

- `PENDING_REVIEW`, `APPROVED`, `REJECTED` status.
- Review queue table.
- Reviewer notification.
- Backend blocking rule that changes publication/download visibility.

Backend files may receive tiny comment cleanup only if the comment directly claims publication review exists. Runtime logic stays unchanged.

## 3. Acceptance Criteria

### AC-S191-1: Publish review medium risk copy is accurate

Given `/publish/review?id=<skillId>` loads a skill with `riskLevel: "MEDIUM"`  
When the scan result is shown  
Then the page displays a medium-risk message such as「中風險，發佈完成」  
And the page does not display「低風險自動上架完成」.

### AC-S191-2: Publish review status is human-readable

Given the API skill response contains `"status": "PUBLISHED"`  
When `/publish/review?id=<skillId>` renders  
Then the status row displays「已發佈」  
And does not display raw `PUBLISHED`.

### AC-S191-3: High-risk page no longer claims manual publication review

Given `/publish/failed?state=B&id=<skillId>` renders  
When the user reads the high-risk message  
Then it explains HIGH risk scan result and next actions  
And it does not contain「已送審」、「人工審核佇列」、「審核員核准」、「24 小時」、`reviewer`, `approve`, or `reject`.

### AC-S191-4: User-facing docs remove publication review promises

Given the frontend source files listed in section 2.4 are searched  
When excluding community flag pages and historical archived docs  
Then no current user-facing copy claims HIGH risk enters a publication review queue or waits for reviewer approval.

### AC-S191-5: Community flag review remains separate

Given the app still has `frontend/src/pages/FlagsQueuePage.tsx` and related flag review copy  
When S191 is implemented  
Then those pages may still say reviewer / 待審回報 because they handle community reports, not publication approval.

### AC-S191-6: Product docs match the current shipped workflow

Given `docs/grimo/PRD.md` and `docs/grimo/glossary.md` are read  
When S191 is complete  
Then they describe automated validation + risk scan as current MVP behavior  
And manual publication review remains explicitly future/backlog/out-of-scope.

### AC-S191-7: Backend publication workflow is unchanged

Given backend source is searched after implementation  
When looking for review workflow additions  
Then no new `PENDING_REVIEW`, review queue table, reviewer notification, or publish-blocking backend rule is added.

## 4. Test Plan

Run:

```bash
cd frontend && npm test
cd frontend && npm run verify
```

Manual/source checks:

```bash
rg -n "人工審核佇列|已送審|審核員核准|24 小時|reviewer|approve|reject" frontend/src/pages frontend/src/components frontend/src/types
rg -n "PENDING_REVIEW|APPROVED|REJECTED|review_queue|ReviewQueue" backend/src/main backend/src/test
```

Expected result:

- The first `rg` may still show community flag review pages (`FlagsQueuePage`, `FlagSubmitModal`, app nav「待審回報」); those are allowed and must be listed in the implementation note.
- The first `rg` must not show publication upload/review docs or pages promising manual publication review.
- The second `rg` must not show newly introduced publication-review backend state or queue names.

## 5. Approach Comparison & Risks

### Option A - Copy and display cleanup only (chosen)

Change the UI/docs/tests to match existing backend behavior.

- Files changed: frontend pages/tests/types comments, PRD/glossary docs.
- Runtime behavior: upload, publish, scan, search, download remain unchanged.
- Cost: small UI/docs sweep; low risk.

### Option B - Implement real publication review now (rejected)

Add `PENDING_REVIEW`, review queue, reviewer approve/reject, notification, and publish visibility blocking.

- Files changed: backend domain/API/schema/frontend flow/e2e.
- Runtime behavior: material workflow change.
- Cost: large feature; user explicitly said not to do review now.

### Option C - Block HIGH risk from public visibility without review (rejected)

Keep no reviewer queue, but make HIGH risk non-public.

- Files changed: backend query/download rules and frontend state.
- Runtime behavior: changes what published skills users can see or download.
- Cost: product decision needed; not part of this cleanup.

### Risks

- `docs/grimo/PRD.md` has older lines that say HIGH goes to review while another section says manual review is out-of-scope. S191 must resolve the current-MVP wording without rewriting historical shipped specs.
- Generic words like `reviewer` are still valid for community flags. The implementation must remove publication-review claims without breaking flag-review copy.
- If `PublishFailedPage` keeps the `state=B` route name internally, tests should assert visible copy, not internal route labels.
