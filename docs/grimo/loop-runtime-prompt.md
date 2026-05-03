# `.claude/loop.md` Runtime Prompt 設計

本文件給「設計 / 維護 cron loop runtime 提示詞」的人類閱讀。loop runtime 指令（agent 每 tick 讀的）放在 `.claude/loop.md`，**本檔不會被 agent 讀**。

> 若你在找 `/loop` 怎麼用 / 怎麼安裝 tmux / claude code，看 `loop教學.md`。

---

## 1. loop.md 何時被載入

| 觸發方式 | loop.md 是否載入 |
|---|---|
| `/loop`（無 inline prompt） | ✅ 被當作預設 prompt 載入 |
| `/loop 5m /<some-command>` | ❌ 不載入；每 tick 跑指定 command |
| `/loop 5m "free-form text"` | ❌ 不載入；每 tick 跑該 text |

機制細節：

- Project 路徑優先：`.claude/loop.md` > user 路徑 `~/.claude/loop.md`
- Hard size limit：**25,000 bytes**，超過會被 truncate
- Edit 立即生效：下個 tick 自動重讀，不必重啟 loop
- Cron lifetime：7 天 auto-expire，或 `CronDelete <id>` 手動停

---

## 2. 結構設計取捨

### 角色 + Chrome MCP 放開頭

agent 第一眼看到「我是誰、我有什麼工具」，後續決策樹才有 context。Chrome MCP 是 deferred tool — 不提醒呼叫前要 `ToolSearch` 載 schema 會卡 IO。

### 三條硬性規則用 IMPORTANT + YOU MUST

per Anthropic 官方建議：「You can tune instructions by adding emphasis (IMPORTANT / YOU MUST) to improve adherence」。其他內文 narrative 用一般 markdown，emphasis 留給真正 critical 規則。

### 決策樹用表格而非 if-then prose

LLM 對 numbered table 的 short-circuit 行為遵循度比 prose 高。表格 3 列（條件 / 動作 / Mode）+ 明文「遇到第一個 match 就停」，避免 agent 把多個條件當作平行 OR 處理。

### Mode A 六階段用 code block

Anthropic 官方範例所有 multi-step workflow with checkpoints 都是 code block。視覺強化「這是 atomic stages」而非 advice。

### 用 `## SECTION` 標準 markdown header

舊版用 `═══ SECTION ═══` 視覺分隔，但 model 解析 markdown structure 比 ASCII art 穩。標準 header 也較能撐過 `/compact` 後 re-injection。

---

## 3. 三條操作原則的歷史

### Loop-Hint-Verify 為什麼存在

cron tick 觸發 prompt 是寫死的字串（人類設定 cron 時的 hint）。這個 hint 在後續 ticks 漸漸與實況脫鉤 — 例如「Round X 還缺反例」hint 可能在 tick 觸發時其實上個 tick 已補完。

觀察到 agent 容易盲信 hint 直接做，做了重複工作。所以加這條原則：先驗證再行動。

### Spec-Only-Handoff 為什麼存在

某些情境 user 只想要 spec 設計（要 review / 要 grill），不想 agent 直接 implement。明確分工：人類 review spec → cron 接手 implement。

不寫成原則的話，agent 容易看到「寫 spec」就一路 implement 到底，違反 user 意圖。

### No-Spec-Means-E2E + Mode B Drift 為什麼存在

S100e 案例：當時 backlog 仍有多個 📋 sub-spec 未設計，agent 卻連續 ticks 跑 Mode B audit 找不到 bug，浪費 wall。實際正確路徑是回去寫 backlog spec doc。

Mode B 是「全 spec 都 designed」的 fallback，不是 default。

---

## 4. EXIT SATURATED 為什麼移除（per user directive 2026-05-03）

> 「按照我的設計應該是定時起來執行任務 spec 開發 & 任務，沒有 spec 就自己進行檢查、完整 E2E 跟邊緣案例測試才對，不應該有停下來發生」

cron loop 設計上是持續性 audit-watchdog，不該因為「現在好像沒事做」自然終止。原本的 SATURATED label 會 terminate loop，與此設計衝突，移除。

唯一停止條件：user 明示「停」/ `CronDelete <id>` / 7 天 auto-expire。

---

## 5. 與 CLAUDE.md `Finish-Current-First` 的關係

CLAUDE.md 有 `Finish-Current-First`：「user mid-flight 提新需求 → ack → 先收尾當前 → 再啟動新需求」。

loop.md 的 Spec-Only-Handoff 看似衝突（「user 寫設計 → 停 → 下 tick 接手」聽起來像中斷），其實不衝突：

- **Finish-Current-First**：中斷時收尾現任
- **Spec-Only-Handoff**：明確分工 — 人寫設計、agent 寫 code

兩者互補，不重疊。

---

## 6. ALWAYS / NEVER 的「可驗證」原則

每條 ALWAYS / NEVER 都應該是「對任何 tick output 可驗證的 assertion」。寫成 vague advice（「要小心」「盡量」）模型不會 follow。

| 形式 | 範例 | 評估 |
|---|---|---|
| ✅ 可驗證 | ALWAYS 改 public signature 前 grep production AND test code | 從 git diff + grep history 可驗證 |
| ✅ 可驗證 | NEVER 跳過 spec doc §7 Result | 從 archive 中 spec doc 可驗證 |
| ❌ 不可驗證 | ALWAYS 注意效能 | 沒有 concrete check |
| ❌ 不可驗證 | NEVER over-engineer | 主觀判斷 |

新增規則前先問：「下次有人 review tick output 時，能不能客觀說『這條被遵守 / 違反』？」

---

## 7. 維護 loop.md 的指南

修改 loop.md 時：

- **保持 < 200 行 / 25KB**：超過 hard limit 會 truncate
- **新規則寫 testable**：能從 git / output 客觀驗證
- **不重複 CLAUDE.md 既有規則**：CLAUDE.md 是 baseline，loop.md 補 cron-specific
- **歷史 / rationale 寫進本檔**：loop.md 只放 prompt，不放教學
- **改完不必重啟 cron**：下個 tick 自動重讀

---

## 8. 進一步閱讀

- Anthropic 官方文件 — Memory & CLAUDE.md：https://code.claude.com/docs/en/memory.md
- Anthropic 官方文件 — Skills authoring：https://code.claude.com/docs/en/skills.md
- Anthropic 官方文件 — Scheduled tasks：https://code.claude.com/docs/en/scheduled-tasks.md
- Anthropic 官方文件 — Best practices：https://code.claude.com/docs/en/best-practices.md
