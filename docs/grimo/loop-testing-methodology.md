# Loop-Driven E2E Testing & Ship Methodology

> 兩個互補的自動執行工作流：(A) cron-driven testing → bug → ship；(B) roadmap-driven spec advancement → all active → ✅。
> 已在 Skills Hub 累積 37+ cron ticks / 23+ specs shipped / 14 bugs (A→AN) / 0 active bugs 驗證有效。
>
> 設計目的：在系統持續演進中保持兩種節奏並行：
> - **(A) Bug 驅動**：「找 bug → 修 → ship」用 cron 機械化開頭，讓 testing surface 慢慢被掃光
> - **(B) Spec 驅動**：「讀 roadmap → 推進 active → ship → 標 ✅」直到清空 backlog

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

## §8 設計領悟模式（從 14 個 bug 萃取）

| Pattern | 範例 |
|---------|------|
| **Lost-update audit** | aggregate 欄位 + atomic SQL 寫入 → 必加 `@ReadOnlyProperty`（Bug AK / AL） |
| **Binding-time fall-through** | `MissingServletRequestParameterException` 不被 `@ExceptionHandler` 自動接（Bug AM） |
| **Theme physics** | npm package light theme 在白背景物理上做不出 glow（Bug AH 後續 S089） |
| **Framework hook leak** | Spring Data JDBC `Persistable.isNew()` 序列化進 API JSON（Bug AA / AI 兩次） |
| **State-machine guards 對稱** | suspend → reactivate 兩個方向都要 409（R23.5 vs R1） |
| **LLM default 是「找問題」** | 不給 severity 分級指引時，theoretical concerns 全標 HIGH（Bug AN）— prompt 必須定義「what counts as HIGH」+ anti-pattern 列表「what is NOT a finding」 |

「同 pattern 兩次出現」是 **architectural sweep** 訊號 — 找出整類同 root cause 的漏洞一次掃光（如 Bug AK 出現後 audit 找出 AL 的 risk_level 同 pattern）。

**1-shot prompt fix leverage**：LLM-driven engine 的 calibration bug（如 Bug AN）改 prompt 一段就同時 fix 所有同類 over-classification — 比 rule-based 改十幾條規則快得多（R35 5/5 fixtures 全方位驗證）。代價：LLM 行為非 deterministic，需 regression sweep 確認沒誤殺真風險。

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

## §11 整體效能（本案實測 — final）

```
37 cron ticks（10 min interval；session 中含 ~7 個 user-driven 中斷處理）
22 specs shipped（S073-S084 + S090-S091）
14 bugs found + fixed（A→AN）
4 polish ships（S079 / S081 / S090 + S091 為 bug-fix 兼 polish）
1 META spec planning（S084）— 含 5 sub-specs roadmap S085-S089
1 Calibration regression sweep（R35）驗證 prompt fix 全方位
0 active bugs
```

平均：每 ~1.7 ticks 1 個 ship。Bug discovery rate 曲線：
- 前 10 ticks：~1 bug/tick（surface 開放掃）
- 中 10 ticks：~0.5 bug/tick（saturation 浮現）
- 後 10 ticks：~0.2 bug/tick（saturation；但仍偶爾抓到 architectural-level bugs 如 AN）

**Bug discovery 並非單調遞減**：表層 saturation 後仍可透過 cross-system audit / corpus re-scan 找到更深層問題。AN 是在 「連續 5 ticks 0 bugs」 之後因 R34 anthropic skill re-scan with new engine 才暴露。

**Polish ship 與 Bug ship 比例**：6 polish / 14 bug-fix。saturation 後 polish 是良好出口，但別忘了重新檢視（R34 就是 saturation 後找到的真 bug）。

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
- **LLM behavior 非 deterministic** — calibration 像 S091 改 prompt 後同 fixture 不同 run 可能略差；需 regression sweep（R35 pattern）確認方向正確而非單一 datapoint
- **Cron prompt 改寫風險** — 改了 prompt 就改了 contract，下次 cron 讀的 progress 對不起來；要改先 CronDelete 再 CronCreate

## §14 何時停 loop

下列任一觸發應停 cron：
- **連續 ≥3 ticks 0 bugs 且 saturation pivot 也用完**（polish 全清 / audit sweep 跑完 / 沒新 user-driven request）
- **要做 user-driven feature spec**（`/planning-spec`）— spec 期間 cron testing 會干擾 spec 設計，需 stop 後做 spec 再重啟
- **Cron prompt 需要更新**（cron 是 immutable contract）
- **Backend / DB schema 重構中** — testing 拿不到一致狀態

停 cron：`CronDelete <job-id>`。重啟：`/loop <interval> <prompt>` 或直接重新 invoke。

---

# Part B: Roadmap-Driven Spec Advancement

> 第二種工作流：用 `docs/grimo/specs/spec-roadmap.md` 當 work queue，**把所有 active specs 推到 ✅**。

## §15 何時用 spec advancement loop

| 場景 | Action |
|------|--------|
| Cron testing surface 飽和（Part A §6） | 切到 spec advancement 把累積 backlog 清空 |
| User 明確下指令「推進所有 active specs」 | 直接進入 spec marathon |
| 某 META spec 拆出多個 sub-specs（如 S084 拆 S085-S089） | sub-specs 連續 ship 直到 META 全 ✅ |
| Phase 結束前清 backlog（v1.x → v2.0 等 milestone 收尾） | 推進直到該 phase 全 ✅ |

## §16 Spec advancement loop 結構

```
讀 spec-roadmap.md
   │
   ├── grep -E "📋|📐|🚧|⏸"  → 列出所有 active specs
   ├── 排優先序：dependency 先（meta → foundation → consumer）
   │              size 小先（XS → S → M）建立節奏
   │
   ├── For each active spec:
   │     1. 讀 spec doc (or roadmap row description if doc 還沒寫)
   │     2. 讀對應 prototype / reference material
   │     3. Research dependencies (per planning-spec Phase 2 protocol)
   │     4. Implement minimum viable diff
   │     5. Run test suite (npm test / gradlew test)
   │     6. Restart service if backend change
   │     7. Smoke test (Chrome / curl)
   │     8. Write spec doc with §1-§7 (or fill §7 Result if doc existed)
   │     9. Update CHANGELOG (top, v bump)
   │     10. Update roadmap row：📋 → ✅ + version + date + 一句話 fix 摘要
   │     11. Archive spec：mv to docs/grimo/specs/archive/
   │     12. Commit (single commit per spec; conventional message)
   │
   ├── 每 3-5 ships 同步一次 progress (累計 metric)
   └── exit when grep "📋|📐|🚧|⏸" 為 0 行
```

## §17 Spec ordering 啟發式

```
1. META specs 不算 implementation work — 標 ✅ 後直接 archive
2. Sub-spec dependency：foundation 先 (S089 BeamFrame → S085 HomePage 用)
3. 共享 components 抽取 spec 先 (S085 抽 IconTile/Pill → S086-S088 用)
4. 同 size 內：UI rework 優先在最常見入口頁 (HomePage > 子頁)
5. Risky / large specs 留最後 — 前面 momentum 累積後再啃
```

## §18 Per-spec ship pipeline (與 Part A §5 對齊但加 spec doc 寫入)

```
1. Plan
   - Read prototype HTML (if UI)
   - Read current code (if rework)
   - Identify minimum viable diff
   - Decide acceptance criteria

2. Implement
   - Single-purpose changes only
   - No drive-by refactors
   - Inline comment explain non-obvious decisions

3. Verify
   - Build & test must pass
   - Smoke test for end-to-end flow
   - Visual regression for UI specs

4. Document
   - spec doc §1 Problem / §2 Approach / §3 AC / §4 Fix / §5 Test plan / §6 Verify / §7 Result
   - Result section 包含實測 metrics + smoke output

5. Persist
   - CHANGELOG entry (semver bump)
   - roadmap row：status icon + version + date + 一句話 highlight
   - archive spec doc

6. Commit
   - Single commit per spec
   - Conventional message (feat: / fix: / polish: / chore: / docs:)
   - Co-Authored-By trailer if AI-assisted
```

## §19 何時 batch 多個 specs 在一個 commit

**預設不 batch**：每 spec 一個 commit。

**例外允許 batch**：
- 兩個 specs 改同一個 component 且互相 dependency（如 S089 + S085 SearchBar 一起改）
- 修同一類 architectural pattern（如 lost-update sweep 一次 fix S077 + S078）— 仍可分兩 commit 但同 PR

**永遠不 batch**：
- 不同類型的 change（feat + fix）
- 不同 page 的 rework（每個 page 一個 commit）
- ship + progress doc（progress 必另一個 docs: commit）

## §20 Roadmap row update 範例

```diff
- | Phase 4 | M85: BorderBeam BeamFrame | S089 | XS(3) | — | 📋 backlog — drop border-beam npm... |
+ | Phase 4 | M85: BorderBeam BeamFrame | S089 | XS(3) | 561 | ✅ `v2.62.0` (2026-05-02 — `BeamFrame.tsx` 1:1 port prototype；drop border-beam npm dep；JS bundle 396KB→347KB) |
```

四個變動點：
1. **Status icon**：📋 → ✅
2. **累計 points**：填入該 spec 的 size pts 加上去
3. **Version**：`vX.Y.Z` semver tag
4. **一句話 highlight**：(date — fix 摘要 + impact)

## §21 已知 spec advancement 失敗模式

| 反模式 | 為何避 |
|--------|--------|
| 同時 in-flight 兩個 spec | spec 互相 reference 變 spaghetti；half-done 累積 |
| 跳過 spec 寫文件直接 commit code | 半年後沒人記得這 spec 為什麼 / 怎麼測 |
| Roadmap row 不及時更新 | 下次讀 roadmap 找 active 找錯，做重複工作 |
| Sub-spec 全 ship 後 META 不關閉 | META spec 一直 in-design 看起來 dangling |
| Skip smoke test 直接 ship | 編譯過但 runtime 壞，下個 spec 受影響時才發現 |

## §22 Spec marathon 累積 metric

每 3-5 ships 在 progress log append：

```
=== Marathon batch X (yyyy-mm-dd) ===
Ships: SXXX, SXXX, SXXX
Bugs found in process: 0 / N (見 ledger AO+)
Cumulative tech debt added: ...
Cumulative tech debt closed: ...
Next 3 specs in queue: SYYY, SZZZ, SAAA
```

讓 user / future maintainer 看到 marathon 進度與 health。

## §23 整體 (Part A + Part B) 移植到其他專案

最小安裝（A + B 共用）：
1. `.claude/progress/loop-e2e-test-coverage.md` + `test-case.md` 起點為空（Part A）
2. `docs/grimo/specs/spec-roadmap.md` table 列所有 specs + status icon（Part B）
3. `docs/grimo/specs/archive/` 目錄存放 shipped specs（Part B）
4. `CLAUDE.md` 記 Finish-Current-First principle
5. 約定 spec naming + commit convention + progress 寫入位置

兩種啟動命令：
- Part A：`/loop 10m <test prompt>`（cron testing）
- Part B：`/spec-marathon` 或 user 直接「讀 roadmap 推進所有 active specs 直到全部 ✅」（manual marathon）

兩者共用 ship pipeline（§5 / §18）和 Finish-Current-First（§7）。
