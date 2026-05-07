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
| `claude --worktree <name>` | 同上，CLI 起手就建 |
| `Agent(isolation: "worktree")` | tool 參數無 path 欄位 |

要用 native tool 切入 session，只能用 `EnterWorktree(path: <絕對路徑>)` 進入**已存在**的 worktree。

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
cd .worktrees/$NAME
```

需要把當前 Claude Code session 切進去（不重啟）：

```
EnterWorktree(path: "<absolute-path-to-.worktrees/$NAME>")
```

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
- ❌ `EnterWorktree(name:)` / `claude --worktree` / `Agent(isolation:)` 建新 worktree —— 全部 fall back `.claude/worktrees/`
- ❌ `git worktree add` 到 `.worktrees/` 以外的路徑 —— 破壞 `.gitignore` 覆蓋
- ❌ `--force` 移除後忘記 `git branch -D` —— 留 dead branch 累積
- ❌ 在 worktree 裡寫到 main 的 `.claude/skills/` —— skill loader 是 CWD-relative，會看到 worktree 的副本，但你要 commit 的是 main，path 會搞混

## Quick reference

| Situation | Action |
|---|---|
| Already in worktree (Step 0 detect) | Skip create, work in place |
| Trigger matches | Step 1 → Step 2 → Step 3 |
| Trigger doesn't match | Stay on main |
| 想要 native tool 體驗 | 只用 `EnterWorktree(path: ...)` 進已存在的 worktree |
| Subagent 要 isolation | Subagent 自己跑 Step 1，不要用 `isolation: "worktree"` 參數 |
