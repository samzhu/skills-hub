# Loop-Driven E2E Testing & Ship Methodology

> 一個自動執行的測試 / 修 bug / 發版工作流。已在 Skills Hub 累積 ~30 cron ticks / 17 specs shipped / 13 bugs (A→AM) / 0 active bugs 驗證有效。
>
> 設計目的：在系統持續演進中保持「找 bug → 修 → ship」的固定節奏；用 cron 機械化開頭，讓 testing surface 慢慢被掃光。

---

## §1 核心結構

```
/loop 10m <test prompt>
   │
   ├── 觸發 1：cron tick N 載入 prompt + 既有 progress 狀態
   ├── 接續上輪未測 round（從 .claude/progress/ 讀）
   ├── 跑 round（正例 / 反例 / 邊緣 各 ≥1 case）
   │     │
   │     ├── 全 PASS → 記 progress，cron 自動下次再觸發
   │     └── 發現 bug
   │            ├── 寫 spec：YYYY-MM-DD-S0XX-<topic>.md
   │            ├── 研究修法（Web verify / framework source 深探）
   │            ├── 實作 + run test suite
   │            ├── 重啟服務 smoke test
   │            └── ship：commit + CHANGELOG + roadmap + archive spec
   │
   └── 觸發 N+1：cron 10 分鐘後重複，讀更新後 progress 接續
```

---

## §2 持久化狀態（survive cron ticks 邊界）

兩個 markdown 檔案存放整個 loop 的 memory：

### `.claude/progress/loop-e2e-test-coverage.md`

```
# Loop E2E Test Coverage Log
> Latest tick: <N> (<date>) — <one-line summary>

>   tick N (cron 10m <id>, <topic>, <date>):
>     R<round> (<m> cases — <category>):
>     - <case 1 desc> → result ✓
>     - <case 2 desc> → ❌ found Bug A<X>
>     **Bug A<X>**：<root cause + fix + ship details>
>     **設計領悟**：<reusable insight>
```

每 tick 在最後一段 **append（不改舊紀錄）**，讓 coverage 隨時間累積。Tick log 同時當作 retrospective material。

### `.claude/progress/test-case.md`

```
## Tick N — Round R: <topic> (<date>)
| # | 類別 | Case | Result | Spec |
| R.1 | 正例 | ... | PASS | — |
| R.2 | 反例 | ... | PASS 400 | (S0XX-related) |
| R.3 | 邊緣 | ... | **FAIL → ship S0XX** | S0XX v2.X.X |
```

每 round 一個 markdown 表，case 數 + PASS/FAIL + 對應 spec 編號。供 future audit 與 recurrence detection。

---

## §3 Round 設計三類覆蓋

每 round **必含三種 case**：

| 類別 | 目的 | 範例 |
|------|------|------|
| **正例** | 確認 happy path 行為 | upload 標準 SKILL.md → 201 |
| **反例** | 確認 fail 路徑訊息正確 | 缺欄位 / 不合 regex → 400 + helpful error |
| **邊緣** | 探 boundary / race / 罕見組合 | 64 char max / 65 over / concurrent N=10 / SUSPENDED state |

**為什麼三類**：
- 只測正例 → 永遠不知道 fail mode
- 只測反例 → 不確認系統能正常工作
- 沒邊緣 → 找不到 lost-update / off-by-one / state-machine race（這類 bug 占本案 13 個 bug 的 9 個）

---

## §4 Bug Ledger 編號 + Spec 編號

```
Bug ledger：A → B → C → ... → AM（連續英文字母，never recycle）
Spec ID：S001 → S002 → ... → S0XX（連續數字，按 ship 時間）
Milestone：M01 → M02 → ...（roadmap 對應）
Version：semver vX.Y.Z（CHANGELOG）
```

每 ship 同時更新四個編號。Bug ledger 字母順序 = 發現順序，方便回溯「Bug AK 是哪個 tick 找到的」。Spec 編號跟 Milestone 一對一。

---

## §5 Ship Pipeline（每個 spec 走一次）

```
1. 寫 spec doc        → docs/grimo/specs/YYYY-MM-DD-SXXX-<topic>.md
                       含 §1 Problem / §2 Approach / §3 AC / §4 Fix / §5 Test / §6 Verify / §7 Result
2. 實作               → 最小 diff；不夾帶其他改動
3. Run full test suite → 確認 0 regression
4. Restart service    → bootRun / npm dev 等
5. Smoke test         → 真實 curl / Chrome 行為驗證；填回 spec §7 Result
6. Update CHANGELOG   → top 加 v2.X.X entry，含 Problem 簡述 + Fix 簡述 + Verification
7. Update roadmap     → 加 Phase X | M0X: <topic> | S0XX | size | sequence | ✅ vX.X.X (date — 一句話 fix 摘要)
8. Archive spec       → mv docs/grimo/specs/2026-XX-XX-SXXX-*.md docs/grimo/specs/archive/
9. Commit             → conventional commit (feat: / fix: / polish: / chore: / docs:)
                       單 spec 一個 commit；不夾帶他事
10. Update progress   → tick log 加 entry；test-case.md 加表
11. Commit progress   → 第二個 commit (docs:)
```

每個 spec **2 個 commits**：一個 spec ship，一個 progress 更新。Git log 重看時非常乾淨。

---

## §6 Saturation 訊號（何時 pivot）

連續 N ticks 都 0 bugs → testing surface 飽和。可選 pivot 方向：

1. **Polish round** — 清累積的 tech debt（known limitations log）
2. **New feature** — user-driven request（如 file browser / design tokens）
3. **Audit sweep** — 同 pattern 漏洞 architectural sweep（lost-update audit 找出 Bug AL）
4. **Cross-endpoint consistency** — 跨 endpoint API shape audit（找出 Bug AM missing-param error）

不要硬找 bug。Saturation = 系統穩定的訊號，是好事。

---

## §7 Stacked User Request 處理（Finish-Current-First）

User mid-flight 提新需求時：

```
1. acknowledge — 一句話「收到，先收尾當前 X」
2. complete current — 跑完現在 spec 的 ship pipeline 全 11 步
3. queue or pivot — 把新需求加進 backlog 或下個 cron 處理
4. NEVER overlap — 不開 parallel 半成品；avoid context 丟失 / PR 混雜
```

此原則寫進 `CLAUDE.md` Principles 持久化。本案 7 個 stacked user requests 全照此 pattern serial 處理，無遺漏無 overlap。

---

## §8 設計領悟模式（從 13 個 bug 萃取）

| Pattern | 範例 |
|---------|------|
| **Lost-update audit** | aggregate 欄位 + atomic SQL 寫入 → 必加 `@ReadOnlyProperty`（Bug AK / AL） |
| **Binding-time fall-through** | `MissingServletRequestParameterException` 不被 `@ExceptionHandler` 自動接（Bug AM） |
| **Theme physics** | npm package light theme 在白背景物理上做不出 glow（Bug AH 後續 S089） |
| **Framework hook leak** | Spring Data JDBC `Persistable.isNew()` 序列化進 API JSON（Bug AA / AI 兩次） |
| **State-machine guards 對稱** | suspend → reactivate 兩個方向都要 409（R23.5 vs R1） |

「同 pattern 兩次出現」是 **architectural sweep** 訊號 — 找出整類同 root cause 的漏洞一次掃光（如 Bug AK 出現後 audit 找出 AL 的 risk_level 同 pattern）。

---

## §9 適用前提

| 條件 | 為何重要 |
|------|---------|
| **dev permit-all** | testing 不被 auth 擋住；MVP 階段 Feature First, Security Later |
| **可測 service running** | curl-able backend + Chrome-controllable frontend |
| **持久化 progress 檔案路徑固定** | cron 跨 ticks 讀寫同一份 markdown |
| **Conventional commits** | git log 可被機器讀解析「ship vs not」 |
| **Sequential spec ID** | bug ledger ↔ spec ↔ milestone ↔ version 四元組對應 |

---

## §10 反模式（避免）

| 反模式 | 為何避 |
|--------|--------|
| 一個 commit 夾兩個 spec ship | git log 變難讀；retro 抓 root cause 變慢 |
| Skip §7 Result 不填回 | 半年後沒人記得這 spec 怎麼驗的 |
| Test 失敗硬 push | regression 滲入 main；下次 ship 出問題追不到根因 |
| Cron prompt 亂改 | 改 prompt = 改 contract；下次 cron 讀的 progress 內容對不起來 |
| Bug ledger 跳號 | A→C→D 看起來像漏了 B；保連號可信度高 |
| 並行多個 in-flight specs | spec doc 互相 reference 變 spaghetti；半成品累積 |

---

## §11 整體效能（本案實測）

```
~30 cron ticks（10 min interval）
17 specs shipped（S073-S089）
13 bugs found + fixed（A→AM）
4 polish ships
1 META spec planning（S084）
0 active bugs
```

平均：每 ~2 ticks 1 個 ship。Bug discovery rate 隨 testing surface 飽和遞減（前 10 ticks 多 / 後 10 ticks 少）→ 觸發 pivot 訊號。

---

## §12 移植到其他專案

最小安裝：
1. 兩個檔案 `.claude/progress/loop-e2e-test-coverage.md` + `test-case.md` 起點為空
2. 一個 prompt template（內含「接續上輪未測 round / 三類覆蓋 / bug→spec→ship」instructions）
3. `/loop 10m <prompt>` 啟動
4. CLAUDE.md 記 Finish-Current-First principle
5. 約定 spec naming + commit convention + progress 寫入位置

剩下交給 cron 自動跑，需要時人工 inject user-driven requests（per §7 流程）。

---

## §13 已知限制

- **視覺 regression** 自動化測試難（chrome screenshot diff 還沒接）— 靠 `npm test` + 視覺 smoke 補
- **跨 host 並發** dev 環境 timing 太緊重現不到 — Bug AL preemptive 用 architectural sweep 替代 reproduce
- **Cron prompt 太長** 容易爆 cache window — 每 5 min cache TTL 理論上每 tick 都 cache miss；實務上 conversation summary 機制幫忙壓
- **/loop 7-day auto-expire** — 長期跑要 user 重啟（或改 schedule 雲端版）
