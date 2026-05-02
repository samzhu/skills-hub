# Claude Code Loop 自動化教學

> 把 Claude Code 變成自動化工程師：用 cron + roadmap 兩條 loop 持續找 bug、修 bug、推進 spec，直到 backlog 清空。
>
> 已在 Skills Hub 累積實戰：~40 cron ticks / 23+ specs shipped / 14 bugs (A→AN) / 0 active bugs。
>
> 配套理論：`docs/grimo/loop-testing-methodology.md`。本檔聚焦**操作教學 + 可貼上的提示詞**。

---

## 何時用哪條 loop

| 你想做的事 | 用哪條 loop | 啟動命令 |
|------------|-------------|----------|
| 找出 bug + 直接 fix + ship | **A. E2E 測試 loop** | `/loop 10m <test prompt>` |
| 把 roadmap 中所有 backlog spec 推到 ✅ | **B. Spec marathon** | 直接貼 prompt（無 cron）|
| 兩者交錯：先測再推 spec，repeat | 切換用 — 不要同時 in-flight | （手動切換） |

**重要原則**：兩條 loop 不能同時跑。若 Loop A 在進行，要先 `CronDelete <id>` 才能切到 Loop B。否則 cron firing 會打斷 spec 設計流程。

---

## Loop A：E2E 測試 + 修 bug + ship（cron 驅動）

### 啟動

```
/loop 10m
<貼下方標準 prompt>
```

### 標準 Prompt（可直接複製貼上）

```
<專案名稱> 完整 E2E 測試（dev permit-all、<其他全域前提>）：
- 從 .claude/progress/loop-e2e-test-coverage.md 與 .claude/progress/test-case.md 接續上輪未測 round
- 每個 round 涵蓋【正例 / 反例 / 邊緣案例】三類
- 發現 bug → 自寫 spec → 研究修法 → 實作 → 測試 → 沒問題就 release（commit + CHANGELOG + roadmap）
- 一邊記錄：
  - 進度寫 .claude/progress/loop-e2e-test-coverage.md（tick 累積、bug ledger A-…、known tech debt）
  - 案例寫 .claude/progress/test-case.md（按 round 分類，每筆標 PASS/FAIL、對應 ship 的 spec 編號）
```

### 啟動前檢查清單

```
1. 兩個進度檔案存在（可空）：
   - .claude/progress/loop-e2e-test-coverage.md
   - .claude/progress/test-case.md
2. CLAUDE.md 含 Finish-Current-First 原則
3. backend / frontend 服務跑得起來
4. spec-roadmap.md 存在且最後一個 spec ID 已知
5. git 工作區乾淨（沒有 uncommitted changes 累積）
```

### Cron tick 流程（Claude 自己跑，不需 user 介入）

```
每 10 分鐘 cron 觸發 → Claude 接收 prompt + load context
   │
   ├── 讀 .claude/progress/*.md 知道上輪測到哪
   ├── 決定下一 round 的測試範圍（聚焦 untested surface）
   ├── 寫測試 script（curl / Python / Chrome JS）
   ├── 跑 → 觀察結果
   │
   ├── If 發現 bug:
   │     1. 寫 spec doc YYYY-MM-DD-SXXX-<topic>.md
   │     2. 研究 root cause
   │     3. 實作 fix（最小 diff）
   │     4. test suite 跑過
   │     5. 重啟服務 smoke test
   │     6. fill spec §7 Result
   │     7. 更新 CHANGELOG（vX.Y.Z bump）
   │     8. 更新 spec-roadmap.md 那行 → ✅
   │     9. archive spec
   │     10. commit (feat: / fix:)
   │     11. update progress logs
   │     12. commit (docs:)
   │
   └── If 0 bug:
         記 progress（這 round PASS）→ 等下次 cron
```

### Saturation 訊號（連續 ≥3 ticks 0 bugs）

切換到 polish ship、audit sweep 或 Loop B。不要硬找 bug。

### 停 cron

```
CronDelete <job-id>     # 例 fc4a79bb
```

時機：要做 user-driven feature spec、cron prompt 要更新、backend 重構中、connect 5+ ticks 0 bugs 且 polish 已清完。

---

## Loop B：Spec marathon — 把所有 active specs 推到 ✅（roadmap 驅動）

### 啟動

直接告訴 Claude（**沒有 cron** — 是 manual marathon，但 Claude 自己連續跑直到 done）：

```
讀 docs/grimo/specs/spec-roadmap.md 推進所有 active specs 直到全部 ✅
```

或更具體版本：

```
讀 docs/grimo/specs/spec-roadmap.md 中所有狀態 📋/📐/🚧 的 spec，
照 dependency 順序逐一 ship（每個獨立 commit），
每 spec 走完整 ship pipeline（spec doc / impl / test / smoke / CHANGELOG /
roadmap row update / archive / commit），
直到 grep "📋|📐|🚧" 回 0 行為止。
```

### 啟動前檢查清單

```
1. spec-roadmap.md 存在且 active rows 用 status icon 標示（📋 backlog / 📐 in-design / 🚧 in-flight / ⏸ blocked / ✅ shipped）
2. 每個 active row 有：spec ID + topic + size + 一句話 description
3. 對應的 prototype / reference material 已存在（如 docs/prototype/*.html、設計圖、API spec 連結）
4. backend / frontend 跑得起來（以便 smoke test）
5. 上一個 cron loop（若有）已 CronDelete
```

### Spec ordering 啟發式（Claude 自己決定，但有規則）

```
1. META specs（如 S084）— 純 doc 直接標 ✅
2. Foundation / dependency 先（如 BeamFrame → SearchBar → HomePage）
3. 共享 components 抽取 spec 先（reusable IconTile / Pill / Callout）
4. 同 size 內：最常見入口優先（HomePage > SubPage）
5. Risky / large 留最後（前面 momentum 累積）
```

### Per-spec ship pipeline（每個 spec 走一次）

```
1. Plan
   - 讀 prototype / reference material
   - 讀現有 code（rework）
   - 列 minimum viable diff
   - 列 Acceptance Criteria（≥3 cases 涵蓋 happy + edge + error）

2. Implement
   - 單一 spec 一個 PR / commit
   - No drive-by refactors
   - 註解寫 why（不寫 what — code 自己會說）

3. Verify
   - test suite 跑過（npm test / gradlew test）
   - smoke test：curl / Chrome 真實 flow
   - UI rework 加視覺截圖比對

4. Document
   - spec doc §1 Problem / §2 Approach / §3 AC / §4 Fix / §5 Test plan / §6 Verify / §7 Result
   - §7 Result 含實測 metrics（test count, bundle size, smoke screenshot ref）

5. Persist
   - CHANGELOG entry（top, semver bump）
   - roadmap row 那行：📋 → ✅ + 累計 points + version + (date — 一句話 fix 摘要)
   - archive spec：mv to docs/grimo/specs/archive/

6. Commit
   - 單 spec 一個 commit
   - Conventional message: feat: / fix: / polish: / chore: / docs:
   - Co-Authored-By trailer
```

### 啟動後 Claude 行為

Claude 會自己連續跑這個流程，不停下來等 user，直到 grep "📋|📐|🚧" 為 0 行。每 1-2 個 spec 給一個簡短 status update（不要 spam）。

如果遇到 blocker（API 錯誤、重啟失敗、smoke test 連續失敗），會停下來問 user。

### Stacked user request 處理

User 在 marathon 中插話新需求時：

```
1. acknowledge — 一句話「收到，先收尾當前 SXXX」
2. complete current — 跑完當前 spec 的 ship pipeline 全 6 phases
3. queue or pivot — 把新需求加進 backlog（寫成 row 進 roadmap）或下個 spec 處理
4. NEVER overlap — 不開 parallel half-done
```

---

## 兩條 Loop 共用的 5 個關鍵檔案

| 檔案 | 用途 | Loop A | Loop B |
|------|------|--------|--------|
| `CLAUDE.md` | Principles（Finish-Current-First etc.） | ✓ | ✓ |
| `docs/grimo/specs/spec-roadmap.md` | spec backlog + ship history | (write) | **(read primary)** |
| `docs/grimo/specs/archive/` | shipped specs 永久存放 | (write) | (write) |
| `docs/grimo/CHANGELOG.md` | 給 user 看的 release notes | (write) | (write) |
| `.claude/progress/*.md` | tick 累積 + test cases | **(read+write primary)** | (touch only when bug found) |

---

## 標準提示詞庫

### ⭐ U.1 — 統一 Loop（Spec 推進 + E2E 測試合一；建議首選）

**直接複製貼上即可用** — 一個 prompt 同時管 (a) 把 roadmap 中所有 active specs 推到 ✅、(b) 對未飽和 surface 做 E2E 測試 + 發現 bug 順手 ship。Tick 內**自動切換**模式，避免兩條 loop 並存。

```
/loop 10m
<產品名稱>（<前提：dev permit-all / 預設權限狀態 etc.>）統一 loop —
每個 tick **先 spec 推進，再 E2E 測試**，serial 不 overlap：

═══ 共用持久化檔案 ═══
- docs/grimo/specs/spec-roadmap.md — spec backlog + status icon (📋/📐/🚧/⏸/✅)
- docs/grimo/specs/archive/ — 已 ship 的 spec doc 歸檔
- docs/grimo/CHANGELOG.md — semver release notes
- .claude/progress/loop-e2e-test-coverage.md — tick 累積 + bug ledger A-…
- .claude/progress/test-case.md — 按 round 分類 case 表（PASS/FAIL + 對應 spec）

═══ Tick 演算法（每次觸發跑一次） ═══
1. **Check active specs**: `grep -E "📋|📐|🚧|⏸" docs/grimo/specs/spec-roadmap.md`
   排除標題行（`## 📋 Status Summary` 之類）。
2. **若有 active spec** → 進 Mode A（spec 推進）
3. **若無 active spec** → 進 Mode B（E2E 測試）
4. 一個 tick 做完一件事就結束；下次 cron 自動接續

═══ Mode A — Spec 推進 ═══
依 dependency 順序選 1 個 spec（META 先 / foundation 先 / 共享 component 抽取先 / size 小先）。
走完整 ship pipeline：
  Plan: 讀 spec doc + prototype/reference + 列 minimum diff + AC ≥3 cases
  Implement: 單 spec 1 commit；no drive-by refactor；註解寫 why
  Verify: test suite 跑過 + 重啟服務 + smoke (curl/Chrome)
  Document: spec doc §1-§7（§7 Result 含實測 metrics）
  Persist: CHANGELOG entry + roadmap row（📋→✅ + 累計 points + version + 一句話 highlight）
         + archive spec to docs/grimo/specs/archive/
  Commit: feat: / fix: / polish: / chore: / docs:（Conventional + Co-Authored-By trailer）

═══ Mode B — E2E 測試 ═══
從 .claude/progress/loop-e2e-test-coverage.md 與 test-case.md 接續上輪未測 round。
每個 round 涵蓋【正例 / 反例 / 邊緣案例】三類。
- 發現 bug → 切 Mode A（自寫 spec → 研究修法 → 實作 → 測試 → ship）；下個 tick 再回 Mode B
- 全 PASS → 記 progress（這 round 結束）→ 等下次 tick

═══ Saturation pivot ═══
連續 ≥3 ticks 0 bugs 且無 active spec → testing surface 飽和 + spec 清空 → loop 自然終結。
最後一 tick 印 final summary（specs / bugs / version / metrics）並停 ScheduleWakeup。

═══ Stacked user request ═══
User mid-flight 提新需求時：
1. acknowledge「收到，先收尾當前 X」
2. complete current（spec ship 全 6 phases / round 全 3 類）
3. queue：把新需求加進 roadmap 為 SXXX 📋 backlog row（讓下個 tick 自然接到）
4. NEVER overlap parallel half-done

═══ 觸發暫停 loop（不 ScheduleWakeup）回報 user ═══
- /planning-spec 進 grill 階段需要 user 答 a/b/c
- /verifying-quality 回 REJECT-BLOCKED（testability gap）
- POC HALT（baseline 不在預期區間）
- Spec scope 模糊需重設計
- Build / smoke 連續 2 次失敗無法自解
```

**啟動前檢查**：
1. 5 個持久化檔案存在（progress 可空）
2. CLAUDE.md 含 Finish-Current-First 原則
3. backend / frontend 服務跑得起來
4. roadmap 有 status icon 標示

---

### A.1 — 純 E2E 測試 loop（無 spec 推進）

如果你只想跑 testing 不碰 spec marathon，用這個短版：

```
/loop 10m
<產品名稱> 完整 E2E 測試（<前提>）：
- 從 .claude/progress/loop-e2e-test-coverage.md 與 .claude/progress/test-case.md 接續上輪未測 round
- 每個 round 涵蓋【正例 / 反例 / 邊緣案例】三類
- 發現 bug → 自寫 spec → 研究修法 → 實作 → 測試 → 沒問題就 release（commit + CHANGELOG + roadmap）
- 一邊記錄：
  - 進度寫 .claude/progress/loop-e2e-test-coverage.md（tick 累積、bug ledger A-…、known tech debt）
  - 案例寫 .claude/progress/test-case.md（按 round 分類，每筆標 PASS/FAIL、對應 ship 的 spec 編號）
```

### A.2 — Polish round（saturation 後切換）

```
連續 N ticks 0 bugs，testing surface 飽和。pivot 到 polish ship：
讀 .claude/progress/loop-e2e-test-coverage.md 的 「Polish Candidates」 / 「Missing Features」 列表，
挑 ROI 最高的 1 個 ship（spec doc + impl + test + CHANGELOG + roadmap row + archive + commit）。
不要硬找 bug；polish 完繼續 testing 直到下次 saturation。
```

### A.3 — Architectural sweep（同 pattern 兩次出現後）

```
Bug AX 是 <pattern 描述>。對其他 aggregate 欄位 / API endpoint / component 做同 pattern audit：
- grep 出所有 <relevant pattern>
- 比對是否有相同 root cause
- 一次 ship 全部修法（每個 sub-fix 獨立 commit）
- 寫 audit summary 到 progress log
```

### B.1 — Spec marathon 啟動

```
讀 docs/grimo/specs/spec-roadmap.md 推進所有 active specs 直到全部 ✅。
每個 spec 走完整 ship pipeline（spec doc / impl / test / smoke / CHANGELOG / roadmap row / archive / commit）。
依 dependency 順序：META → foundation → consumer。
1 spec = 1 commit。連續跑直到 grep "📋|📐|🚧" 為 0 行。
遇 blocker（test fail / build error / 預期外 API 行為）停下來問。
```

### B.2 — Spec marathon 中插話新需求

```
收到。Per Finish-Current-First：先收尾當前 SXXX（test + ship + commit），
再啟動新需求 <description>。新需求加到 roadmap 為 SYYY 📋 backlog row。
```

### B.3 — 單一 spec 深度設計（不馬上 ship）

```
/planning-spec SXXX <topic>
```

啟動 planning-spec skill — 走完整 SA/SD 流程（Phase 1 context / Phase 2 research with parallel agents / Phase 3 grill + design + spec doc）。產出 spec doc，不 ship。`/planning-tasks SXXX` 才開始 ship。

---

## 常見故障排除

| 症狀 | 原因 | 修法 |
|------|------|------|
| Cron tick 觸發但沒做事 | prompt 太長爆 cache window | 縮短 prompt；或改 manual 啟動 |
| 同個 bug 連續 3 個 tick 都找到 | progress log 沒寫對；tick 沒讀 progress | 檢查 progress 路徑；強制重讀 |
| Marathon 中 spec 互相 reference | 並行 in-flight 違反 Finish-Current-First | 取消最近 commit；改成 serial |
| Smoke test 過但 user 看不到 | vite HMR / cache 問題 | hard refresh / `npm run build` 確認 |
| Backend 重啟卡 | gradle daemon stuck | `./gradlew --stop` 清 daemon |
| Chrome extension 斷線 | 自動 disconnect | browser refresh + retry |
| Test 過但 smoke 失敗 | tests mock 部分（hooks / event listener）沒覆蓋 | 加 integration test 補 |

---

## 移植到其他專案的最小安裝

```
專案根目錄/
├── CLAUDE.md                              ← 加 Finish-Current-First principle
├── .claude/
│   └── progress/
│       ├── loop-e2e-test-coverage.md      ← 起點為空
│       └── test-case.md                   ← 起點為空
├── docs/
│   └── grimo/
│       ├── CHANGELOG.md                   ← 已有 v0.0.1 起頭
│       ├── loop-testing-methodology.md    ← 從本專案拷貝
│       ├── claude-code-loop-tutorial.md   ← 從本專案拷貝（本檔）
│       └── specs/
│           ├── spec-roadmap.md            ← 列 active backlog with status icon
│           └── archive/                   ← 空目錄
└── 約定:
    - Sequential spec ID（S001, S002, ...）
    - Bug ledger 連號（A, B, C, ...）
    - Conventional commits
    - 1 spec = 1 commit
    - semver in CHANGELOG
```

完成上述後可直接：
1. Loop A：`/loop 10m <test prompt>`（用 A.1）
2. Loop B：直接貼 B.1 prompt

---

## 兩條 loop 累積的設計領悟（必看）

### 1. Anti-pattern 列表 ≥ 正面定義
LLM-driven engine（如 Skills Hub LlmJudge）calibration：必須**同時**寫「what is HIGH」和「what is NOT a finding」。光有正面定義會讓 LLM 預設行為（找問題）覆蓋你的限縮意圖。

### 2. 1-shot prompt fix 的 leverage
LLM-driven 的 calibration bug 改 prompt 一段就能 fix 整類 over-classification。比 rule-based 改十幾條規則快得多。**代價**：LLM 非 deterministic，需 regression sweep 確認沒誤殺真風險。

### 3. Saturation 不等於沒 bug
連續 5 ticks 0 bugs 不代表系統完美。本案 Bug AN 是在 saturation 5 ticks 後，因「new engine 啟用 + corpus re-scan」才浮現。Saturation pivot 到 polish 是好策略，但仍要週期性 cross-system audit。

### 4. Lost-update audit 是 architectural sweep
aggregate 欄位 + atomic SQL 寫入 → 必加 `@ReadOnlyProperty`（per Spring Data JDBC）。同 pattern 兩次出現（download_count → risk_level）就要做 sweep 找出整類同模式漏洞，一次掃光。

### 5. Stack-not-overlap
Half-done specs 累積會讓 git log / spec docs / progress logs 互相 reference 變 spaghetti。Finish-Current-First 是強約束，不是建議。

### 6. Roadmap 是 contract
Spec ID / version / status icon 形成四元組（bug ledger ↔ spec ↔ milestone ↔ version）。漏掉一個更新就破壞下次 cron 讀取的正確性。
