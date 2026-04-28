/loop 5m
讀 docs/grimo/specs/spec-roadmap.md 推進所有 active specs 直到全部 ✅。

## 每個 tick 一個動作

1. 讀 spec-roadmap.md「Active Work」table 找最早未 ✅ 的 spec。
2. 讀該 spec 檔案 header 的 `Depends:` 行；若任一 dep 未 ✅ 也未 graceful-degrade（如 S018 用 `hasRole('admin')` 占位 S016）→ 跳過此 spec 找下一個 active spec。
3. 該 spec 為前端相關（檔名含 `frontend` / `ui` / `page` / `component`）→ 先讀 `docs/grimo/ui/README.md` 再進 step 4。
4. 依狀態決定動作：
   - 🔲 Planning → `/planning-spec <spec-id>`
   - ⏳ Design 且 spec §2.4 全 Validated confidence → `/planning-tasks <spec-id>`
   - ⏳ Design 且 §2.4 含 Hypothesis 或 Unknown confidence → 先 `/deep-research <topic>`，待 findings 補回 §2.4 後再 `/planning-tasks`
   - ⏳ Design 但 header 標 `(paused)` → 重新檢查 deps；可 unpause 則 `/planning-spec <id>`（revise mode）；否則跳過
   - Tasks Done + QA PASS → `/shipping-release <spec-id>`
5. 動作完成後：
   - 仍有未 ✅ 的 active spec → ScheduleWakeup（cadence 見下）
   - 全部 ✅ → 印 final summary（每 spec 一行對應 CHANGELOG entry）+ **不** ScheduleWakeup（loop 結束）
6. **暫停 loop（不 ScheduleWakeup）回報 user** 的情況：
   - `/planning-spec` 在 grill 階段等用戶答 a/b/c 或 user 介入決策
   - `/verifying-quality` 回 `REJECT-BLOCKED`（testability gap → 提案 testing infra spec）
   - POC 執行 HALT（如 baseline 不在預期區間 → S019 < 50%）
   - Spec scope 模糊需重設計、或實作中發現需要新 spec

## Skill 流程鏈

```
/planning-spec [/deep-research]
        ↓
/planning-tasks ⟺ /implementing-task (loop)
        ↓
/verifying-quality（由 /planning-tasks 內部 spawn 為 subagent）
        ↓
/shipping-release
```

## 各 skill 職責

- **/deep-research** — Hypothesis 升級路徑：spec §2.4 含 Hypothesis confidence 時補完研究；產 deepwiki-style 設計文件
- **/planning-spec** — Phase 1 context + Phase 2 research（BLOCKING gate）+ Phase 3 grill + 寫 spec §1-5（Goal / Approach / AC / Interface / File Plan）
- **/planning-tasks** — 拆 BDD task → ping-pong `/implementing-task` → 內部 spawn `/verifying-quality` → 把結果合回 spec §6/§7
- **/implementing-task** — TDD Red → Green → Refactor 單一 task；只能由 `/planning-tasks` 觸發
- **/verifying-quality** — 三層驗證（自動 + 整合 + 手動）+ testability gate；`REJECT-BLOCKED` → 暫停 loop 回報 user
- **/shipping-release** — git commit + tag + CHANGELOG 追加 + spec 歸檔到 `specs/archive/`

## Wake-up cadence（cache 5min TTL aware）

- **Long phase**（`/planning-tasks` task TDD 週期、`/implementing-task` 單 task）→ **1500s**（25min；一次 cache miss 換長 idle 省 cost）
- **Medium phase**（`/planning-spec` 寫 spec 中、`/shipping-release` archive 流程）→ **600s**（10min；可接受 cache miss）
- **Short phase**（status check / 切下個 spec）→ **不睡，直接接續**（cache 內，零成本）
- **等用戶 input** → **不 ScheduleWakeup**（pause loop；user 觸發後再恢復）

## Tick 行為原則

- 一個 tick 只做一個 phase advance；不 cross-phase
- 不重複跑已完成的 phase（讀 spec status + roadmap 判斷）
- 每個 tick 開頭一句話自報「當前推進 SXXX，本 tick 執行 /XXX」
- 偵測到下列其一 → 立即停 loop 並文字回報用戶：grill 等 user input / REJECT-BLOCKED / POC HALT / scope 模糊

## 狀態 emoji 語意（對齊 spec-roadmap.md）

- 🔲 Planning — 未啟動 `/planning-spec`
- ⏳ Design — `/planning-spec` 已寫完 §1-5；可能 `(paused)` 等 deps；可能 §2.4 含 Hypothesis 待研究升級
- ✅ — 已 ship + archive；`/shipping-release` 完成
