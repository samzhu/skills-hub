# Codex Development Loop

這份文件給「功能開發推進」 automation 使用。它的工作是每次醒來推進一個已規劃的 spec / task，透過 `$skill-name` 呼叫下一個 specialist skill，不直接把 specialist workflow inline 寫在 automation prompt 裡。

## File Placement

| File | 放什麼 |
|---|---|
| `AGENTS.md` | 專案共通規則：產品、技術棧、路徑、測試命令、共通禁止事項、goal router 入口 |
| `.codex/loop.dev.md` | 本文件：dev automation 的狀態機、NEXT_SKILL 判斷、worktree policy、exit label |
| `.codex/prompts/dev-loop.md` | 可貼進 Codex App 的薄 prompt；只放入口、必讀檔案、硬邊界 |
| spec / task docs | feature 真相、驗收標準、實作進度、驗證結果 |

不要把本文件的完整內容複製回 `AGENTS.md` 或 automation prompt。prompt 只需要明確要求「讀 `AGENTS.md` 與 `.codex/loop.dev.md`」並設定本 automation 的邊界。

## Responsibility

Dev loop 只負責：

- 從 repo 檔案判斷目前開發狀態。
- 選出下一個應執行的 skill。
- 讓該 skill 完成一個可保存的開發成果。
- commit 本 tick 自己產生的程式 / 測試 / spec / task / release docs。

Dev loop 不負責：

- 主動測正式站。
- 主動新增 production bug finding。
- 修復 site-audit loop 剛發現但尚未排入 roadmap / spec 的問題。
- production deploy。
- 在普通 worktree implementation 前用 Local checkout dirty state 擋住自己。
- 管理 production site-audit finding；finding 要先被 user 或 roadmap 明確轉成 dev spec。

官方依據：

- Codex Automations: `https://developers.openai.com/codex/app/automations` — automation 可搭配 skills，並可用 `$skill-name` 明確觸發 skill；Git repo 可選 background worktree 隔離變更。
- Codex Worktrees: `https://developers.openai.com/codex/app/worktrees` — worktree 讓 Codex 在背景平行工作，不干擾 Local checkout。
- Codex Prompting: `https://developers.openai.com/codex/prompting` — 複雜工作拆成小而可驗證的步驟。
- Agents Orchestration: `https://developers.openai.com/api/docs/guides/agents/orchestration` — specialist 只有在不同工作需要不同 instruction / tools / policy 時才拆；開發 loop 保持一個 manager，specialist 用 skill 呼叫。

## Start-of-Tick Reads

每 tick 先讀：

1. `AGENTS.md`
2. `.codex/loop.dev.md`
3. `docs/grimo/PRD.md`
4. `docs/grimo/specs/spec-roadmap.md`
5. `docs/grimo/CHANGELOG.md`

讀完固定檔案後，NEXT_SKILL 前必須重新建立一次 current repo snapshot：

- 重新檢查 `docs/grimo/specs/spec-roadmap.md` 的 Active / planned rows，不沿用上一 tick 回報的 next action。
- 列出 `docs/grimo/tasks/`，用 task filename 的 `SNNN` 對回 spec；若 task files 已存在，代表 spec 可能已從設計進入 task / implementation loop。
- roadmap 或 task files 指向 active `SNNN` 時，讀該 spec doc 的 header、§6 task plan、§7 results，再決定 `$planning-spec` / `$planning-tasks` / `$verifying-quality` / `$shipping-release`。

依狀態再讀：

- `docs/grimo/specs/<active-spec>.md`
- `docs/grimo/tasks/`
- `docs/grimo/architecture.md`
- `docs/grimo/development-standards.md`
- `docs/grimo/qa-strategy.md`
- `docs/grimo/glossary.md`
- `docs/grimo/adr/`
- `docs/grimo/specs/archive/`

不要讀 `.codex/loop.md` 來覆蓋本文件；它是 legacy / general loop。不要讀 `docs/grimo/site-audit/` 來改變開發優先序；site-audit 產物要先被 user 或 roadmap 明確排入 spec。

## State Contract

狀態存在 repo 檔案，不存在對話記憶：

| Repo fact | Meaning |
|---|---|
| spec doc 標示 `Status: Auto-Draft` 或 `automation_status: auto-draft` | 這是 site-audit 自動開立草稿；dev loop 必須忽略，不得規劃或實作 |
| `docs/grimo/specs/` 根目錄有 spec doc，且 §7 / QA / roadmap / task evidence 顯示 AC 已完成或 QA PASS，但 changelog / roadmap release row / archive / tag 任一缺失 | 這不是新功能入口；下一步必須先 `$shipping-release SNNN`，不得跳到下一個 planned spec |
| `docs/grimo/tasks/` 還有 `SNNN` task file，且對應 spec 已 QA PASS 或已標 Done / Shipped | 這是未收尾 release；下一步必須先 `$shipping-release SNNN` 清 task、歸檔 spec、更新 changelog / roadmap |
| `docs/grimo/specs/spec-roadmap.md` 有 planned row，但沒有 spec doc | 下一步是 `$planning-spec SNNN` |
| spec doc 已有設計章節，且標示 `Status: Approved-for-Dev` / roadmap 明確排入 planned 或 active，但 task files 不存在或未完成 | 下一步是 `$planning-tasks SNNN` |
| task files / spec result 顯示 implementation 完成，但 QA 尚未 PASS | 下一步是 `$verifying-quality SNNN`，通常由 `$planning-tasks` 路由；若 automation 明確位於 QA gate 可直接呼叫 |
| QA PASS，但 changelog / roadmap / archive / tag 未完成 | 下一步是 `$shipping-release SNNN` |
| 沒有 active / planned / releasable spec | 回 `DONE`，說明目前沒有 dev work |

NEXT_SKILL 要從檔案事實推導，不從上一輪聊天記憶推導。自動草稿不是開發授權；人類隔日確認後，必須把 spec 狀態改成 `Approved-for-Dev` 或把 roadmap row 改成可執行 planned / active，dev loop 才能處理。若 prompt、roadmap、spec 三者衝突，以 user 最新 prompt 為最高優先；仍不清楚時回 `BLOCKED` 並指出哪個檔案哪一行互相矛盾。

### Release Completeness Gate

每 tick 選新 spec 前，先掃 `docs/grimo/specs/*.md`、`docs/grimo/tasks/*S*.md`、`docs/grimo/CHANGELOG.md`、`docs/grimo/specs/spec-roadmap.md`、`git tag --points-at HEAD`。若任何 spec 已實作完成或 QA PASS，但仍在 `docs/grimo/specs/` 根目錄、task file 還存在、CHANGELOG 無版本記錄、roadmap 沒有 shipped / archived row、或 git tag 缺失，NEXT_SKILL 必須是 `$shipping-release SNNN`。

「完成 spec」在 dev loop 中只代表 `$shipping-release` 已做完這些檔案事實：

- spec doc 從 `docs/grimo/specs/` 移到 `docs/grimo/specs/archive/`
- `docs/grimo/tasks/` 沒有該 SNNN 的 task file
- `docs/grimo/CHANGELOG.md` 有該 SNNN 的版本 entry
- `docs/grimo/specs/spec-roadmap.md` 有該 SNNN 的 shipped / archived 狀態與版本
- git commit 存在；若 release 流程要求 tag，`git tag --points-at HEAD` 能看到版本 tag

只要上述任一項缺失，該 tick 只能回 `WIP` 或 `BLOCKED`，不得回 `DONE`，也不得選下一個 planned spec。

## Skill Router

每 tick 只選一個 NEXT_SKILL，並用 `$skill-name` 明確呼叫。不要在 automation prompt 裡手寫該 skill 的完整流程。

| Condition | NEXT_SKILL |
|---|---|
| user goal 明確指定 product scope / PRD | `$defining-product` |
| user goal 明確指定 project roadmap / architecture | `$planning-project` |
| 已完成 / QA PASS 的 spec 還沒通過 Release Completeness Gate | `$shipping-release SNNN` |
| roadmap 有 planned spec，但缺 spec doc | `$planning-spec SNNN` |
| spec doc 已設計完成，且非 `Auto-Draft`，要拆 task / 實作 / consolidate / QA | `$planning-tasks SNNN` |
| spec task 全 PASS，但需要獨立 QA | `$verifying-quality SNNN` |
| QA PASS 且需要 release docs / archive / tag | `$shipping-release SNNN` |
| 同錯誤連續 2 次以上，且 fix 沒讓錯誤改變 | `$root-cause-debugging` |
| Spring Boot config / profile / property / starter 選型 | `$springboot-project-architect` |
| Browser E2E / Playwright acceptance work 是該 spec 的一部分 | `$playwright-expert` |

`$implementing-task` 只由 `$planning-tasks` 內部路由；dev automation 不直接呼叫它。

## Execution Environment

建議 automation 使用 Codex App project automation，execution environment 選 `worktree`。

tick 開頭只做基本 audit：

```bash
pwd
git rev-parse --show-toplevel
git worktree list
git status --short
```

如果 automation 本身跑在 Codex background worktree：

- 普通 implementation 不因 Local checkout dirty files 停止。
- 只檢查目前 worktree 的 dirty state，避免 stage unrelated changes。
- 只有 merge、release、deploy、handoff 回 Local 前，才跑 Dirty Overlap Gate。
- 不要刪除目前這個 Codex App execution worktree；它由 Codex App 管理 lifecycle。tick 結束時要留下乾淨 commit，並回報 worktree path、branch、commit、handoff / merge 狀態。

如果 automation 跑在 Local checkout：

- `git status --short` 有 unrelated runtime/code/config 變更時，不要直接實作會碰同一批檔案的 task。
- 若只是 unrelated docs 變更，尤其是 `docs/grimo/specs/spec-roadmap.md` 新增任務 / 狀態調整，這是正常共享 ledger 變動，不是 dev loop blocker。優先改用 Codex App background worktree 或只在自己的 touched files 範圍內繼續；不要 stage user docs。

### Shared Roadmap Policy

`docs/grimo/specs/spec-roadmap.md` 是多人 / 多 automation 會持續更新的共享索引，常見的新增 spec row、status row、archive row 不應讓 dev loop 或 `$shipping-release` 直接 `BLOCKED`。

判斷方式：

1. 若 automation 跑在 Codex App background worktree：忽略 Local checkout 的 dirty `spec-roadmap.md`；只檢查目前 worktree 內是否有 unrelated runtime/code/config dirty files。
2. 若 automation 跑在 Local checkout，且本 tick 需要 `$shipping-release` 修改 roadmap：
   - 只有 `spec-roadmap.md` / docs 類 dirty：不要把它記成 runtime blocker；改用 background worktree 執行 release，或在 Local 做 scoped edit 但只 stage 本 tick 自己的 release diff。
   - 有 `backend/**`、`frontend/**`、`e2e/**`、migration、build/deploy config、scripts、lockfile dirty overlap：才回 `BLOCKED`。
3. `$shipping-release` skill 的「git status clean of unrelated changes」在 automation context 中解讀為：目前 execution worktree 不能有 unrelated runtime/code/config 變更；Local checkout 的 shared docs ledger 變動不算 local release correctness failure。
4. roadmap 合併衝突才是 blocker；單純看到 `M docs/grimo/specs/spec-roadmap.md` 不是 blocker。

### Worktree Cleanup

automation 如果另外手動建立 child worktree / POC worktree，tick 結束前必須收尾：

1. 若 worktree 內沒有要保留的變更：確認 `git status --short` 乾淨後，從主 checkout 執行 `git worktree remove <path>`。
2. 若 worktree 內有完成成果：先 commit，回報 branch / commit / merge 或 cherry-pick 指令；合回或確認保留後再 remove。
3. 若 worktree 內有 blocker evidence：commit blocker note 或回報 path + branch + blocker fingerprint；不要留下沒有說明的孤兒 worktree。
4. 不要刪 Codex App 當前 execution worktree；只清理本 tick 額外建立的 worktree。

## Dirty Overlap Gate

只在以下操作前執行：

- merge / rebase
- handoff worktree 成果回 Local
- `$shipping-release` 需要改 main / tag / archive
- production deploy preflight（dev loop 原則上不 deploy）

命令：

```bash
git status --short
git diff --name-only
git diff --name-only --cached
git diff --name-only main...<target-branch>
```

判斷：

- Local dirty files 與 target branch changed files 沒交集：可繼續。
- 交集只包含 `docs/**`、`.codex/**`、`AGENTS.md`、`CLAUDE.md`、`README*`、`CHANGELOG*`：不視為 runtime blocker。若包含 `docs/grimo/specs/spec-roadmap.md`，把它視為 shared roadmap ledger；優先在 worktree 完成 release commit，回報 user 後續整合，不要因它單獨回 `BLOCKED`。
- 交集包含 `backend/**`、`frontend/**`、`e2e/**`、migration、build/deploy config、scripts、lockfile：不 merge、不 stash、不覆寫；寫 blocker note 或回 `BLOCKED`。

## One Tick Algorithm

1. 讀 Start-of-Tick files。
2. 重建 current repo snapshot：重新讀 roadmap Active / planned rows、列出 tasks、讀 task 指向的 active spec doc。
3. 跑基本 worktree audit。
4. 先跑 Release Completeness Gate：找已完成但未歸檔 / 未 tag / 未清 task 的 spec。
5. 若 Gate 找到未收尾 spec，NEXT_SKILL 固定為 `$shipping-release SNNN`；只有 Gate 乾淨才用 current repo snapshot 找 active / planned spec。
6. 選 exactly one NEXT_SKILL，並在回報中寫出選它的 repo evidence。
7. 呼叫該 skill，讓它完成一個可保存成果。
8. 跑該 skill 要求的最小必要 verify。
9. 更新 spec / task / changelog / roadmap 中對應的結果。
10. 只 stage 本 tick 自己改的檔案。
11. commit 一個 atomic result，或在不能安全 commit 時回 `BLOCKED`。
12. 清理本 tick 額外建立的 child worktree；Codex App execution worktree 只回報，不刪。
13. 結尾回 exactly one EXIT label。

## Write Scope

Dev loop 可以修改：

- `backend/**`
- `frontend/**`
- `e2e/**`
- `docs/grimo/specs/**`
- `docs/grimo/tasks/**`
- `docs/grimo/CHANGELOG.md`
- `docs/grimo/specs/spec-roadmap.md`
- `docs/grimo/adr/**`
- build / deploy config（只有 spec 或 release 明確需要時）

Dev loop 不應修改：

- `docs/grimo/site-audit/**`，除非 user 明確要求把 finding 轉入正式 spec。
- unrelated user dirty files。

## Exit Labels

每 tick 結尾只選一個：

| Label | Meaning |
|---|---|
| `DONE` | 本 tick 完成 `$shipping-release` 且 Release Completeness Gate 乾淨；或 Gate 乾淨且目前沒有 dev work |
| `WIP` | 有可保存進度，但 spec / task 尚未完成 |
| `BLOCKED` | 需要 user input、外部權限、工具缺失，或 dirty overlap 不能安全處理 |
| `WALL-HIT` | 時間接近上限，已保存最近 atomic step |

回報格式：

```text
本輪完成：
- <file path / command / result>

驗證：
- <command> -> <result>

Commit:
- <hash or none>

Worktree:
- <current worktree path / branch / commit / cleanup result>

NEXT_SKILL:
- <下一輪建議的 $skill-name or none>

EXIT: <DONE|WIP|BLOCKED|WALL-HIT>
```
