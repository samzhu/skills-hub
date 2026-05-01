# Skills Hub E2E Test Case Ledger

> 累積測試案例記錄。每筆標 PASS/FAIL 與對應 ship 的 spec（如有）。
> 按 round 分類；正例 / 反例 / 邊緣案例 各列。
>
> Bug ledger 索引見 `loop-e2e-test-coverage.md`。

## Tick 56 — Loop cron `fc4a79bb` (every 10m), 2026-05-01

### Round 12 — ACL Lifecycle (target: webapp-testing `dbaece25-...`)

| # | 類別 | Case | Result | Spec |
|---|------|------|--------|------|
| 12.1 | 正例 | GET ACL — default seed (`Anthropic:read/write/delete` + `*:read`) | PASS 200 | — |
| 12.2 | 正例 | POST `user:alice:read` (lowercase) | PASS 201 | — |
| 12.3 | 正例 | POST `group:dev:write` | PASS 201 | — |
| 12.4 | 正例 | GET ACL after grants — 6 entries shown | PASS 200 | — |
| 12.5 | 正例 | DELETE `user:alice:read` via query params | PASS 204 | — |
| 12.6 | 反例 | DELETE non-existent `user:ghost:read` | PASS 409 STATE_CONFLICT | (S055-related) |
| 12.7a | 邊緣 | POST `user:bob:read` (1st) | PASS 201 | — |
| 12.7b | 邊緣 | POST `user:bob:read` (2nd, duplicate) | PASS 409 STATE_CONFLICT (non-idempotent) | (S055-related) |
| 12.8 | 反例 | GET ACL on bogus UUID | OBSERVE 200 [] (intentional per `SkillAclQueryService.listEntries:39`) | tech-debt |
| 12.9 | 反例 | POST ACL on bogus UUID | OBSERVE 400 VALIDATION_ERROR (REST: 應為 404；msg 已給對) | tech-debt |
| 12.10 | 反例 | DELETE ACL on bogus UUID | OBSERVE 400 VALIDATION_ERROR (同上) | tech-debt |
| 12.A | 正例 | GET `/api/v1/me` → `lab-user`/admin | PASS 200 | — |
| 12.B | 正例 | GET `/api/v1/admin/echo` → `{by:"lab-user"}` | PASS 200 | — |

**Note**: ACL `type` 是 case-sensitive (`role`/`user`/`group` only) — 此規則由 S055 ship 明確；R12.2 第一次用 `USER` 收 400 with helpful enum hint。

### Round 13 — Pagination & Sort edges (`/api/v1/skills`)

| # | 類別 | Case | Result | Spec |
|---|------|------|--------|------|
| 13.1 | 正例 | `page=0&size=5` baseline | PASS 200, content=5, page meta nested under `page` | — |
| 13.2 | 正例 | `page=2&size=10` | PASS 200, page.number=2 | — |
| 13.3 | 邊緣 | `page=999` (beyond total) | PASS 200, content=0, page.totalPages=5 | — |
| 13.4 | 邊緣 | `size=0` | PASS 200 (Spring coerces to default 20) | — |
| 13.5 | 反例 | `page=-1` | PASS 200 (Spring coerces to 0) | — |
| 13.6 | 邊緣 | `size=10000` | PASS 200 (Spring clamps to 2000) | — |
| 13.7 | 反例 | `size=-5` | PASS 200 (coerce 20) | — |
| 13.8 | 反例 | `page=abc` (non-int) | PASS 200 (coerce 0) | — |
| 13.9 | 正例 | `sort=name,asc` | PASS 200 | — |
| 13.10 | 反例 | `sort=bogusField,asc` | PASS 200 (silent ignore — Spring property whitelist) | — |
| 13.11 | 反例 | `sort=DROP%20TABLE,asc` (SQL injection probe) | PASS 200 (silent ignore — no injection) | — |

### Round 14 — Categories / Search combinations / Detail edges

| # | 類別 | Case | Result | Spec |
|---|------|------|--------|------|
| 14.1 | 正例 | GET `/categories` → 8 items with counts | PASS 200 | — |
| 14.2a | 正例 | `keyword=test&category=Testing` | PASS 200, 13 results all in Testing | — |
| 14.2b | 邊緣 | `keyword=docx&category=DevOps` (no overlap) | PASS 200, total=0 | — |
| 14.2c | 反例 | `category=NonExistent` | PASS 200, total=0 | — |
| 14.3a | 反例 | `keyword=%%` (urlenc `%%`) | PASS 200, total=1 (no docx since `%%` 字面不在任何 name/desc) | — |
| 14.3b | 反例 | `keyword=' OR 1=1--` (SQL injection probe) | PASS 200, total=0 (parameterized) | — |
| 14.3c | 邊緣 | `keyword=` (single space) | PASS 200, total=48 (S044 trim fallback) | — |
| 14.4 | 邊緣 | `keyword=<2000-char string>` | PASS 200, total=0 | — |
| 14.5a | 反例 | `GET /skills/not-a-uuid-at-all` | PASS 404 NOT_FOUND「Skill not found: ...」 | — |
| 14.5b | 邊緣 | `GET /skills/` (empty path) | PASS 404 (Spring static-resource fallback) | — |
| 14.5c | 反例 | `GET /skills/00000000-0000-4000-8000-000000000000` (valid UUID v4 unknown) | PASS 404 | — |

**Discovery**: 第一次用 `?q=docx` 收回 48（all skills），看似 keyword filter 失效；查 `SkillQueryController.search:60` 發現 param 名是 `keyword` 不是 `q`，Spring silent-drop 未知 query param 是預期行為，非 bug。改 `?keyword=docx` 後 1 結果正確。

### Tick 56 Summary

- 31 cases 跨 3 rounds
- **0 new bugs**
- 2 個 ACL endpoint REST status code 不一致 → tech debt (P3)
- 1 個假警報（`q` vs `keyword` param name）已澄清

---

## Tick 57 Plan — Round 15: 3rd-party SKILL.md compatibility

> 場景：模擬使用者從外部抓 SKILL.md 來上傳。對照 agentskills.io 官方規格 + 我們的 validator（NAME_REGEX `^[a-z0-9-]{1,64}$` / DESCRIPTION_MAX 1024 / COMPATIBILITY_MAX 500 / REQUIRED `name+description` / ALLOWED_TOOLS regex）。

**研究結論（tick 56 探索）**：
- mcpmarket.com leaderboard 資料品質參差（top 10 中 #1 `openclaw/openclaw` GitHub repo 不存在；多項顯示一致 156033 stars）
- mcpmarket.com SKILL.md tab 內容 lazy load／需 signup，不是好來源
- **改採 hand-crafted variants**——模擬使用者從各種來源（gist／blog／3rd-party repo）拼湊出的 frontmatter 形狀

**測試 variants（10 個）**：

| # | 類別 | Frontmatter 形狀 | 預期 |
|---|------|-----------------|------|
| 15.1 | 正例 | minimal: `name + description` only | 201 PUBLISHED |
| 15.2 | 正例 | extended: `+ version + tags + author` | 201（額外欄位忽略） |
| 15.3 | 正例 | with `allowed-tools: [Read, Edit, Bash(git:*)]` | 201 LOW risk |
| 15.4 | 正例 | with `license: MIT` | 201 |
| 15.5 | 邊緣 | description 用 YAML pipe `|` 多行 | 201 |
| 15.6 | 邊緣 | description 含 markdown（backticks、連結） | 201 |
| 15.7 | 邊緣 | description 含 emoji + 中文混排 | 201 |
| 15.8 | 反例 | `Name: Foo`（capitalized key） | 400「missing required field 'name'」 |
| 15.9 | 反例 | description 含 `<script>alert(1)</script>` | 201 但前端應 escape (XSS 防禦觀察) |
| 15.10 | 反例 | `allowed-tools: [Bash; rm -rf /]` (shell injection in args) | 400 ALLOWED_TOOL_TOKEN_REGEX 拒收 |

**執行步驟（cron 下次觸發時）**：
1. 為每個 variant 生 SKILL.md → 包 minimal STORED zip
2. POST `/api/v1/skills/upload` → 記 HTTP code + error code + 訊息
3. 對 201 的 case → poll `outbox` 至 0 → 驗 vector_store + risk badge
4. 對 15.9 → 從 frontend 觀察 description 是否 sanitized
5. 全部記到本檔，bug 入 ledger AH/AI...

---

## Tick 57 — Round 15 results (2026-05-01)

| # | 類別 | Variant | Pre-fix | Post-fix | Spec |
|---|------|---------|---------|----------|------|
| 15.1 | 正例 | minimal | 201 ✓ | 409 (dup name) | — |
| 15.2 | 正例 | extended (`+version+author+tags`) | 201 ✓ | 409 (dup) | — |
| 15.3 | 正例 | **allowed-tools YAML list** | **400 invalid token: `[Read]`** | **201 ✓** | **S073 v2.51.0** |
| 15.4 | 正例 | `license: MIT` | 201 ✓ | 409 (dup) | — |
| 15.5 | 邊緣 | YAML pipe multiline desc | 201 ✓ | 409 (dup) | — |
| 15.6 | 邊緣 | markdown in desc | 201 ✓ | 409 (dup) | — |
| 15.7 | 邊緣 | emoji + CJK mix | 201 ✓ | 409 (dup) | — |
| 15.8 | 反例 | capitalized `Name:` key | 400「Missing required field: name」✓ | 400 ✓ | — |
| 15.9 | 反例 | XSS `<script>` in desc | 201（FE 應 sanitize；後端只防注入語意） | 409 (dup) | — |
| 15.10 | 反例 | shell injection in allowed-tools | 400 ✓ | 400 ✓ | — |

**Discovery**：先嘗試 list flow seq `[Read, Bash]`、block seq、單一 `- Read`，**全部 400** —  err msg `"invalid token: [Read]"` 中括號透露根因：`ArrayList.toString()` 直接餵 `split("\\s+")`。比對 `.claude/skills/handover/SKILL.md` 等 canonical Anthropic 形狀全用 list → 確認真實 user impact。

**Ship S073 v2.51.0 (M69)**：fix 5 行；`SkillValidatorTest` +3；backend tests 288 → 291。Smoke：R15.3 同一 zip 重 POST → 201 ✓。

### Tick 57 Summary
- Round 15: 10 cases / 1 high-severity bug (AH) shipped same tick
- 既有 9 個 anthropic skills 之所以未暴露 → 那 batch frontmatter 沒有 `allowed-tools`

---

## Tick 58 — Round 16: canonical Anthropic SKILL.md regression sweep (2026-05-01)

S073 fix 對真實 user-facing 場景的端到端驗證。把 `.claude/skills/` 下 canonical Anthropic SKILL.md（含 `description: >` folded、`metadata:` nested、`argument-hint`、`allowed-tools` block list 等多種形狀）整檔包 zip 上傳。

| # | 類別 | Case | Result | Notes |
|---|------|------|--------|-------|
| 16.1 | 正例 | upload `.claude/skills/handover/SKILL.md` | PASS 201 | description folded `>`, allowed-tools block list `Read/Glob/Grep/Bash/Write` |
| 16.2 | 正例 | upload `planning-project/SKILL.md` | PASS 201 | argument-hint, 8 tools (`+ Edit + Agent + WebFetch + WebSearch`) |
| 16.3 | 正例 | upload `deep-research/SKILL.md` | PASS 201 | 標準 5-tool list |
| 16.4 | 正例 | upload `retro/SKILL.md` | PASS 201 | trigger-action checklist 主題 |
| 16.5 | 正例 | upload `takeover/SKILL.md` | PASS 201 | handover 對偶角色 |
| 16.6 | 反例 | rename to `name: Handover` (大寫) | PASS 400 | `Field 'name' fails regex ^[a-z0-9-]{1,64}$ (got: Handover)` 訊息精準 |

**完整 pipeline 驗證**：
- 5/5 outbox drain（`event_publication.completion_date IS NULL` count = 0 在 upload 後）
- 5/5 vector_store entry 自動 seed
- 5/5 risk=LOW（正確：workflow skill 無危險命令）
- keyword search `handover` → `takeover` + `handover` 互引用匹配
- semantic 中文 `工作交接` → `handover` / `takeover` top 2（跨語言 embedding 運作）

### Tick 58 Summary
- Round 16: 6 cases / **0 new bugs**
- S073 fix end-to-end 確認生效；canonical Anthropic SKILL.md 形狀完全相容
- 中文 semantic search 對英文技能正確 retrieve（vector embedding 多語言能力驗證）

---

## Tick 59 — Round 17: download bytes integrity (2026-05-01)

確認 zip 上傳後 → 下載 → SHA256 完全相同；多版本互不污染；suspend/missing 邊界。

| # | 類別 | Case | Result |
|---|------|------|--------|
| 17.1 | 正例 | 296-byte minimal zip → upload → download | PASS — SHA match；filename `r17-roundtrip-minimal-1.0.0.zip` (S061) |
| 17.2 | 正例 | 51KB multi-file zip（SKILL.md + refs + 51200-byte binary blob） | PASS — SHA match；binary content 完全保留 |
| 17.3 | 邊緣 | multi-version：v1.0.0 → PUT v1.1.0 → 三條 download 路徑驗證 | PASS — latest=v1.1.0 ✓；`/versions/1.0.0/download` = 原始 ✓；`/versions/1.1.0/download` = 新 ✓ |
| 17.4 | 反例 | SUSPENDED skill `/download` | PASS — **403 SKILL_SUSPENDED**「Skill is suspended and cannot be downloaded」 |
| 17.5 | 反例 | `/versions/9.9.9/download` (不存在) | PASS — 404 NOT_FOUND「Version 9.9.9 not found」 |
| 17.6 | 邊緣 | 同一 latest 連續下載 3 次 | PASS — 三次 SHA256 完全一致（無 byte-drift） |

**Note**：17.4 的 403 vs 404 設計正確 — SUSPENDED 是 explicit business state（已存在但不可下載），404 應留給「skill 真的不存在」。Frontend 可區分顯示「已下架」vs「不存在」。

### Tick 59 Summary
- Round 17: 6 cases / **0 new bugs**
- byte 層級 round-trip 在 single-file / multi-file / binary blob / multi-version 場景全 GREEN
- suspended skill download 防護生效（403 SKILL_SUSPENDED）
- 多版本三條 download 路徑（latest / explicit-v1.0.0 / explicit-v1.1.0）互不污染

---

## Tick 60 — Round 18: frontend publish-flow with S073 list-style allowed-tools (2026-05-01)

從 UI 視角驗 S073 fix：在 `/publish` 頁面實際拖檔上傳 + 提交 + 跳轉到 detail page。

| # | 類別 | Case | Result |
|---|------|------|--------|
| 18.1 | 正例 | UI build STORED zip with `allowed-tools: [Read, Edit, Bash(git:*)]` (list shape) + DataTransfer + React value setter + submit | PASS — 「發佈成功！Skill ID: 216ade4c...」+「查看技能 →」 |
| 18.2 | 邊緣 | 點「查看技能 →」→ `/skills/{id}` detail page | PASS — `低風險` badge + `已發佈` + 3 tabs + 描述完整 + 安裝指引 PUBLISHED gating ✓ |
| 18.3 | 反例 | 上傳 `Name:` 大寫 frontmatter | PASS — FE 顯示「發佈失敗 zip 套件驗證失敗」i18n localized message |

**Notes**：
- FE 沒有專門 UI 渲染 `allowed-tools` 欄位（by design — UI 用 risk badge 取代具體 tool list，更 user-friendly）
- 18.3 i18n 訊息泛用化（tech debt 已記）— 不顯示具體 field 名稱
- JS 第一次 build zip 漏寫 LFH 後的 data segment → 114 bytes → backend 回「Invalid zip file: cannot read package contents」(S049 訊息正確)。修正後 308 bytes 順利上傳

### Tick 60 Summary
- Round 18: 3 cases / **0 new bugs**
- S073 fix 從 UI 端到 detail page 全 pipeline 渲染正確
- i18n 訊息 mapping 對 VALIDATION_ERROR 路徑正確觸發

---

## Tick 61 — Round 19: multipart limit + SUSPEND/REACTIVATE complete lifecycle (2026-05-01)

| # | 類別 | Case | Result |
|---|------|------|--------|
| 19.1 | 正例 | 1MB zip upload | PASS 201 |
| 19.2 | 邊緣 | 9.5MB zip (近 10MB limit) | PASS 201 |
| 19.3 | 反例 | 12MB zip (超 10MB limit) | PASS **413 PAYLOAD_TOO_LARGE**「Upload size exceeds the 10 MB limit」 — 有 dedicated error type 不洩漏 stack |
| 19.4 | 邊緣 | SUSPEND/REACTIVATE 完整 lifecycle | PASS — upload `(listed/dl/status/vec)=(T/200/PUB/1)` → suspend `(F/403/SUS/0)` → reactivate `(T/200/PUB/1)` ✓ |
| 19.5 | 反例 | re-suspend SUSPENDED skill | PASS **409 STATE_CONFLICT**「Cannot suspend skill in SUSPENDED status」 |

**Notes**：
- suspend/reactivate require `{"reason":"..."}` JSON body (Required `@RequestBody SuspendRequest req`)；空 body → 400 INVALID_REQUEST_BODY
- 12MB 超檔上傳 via Python `urllib` 收 broken-pipe（client-side socket reset by server）；改用 curl 才正確收到 413 body
- S033 invariant：vector_store entry 對 PUBLISHED skill 存在；SUSPENDED 時 async listener 清掉；reactivate 時 async listener 重建（all 由 domain event listener orchestrate）
- state machine 對稱性確認：R1 already-PUBLISHED reactivate → 409；R19.5 already-SUSPENDED suspend → 409。兩端都 guard。

### Tick 61 Summary
- Round 19: 5 cases / **0 new bugs**
- multipart 10MB limit clean-error；不會 leak stack trace
- S033 vector store invariant 在 suspend/reactivate 兩個方向都正確 async 維護
- state machine guards 對稱（two-way 409 STATE_CONFLICT）

---

## Tick 62 — Ship S074 Skill Files Browser API (2026-05-01)

7 ACs smoke verified（合併在 tick 62 ship 流程，case 表略；詳 progress log）。

---

## Tick 63 — Round 20: S074 deeper coverage (2026-05-01)

| # | 類別 | Case | Result |
|---|------|------|--------|
| 20.1 | 正例 | multi-version `pdf` (3 versions, 12 files) /files | PASS — 200 + latest 12 entries（含 reference.md / forms.md / scripts/*.py） |
| 20.2 | 反例 | DRAFT skill (no PUBLISHED) /files | PASS — 404 「No versions found for skill: ...」 |
| 20.3 | 邊緣 | HEAD /files/SKILL.md | PASS — 200 + Content-Type + Content-Length 與 GET 一致 + body=0 bytes（Spring 自動 GET→HEAD） |
| 20.4 | 邊緣 | OPTIONS /files (CORS preflight) | PASS — 200 + `Allow: GET,HEAD,POST,...`；無 Access-Control（dev vite proxy 同 origin） |
| 20.5 | 邊緣 | 5 concurrent readers 同檔 | PASS — 5/5 200 + 同 size + 同 head bytes，無 race |
| 20.6 | 反例 | bogus UUID /files | PASS — 404 NOT_FOUND |

**Bonus**：anthropics/pdf 包 12 檔案含 6 個 Python scripts — 檔案瀏覽器對這類 multi-script skill UX value 高，user 可預覽 script 才決定下載。

### Tick 63 Summary
- Round 20: 6 cases / **0 new bugs**
- S074 在 multi-version / DRAFT / HEAD / OPTIONS / 並發 / bogus-id 場景全 robust
- 新 tech debt：production CORS 配置（platform-level，不限 S074）

---

## Tick 64 — Round 21: flag flow lifecycle (2026-05-01)

| # | 類別 | Case | Result | Spec |
|---|------|------|--------|------|
| 21.1 | 正例 | POST flag → GET /flags → 1 entry | 200 — **但 entry 含 `"new": true` framework artifact** | **S075 v2.53.0** |
| 21.2 | 邊緣 | 同 user 同 skill 同 type 連 5 次 | DB 5 筆 (no dedup, intentional MVP) | — |
| 21.3 | 邊緣 | accumulate 後 GET /flags | 200 — 6 entries grouped (copyright:5, spam:1) | — |
| 21.4 | 邊緣 | flag 後 skill status | 仍 PUBLISHED (flags = passive signal) | — |
| 21.5 | 反例 | GET /flags on bogus UUID | 200 + `[]` (同 ACL endpoint design choice — already tech debt) | — |
| 21.6 | 邊緣 | 6 flags status 統計 | 全 OPEN (no admin endpoint to change — MVP design) | — |

**Bug AI shipped**：S075 — `FlagReadModel.isNew()` 加 `@JsonIgnore`。完全平行於 Bug AA / S063（Skill aggregate）；S063 修法當時沒覆蓋 Flag。FlagControllerTest +1 (`getFlagsExcludesIsNewArtifact`)。298 → 299 backend tests / 0 fail。

### Tick 64 Summary
- Round 21: 6 cases / **1 new bug shipped (AI / S075 v2.53.0)**
- Flag flow 既有 design 確認：no dedup（intentional）、status='OPEN' 固定（admin queue 是 future spec）、bogus UUID → 200 [] (same as ACL — known tech debt)

---

## Tick 65 — Round 22: concurrent download counter → ship S076 (2026-05-01)

| # | 類別 | Case | Pre-fix | Post-fix | Spec |
|---|------|------|---------|----------|------|
| 22.1 | 邊緣 | N=1 download | 100% | 100% | — |
| 22.2 | 邊緣 | **N=2 parallel** | **50%** | **100%** | **S076 v2.54.0** |
| 22.3 | 邊緣 | N=3 parallel | 33% | 100% | S076 |
| 22.4 | 邊緣 | N=5 parallel | 20% | 100% | S076 |
| 22.5 | 邊緣 | N=10 parallel | 10% | 100% | S076 |
| 22.6 | 邊緣 | N=30 parallel | 13% | 100% | S076 |

**Bug AJ (HIGH / production-grade)** — aggregate `@Version` optimistic locking 對 counter 過度保護。fix 改用 `@Modifying @Query` 原子 SQL UPDATE + `ApplicationEventPublisher`；Modulith 透過 `@TransactionalEventListener` 攔截，outbox at-least-once 保證不變。Aggregate `recordDownload()` 保留供 SkillAggregateTest 覆蓋。299 tests / 0 fail。

**Bonus discovery**：AuditEventListener 不訂閱 SkillDownloadedEvent（by design — volume）；download_events 表才是消費點，delta == HTTP 200 count 確認事件路徑完整。

### Tick 65 Summary
- Round 22: 6 cases / **1 new production-grade bug shipped (AJ / S076 v2.54.0)**
- 並行下載成功率從 N=2 50% / N=10 10% → 全 N **100%**
- Modulith outbox 對 ApplicationEventPublisher 與 @DomainEvents 路徑同效驗證

---

## Tick 66 — Round 23: race conditions on state machine (2026-05-01)

| # | 類別 | Case | Result | Spec |
|---|------|------|--------|------|
| 23.1 | 反例 | DELETE /skills/{id} | PASS — 405 METHOD_NOT_ALLOWED (by design) | — |
| 23.2 | 邊緣 | concurrent 5 suspend + 5 reactivate | PASS — 1×suspend 200 + 9×409；無 data corruption | — |
| 23.3 | 邊緣 | 5 並行 grantAcl 同 tuple | PASS — 1×201 + 4×409 STATE_CONFLICT；DB 1 grant | — |
| 23.4 | 邊緣 | 5 並行 PUT version (different) | PASS — 1×200 + 4×409；version-add 屬 state-machine，409 正確 (accepted limitation) | — |
| 23.5 | 邊緣 | 10 並行 dl + 1 concurrent suspend | **FAIL pre-fix counter=3** → **PASS post-fix counter=10** | **S077 v2.55.0** |

**Bug AK (HIGH / S076 regression)** — Spring Data JDBC `save()` full-row UPDATE 覆蓋 atomic SQL increment（無 dirty tracking）。Fix: `@ReadOnlyProperty` 排除 `downloadCount` 從 save write set。

### Tick 66 Summary
- Round 23: 5 cases / **1 new bug shipped (AK / S077 v2.55.0)**
- DELETE skill 設計確認；state-machine race conditions 4/5 正確；發現 lost-update regression 並修復
- **設計領悟**：counter-style 欄位若有獨立 atomic 寫入路徑，aggregate 必須用 `@ReadOnlyProperty` 排除以避免 save 覆蓋

---

## Tick 67 — Round 24: lost-update architectural audit (2026-05-01)

系統性掃描 `@Modifying @Query` 路徑與 aggregate save 寫入欄位的交叉，找出與 Bug AK 同 pattern 的漏洞。

| Aggregate field | Atomic SQL writer | Aggregate save writer | Vulnerable? | Fix |
|-----------------|-------------------|----------------------|-------------|-----|
| `Skill.downloadCount` | `incrementDownloadCount` (S076) | suspend/reactivate/grantAcl save() | YES | S077 v2.55.0 ✓ |
| `Skill.riskLevel` | `updateRiskLevel` (S024 T5 / ScanOrchestrator) | suspend/reactivate/grantAcl save() | **YES (theoretical)** | **S078 v2.56.0** ✓ |
| `Skill.status` | — | suspend/reactivate save() | NO | — |
| `Skill.latestVersion` | — | publishVersion save() | NO | — |
| `Skill.aclEntries` | — | grantAcl/revokeAcl save() | NO | — |
| `Skill.name/description/category/author` | — | immutable post-create | NO | — |
| `SkillVersion.riskAssessment` | — | `attachRiskAssessment` save() only | NO | — |

**Bug AL (theoretical)** — 5 trial 重現失敗（dev 環境 scan async timing 落在 grantAcl 群組之後）；架構漏洞確實存在（`updateRiskLevel` SQL 不增加 aggregate `version` → optimistic lock 偵測不到此衝突），ship preemptive defense per S077 precedent（一行 fix 零風險）。

### Tick 67 Summary
- Round 24: lost-update audit / **1 preemptive bug shipped (AL / S078 v2.56.0)**
- **Skill aggregate 所有欄位 lost-update 漏洞清零**（兩個 atomic-path 欄位 `download_count` + `risk_level` 都已 `@ReadOnlyProperty`）
- 設計領悟：lost-update audit 是 architectural sweep；同模式漏洞應一次掃光，避免日後踩雷

---

## Tick 68 — Round 25: semantic search edges + data quality invariants (2026-05-01)

### Data quality snapshot — all GREEN

| metric | value |
|--------|-------|
| published_skills | 81 |
| suspended_skills | 6 |
| draft_skills | 18 |
| vector_count | 99 (= published + draft，符合 S033 invariant) |
| outbox_pending | 0 |
| published_without_vector | 0 |
| orphan_vectors | 0 |
| orphan_versions | 0 |

### Semantic search 9 cases

| # | 類別 | Case | Result |
|---|------|------|--------|
| 25.1 | 正例 | 中文「PDF 文件處理」cross-lingual | PASS — top 5 含 pdf/docx/xlsx |
| 25.2 | 正例 | q=docx (exact name) | PASS — 第一筆 docx |
| 25.3 | 反例 | q='' | PASS 400「No embedding input...」|
| 25.4 | 邊緣 | q='x' 1 char | PASS 200 (xlsx top) |
| 25.5 | 邊緣 | q=8400 chars | OBSERVE 400 Tomcat HTML page (already tech debt) |
| 25.6 | 邊緣 | q='🚀💡' emoji-only | PASS 200 |
| 25.7 | 邊緣 | limit=0/1/50/100/1000 | OBSERVE 全 10 — **limit param silently ignored** (Spring default for unknown param; non-bug per contract) |
| 25.8 | 反例 | q=SUSPENDED skill name | PASS 0 results (S059 filter) |
| 25.9 | 邊緣 | whitespace + special chars | PASS — '   '→400 / '!!!','%%%' →200 best-effort match |

**Note R25.7**: `/search/semantic` controller 只接受 `q`；TOP_K=10 hardcoded；OpenAPI 沒承諾 limit。Logged as missing feature for future spec（exposing configurable limit）.

### Tick 68 Summary
- Round 25: 9 cases / **0 new bugs**
- 系統 health 全綠（無 orphan / outbox 0 / vector invariant 守住）
- 1 個 missing-feature observation（semantic search limit）→ 排入 tech debt

---

## Tick 69 — Round 26: bare POST /skills + name regex boundaries (2026-05-01)

### Bare POST /skills endpoint discovery
- 接受 JSON body `CreateSkillCommand{name, description, author, category}`
- 建立 "shell" skill（無 zip/version/embedding/risk assessment）；用途註記「測試和資料 seeding，正式發佈請使用 uploadSkill」

### Name regex `^[a-z0-9-]{1,64}$` 17 boundary cases — all PASS

| # | name | expected | got |
|---|------|----------|-----|
| 1 | `a` (1 char min) | 201 | 201 ✓ |
| 2 | `a*64` (max len) | 201 | 201 ✓ |
| 3 | `a*65` (over) | 400 | 400 ✓ |
| 4 | `""` (empty) | 400 | 400 ✓ |
| 5 | `ABCDEF` (uppercase) | 400 | 400 ✓ |
| 6 | `-` (single hyphen) | 201 | 201 ✓ |
| 7 | `--` (double hyphen) | 201 | 201 ✓ |
| 8 | `foo-1777...` (trailing) | 201 | 201 ✓ |
| 9 | `-foo-1777...` (leading) | 201 | 201 ✓ |
| 10 | `123-1777...` (numbers) | 201 | 201 ✓ |
| 11 | `foo_bar` (underscore) | 400 | 400 ✓ |
| 12 | `foo.bar` (dot) | 400 | 400 ✓ |
| 13 | `foo/bar` (slash) | 400 | 400 ✓ |
| 14 | `中文-...` (CJK) | 400 | 400 ✓ |
| 15 | `foo bar` (space) | 400 | 400 ✓ |
| 16 | `foo+bar` (plus) | 400 | 400 ✓ |
| 17 | `r26-test-...` (happy path) | 201 | 201 ✓ |

### Tick 69 Summary
- Round 26: 17 cases / **0 new bugs**
- name regex 行為與 documented spec 完全一致
- Polish candidate logged：regex 接受邊界 hyphen / 連續 hyphen，Docker-style 慣例會更嚴謹

---

## Tick 70 — Polish: SkillSuspendedException message (2026-05-01)
- ship S079 v2.56.1 / M75；message operation-agnostic；3 paths smoke ✓；FE i18n 不受影響

---

## Tick 71 — Round 27: API consistency audit → Bug AM ship S080 (2026-05-01)

| # | Endpoint | Pre-fix shape | Result |
|---|----------|--------------|--------|
| 27.1 | GET /skills/{bogus} (404) | `{error,message,timestamp}` standard | PASS |
| 27.2 | DELETE /skills/{id} (405) | standard | PASS |
| 27.3 | POST /skills empty body (400) | standard | PASS |
| 27.4 | GET /search/semantic?q= (400) | standard | PASS |
| 27.5 | POST /skills/{id}/suspend already-SUSPENDED (409) | standard | PASS |
| 27.6 | POST /skills/upload missing 'version' (400) | **Spring default `{timestamp,status,error:"Bad Request",message,path}`** | **FAIL pre-fix** → **PASS post-S080** |

**Bug AM (MEDIUM)**：missing required @RequestParam / @RequestPart 走 Spring 預設 fall-back error path，不會被既有 @ExceptionHandler 自動 handle。FE i18n 用 error code 對應 localized — 「Bad Request」不在白名單 → silent fallthrough。Fix: 顯式 register `handleMissingParam`，回 `error: "VALIDATION_ERROR"`。

### Tick 71 Summary
- Round 27: 6 cases / **1 new bug shipped (AM / S080 v2.57.0)**
- 設計領悟：Spring binding-time 例外不會被一般 ExceptionHandler 自動 handle，必須顯式註冊

---

## Tick 72 — User-driven UI work: ship S081 Design Tokens (2026-05-01)

無 testing round — 切到 user-driven UI track。ship S081 v2.58.0 (M77) Design Token Migration foundation：DESIGN.md 55 colors + 6 radius + 3 font stack 寫入 `frontend/src/index.css`。

---

## Tick 73 — Finish-Current-First triple ship (2026-05-01)

依 Finish-Current-First 原則 stack-not-overlap 處理 user mid-flight 三件：
- ship S082 v2.59.0 (M78) SkillDetailPage Files Tab UI（接 S074 API；smoke anthropic/pdf 12 files ✓）
- commit CLAUDE.md Finish-Current-First principle（持久化原則）
- ship S083 v2.59.1 (M79) BorderBeam light theme tuning（fix theme=dark default 在淺色背景偏霧；對齊 DESIGN.md §Elevation 4-5s rotation）

---

## Tick 74 — Round 28: S082 Files tab E2E AC matrix (2026-05-01)

對 S082 spec §3 5 個 AC 各驗 1 fixture：

| # | 類別 | Fixture | Result |
|---|------|---------|--------|
| 28.1 | 正例 (recap) | r17-roundtrip-minimal / anthropic-pdf 12 entries | PASS — list + SKILL.md preview |
| 28.2 | 邊緣 | r17-multi-bigger / data.bin (binary, 50KB) | PASS — 「此為 binary 檔案，無法預覽」 |
| 28.3 | 邊緣 | r19-s074-1777642565 / big.bin (1.49 MB) | PASS — backend 413 → 「檔案過大，無法預覽（單檔上限 1 MB）」 |
| 28.4 | 反例 | suspend-download-test (SUSPENDED) | PASS — backend 403 → 「此技能已被停用，無法瀏覽檔案」 |
| 28.5 | 反例 | draft-skill-tick5 (DRAFT, no PUBLISHED) | PASS — backend 404 → 「此技能尚未發布版本」 |

### Tick 74 Summary
- Round 28: 5 cases / **0 new bugs**
- S082 spec §3 全 5 AC 端到端驗證完成；feature ship 後深度驗證收尾。

---

## Tick 75 — Round 29: LLM 解說 + LOW/MEDIUM/HIGH 評分 E2E (2026-05-01)

User: 「E2E 要測試 LLM 解說功能, 跟中高風險評分效果」
Pre-condition: LlmJudge engine enabled in dev profile (commit 97cc24b)

| # | 類別 | Fixture | Expected | Got | LLM Reasoning |
|---|------|---------|----------|-----|---------------|
| 29.1 | 正例 | r29-low-... (pure docs, no scripts) | LOW | **LOW** ✓ | "no identifiable risks... pure documentation skill providing basic Markdown best practices" |
| 29.2 | 邊緣 | r29-med-... (allowed-tools=Bash + npm/git routine) | MEDIUM | **HIGH** ⚠️ | "explicit safety claims while listing actions that are well-known supply chain attack vectors (AS5)" |
| 29.3 | 反例 | r29-high-... (rm -rf + curl|bash + /etc/passwd + AWS+GitHub PAT) | HIGH | **HIGH** ✓ + 12 findings | "multiple highly dangerous actions: destructive file ops, RCE, credential theft" |

**LLM Explanation Quality**: HIGH ✓ — 三個 case 解說都精準、技術正確、actionable；對 user「夠不夠清楚」答案：清楚。

**Severity Calibration Observation (not bug)**：
- DB 76 LOW / 11 HIGH / 27 NULL / **0 MEDIUM**
- LLM Judge 給 npm routine commands 嚴重度 8.5（同 rm -rf）→ max-severity rule 推到 HIGH
- Design philosophy 選擇：conservative security-first vs nuanced anti-alarm-fatigue
- Future S090+ severity calibration spec — 需更大樣本

### Tick 75 Summary
- Round 29: 3 cases (1 LOW ✓ / 1 MEDIUM-rated-HIGH calibration observation / 1 HIGH ✓) / **0 new bugs**
- LLM 解說品質：HIGH 三案皆精準
- Calibration tech debt logged for future S090+

---

## Tick 76 — Round 30: MEDIUM tier reachability probe (2026-05-01)

5 borderline fixtures to map LLM Judge calibration boundary（refines R29's MEDIUM hypothesis）:

| # | Fixture | Got | Findings | Severities | LLM Reasoning Highlight |
|---|---------|-----|----------|------------|-------------------------|
| 30.1 | read-only Bash (cat/ls/grep) | HIGH | 3 | {5.0, 8.5} | "user input + Bash = command injection (AST-SKILL-001)"；description-vs-impl mismatch |
| 30.2 | write /tmp (echo+cp) | HIGH | 3 | {5.0, 8.5} | 同上 pattern |
| 30.3 | git inspection (status/diff/log) | HIGH | 1 | {8.5} | "declares Bash 但 SCRIPTS section 空的，why declare Bash if no script?" |
| 30.4 | /etc/hostname | **MEDIUM ✓** | 1 | {5.0} | 證實 MEDIUM 可達 |
| 30.5 | docker ops (no privileged) | **LOW** | 0 | — | LLM 看不出 concern |

**重大發現**:
1. **MEDIUM IS reachable** — R29 sample bias 修正；正常系統可產生 LOW/MEDIUM/HIGH 三 tier
2. **LLM 智商高** — 識別 description-vs-impl mismatch、empty-scripts-but-Bash、command injection 向量；reasoning 都對應真實 supply chain attack 模式
3. **Severity → tier mapping 是黑白分明**：任何 8.5 → HIGH；只 5.0 → MEDIUM；0 → LOW（無 weighted scoring）

### Tick 76 Summary
- Round 30: 5 cases / **0 new bugs**
- MEDIUM tier 證實可達（撤銷 R29 0-MEDIUM 假設）
- LLM reasoning 智商高，真有抓到 supply chain attack pattern
- Calibration 是 design choice (conservative security-first)；future S090+ 可考慮 weighted scoring

---

## Tick 77 — Round 31: HTTP method + encoding edges (2026-05-01)

| # | 類別 | Case | Result |
|---|------|------|--------|
| 31.1 | 反例 | PATCH /skills/{id} | PASS — 405 METHOD_NOT_ALLOWED |
| 31.2 | 反例 | PUT /skills (collection root, no id) | PASS — 405 |
| 31.3 | 邊緣 | GET /skills/{id} with `%20` (URL-encoded) | PASS — 404 with decoded "abc def" |
| 31.4 | 邊緣 | OPTIONS /skills/{id} (CORS preflight) | PASS — 200 + Allow + Spring Security hardened headers (X-Content-Type-Options nosniff, X-Frame-Options DENY, X-XSS-Protection, Cache-Control no-store) |
| 31.5 | 邊緣 | `?keyword=docx&keyword=pdf` (duplicate param) | PASS — Spring joins as "docx,pdf" → 0 hits |
| 31.6 | 反例 | URL-encoded path traversal `%2E%2E%2F` in /files | PASS — 400 Tomcat HTML page (per 既有 tech debt) |
| 31.7 | 反例 | SQL injection `'; DROP TABLE skills; --` in keyword | PASS — 200 + 0 results；DB 122 skills 完整 |

### Tick 77 Summary
- Round 31: 7 cases / **0 new bugs**
- security boundaries 全守住（method whitelist / URL decoding / SQL injection prevention / Spring Security hardened headers）
- 連續 4 ticks 0 bugs (74/75/76/77) — testing surface 確認 saturation

---

## Tick 78 — Round 32: cross-system invariant audit (2026-05-02)

3 audits across system boundaries:

| # | 類別 | Audit | Result |
|---|------|-------|--------|
| 32.1 | 邊緣 | OpenAPI doc accuracy（vs deployed handlers） | PASS — 22 operations / 17 paths（自 R12 21 ops 後 +1 為 S074 /files） |
| 32.2 | 邊緣 | Modulith module boundaries (`/actuator/modulith`) | PASS — 7 modules 全註冊（shared/storage/skill/analytics/audit/search/security 對齊 CLAUDE.md） |
| 32.3 | 邊緣 | Storage 雙向 hygiene | PASS DB→FS 109/109 ✓；OBSERVE FS→DB 14 orphans (累積 tech debt) |

### Tick 78 Summary
- Round 32: 3 cases / **0 new bugs**
- system invariants 全 GREEN（API 契約 / 模組邊界 / DB↔Storage 一致性）
- 14 orphan storage files 為 recurring dev-churn tech debt（不影響 user-visible 正確性）
- **連續 5 ticks 0 bugs (74/75/76/77/78)** — surface 確認飽和

---

## Tick 79 — Polish: ship S090 Semantic search ?limit= (2026-05-02)
- 7/7 AC PASS；close R25.7 missing-feature；tech debt 5 → 4

---

## Tick 80 — Round 33: audit log + outbox + vector + download invariants (2026-05-02)

| # | 類別 | Audit | Result |
|---|------|-------|--------|
| 33.1 | 邊緣 | sequence monotonicity per aggregate | PASS — 0 gaps |
| 33.2 | 邊緣 | duplicate (aggregate_id, sequence) | PASS — 0 (UNIQUE constraint) |
| 33.3 | 邊緣 | sequence starts at 1 | PASS — all aggregates |
| 33.4 | 正例 | event_type distribution | PASS — 10 types healthy spread |
| 33.5 | 反例 | orphan events (skill row 不存在) | PASS — 0 |
| 33.6 | 邊緣 | JSONB payload integrity | PASS — object type, valid |
| 33.7 | 邊緣 | outbox health | PASS — 1221 total / 1221 completed / 0 pending / 0 stale 1h |
| 33.8 | 邊緣 | vector_store ↔ skills S033 invariant | PASS — 116 active skills ↔ 116 vectors / 0 SUSPENDED-with-vector |
| 33.9 | 邊緣 | download events vs counter accuracy | OBSERVE — 16-event gap in 2 R23.5 race-test fixtures (historical Bug AK residue, S077 fixed) |

### Tick 80 Summary
- Round 33: 9 cases / **0 new bugs**
- system invariants 全 GREEN（audit log / outbox / vector / event count）
- R23.5 historical residue confirmed harmless (dev-only test fixtures from pre-S077 lost-update)







