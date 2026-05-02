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