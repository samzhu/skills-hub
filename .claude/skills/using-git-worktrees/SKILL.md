---
name: using-git-worktrees
description: Use when starting isolated work that risks disrupting the main checkout — POC for unfamiliar SDKs, multi-attempt root-cause debugging, hotfix interrupting an in-flight spec, or a sub-agent that edits code. Skip for normal spec implementation; this project's single-track workflow does not need a worktree per spec. Worktrees live at `.worktrees/<name>/` (NOT the platform default `.claude/worktrees/`).
---

# Using Git Worktrees

## When to open a worktree

**Yes** — match any:

| Trigger | Why |
|---|---|
| POC for unfamiliar SDK / risky design hypothesis | failed POCs need a disposable restore point（`Clean Experiments` 原則）|
| Multi-attempt debug expected (≥3 tries) | branch commits = restore points；確認 fix 後 cherry-pick 真正必要的 diff，丟掉中間 noise |
| Hotfix mid-tick disrupting an in-flight spec | 唯一打破 `Finish-Current-First` 的合法理由 |
| Sub-agent that edits code (research / QA writing fixes) | independent context、main 不受實驗污染 |

**No** — stay on `main`:

- Normal spec implementation（single-track + commit per tick 已經夠）
- Mode B E2E round（read-only investigation）
- Trivial doc / config edit

## Path policy (project-specific)

Worktrees live at **`.worktrees/<name>/`** at repo root（已加入 `.gitignore`）。

**禁用 `.claude/worktrees/`**（platform default）。`.claude/` 是 live config namespace，cron-loop 半夜跑時對這個路徑下的 file ops 特別容易卡 permission / state。

→ **不要用以下 native tool 進入點建新 worktree**，它們全部 fall back 到 `.claude/worktrees/`：

| Tool | 為何不能用 |
|---|---|
| `EnterWorktree(name: ...)` | 平台內建邏輯寫死 `.claude/worktrees/`，無 path override |
| `EnterWorktree(path: ...)` | 同 tool，policy 一致禁用（見下） |
| `ExitWorktree` | 同上 |
| `claude --worktree <name>` | CLI 起手就建在 `.claude/worktrees/` |
| `Agent(isolation: "worktree")` | tool 參數無 path 欄位 |

**Hard enforcement**：`.claude/settings.local.json` 的 `permissions.deny` 已加 `EnterWorktree` / `ExitWorktree`。任何呼叫直接被拒絕（不是 prompt），cron 半夜跑也不會卡。skill instruction 升級為 settings 層級執行。

## Preconditions（開工前自檢）

第一次跑或不確定 baseline 是否就緒時，**先驗證**：

```bash
# 1. Deny 規則（任一檔命中即 OK）
( grep -q '"EnterWorktree"' .claude/settings.local.json 2>/dev/null \
    || grep -q '"EnterWorktree"' .claude/settings.json 2>/dev/null ) \
  && echo "deny: OK" || echo "deny: MISSING"

# 2. .worktrees/ 已 gitignore
git check-ignore -q .worktrees/ && echo "gitignore: OK" || echo "gitignore: MISSING"
```

任一 MISSING → 跑 `/planning-project`（Project Policy Bootstrap 步驟）補齊，
或手動修。policy 沒就緒就往下走 Step 1，會踩到平台 default 行為（cron 卡 prompt）。

## Step 0 — Already in a worktree?

開工前先驗證，避免 nested worktree：

```bash
GIT_DIR=$(git rev-parse --git-dir)
GIT_COMMON=$(git rev-parse --git-common-dir)
[ "$GIT_DIR" != "$GIT_COMMON" ] && echo "yes — work in place, skip Step 1"

# Submodule guard：上式在 submodule 也成立。額外驗證：
git rev-parse --show-superproject-working-tree 2>/dev/null
# 有輸出 → 你在 submodule，當作普通 repo 處理
```

## Step 1 — Create

用 git CLI 直接建：

```bash
NAME=<feature-slug>
git worktree add .worktrees/$NAME -b feature/$NAME
```

要在 worktree 裡幹活，兩種模式擇一（**不要嘗試切換當前 session 的 CWD**，`EnterWorktree` 已 deny）：

| 模式 | 怎麼做 | 適合 |
|---|---|---|
| **同 session、跨 worktree 操作** | 從 main session 用 `git -C .worktrees/$NAME ...` 跟 `(cd .worktrees/$NAME && <cmd>)` 操作 worktree 內容 | 短任務、subagent 自跑 |
| **新 session 進 worktree** | 另一個 terminal: `cd .worktrees/$NAME && claude` | 長任務、要互動式工作 |

## Step 2 — Setup + clean baseline

Worktree 是 fresh checkout，untracked file（如 `.env`）不在裡面。視 module 補：

```bash
# Backend：Gradle 自己 resolve，無需 setup
[ -d frontend ] && (cd frontend && npm install)
[ -d e2e ] && (cd e2e && npm install)
```

**先驗 baseline 綠**，後續失敗才能歸因到你的改動：

```bash
cd backend && ./gradlew test       # 或對應 ecosystem 的 targeted test
```

## Step 3 — Finish (merge / cherry-pick / discard)

| Outcome | Action |
|---|---|
| **Ship** — POC validated / hotfix done | `cd <main>; git merge --ff-only feature/$NAME; git worktree remove .worktrees/$NAME` |
| **Cherry-pick clean** — debug 確認 fix，丟掉中間嘗試 | `cd <main>; git cherry-pick <fix-sha>; git worktree remove --force .worktrees/$NAME; git branch -D feature/$NAME` |
| **Discard** — POC failed / 設計被推翻 | `git worktree remove --force .worktrees/$NAME; git branch -D feature/$NAME` |

收尾後 `git worktree list` 應只剩你想保留的 entries。**不要累積孤兒 worktree**。

## Sub-agent isolation 的妥協方案

若你需要真正 isolated subagent（e.g., `planning-tasks` Phase 4 QA reviewer 實際會寫程式）：

- **不要**用 `Agent(isolation: "worktree")` —— 會落到 `.claude/worktrees/`
- **改用**：spawn 普通 subagent，prompt 中要它**自己跑 Step 1** 在 `.worktrees/` 建 workspace、結束時 Step 3 收尾。Skill 內容會被 subagent 一同讀到。

## Anti-patterns

- ❌ 每個 spec 都開 worktree —— 跟 single-track workflow 衝突，純增加 friction
- ❌ 呼叫 `EnterWorktree` / `ExitWorktree` —— 已被 `permissions.deny` 攔截，會直接 fail；改走 `git worktree add` / `git worktree remove`
- ❌ `claude --worktree` / `Agent(isolation: "worktree")` —— 都會落 `.claude/worktrees/`（permissions deny 對 CLI flag 跟 isolation 參數無效，得從工作流規範端避免）
- ❌ `git worktree add` 到 `.worktrees/` 以外的路徑 —— 破壞 `.gitignore` 覆蓋
- ❌ `--force` 移除後忘記 `git branch -D` —— 留 dead branch 累積
- ❌ 在 worktree 裡寫到 main 的 `.claude/skills/` —— skill loader 是 CWD-relative，會看到 worktree 的副本，但你要 commit 的是 main，path 會搞混

## Quick reference

| Situation | Action |
|---|---|
| Already in worktree (Step 0 detect) | Skip create, work in place |
| Trigger matches | Step 1 → Step 2 → Step 3 |
| Trigger doesn't match | Stay on main |
| 想切 session 進 worktree | 不要試 `EnterWorktree`（已 deny）；另開 terminal `cd .worktrees/<name> && claude` |
| 同 session 想操作 worktree 內容 | `git -C .worktrees/<name> ...` 或 `(cd .worktrees/<name> && <cmd>)` |
| Subagent 要 isolation | Subagent 自己跑 Step 1，不要用 `isolation: "worktree"` 參數 |
