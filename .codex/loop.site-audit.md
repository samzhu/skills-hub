# Codex Production Site Audit Loop

這份文件給「正式站巡檢」 automation 使用。它的工作是每次醒來檢查一個已 shipped 的使用者流程，記錄 production bug finding 或 no-bug audit result。它只產生 docs-only evidence，不修 code、不跑開發 workflow。

目標網站由 automation prompt 指定，預設：

```text
https://skillshub-644359853825.asia-east1.run.app/
```

## File Placement

| File | 放什麼 |
|---|---|
| `AGENTS.md` | 專案共通規則：產品、技術棧、路徑、共通禁止事項、goal router 入口 |
| `.codex/loop.site-audit.md` | 本文件：production 巡檢狀態機、flow selection、evidence contract、docs-only write scope |
| `.codex/prompts/site-audit-loop.md` | 可貼進 Codex App 的薄 prompt；只放 target site、必讀檔案、硬邊界 |
| `docs/grimo/site-audit/**` | production finding / no-bug result / tool-unavailable note |
| `docs/grimo/specs/*-prod-*.md` | site-audit 可選擇新增的 production bug spec 草稿；必須標示自動開立、不可直接執行 |
| spec / roadmap docs | 只讀 shipped 狀態與已完成 AC；不在本 loop 直接排開發工作 |

不要把本文件的完整內容複製回 `AGENTS.md` 或 automation prompt。prompt 只需要明確要求「讀 `AGENTS.md` 與 `.codex/loop.site-audit.md`」並設定 target site。

## Responsibility

Site-audit loop 只負責：

- 依 PRD critical path、roadmap shipped status、archived specs，選一個正式站流程。
- 用 Chrome / browser tool 操作網站。
- 記錄 URL、操作步驟、預期結果、實際結果、console error、failed network request / response body。
- 找到 bug 時新增一份 docs-only finding，或新增一份 `Auto-Draft` production bug spec。
- 沒找到 bug 時新增或更新一份 docs-only audit result。

Site-audit loop 不負責：

- 修改 `backend/**`、`frontend/**`、`e2e/**`。
- 修 bug。
- 跑 `$planning-tasks`、`$implementing-task`、`$verifying-quality`、`$shipping-release`。
- production deploy。
- 因 Local checkout 有 runtime dirty files 而停止；它根本不碰 runtime files。
- 決定開發優先序；finding / auto-draft spec 必須等人類確認後改成可執行狀態，dev loop 才能處理。

官方依據：

- Codex Automations: `https://developers.openai.com/codex/app/automations` — recurring automation 適合背景檢查並把 findings 放到 inbox。
- Codex Workflows: `https://developers.openai.com/codex/workflows` — 每個 workflow 要有 explicit context 與 clear done definition。
- Codex Prompting: `https://developers.openai.com/codex/prompting` — 提供 repro steps 與驗證方式會提高輸出品質。

## Start-of-Tick Reads

每 tick 先讀：

1. `AGENTS.md`
2. `.codex/loop.site-audit.md`
3. `docs/grimo/PRD.md`
4. `docs/grimo/specs/spec-roadmap.md`
5. `docs/grimo/CHANGELOG.md`

依狀態再讀：

- `docs/grimo/specs/archive/` 中與本輪流程相關的 shipped spec。
- `docs/grimo/progress/` 最新 production / blocker note。
- `docs/grimo/site-audit/` 最近 findings（若存在）。
- `docs/grimo/glossary.md`（需要確認 UI 用語時）。

不要讀 `.codex/loop.dev.md` 或 `.codex/loop.md` 來覆蓋本文件。不要讀 active spec 來決定要修什麼；site-audit 只驗證已 shipped / production 應可用的行為。

## Write Scope

預設只寫：

```text
docs/grimo/site-audit/findings/YYYY-MM-DD-FNNN-<slug>.md
docs/grimo/site-audit/results/YYYY-MM-DD-<flow-slug>.md
```

若找到 production bug，site-audit loop 可以新增一份自動開立的 spec 草稿：

```text
docs/grimo/specs/YYYY-MM-DD-SNNN-prod-<slug>.md
```

這份 spec 必須清楚標示：

```text
Status: Auto-Draft
automation_status: auto-draft
Created by: production-site-audit-loop
Human gate: required before dev-loop may execute
```

`Auto-Draft` spec 只能保存 evidence、repro steps、初步 AC / scope；不能建立 task files，不能標成 planned / active，不能呼叫 `$planning-tasks`。人類隔日確認後，才可把狀態改成 `Approved-for-Dev` 或把 roadmap row 改成可執行 planned / active。

site-audit loop 不應直接修改 `docs/grimo/specs/spec-roadmap.md`，避免跟 dev loop 搶同一個 roadmap 檔案。roadmap 排程由 user 或 dev loop 在後續 tick 明確處理。

檔名規則：

- Finding ID 從現有 `docs/grimo/site-audit/findings/` 中最大的 `FNNN` 往後加 1；沒有既有 finding 時用 `F001`。
- 若不確定下一個 ID，使用日期與 flow slug 產生不覆寫的新檔，例如 `YYYY-MM-DD-F-new-<flow-slug>.md`。
- Result 檔案以日期與 flow slug 命名；同一天同 flow 已存在時更新同檔，否則新增。
- Auto-Draft spec 的 `SNNN` 只能使用 roadmap 尚未使用的下一個號碼；若不確定下一個號碼，改寫 finding，不要猜 spec number。

嚴禁修改：

- `backend/**`
- `frontend/**`
- `e2e/**`
- build / deploy config
- migrations
- lockfiles
- scripts

## Audit Selection

每 tick 選一個 flow，避免連續兩輪測同一個 flow：

- 技能瀏覽與搜尋
- 技能詳情
- 上傳 / 發佈
- 下載
- collection
- request board
- analytics
- 登入 / 使用者狀態
- ACL / 可見性
- 錯誤處理
- 深連結 / 404 / 空狀態

優先順序：

1. 上輪 finding 指向需要補 evidence 的 flow。
2. 最近 shipped spec 的 critical path。
3. PRD P1-P6 已 shipped 的功能。
4. 上次最久未測的 flow。

flow 選擇必須寫出 repo evidence，例如：讀到哪個 archived spec / CHANGELOG row / PRD section。若 evidence 不足以判斷該功能已 shipped，不要測它；改選下一個已 shipped flow。

## Evidence Contract

每個 Auto-Draft production bug spec 必須包含：

```text
# SNNN - <title>

Status: Auto-Draft
automation_status: auto-draft
Created by: production-site-audit-loop
Human gate: required before dev-loop may execute
Date: YYYY-MM-DD
Target: <production URL>
Flow: <flow name>
Related shipped spec / AC: <spec id or unknown>

## Production Evidence
1. <step>

Expected:
<user should see / API should return>

Actual:
<what happened>

Evidence:
- Browser console: <message or none>
- Failed request: <method URL status response-body-summary or none>
- Screenshot / trace: <path or unavailable>
- Cloud Run / app log: <command and key lines, or unavailable>

## Draft Scope
<initial fix scope / acceptance criteria draft>

## Human Review Checklist
- Confirm this is still reproducible.
- Decide whether to merge with an existing planned spec.
- If approved, change status to Approved-for-Dev or add a planned / active roadmap row.
```

每個 bug finding 必須包含：

```text
# FNNN - <title>

Date: YYYY-MM-DD
Target: <production URL>
Flow: <flow name>
Related spec / AC: <spec id or unknown>

## Steps
1. <step>
2. <step>

## Expected
<user should see / API should return>

## Actual
<what happened>

## Evidence
- Browser console: <message or none>
- Failed request: <method URL status response-body-summary or none>
- Screenshot / trace: <path or unavailable>
- Cloud Run / app log: <command and key lines, or unavailable>

## Impact
<who is affected and what they cannot do>

## Proposed next action
Human reviews this finding and either creates / approves a dev spec or attaches it to an existing planned spec.
```

No-bug audit result 必須包含：

```text
# Site Audit Result - <flow>

Date: YYYY-MM-DD
Target: <production URL>
Flow: <flow name>

## Checked
1. <step>
2. <step>

## Result
No production bug found in this round.

## Evidence
- Browser console: <none or summary>
- Failed request: <none or summary>
- Screenshot / trace: <path or unavailable>
```

## Tool Policy

- 可用 Chrome plugin 時，用 Chrome 操作正式站，因為 production cookies / auth / extension state 可能有用。
- 若 Chrome tool 不可用，但 Browser tool 可用，可用 Browser 驗證 public / anonymous flow。
- 若兩者都不可用，不要假裝做過 UI 操作；寫 tool-unavailable finding/result，回 `BLOCKED`。
- 可用 `gcloud logs` 時，把 failed request 與 Cloud Run log 串起來；不可用時明確寫 unavailable。
- 不要因為缺 `gcloud` 就擋住 browser evidence；只有 browser / Chrome 兩者都不可用才回 tool-unavailable `BLOCKED`。

## Git Policy

Site-audit loop 是 docs-only：

- 不因 Local runtime dirty files 停止。
- 不跑 Dirty Overlap Gate，除非要 merge / handoff / release（正常不會）。
- 只 stage 本 tick 新增或更新的 `docs/grimo/site-audit/**` finding/result。
- 若本輪新增 Auto-Draft spec，只 stage 本 tick 新增的 spec draft，不 stage roadmap，不 stage task files。
- 若跑在 Codex App background worktree，不要刪除目前 execution worktree；tick 結束時回報 worktree path、branch、commit。

如果 docs-only path 與 user local docs dirty state 衝突：

- 不覆寫 user changes。
- 改用新的 finding file name。
- 若無法安全新增新檔，回 `BLOCKED`。

### Worktree Cleanup

site-audit loop 原則上不需要再手動建立額外 worktree。若本 tick 另外建立 child worktree / POC worktree，結束前必須收尾：

1. 沒有要保留的變更：確認 `git status --short` 乾淨後，從主 checkout 執行 `git worktree remove <path>`。
2. 有 docs-only finding / Auto-Draft spec：先 commit，回報 branch / commit / handoff 指令；合回或確認保留後再 remove。
3. 有 tool-unavailable / blocker evidence：寫 docs-only note 或回報 path + branch + blocker fingerprint；不要留下沒有說明的孤兒 worktree。
4. 不要刪 Codex App 當前 execution worktree；只清理本 tick 額外建立的 worktree。

## One Tick Algorithm

1. 讀 Start-of-Tick files。
2. 選一個 production flow，並記錄選它的 repo evidence。
3. 用 browser / Chrome 開 target site。
4. 執行最小可重現操作。
5. 收集 console / network / screenshot / trace / logs。
6. 有 bug：新增一份 finding 或 `Auto-Draft` production bug spec。
7. 無 bug：新增或更新一份 audit result。
8. 只 stage docs-only output。
9. commit docs-only result；不能安全 commit 時回 `BLOCKED`。
10. 清理本 tick 額外建立的 child worktree；Codex App execution worktree 只回報，不刪。
11. 結尾回 exactly one EXIT label。

## Exit Labels

每 tick 結尾只選一個：

| Label | Meaning |
|---|---|
| `FINDING` | 找到 production bug，已寫 docs-only finding |
| `NO-BUGS` | 本輪 flow 沒找到 bug，已寫 docs-only result |
| `BLOCKED` | browser / Chrome / credential / log tool 缺失，或 docs-only write 不能安全完成 |
| `WALL-HIT` | 時間接近上限，已保存已收集 evidence |

回報格式：

```text
本輪檢查：
- <flow / URL>

結果：
- <finding or no bug>

Evidence:
- <console / network / screenshot / log summary>

Commit:
- <hash or none>

Worktree:
- <current worktree path / branch / commit / cleanup result>

Next audit:
- <下一輪建議 flow>

EXIT: <FINDING|NO-BUGS|BLOCKED|WALL-HIT>
```
