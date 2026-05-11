# Cron Loop Progress — S157/S160/S161/S163/S164 完成記錄（2026-05-12）

> 30 分鐘 cron tick loop（job ID `c9ca6c4a`，每 30 分鐘 fire 一次）跨多 tick 推進原 5-spec 從 📐 設計階段到 ✅ shipped。
> 本檔總結 18-tick cron run（17 commits）+ Loop-Hint-Verify 修正點 + S157 cron 邊界。

---

## 完成度總覽

| Spec | 起始狀態 | 結束狀態 | AC 進度 | Sub-specs ship |
|---|---|---|---|---|
| S157 | 📐 in-design | 🚧 backend ship；等 LAB deploy | 3/8 unit-verifiable PASS；AC-1~4/8 待 LAB | — |
| S160 | 📐 in-design | ✅ **fully shipped** | **9/9** | Phase 1-5: headers + CSRF infra + frontend apiFetch + CSP report + AC-1 chain test |
| S161 | 📐 in-design | ✅ **fully shipped** | **8/8** | Phase 1-5: review.content + flag/collection 5 欄位 + V19 backfill + request.title + markdown allowlist |
| S163 | 📐 in-design | ✅ **fully shipped** | **8/8** | backend PUT + EditSkillModal + VisibilityToggleButton |
| S164 | 📐 in-design | ✅ **fully shipped** | **8/8** | backend PUT+DELETE + EditCollectionModal + action bar |

**4/5 fully shipped；S157 屬 cron 邊界（需 gcloud builds submit + 真實 Gemini API 驗證）。**

---

## 17 個 Commit 進度條

| # | Commit | Spec | 內容 |
|---|---|---|---|
| 1 | `25996b7` | docs | 5-spec 設計完成標記 + S160 stale cross-ref fix |
| 2 | `544969b` | S157 | backend native AOT bake-out fix（SearchConfig/ScannerAiConfig/LlmJudge/SearchNativeConfig）|
| 3 | `5acfd17` | S160 Phase 1 | 4 security headers (CSP/HSTS/Referrer/Permissions) |
| 4 | `136564d` | S163 backend | PUT /skills/{id} update metadata |
| 5 | `3ca6778` | S161 Phase 1 | PlainTextDeserializer + review.content（OWASP encode 破繁中 → 改 regex）|
| 6 | `8fbee3d` | S164 backend | PUT + DELETE /collections/{id} + 2 events + auth check |
| 7 | `0af2883` | S161b | 套到 flag + collection 5 個 DTOs；附帶 fix S157 LlmJudge regression |
| 8 | `46eee1e` | S161c | V19 Flyway backfill SQL（reviews/flags/collections 4 欄位）|
| 9 | `fbce208` | S163b | EditSkillModal + PageHeader 編輯 button + updateSkill helper |
| 10 | `90a02e9` | S163b' | VisibilityToggleButton 自包 grants query — **S163 fully shipped 8/8** 🎉 |
| 11 | `47a4506` | S161b' | request.title plain-text strip |
| 12 | `be228d7` | S161b'' | MarkdownSafeDeserializer (OWASP allowlist) — **S161 fully shipped 8/8** 🎉 |
| 13 | `7449b06` | S160b | CSRF infrastructure feature-flag |
| 14 | `686fda0` | S160b' | frontend apiFetch X-XSRF-TOKEN auto-inject |
| 15 | `fec5ccc` | S160b'' | CSP report endpoint POST /api/v1/csp-report |
| 16 | `ab6d182` | S160b''' | CSRF chain rejection integration test — **S160 fully shipped 9/9** 🎉 |
| 17 | `7906206` | S164b | EditCollectionModal + action bar + S150 stale status fix — **S164 fully shipped 8/8** 🎉 |

---

## Loop-Hint-Verify 修正點

Per CLAUDE.md 三條原則之一：「Loop-Hint-Verify：`/loop` priority hint 落後實際狀態 2-4 ticks。每 tick 開始 grep 真實 roadmap / ledger 驗證；hint 與事實不符**以 ledger 為準**」。

| Tick | Hint（roadmap）| Verify（grep / test）| 修正 |
|---|---|---|---|
| 18 (S164b) | S150 📋 planned；S164b 因此 blocked | grep find `CollectionDetailPage.tsx` 已存在 + 6/6 tests PASS = S150 functional ship | roadmap S150 → ✅；S164b unblocked 直接做 |

**教訓**：兩個 loop session 前刻意 spec doc 全部寫齊 + 兩個 fully-ship session 中 S150 implementation 被某個 PR 一起帶進，但 roadmap status 漏更新。Verify 救一次。

---

## 設計轉折紀錄

### S161 Phase 1 OWASP → regex（3ca6778）

Spec §2.3 原選 `owasp-java-html-sanitizer`，但實測：
- 把「！」(U+FF01 全形驚嘆號) encode 成 `&#xff01;` → 破繁中 user 內容
- 把 `a < b` 中的 `<` encode 成 `&lt;` → escape-for-render 邏輯處理 storage 不適合

改成簡單 regex 兩 pass（script/style 連內容 + 一般 tag）。OWASP dep 暫不引入；等 S161b'' markdown allowlist 才真需要再加回（be228d7）。

### S161b'' OWASP 復活（be228d7）

markdown 場景下：
- `&amp;` 等 entity 本來就是合法 markdown HTML entity
- frontend 若用 markdown renderer 自動 decode 回字符
- 對 stored XSS 是縱深防禦（多一層 safe-by-default）

OWASP encode 副作用 acceptable for markdown context。

### S160b CSRF feature-flag 模式（7449b06）

原 spec design 預期 backend + frontend 同 commit 啟用 CSRF；現實是 multi-PR coord 風險高（frontend 沒 ready 前啟用 → 所有 mutation 立刻炸 403）。改用 feature-flag：infrastructure ready，default OFF，frontend ready + LAB 驗證 OK 後 env var 顯式 toggle ON。

### Modulith violation 修正（0af2883）

PlainTextDeserializer 原放 `shared.api.sanitize` sub-package — review module allowed targets 列「shared :: api」未含 nested `sanitize`。改放 `shared.api` 直接層級。

### S157 LlmJudge regression（0af2883 附帶 fix）

S157 commit 544969b 把 LlmJudge 改為 always-on（取代 `@Conditional(LlmEnabledCondition.class)`）— 但跑 targeted test 漏 `RiskAssessmentIntegrationTest`（在 security 而非 security.scan package）。兩 ticks 後 S161b 跑 broader test 發現 runs.size=7 → 8（多 llm-judge engine emit「disabled」notice），附帶在當 tick 修正 assertion + comment。

---

## S157 cron 邊界

S157 backend 已完整 ship (544969b)：
- SearchConfig 改 runtime factory branching
- ScannerAiConfig 同樣處理
- LlmJudge 改 `Optional<ChatClient>` graceful skip
- SearchNativeConfig 新檔（AOT reflection hint）

仍待 user 執行的 AC：
- **AC-1**: `terraform` 搜尋命中 → 需 LAB deploy + 真 Gemini API call
- **AC-2**: 中文 query 命中 → 同 AC-1
- **AC-3**: `/api/v1/search/intent` 真實 concepts → 同
- **AC-4**: Cloud Run startup log 確認 `Initialising GoogleGenAiTextEmbeddingModel`、不再 `No EmbeddingModel configured` → `gcloud logging read`
- **AC-8**: Frontend graceful fallback 文案不退化 → manual UI check 後 deploy

Cron 不能執行 `gcloud builds submit` / 不能 issue 真 Gemini API calls / 不能 manual UI verify。屬 user-driven workflow。

未來可考慮拆 sub-spec `S157b` 寫 `SemanticSearchIntegrationTest`（Testcontainers + pgvector + deterministic stub embedder，per spec §6.1 提的 DEFER），對 backend factory wiring + projection listener 提供自動 regression 保護（不涵蓋真 Gemini call）。

---

## 平均 tick velocity

- 17 commits / 18 ticks ≈ 0.94 commit/tick
- 7 個 sub-specs 完整收尾（含 backend + frontend + tests + docs）
- 平均每 commit：~5-10 file changes、200-400 lines diff
- 0 個 [WIP] commit；每 tick 都有可 ship 的 atomic unit

---

## EXIT label of this tick

✅ DONE — progress log shipped；4/5 specs fully shipped；S157 in cron boundary。

下個 tick 候選：
- Mode B E2E round（找 regression）
- 寫 SemanticSearchIntegrationTest（拆 S157b 自動化）
- 為 S163b/S164b EditModal 寫 Playwright happy-path spec（regression 保護）
- LAB deploy verify S157（user-driven，cron 不能自動）
