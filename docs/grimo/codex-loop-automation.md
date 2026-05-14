# Codex Loop Automation Guide

> 目的：把 Claude Code `/loop` 的成功做法移植到 Codex App Automations，讓 Codex 每次醒來都能依 roadmap 推進 1 個 spec，或在沒有 active spec 時跑 1 個 E2E / edge-case round。

## 結論

Codex 版本不要把整個 workflow 塞進 `AGENTS.md`。最佳拆法：

| 放哪裡 | 放什麼 | Codex 會怎麼用 |
|---|---|---|
| `AGENTS.md` | 穩定 repo 規則、產品上下文、常用路徑、何時讀 `.codex/loop.md` | 每個 run / session 開始自動讀 |
| `.codex/loop.md` | tick 狀態機、Decision Tree、EXIT label、Automation prompt contract | automation prompt 明確要求先讀 |
| `docs/grimo/specs/<SNNN>.md` | feature 真相、AC、file plan、test plan、§7 result | `$planning-tasks` / `$shipping-release` 的依據 |
| `docs/grimo/tasks/` | 單一 spec 的 BDD task queue | `$planning-tasks` 逐一派給 `$implementing-task` |
| `docs/grimo/progress/` | Mode B round、bug ledger、跨 tick 狀態 | 無 active spec 時接續 E2E round |

這樣做的原因很實際：`AGENTS.md` 預設 instruction chain 有大小上限；workflow 太長會擠掉更重要的 repo 規則。Codex 官方也把 AGENTS 定位成「persistent context」，把 prompt / automation prompt 定位成當次 goal。

`AGENTS.md` 必須進 Git。Codex project automation 若跑在 background worktree，只有 Git 追蹤的檔案會穩定出現在新 worktree；把 `AGENTS.md` 當本機 ignored artifact 會讓 automation 找不到 repo 指令。

## Skill Router，不只 planning-tasks

`$planning-tasks` 是「已有設計 spec 之後」的 implementation hub。Codex loop 每次醒來要先看 goal / repo 狀態，選對 skill：

| 要做的事 | Codex skill | 實際行為 |
|---|---|---|
| 定義產品 / PRD | `$defining-product` | 寫 `docs/grimo/PRD.md` |
| 從 PRD 拆架構與 roadmap | `$planning-project` | 寫 architecture / standards / QA / roadmap |
| 設計單一 spec | `$planning-spec S00N` | 寫 spec §1-§5 |
| 拆 task / 實作 / consolidate / QA | `$planning-tasks S00N` | 建 task files，跑 task loop，寫 spec §6-§7 |
| 單 task TDD | `$implementing-task S00N` | 只由 `$planning-tasks` 路由，不當 automation 入口 |
| 獨立 QA | `$verifying-quality S00N` | 檢查 AC / tests / code / docs，寫 QA verdict |
| release | `$shipping-release` | 更新 changelog、roadmap、archive、tag |
| 同錯誤反覆出現 | `$root-cause-debugging` | 先找根因，清掉失敗實驗 |
| Spring Boot 設定 / profile / properties | `$springboot-project-architect` | 查官方 property，修 config 與 docs |
| Browser E2E / Playwright | `$playwright-expert` | 建或跑 acceptance tests，輸出 evidence |
| 外部專案研究 | `$deep-research` | 產出 deepwiki-style docs |
| 建立 / 優化 skill | `$skill-author` | 產出合規 `SKILL.md` |
| 隔離 POC / 多輪 debug / hotfix | `$using-git-worktrees` | 建 `.worktrees/<name>`，保護主 checkout |
| 交班 / 接班 | `$handover` / `$takeover` | 寫或讀 handover note |
| 反覆卡住後檢討 | `$retro` | 產出 trigger-action checklist |

一句話：Codex loop 不是「每次都跑 `$planning-tasks`」，而是「先用 Skill Router 選入口；只有 spec 已經設計好時才進 `$planning-tasks`」。

## Claude Loop 到 Codex 的對應

| Claude Code | Codex App |
|---|---|
| `/loop 30m <prompt>` | Codex App Automation，排程每 N 分鐘 / 每日 / 每週 |
| `ScheduleWakeup` thread wake-up | Thread Automation |
| `/planning-spec` | `$planning-spec` |
| `/planning-tasks` | `$planning-tasks` |
| `/shipping-release` | `$shipping-release` |
| `.claude/loop.md` | `.codex/loop.md` |
| local checkout cron | background worktree project automation（建議） |

## 建議啟動方式

在 Codex App 建一個 project automation：

- Project: `/Users/samzhu/workspace/github-samzhu/skills-hub`
- Execution environment: worktree
- Cadence: 30 minutes for active marathon；daily for maintenance
- Model: default coding model；遇到大型 spec 再提高 reasoning effort
- Prompt: 使用下方 durable prompt

```text
This wake-up is one Codex loop tick for /Users/samzhu/workspace/github-samzhu/skills-hub.
Use a background worktree when available.
Read AGENTS.md and .codex/loop.md first.
Then read docs/grimo/PRD.md, docs/grimo/specs/spec-roadmap.md, and docs/grimo/CHANGELOG.md.
Follow .codex/loop.md Decision Tree.
Use .codex/loop.md Skill Router before coding:
- $defining-product for PRD/product scope
- $planning-project for architecture/roadmap/QA strategy
- $planning-spec for missing planned spec docs
- $planning-tasks as the hub for Plan/Dev specs
- $implementing-task only when routed by $planning-tasks
- $verifying-quality when routed by $planning-tasks or QA is explicitly requested
- $shipping-release for completed specs
- $root-cause-debugging after repeated unchanged failures
- $springboot-project-architect for Spring Boot config/profile/property work
- $playwright-expert for browser E2E / acceptance tests
- $deep-research for external project research
- $skill-author for creating or optimizing skills
- $using-git-worktrees for isolated POC/debug/hotfix work
- $handover / $takeover for session transfer
- $retro for repeated-process lessons
Do exactly one unit of work.
Do not stage or commit user unrelated changes.
Commit at least one atomic result or blocker note when safe.
End with exactly one EXIT label: DONE, WIP, BLOCKED, NO-BUGS-MODE-B, or WALL-HIT.
```

## 為什麼用 worktree

`git status --short` 目前常會出現 user / in-flight spec 的改動。Codex automation 若直接跑 local checkout，很容易把使用者正在改的檔案混進自己的 commit。Codex App 官方支援 automation 在 dedicated worktree 跑；這是本 repo 的預設建議。

如果必須跑 local mode：

1. tick 開頭跑 `git status --short`。
2. 只 stage 本 tick 自己改的檔案。
3. 如果 unrelated dirty state 讓安全 commit 不可能，寫 blocker note，標 `BLOCKED`。

## Decision Tree 的關鍵差異

Claude 舊版教學有時會先找 roadmap planned row。Codex 版本改成先收尾 active spec：

1. `docs/grimo/specs/` 有 active spec doc → 先 `$planning-tasks`。
2. active spec 已完成但未 ship → `$shipping-release`。
3. roadmap 有 planned row 但沒有 spec doc → `$planning-spec`。
4. 沒有 active spec → Mode B E2E / edge-case round。

這避免 S169 這種已經有 task / WIP diff 的 spec 被 S147 planned row 搶走。

## Stop / Pause 條件

Automation 不應該因為 0 bug 就停；0 bug 只是本 tick 結果。真正停下來的條件：

- user 明確要求停止；
- automation 被刪除或排程到期；
- 需要 user input；
- 外部權限 / credential 缺失；
- local dirty state 不能安全 commit；
- 同一錯誤連續 3 次、且已寫 blocker note。

## 官方依據

- Codex 會在工作前讀 `AGENTS.md`，並依目錄層級串接 instructions；預設合併上限 32 KiB：<https://developers.openai.com/codex/guides/agents-md>
- Codex Automations 可在背景跑 recurring tasks，Git repo 可選 local 或 worktree；automation 可用 `$skill-name` 觸發 skill：<https://developers.openai.com/codex/app/automations>
- Codex prompt 要包含可驗證步驟，複雜工作要拆成小 scope：<https://developers.openai.com/codex/prompting>
- Codex workflows 建議提供 explicit context 與清楚 done definition：<https://developers.openai.com/codex/workflows>
