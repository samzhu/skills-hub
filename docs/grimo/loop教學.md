# Claude Code /loop 自動化教學

## 前置安裝（macOS）

### 安裝 tmux

```bash
# 用 Homebrew 安裝
brew install tmux

# 確認安裝成功
tmux -V
# 輸出類似：tmux 3.5a
```

如果沒有 Homebrew，先安裝：

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

### 安裝 Claude Code

```bash
# 用 npm 安裝（需要 Node.js 18+）
npm install -g @anthropic-ai/claude-code

# 確認安裝成功
claude --version
# 需要 v2.1.72 或更新版本才支援排程任務
```

如果沒有 Node.js：

```bash
# 用 Homebrew 安裝 Node.js 24 LTS
brew install node@24
```

### 首次登入

```bash
# 第一次使用需要登入 Anthropic 帳號
claude

# 按照提示完成 OAuth 登入
# 登入後可以開始使用
```

---

## 快速開始

### Step 0: 設定 .claude/loop.md（只需做一次）

`/loop` 裸執行時會讀取專案根目錄的 `.claude/loop.md` 作為預設 prompt。這個檔案定義了「Claude 每一輪要做什麼」。

在專案根目錄建立 `.claude/loop.md`：

```bash
mkdir -p .claude
cat > .claude/loop.md << 'EOF'
讀取 docs/grimo/specs/spec-roadmap.md，依照以下規則每次處理一個 spec：

1. 如果有 ⏳ 狀態且其依賴都已 ✅ 的 spec，執行 /planning-tasks 完成實作
2. 否則找第一個 🔲 的 spec，執行 /planning-spec 完成設計
3. 前端相關 spec（S002, S004 等）設計前先讀 docs/grimo/ui/README.md 和 docs/grimo/ui/ 的 HTML mockups
4. 每次只處理一個 spec
5. 所有 spec 都 ✅ 後停止

優先順序：先實作（⏳ → ✅），再設計（🔲 → ⏳）。
遵守依賴關係：依賴未完成就不能實作該 spec。
EOF
```

**寫法要點：**
- 用自然語言寫，像在跟 Claude 說話
- 明確指出要讀什麼檔案、執行什麼 skill
- 定義優先順序和停止條件
- 檔案上限 25,000 bytes，保持簡潔
- 修改即時生效（下一輪就會用新 prompt）

**放置位置：**

| 路徑 | 範圍 |
|------|------|
| `.claude/loop.md` | 專案級（優先讀取） |
| `~/.claude/loop.md` | 使用者級（所有沒有專案級的都用這個） |

---

接下來三個步驟啟動：

### Step 1: 開 tmux session

```bash
tmux new -s claude
```

這會建立一個名為 `claude` 的終端 session。之後就算關掉終端視窗，session 還是在背景跑。

### Step 2: 啟動 YOLO 模式

```bash
claude --dangerously-skip-permissions
```

`--dangerously-skip-permissions`（又叫 YOLO 模式）會跳過所有權限確認：
- 讀寫檔案不會問你
- 執行指令不會問你
- 適合自動化場景，但**建議先手動跑一次確認行為正常再開**

一般模式（不加 flag）每次動作都會問你 yes/no，不適合無人值守。

### Step 3: 執行 /loop

```
/loop
```

裸 `/loop` 會自動讀取專案的 `.claude/loop.md`，開始依序設計和實作所有 spec。

### 完成後要回來看？

```bash
# 從任何終端接回 session
tmux attach -t claude

# 想脫離（讓 Claude 繼續跑）
# 按 Ctrl+B，放開，再按 D
```

### 一次 copy-paste 版本

```bash
tmux new -s claude \; send-keys 'claude --dangerously-skip-permissions' Enter
# 等 Claude 啟動後，在 Claude Code 裡輸入：
# /loop
```

---

## /loop 是什麼

`/loop` 讓 Claude 按間隔自動重複執行一段 prompt。適合用在：
- 逐一完成 spec 設計（`/planning-spec`）
- 逐一實作 spec（`/planning-tasks`）
- 監控 CI、PR review、部署狀態

## 三種用法

| 用法 | 範例 | 行為 |
|------|------|------|
| **間隔 + prompt** | `/loop 30m check the deploy` | 固定每 30 分鐘執行一次 |
| **只有 prompt** | `/loop 檢查下一個 pending spec` | Claude 自己決定間隔（動態模式） |
| **裸 /loop** | `/loop` | 讀取 `.claude/loop.md` 的預設 prompt，動態間隔 |

### 間隔格式

| 格式 | 意思 |
|------|------|
| `30s` | 30 秒（會四捨五入到 1 分鐘） |
| `5m` | 5 分鐘 |
| `2h` | 2 小時 |
| `1d` | 1 天 |

### 動態模式（推薦）

省略間隔時，Claude 會根據上一輪結果自己決定等多久：
- 剛完成一個 spec，還有下一個 → 等 1-2 分鐘就繼續
- 沒事做了 → 等更久（最多 1 小時）

**適合 spec pipeline**，因為每個 spec 複雜度不同。

---

## 本專案的 /loop 設定

`.claude/loop.md` 定義了裸 `/loop` 的預設行為：

```
讀取 spec-roadmap.md → 找下一個要處理的 spec → 執行對應 skill
```

### 自動化流程

```
/loop 啟動
    │
    ▼
讀取 spec-roadmap.md
    │
    ├─ 有 ⏳ spec 且依賴都 ✅ → /planning-tasks (實作)
    │                              ↓
    │                         內部 loop: /implementing-task × N
    │                              ↓
    │                         spec → ✅
    │
    └─ 有 🔲 spec → /planning-spec (設計)
                        ↓
                    spec → ⏳
    │
    ▼
回到開頭，處理下一個 spec
    │
    ▼
全部 ✅ → 停止
```

### 實際執行順序（9 個 spec）

| 輪次 | 動作 | 結果 |
|------|------|------|
| 1 | S000 ⏳ → `/planning-tasks S000` | S000 ✅ |
| 2 | S001 ⏳ (S000✅) → `/planning-tasks S001` | S001 ✅ |
| 3 | S002 🔲 → `/planning-spec S002` | S002 ⏳ |
| 4 | S002 ⏳ (S001✅) → `/planning-tasks S002` | S002 ✅ |
| 5 | S003 🔲 → `/planning-spec S003` | S003 ⏳ |
| 6 | S003 ⏳ (S001✅) → `/planning-tasks S003` | S003 ✅ |
| ... | ... | ... |
| ~18 | S008 ⏳ (S006✅) → `/planning-tasks S008` | S008 ✅ |

預估總時間：**8-12 小時**

---

## tmux 常用操作

tmux 讓 Claude 在背景持續運行，即使關掉終端也不會中斷。

### 基本流程

```bash
# 建立 session
tmux new -s claude

# 脫離（Claude 繼續跑）
# 按 Ctrl+B，放開，再按 D

# 回來查看
tmux attach -t claude

# 列出所有 session
tmux ls
```

### 快捷鍵（先按 Ctrl+B 放開，再按第二鍵）

| 按鍵 | 功能 |
|------|------|
| `Ctrl+B` → `D` | 脫離（detach） |
| `Ctrl+B` → `[` | 捲動模式（方向鍵/PgUp 看歷史，Q 離開） |
| `Ctrl+B` → `C` | 新增視窗 |
| `Ctrl+B` → `N` | 下一個視窗 |

---

## 控制 /loop

| 操作 | 方法 |
|------|------|
| **停止** | 在 /loop 等待時按 `Esc` |
| **查看排程** | 輸入「what scheduled tasks do I have?」 |
| **取消特定任務** | 輸入「cancel the XXX job」 |

---

## 進階用法

### 指定間隔跑特定 skill

```
/loop 30m /planning-spec S002
```

每 30 分鐘執行一次 `/planning-spec S002`（通常不需要重複，用於需要等待外部回應的場景）。

### 串接其他 skill

```
/loop 20m /review-pr 123
```

每 20 分鐘檢查 PR #123 的 CI 和 review comments。

### 自訂 loop.md

編輯 `.claude/loop.md` 改變裸 `/loop` 的行為。修改即時生效（下一輪迭代就會用新 prompt）。

| 位置 | 優先級 |
|------|--------|
| `.claude/loop.md` | 專案級（優先） |
| `~/.claude/loop.md` | 使用者級（所有專案的預設） |

---

## 其他排程方式比較

| | `/loop` (CLI) | Desktop 排程 | Cloud Routines |
|---|---|---|---|
| 需要終端開著 | 是 | 否 | 否 |
| 需要電腦開著 | 是 | 是 | 否 |
| 存取本地檔案 | 是 | 是 | 否（用 clone） |
| 最短間隔 | 1 分鐘 | 1 分鐘 | 1 小時 |
| 適合場景 | Session 內輪詢 | 定時本地任務 | 無人值守 |

---

## 注意事項

1. **Session 必須保持開啟** — `/loop` 是 session-scoped，關掉就停了。用 tmux 保持
2. **7 天自動過期** — 重複任務最多跑 7 天
3. **中斷恢復** — `claude --resume` 可恢復未過期的排程
4. **YOLO 模式風險** — `--dangerously-skip-permissions` 跳過所有確認，建議先手動跑一次確認行為正常
5. **loop.md 上限** — 不超過 25,000 bytes
