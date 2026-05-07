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

---

## Tick 81 — Round 34: anthropic skill re-scan with LLM Judge → Bug AN ship S091 (2026-05-02)

| # | 類別 | Skill | Pre-LLM | Post-LLM (pre-S091) | Post-S091 |
|---|------|-------|---------|---------------------|-----------|
| 34.1 | 正例 | handover | LOW | HIGH (2 findings) | **LOW** ✓ |
| 34.2 | 正例 | planning-project | LOW | HIGH (5 findings) | **LOW** ✓ |
| 34.3 | 正例 | deep-research | LOW | HIGH | **LOW** ✓ |
| 34.4 | 反例 (regression) | real-high (rm -rf + secrets) | — | HIGH (12 findings) | **HIGH** 14 findings ✓ 真風險不漏 |
| 34.5 | 邊緣 (regression) | pure-docs | — | LOW (0 findings) | **LOW** unchanged ✓ |

**Bug AN (HIGH / production-impact)**：LlmJudge 對任何 `allowed-tools: Bash` 都打 OWASP-AS4 sev=8.5 → Anthropic canonical skills 全 HIGH → user trust 失靈。

**Fix S091 v2.61.0**：重寫 SYSTEM_PROMPT 區分 demonstrated vs theoretical risk + severity 分級規則 + anti-pattern 列表。

---

## Tick 82 — Round 35: S091 calibration regression sweep on R30 borderline (2026-05-02)

| # | Fixture | Pre-S091 | Post-S091 | Verdict |
|---|---------|----------|-----------|---------|
| R30.1 | read-only Bash (cat/ls/grep) | HIGH | **LOW** | ✓ FIXED |
| R30.2 | write /tmp (echo+cp) | HIGH | **LOW** | ✓ FIXED |
| R30.3 | git inspection | HIGH | **LOW** | ✓ FIXED |
| R30.4 | /etc/hostname (system info) | MEDIUM | **LOW** | ✓ same/better |
| R30.5 | docker ops | LOW | **LOW** | ✓ unchanged |

**結論**：S091 calibration fix 全方位驗證 — 4 個 over-classified HIGH 降為 LOW；R34 真風險 regression 仍 HIGH；無 false positive 過度，無 false negative 漏抓。

### Tick 82 Summary
- Round 35: 5 cases / **0 new bugs**
- S091 prompt calibration fix 在 R30 borderline 全 5 case 表現一致符合預期
- Calibration 是 1-shot fix（prompt 改一段）同時解決所有同類 over-classification









---

## Tick 83 — Round 36: User-visible string compliance (2026-05-07)

Cut axis: **User-visible string compliance**（spec ID leak / 英文 / 過時文字）

| # | 檢查項 | 結果 |
|---|--------|------|
| 1 | Spec ID 出現在 page render output | ✅ 無 spec ID 出現在 JSX return 文字 |
| 2 | TODO/FIXME/hardcoded English in production UI | ✅ 英文只在 placeholder + 技術術語（可接受）|
| 3 | RiskScannerScopePage 已 ship 功能仍標「規劃中」| ❌ **Bug AO** — LLM01/LLM04/LLM05/LLM06 描述過時 |
| 4 | EventPayloadPage 內部 dev reference 洩漏 | ❌ **Bug AO** — "PRD §B6 Backlog" 出現在用戶可見文字 |
| 5 | Coverage summary card 計數對齊 | ❌ **Bug AO** — Covered=2（應=3），Gap=1（應=0）|

**Bug AO (MEDIUM / user-visible)**：RiskScannerScopePage 四個 OWASP coverage item 描述仍引用「規劃中」spec（S099e1/e2/e3/e4），但這些 spec 皆已 ship；EventPayloadPage 顯示內部「PRD §B6 Backlog」dev reference。Fix：更新描述、LLM01 partial→covered、LLM04 gap→partial、summary card 3/0；移除 PRD reference。240/240 tests pass。

### Tick 83 Summary
- Round 36: 5 checks / **1 bug (AO)**
- Fix commit: 412ea5d

---

## Tick 84 — Round 37: Cross-cutting links (2026-05-07)

Cut axis: **Cross-cutting links**（所有路由都有導覽入口？）

| # | 路由 | 入口 | 結果 |
|---|------|------|------|
| 1 | `/browse` | AppShell nav | ✅ |
| 2 | `/collections` | AppShell nav | ✅ |
| 3 | `/requests` | AppShell nav | ✅ |
| 4 | `/flags` | AppShell nav | ✅ |
| 5 | `/my-skills` | AppShell nav / AuthArea | ✅ |
| 6 | `/analytics` | AppShell nav | ✅ |
| 7 | `/notifications` | AppShell bell icon | ✅ |
| 8 | `/skills/:id/diff` | VersionList.tsx "比較版本" link | ✅ |
| 9 | `/docs/*` 11 頁 | DocsSidebar 全覆蓋 | ✅ |
| 10 | `/search` | 無任何連結 | ❌ **Bug AP** |

**Bug AP (LOW / discoverability)**：`/search`（SearchResultsPage S094b）路由在整個 UI 沒有任何導覽入口，只能直接輸入 URL 到達。`docs/semantic-search` 文件說明語意搜尋原理但無「試用」CTA。Fix：在 SemanticSearchPage 加「試試語意搜尋 →」按鈕。

### Tick 84 Summary
- Round 37: 10 routes checked / **1 bug (AP)**
- Fix commit: 214c9c3

---

## Tick 85 — Round 38: Page-level data audit — SkillDetailPage v2 (2026-05-07)

Cut axis: **Page-level data audit**（SkillDetailPage v2 全端點 + fallback 路徑）

| # | 端點 | Hook / 來源 | Null/Error fallback | 結果 |
|---|------|-------------|---------------------|------|
| 1 | GET /skills/:id | useSkill / useSkillByAuthorAndName | 404 → 找不到此技能；500 → 載入失敗 | ✅ |
| 2 | GET /skills/:id/versions | useVersions | undefined → `?? []` | ✅ |
| 3 | GET /skills/:id/scores | useSkillScores | undefined → `?? null` → QualityTabV2 "尚未評分" fallback | ✅ |
| 4 | GET /skills/:id/security-report | useSecurityReport | 404 → null → SecurityTab "尚未掃描" fallback | ✅ |
| 5 | GET /skills/:id/stats?period=30d | useQuery direct | null → `?? []` | ✅ |
| 6 | GET /skills/:id/files/SKILL.md | useSkillFile | error → skillMdContent=null → SkillMdTab "暫不可用" | ✅ |

所有 6 個端點均有 graceful fallback；無 crash 路徑。

微小缺陷（非 user-visible bug）：SKILL.md 500 error 與 404 顯示同一 fallback 訊息，無區分。不開 bug 條目（對 user 無差異）。

### Tick 85 Summary
- Round 38: 6 endpoints audited / **0 bugs**

---

## Tick 86 — Round 39: User-visible string compliance — v2 components (2026-05-07)

Cut axis: **User-visible string compliance**（v2 components 英文字串審查）

| # | 元件 | 違規字串 | 結果 |
|---|------|---------|------|
| 1 | StatStrip.tsx relativeTime | 'today' / '1 day ago' / 'N days ago' | ❌ **Bug AQ** |
| 2 | StatStrip.tsx subtext | 'N reviews' / 'Latest ...' / 'No versions' / 'Active/No active reports' | ❌ **Bug AQ** |
| 3 | SecurityTab.tsx heroTitle | 'No security issues found' / English WARN/FAIL messages | ❌ **Bug AQ** |
| 4 | SecurityTab.tsx statusBadgeText | '✓ Passed' / '! Review' / '✗ Fail' | ❌ **Bug AQ** |
| 5 | SecurityTab.tsx metaSub | 'Scanned ... · engine ... · rule set ...' | ❌ **Bug AQ** |
| 6 | FileExplorerPanel.tsx | '⚠ This file is in scripts/ ...' / 'Binary file — preview unavailable' | ❌ **Bug AQ** |
| 7 | QualityTabV2.tsx | 'Show less' / 'Show more' | ❌ **Bug AQ** |
| 8 | VersionsTabV2.tsx | 'Latest' badge / dead-code 'Show less'/'Show more' | ❌ **Bug AQ** |
| 9 | InstallCard.tsx | 'Install' label / 'What are skills?' link | ❌ **Bug AQ** |

**Bug AQ (LOW / user-visible)**：S142a v2 components 含多處英文字串，違反 CLAUDE.md 「UI 語言: 繁體中文」政策。全數改為 zh-TW（今天/天前/則評論/最新版本/尚無版本/活躍回報/未發現安全問題/N 個問題需要審查/通過/需審查/失敗/掃描時間·引擎·規則集/已進行安全掃描/二進制檔案 — 無法預覽/顯示較少·更多/最新/安裝/什麼是技能？）；同步更新 9 個 test assertions。318/318 Vitest PASS。

### Tick 86 Summary
- Round 39: 9 元件審查 / **1 bug (AQ)** — 全數 inline 修復

---

## Tick 87 — Round 40: Interactive state consistency — SkillDetailPage v2 (2026-05-08)

Cut axis: **Interactive state consistency**（計數 / 狀態訊號一致性）

| # | 檢查項 | 結果 |
|---|--------|------|
| 1 | Files tab badge 資料來源 | ❌ **Bug AR-1** — `skill.versionCount`（版本數）誤當 `fileCount`；應使用 `versions[0].fileCount` |
| 2 | Tab 標籤英文 | ❌ **Bug AR-2** — Quality/Versions/Reviews/Security/Flags/Files 皆英文，違反 zh-TW 政策 |
| 3 | StatStrip 欄位標頭英文 | ❌ **Bug AR-3** — DOWNLOADS/RATING/VERSIONS/OPEN FLAGS + "vs last week" 英文 |
| 4 | StatStrip reviewCount=0 → 評分「—」對齊 ReviewsPanel empty state | ✅ 一致 |
| 5 | StatStrip openFlagCount > 0 → 紅色，FlagsList badge 紅色 | ✅ 一致 |
| 6 | VersionsTabV2 first card = latest version（與 Sidebar VersionHistoryMini 一致） | ✅ 一致 |
| 7 | AddVersionForm 成功後 versions + skill 兩個 query 均 invalidate | ✅ 一致（lines 213-216） |

**Bug AR (MEDIUM / user-visible + data-integrity)**：
- AR-1：Files tab badge 顯示 `skill.versionCount`（版本數 7 等）而非 `versions[0].fileCount`（實際檔案數），造成 badge 數字誤導用戶。修正：`const fileCount = versions?.[0]?.fileCount ?? 0`
- AR-2：7 個 tab 標籤 Quality/Versions/Reviews/Security/Flags/Files 改為 品質/版本/評論/安全性/旗標/檔案
- AR-3：StatStrip 四個標頭改為 下載次數/評分/版本數/待處理旗標；delta 副文字「vs last week」改為「相較上週」

同步更新 StatStrip.test.tsx 4 個斷言。318/318 Vitest PASS。

### Tick 87 Summary
- Round 40: 7 checks / **1 bug cluster (AR-1+2+3)** — 全數 inline 修復

---

## Tick 88 — Round 41: Component-context alignment — PageHeader / SecurityHeroCard / QualityHeroCard (2026-05-08)

Cut axis: **Component-context alignment**（跨 context 共用元件語意一致性）

| # | 元件 | 問題 | 結果 |
|---|------|------|------|
| 1 | PageHeader — VerifiedPill | 顯示 "Verified"（英文）| ❌ **Bug AS** |
| 2 | PageHeader — StarButton title | 'Unsubscribe'/'Subscribe' 英文 tooltip | ❌ **Bug AS** |
| 3 | PageHeader — 作者行 | "by {author}" / "Updated ..." 英文 prefix | ❌ **Bug AS** |
| 4 | SecurityHeroCard — overallLabel | 'Passed' / 'N Issue(s)' 英文 | ❌ **Bug AS** |
| 5 | SecurityHeroCard — subText | 'No known issues' 英文 | ❌ **Bug AS** |
| 6 | SecurityHeroCard — section header | 'SECURITY' 英文 | ❌ **Bug AS** |
| 7 | QualityHeroCard — section header | 'QUALITY' 英文 | ❌ **Bug AS** |
| 8 | QualityHeroCard — subText | 'Does it follow best practices?' 英文 | ❌ **Bug AS** |
| 9 | QualityHeroCard — breakdown labels | 'Validation/Implementation/Discovery' 英文，且 Discovery ≠ activation（語意不一致）| ❌ **Bug AS** |
| 10 | RiskBadge — 4 consumers all use `level` prop | ✅ 一致 |
| 11 | SkillScoreBadge — HeroMetricsRow 使用一致 | ✅ 一致 |

**Bug AS (LOW / user-visible + semantic consistency)**：
PageHeader / SecurityHeroCard / QualityHeroCard 包含多處英文字串，違反 CLAUDE.md zh-TW 政策。
另 QualityHeroCard breakdown "Discovery" 與 QualityTabV2 軸名 "觸發能力"（activation key）不一致。
全數改為繁體中文：已驗證/取消訂閱/訂閱/作者：/更新於/通過/N 個問題/無已知問題/安全性/品質/是否符合最佳實踐？/規格驗證/實作品質/觸發能力。
同步更新 SecurityHeroCard.test.tsx 2 assertions + PageHeader.test.tsx 1 assertion；318/318 Vitest PASS。

### Tick 88 Summary
- Round 41: 11 checks / **1 bug cluster (AS)** — 全數 inline 修復

---

## Tick 89 — Round 42: API projection field completeness (2026-05-08)

Cut axis: **API projection field completeness**（同 entity 跨 endpoint 欄位 consistent）

| # | 檢查項目 | 結果 |
|---|---------|------|
| 1 | `GET /skills` vs `GET /skills/{id}` — 6 個 `@Transient` 欄位（verified/latestVersionPublishedAt/license/compatibility/versionCount/openFlagCount）僅 detail enriched | ✅ 刻意設計（N+1 防止；list card 不使用這些欄位）|
| 2 | React Query cache key 隔離：list `['skills','list',params]` vs detail `['skills',id]` — 無互汙染 | ✅ 正確隔離 |
| 3 | `ScoreResponse` 後端型別 vs 前端 `SkillScores` 介面 shape 一致性 | ✅ 完全吻合 |
| 4 | `SecurityReportResponse` 後端型別 vs 前端 `SecurityReport` 介面 shape 一致性 | ✅ 完全吻合 |
| 5 | `SkillVersion` — `fileCount`/`fileSize` 是否正確暴露於 API（非 @JsonIgnore）| ✅ 正確暴露（`storagePath` 才是 @JsonIgnore 保護的 GCS 路徑）|

**0 bugs** — API projection 欄位一致性無問題。

### Tick 89 Summary
- Round 42: 5 checks / **0 bug** — `🔍 NO-BUGS-MODE-B`

---

## Tick 90 — Round 43: Control-behavior alignment — v2 SkillDetailPage interactive controls (2026-05-08)

Cut axis: **Control-behavior alignment**（button/chip label 與實際行為 1:1 mapping）

| # | 元件 | 檢查項目 | 結果 |
|---|------|---------|------|
| 1 | HeroMetricsRow → QualityHeroCard onClick | `onTabChange('quality')` → TabsTrigger value='quality' ✓ | ✅ |
| 2 | HeroMetricsRow → SecurityHeroCard onClick | `onTabChange('security')` → TabsTrigger value='security' ✓ | ✅ |
| 3 | VersionHistoryMini "查看全部 →" | `onTabChange('versions')` → TabsTrigger value='versions' ✓ | ✅ |
| 4 | VersionHistoryMini latest badge text | 顯示 "latest"（英文）| ❌ **Bug AT** |
| 5 | DetailsCard row labels (5 個) | 'Published'/'License'/'Size'/'Files'/'Scripts' + value 'None' 全英文 | ❌ **Bug AT** |
| 6 | SecurityTab empty state | 'Security report 尚未掃描'（英中混用）| ❌ **Bug AT** |
| 7 | SecurityTab ShieldIcon aria-label | `Security ${overall}` 英文 aria-label | ❌ **Bug AT** |
| 8 | QualityTabV2 expand toggle | 展開=true 初始→按鈕 '顯示較少'，收合→'顯示更多'，行為一致 | ✅ |
| 9 | InstallCard copy button | ⧉ → ✓ 複製後 1500ms reset，aria-label 正確 | ✅ |
| 10 | StarButton subscribe toggle | subscribed/unsubscribed title='取消訂閱'/'訂閱'，行為一致 | ✅ |
| 11 | AddVersionForm submit button | `mutation.isPending ? '上傳中...' : '新增'`，disabled 邏輯正確 | ✅ |

**Bug AT (LOW / user-visible zh-TW violation)**：
VersionHistoryMini 顯示 "latest"（英文）而非"最新"，與 VersionsTabV2 不一致。
DetailsCard 5 個 row label 全為英文（Published/License/Size/Files/Scripts）及 value 'None'。
SecurityTab empty state 英中混用、ShieldIcon aria-label 英文。
全數改為繁體中文，同步更新 SecurityTab.test.tsx + Sidebar.test.tsx；318/318 Vitest PASS。

### Tick 90 Summary
- Round 43: 11 checks / **1 bug cluster (AT)** — 全數 inline 修復

---

## Tick 91 — Round 44: Accessibility — aria-label / keyboard nav / role (2026-05-08)

Cut axis: **Accessibility**（鍵盤導航 / aria-label / role / focus order）

| # | 元件 | 檢查項目 | 結果 |
|---|------|---------|------|
| 1 | QualityHeroCard — 互動 div | 無 `role="button"` / `tabIndex` / `onKeyDown` → 鍵盤無法觸發 | ❌ **Bug AU** |
| 2 | SecurityHeroCard — 互動 div | 無 `role="button"` / `tabIndex` / `onKeyDown` → 鍵盤無法觸發 | ❌ **Bug AU** |
| 3 | SkillScoreBadge SVG | `aria-label="Skill score ring"` 英文；SVG 內文字 "SKILL SCORE" 英文 | ❌ **Bug AU** |
| 4 | FileExplorerPanel tree node | 已使用 `<button>` | ✅ |
| 5 | VersionHistoryMini "查看全部 →" | `<button>` ✓ | ✅ |
| 6 | InstallCard copy button | `<button>` + `aria-label` zh-TW ✓ | ✅ |
| 7 | PageHeader StarButton | `AuthGatedButton` → `<button>` ✓ | ✅ |
| 8 | PageHeader 分享/下載 buttons | `<button type="button">` ✓ | ✅ |
| 9 | QualityTabV2 expand toggle | `<button>` ✓ | ✅ |
| 10 | AddVersionForm submit | `<button type="submit">` disabled 邏輯正確 ✓ | ✅ |

**Bug AU (MEDIUM / accessibility)**：
QualityHeroCard + SecurityHeroCard 用 `<div onClick>` 無 `role="button"` + `tabIndex={0}` + `onKeyDown`；
鍵盤使用者無法以 Enter/Space 觸發品質/安全性分頁切換。
SkillScoreBadge SVG `aria-label` 及內部文字均為英文。
修復：加 `role="button"` + `tabIndex={0}` + `aria-label` + `onKeyDown` 至兩個 hero card；
SVG `aria-label` → "技能分數環狀圖"；SVG 內文字 "SKILL SCORE" → "技能分數"。
同步更新 SkillScoreBadge.test.tsx；318/318 Vitest PASS。

### Tick 91 Summary
- Round 44: 10 checks / **1 bug cluster (AU)** — 全數 inline 修復

---

## Tick 92 — Round 45: Anonymous vs authenticated flow 比對 (2026-05-08)

Cut axis: **Anonymous vs authenticated flow 比對**（未登入/登入使用者看到不同 UI 的正確性）

| # | 元件 | 檢查項目 | 結果 |
|---|------|---------|------|
| 1 | AuthGatedButton (StarButton) | anonymous click → `auth.login()` redirect ✓ | ✅ |
| 2 | isOwner 判斷 | `!!skill && !!me && skill.ownerId === me.sub`；anonymous → me=undefined → isOwner=false ✓ | ✅ |
| 3 | PageHeader 分享按鈕 | `isOwner && onShareClick &&` 才顯示 ✓ | ✅ |
| 4 | AddVersionForm | `{skill.status !== 'SUSPENDED' && <AddVersionForm />}` — 缺 `isOwner` gate！非 owner 可見版本上傳表單 | ❌ **Bug AV** |
| 5 | MarkdownActionMenu | 程式碼 comment 標 "owner only" 但 S133 AC-5 確認應對 PUBLISHED skill 全訪客可見；comment 錯誤 | ❌ **Bug AV（注釋）** |
| 6 | ReviewsPanel "撰寫評論" | anonymous 時 currentUserId=undefined，`!myReview` 永遠 true → 表單可見；MVP permit-all 下可接受 | ✅（MVP 可接受）|
| 7 | FlagsList "回報問題" | anonymous 可見；MVP permit-all 下為社群功能，設計如此 | ✅（MVP 可接受）|
| 8 | MarkdownActionMenu 功能 | copy SKILL.md — 讀取操作，全訪客可用 ✓ | ✅ |
| 9 | useMe() 失敗時 | query error → me=undefined → isOwner=false，owner gate 失效安全 ✓ | ✅ |
| 10 | 下載技能 CTA | `skill.status === 'PUBLISHED'` 才顯示；無 auth gate（MVP 設計）✓ | ✅ |

**Bug AV (MEDIUM / auth/UX)**：
`AddVersionForm` 未檢查 `isOwner`，非 owner（含 anonymous）可見版本上傳表單。
新增版本是 owner-only 寫入操作；前端 UI 應與所有權語意對齊，防止誤導性 UX。
另 MarkdownActionMenu 旁的 comment "owner only" 與 S133 AC-5 規格不符，已更正。
修復：加入 `isOwner &&` 判斷；更新 comment；318/318 Vitest PASS。

### Tick 92 Summary
- Round 45: 10 checks / **1 bug cluster (AV)** — 全數 inline 修復

---

## Tick 93 — Round 46: Cross-cutting links — 內部 SPA 路由 callsite 一致性 (2026-05-08)

Cut axis: **Cross-cutting links**（所有 Link/navigate callsite 對齊 router 定義）

| # | 元件 | 路由/連結 | 結果 |
|---|------|---------|------|
| 1 | AppShell logo `<Link to="/">` | / → LandingPage ✓ | ✅ |
| 2 | AppShell notifications `<Link to="/notifications">` | /notifications → NotificationsPage ✓ | ✅ |
| 3 | AuthArea "我的技能" `<Link to="/my-skills">` | /my-skills → MySkillsPage ✓ | ✅ |
| 4 | InstallCard `<a href="/docs/your-first-skill">` | 使用 `<a>` 而非 `<Link>`，SPA 內部路由全頁重載 | ❌ **Bug AW** |
| 5 | PublishPage `<a href="/docs/skill-md-spec">` | 使用 `<a>` 而非 `<Link>`，SPA 內部路由全頁重載 | ❌ **Bug AW** |
| 6 | docs 頁面間導航 Links (OverviewPage/FrontmatterPage/etc.) | 全部使用 `<Link to>` ✓ | ✅ |
| 7 | navigate('/publish/validate') / navigate('/publish/review') / navigate('/publish/failed') | 路由全部定義 ✓ | ✅ |
| 8 | navigate('/browse') (SearchResultsPage) | 路由已定義 ✓ | ✅ |
| 9 | navigate('/search?q=...') | /search → SearchResultsPage ✓ | ✅ |
| 10 | OverviewPage Swagger UI `<a href="/swagger-ui/index.html">` | 外部/伺服器路由（非 SPA）— `<a>` 正確 | ✅ |

**Bug AW (LOW / SPA UX)**：
`InstallCard` 和 `PublishPage` 各有一個 `<a href>` 連結指向 SPA 內部路由 `/docs/...`，
導致點擊時全頁重載而非 React Router 客戶端導航，破壞 SPA 體驗。
修復：改用 `<Link to>`；同步更新 InstallCard.test.tsx + Sidebar.test.tsx 加入 MemoryRouter wrapper；318/318 Vitest PASS。

### Tick 93 Summary
- Round 46: 10 checks / **1 bug cluster (AW)** — 全數 inline 修復

---

## Tick 94 — Round 47: Page-level data audit (2026-05-08)

Cut axis: **Page-level data audit**（每個 page 是否 fetch 真實 endpoint，無假資料/hardcoded stub）

| # | 頁面 | 資料來源 | 結果 |
|---|------|---------|------|
| 1 | LandingPage | `fetchPublicStats` → `GET /api/v1/skills/stats`；`?? '—'` fallback ✓ | ✅ |
| 2 | HomePage | `useSkillList` → `GET /api/v1/skills` ✓ | ✅ |
| 3 | SearchResultsPage | `useSearchIntent` → 真實 search hook ✓ | ✅ |
| 4 | SkillDetailPage | `useSkill` + `useVersions` + `useScores` + `useSecurityReport` → 真實 API ✓ | ✅ |
| 5 | VersionDiffPage | `useVersionDiff` + `useFileListDiff` + `useSkill` + `useVersions` → 真實 API ✓ | ✅ |
| 6 | PublishPage | 表單（無 pre-fetch）+ `useMutation` 提交 ✓ | ✅ |
| 7 | PublishValidatePage | `useQuery` + `fetchSkillById` + polling interval ✓ | ✅ |
| 8 | PublishReviewPage | `useQuery` + `fetchSkillById` ✓ | ✅ |
| 9 | PublishFailedPage | 靜態錯誤提示頁（無 fetch 需求）✓ | ✅ |
| 10 | MySkillsPage | `useMe` + `useSkillList` → 真實 API ✓ | ✅ |
| 11 | AnalyticsPage | `useOverview` → `GET /api/v1/analytics/overview` ✓ | ✅ |
| 12 | CollectionsPage | `useCollections` → `fetchCollections` 真實 API ✓ | ✅ |
| 13 | RequestBoardPage | `useRequests` → `fetchRequests` 真實 API ✓ | ✅ |
| 14 | NotificationsPage | 真實 API hooks ✓ | ✅ |
| 15 | FlagsQueuePage | `useMutation` + `useQueryClient` 真實 API ✓ | ✅ |
| 16 | AuthDebugPage | `useQuery` → `fetchAuthDebug` → `GET /api/v1/dev/auth-debug`（404 fallback）✓ | ✅ |
| 17 | docs/* 頁面群 | 靜態 Markdown 說明文件（無 API 需求）✓ | ✅ |

**0 bug**：所有頁面使用真實 API hooks；無 hardcoded stub 或假資料。

### Tick 94 Summary
- Round 47: 17 checks / **0 bugs** — 全部通過

---

## Tick 95 — Round 48: Interactive state consistency (2026-05-08)

Cut axis: **Interactive state consistency**（filter / pagination / count / empty state 四信號對齊）

| # | 元件 / 頁面 | 檢查項目 | 結果 |
|---|------------|---------|------|
| 1 | HomePage — riskFilter toggle | `filteredSkills` 即時更新；count 切換 filtered/total context ✓ | ✅ |
| 2 | HomePage — category 切換 | `handleCategorySelect` 呼叫 `setPage(0)` ✓ | ✅ |
| 3 | HomePage — search 輸入 | `handleSearch` 呼叫 `setPage(0)` ✓ | ✅ |
| 4 | HomePage — **sortMode 切換** | `setSortMode(mode)` 未呼叫 `setPage(0)`；第 3 頁切換排序停在第 3 頁 | ❌ **Bug AX** |
| 5 | HomePage — count signal | riskFilter active → `filteredSkills.length`（當頁過濾）+ `totalElements`；設計已知限制 ✓ | ✅（設計已知）|
| 6 | HomePage — pagination guard | `totalPages > 1 && filteredSkills.length > 0` 才顯示 ✓ | ✅ |
| 7 | HomePage — empty state (no filter) | `SkillCardGrid` 空陣列 → seed tone / redirect tone 依 query ✓ | ✅ |
| 8 | HomePage — empty state (filter active, 0 hits) | `filteredSkills.length === 0 && riskFilter.size > 0` → redirect EmptyState + 清除篩選 ✓ | ✅ |
| 9 | CollectionsPage — filter empty state | `filtered.length === 0 → redirect EmptyState`；no-collections 優先 → invite EmptyState ✓ | ✅ |
| 10 | MySkillsPage — tab filter count | TabPill 顯示 `(total)` / `(published)` / `(drafts)` / `(suspended)` 隨資料動態 ✓ | ✅ |
| 11 | MySkillsPage — tab empty state | `filteredSkills.length === 0` → inline "此分類無技能"（非 EmptyState component，輕量可接受）✓ | ✅ |
| 12 | MySkillsPage — 英文標籤 | 下載數下方顯示 "downloads"（英文）— 不同 cut 軸，記錄為待查 Bug AY 候選 | 📝 |

**Bug AX (LOW / interactive state)**：
`HomePage` 排序 chip 的 `onClick` 只呼叫 `setSortMode(mode)`，未同時呼叫 `setPage(0)`。
分類切換與搜尋輸入皆會重置頁碼，排序切換卻不會，導致狀態不一致（用戶在第 3 頁切換排序仍停留第 3 頁新排序結果）。
修復：`onClick={() => { setSortMode(mode); setPage(0) }}`；加入 AC-S48 測試驗證重置行為。
319/319 Vitest PASS。

### Tick 95 Summary
- Round 48: 12 checks / **1 bug (AX)** — 全數 inline 修復

---

## Tick 96 — Round 49: User-visible string compliance (2026-05-08)

Cut axis: **User-visible string compliance**（全局 English strings scan — CLAUDE.md 繁體中文政策）

| # | 元件 / 頁面 | 英文字串 | 結果 |
|---|------------|---------|------|
| 1 | MySkillsPage line 237 | `downloads` sub-label under download count number | ❌ **Bug AY** |
| 2 | AnalyticsPage MetricCard subtitle | `subtitle="rolling 7-day"` | ❌ **Bug AY** |
| 3 | AnalyticsPage MetricCard value | `` `Top ${stats.topSkills.length}` `` | ❌ **Bug AY** |
| 4 | AnalyticsPage h2 heading | `熱門技能 Top 10` | ❌ **Bug AY** |
| 5 | StatStrip.tsx labels | 下載次數 / 評分 / 版本數 / 待處理旗標 — 全 zh-TW ✓ | ✅ |
| 6 | SkillCard.tsx visible text | 全 zh-TW（category/status/version pill）✓ | ✅ |
| 7 | ReviewsPanel.tsx | 全 zh-TW ✓ | ✅ |
| 8 | FlagsQueuePage.tsx | 全 zh-TW ✓ | ✅ |
| 9 | docs/RestApiPage "Analytics" | API 文件 section 標題，技術文件可接受 ✓ | ✅（文件頁）|
| 10 | docs/* Code component 包裹的代碼 | `<Code>name</Code>` 等為代碼片段，非 UI 標籤 ✓ | ✅ |
| 11 | AnalyticsPage `{skill.downloads}` | 資料數值，非 UI 標籤 ✓ | ✅ |
| 12 | docs/RiskScannerScopePage "OWASP LLM Top 10 v1.1" | 業界標準固有名詞，應保留英文 ✓ | ✅（固有名詞）|

**Bug AY (LOW / i18n)**：
`MySkillsPage` 下載數下方顯示 "downloads"；`AnalyticsPage` 含 "rolling 7-day"、"Top N"、"Top 10" 英文標籤，
違反 CLAUDE.md 繁體中文政策（所有前端頁面皆使用繁體中文）。
修復：`downloads` → `次下載`；`rolling 7-day` → `近 7 天`；`Top ${n}` → `前 ${n} 名`；`Top 10` → `前 10 名`。
319/319 Vitest PASS。

### Tick 96 Summary
- Round 49: 12 checks / **1 bug cluster (AY)** — 全數 inline 修復

---

## Tick 97 — Round 50: Negative deep-link (2026-05-08)

Cut axis: **Negative deep-link**（`/skills/null` / 不存在 ID / 超長 query / 邊緣 URL）

| # | 測試 URL / 情境 | 前端行為 | 結果 |
|---|---------------|---------|------|
| 1 | `/skills/null` | id="null" → `useSkill("null")` → API 404 → "找不到此技能" + 返回列表 ✓ | ✅ |
| 2 | `/skills/undefined` | id="undefined" → 同上 ✓ | ✅ |
| 3 | `/skills/not-a-real-uuid` | id=non-existent → API 404 → error state ✓ | ✅ |
| 4 | `/skills/null/null` | author/name route → `useSkillByAuthorAndName("null","null")` → API 404 → error state ✓ | ✅ |
| 5 | `/skills/:id/diff`（無 from/to）| from=null, to=null → `useVersionDiff` disabled；版本數 < 2 → "技能版本不足 2 個" ✓ | ✅ |
| 6 | `/publish/validate`（無 id）| `!skillId` guard → ErrorState "缺少 skill id 參數" ✓ | ✅ |
| 7 | `/publish/review`（無 id）| 同 guard pattern ✓ | ✅ |
| 8 | `/publish/failed`（無 state）| `stateRaw !== 'B'` → default A → state A UI 顯示 ✓ | ✅ |
| 9 | `/search?q=`（空 query）| `!query.trim()` → invite EmptyState "輸入一句描述或關鍵字搜尋技能" ✓ | ✅ |
| 10 | `*`（任何未定義路由）| `<Route path="*">` → NotFoundPage "404 / 找不到此頁面" ✓ | ✅ |
| 11 | SearchResultsPage 結果後置文字 | `"ranked by semantic similarity · embeddings via Gemini"` 英文 | ❌ **Bug AZ**（bonus）|
| 12 | SearchResultsPage 頁尾連結 | `"看 description writing tips →"` 英文 | ❌ **Bug AZ**（bonus）|

**0 deep-link bug**：所有邊緣 URL 均有適當 guard 或 fallback。

**Bug AZ (LOW / i18n 跨 round bonus)**：
SearchResultsPage 兩個英文可見標籤：
`"ranked by semantic similarity · embeddings via Gemini"` → `"以語意相似度排序 · 向量由 Gemini 建立"`；
`"看 description writing tips →"` → `"看描述撰寫技巧 →"`。
319/319 Vitest PASS。

### Tick 97 Summary
- Round 50: 12 checks / **0 deep-link bugs; 1 bonus Bug AZ (i18n) 全數修復**

---

## Tick 98 — Round 51: Component-context alignment (2026-05-08)

Cut axis: **Component-context alignment**（共用元件在多 context 的語意一致性）

### EmptyState 4 tones 使用對齊

| # | 元件 | Context / 使用位置 | tone | 語意 | 結果 |
|---|------|------------------|------|------|------|
| 1 | EmptyState | SkillCardGrid (0 skills, 無 query) | seed | 平台空殼，邀請第一筆 skill | ✅ |
| 2 | EmptyState | SkillCardGrid (0 results, 有 query) | redirect | 搜尋 0 結果，導流 | ✅ |
| 3 | EmptyState | HomePage (filter-active 0 hits) | redirect | 篩選 0 結果 + 清除篩選 CTA | ✅ |
| 4 | EmptyState | HomePage (semantic search 0 results) | redirect | 語意搜尋 0 結果 + suggestions | ✅ |
| 5 | EmptyState | SearchResultsPage (no query) | invite | 邀請輸入 query | ✅ |
| 6 | EmptyState | SearchResultsPage (0 semantic results) | redirect | 語意搜尋 0 結果 | ✅ |
| 7 | EmptyState | CollectionsPage (0 collections total) | invite | 邀請建立集合 | ✅ |
| 8 | EmptyState | CollectionsPage (filter 0 results) | redirect | 篩選 0 結果 | ✅ |
| 9 | EmptyState | MySkillsPage (not logged in) | invite | 邀請登入 | ✅ |
| 10 | EmptyState | MySkillsPage (0 skills) | invite | 邀請發布第一個技能 | ✅ |
| 11 | EmptyState | NotificationsPage (anonymous) | invite | 邀請登入 | ✅ |
| 12 | EmptyState | NotificationsPage (0 notifications) | clear | 全部已讀 + celebratory stats | ✅ |
| 13 | EmptyState | RequestBoardPage (0 requests) | invite | 邀請提出需求 | ✅ |
| 14 | EmptyState | FlagsQueuePage (0 flags) | clear | 沒有待審回報 | ✅ |

### MetricCard 跨 context 一致性

| # | Context | labels | 語意 | 結果 |
|---|---------|--------|------|------|
| 15 | AnalyticsPage | 總技能數 / 總下載次數 / 本週新增 / 熱門排行 | platform-level aggregate metrics | ✅ |
| 16 | MySkillsPage | 技能總數 / 下載總數 / 待處理回報 | author-level personal metrics | ✅ |

### RiskBadge 跨 context 一致性

| # | Context | 用途 | 結果 |
|---|---------|------|------|
| 17 | SkillCard | 瀏覽列表顯示 riskLevel badge | ✅ |
| 18 | MySkillsPage SkillRow | 作者儀表板列表顯示 riskLevel | ✅ |
| 19 | PublishReviewPage | 發布審核流程顯示 riskLevel | ✅ |

### IconTile 跨 context size 適配

| # | Context | size | 用途 | 結果 |
|---|---------|------|------|------|
| 20 | SkillCard | md | browse 卡片 | ✅ |
| 21 | MySkillsPage SkillRow | sm | 作者儀表板 table row | ✅ |
| 22 | v2/PageHeader | xl | 技能詳情頁 hero | ✅ |

**Bug BA (MEDIUM / SPA routing)**：
`EmptyState` 的 `PrimaryButton` / `SecondaryButton` / `ClearTone` auditLink 使用原生 `<a href>` 而非 React Router `<Link to>` — 點擊後觸發完整頁面 reload 而非 SPA client-side navigation。
影響範圍：5 個 callsite（`/browse` × 3、`/publish` × 1、`/docs/...` × 1）。
修復：`EmptyState.tsx` 改用 `<Link to>`；`EmptyState.test.tsx` AC-1 / AC-4 補 `MemoryRouter` wrapper。
319/319 Vitest PASS。

### Tick 98 Summary
- Round 51: 22 checks / **1 bug (BA) — SPA routing regression 修復**

---

## Tick 99 — Round 52: Control-behavior alignment (2026-05-08)

Cut axis: **Control-behavior alignment**（chip / button label 與 behavior 1:1 mapping）

| # | 控制元件 | label | 實際行為 | 結果 |
|---|---------|-------|---------|------|
| 1 | HomePage sort chip「推薦」| recommended | `sort=downloadCount,desc` | ✅ |
| 2 | HomePage sort chip「最新」| newest | `sort=createdAt,desc` | ✅ |
| 3 | HomePage sort chip「風險低」| risk-low | `sort=riskLevel,asc` | ✅ |
| 4 | HomePage sort chip「下載最多」| most-downloaded | `sort=downloadCount,desc`（同 recommended，S106 intentional）| ✅ |
| 5 | RiskFilterSidebar「全部」| clear | `setRiskFilter(new Set())` — 清空全部篩選 | ✅ |
| 6 | RiskFilterSidebar「無風險」| NONE tier | `toggleRisk('NONE')` — 加/移該 tier | ✅ |
| 7 | RiskFilterSidebar「低風險」| LOW tier | `toggleRisk('LOW')` | ✅ |
| 8 | RiskFilterSidebar「中風險」| MEDIUM tier | `toggleRisk('MEDIUM')` | ✅ |
| 9 | RiskFilterSidebar「高風險」| HIGH tier | `toggleRisk('HIGH')` | ✅ |
| 10 | RiskFilterSidebar TIER_DOT 顏色 | NONE=gray `#A8A49C`, LOW=green `#6FD8B0` | RiskBadge: NONE=green `#6FD8B0`, LOW=blue `#B0D5F2` | ❌ **Bug BB** |
| 11 | MySkillsPage tab「全部」| all | `filteredSkills = allSkills`（no status filter）| ✅ |
| 12 | MySkillsPage tab「已發布/草稿/已停用」| PUBLISHED/DRAFT/SUSPENDED | `filter(s.status === tab)` | ✅ |
| 13 | NotificationsPage filter「全部/回報/評論/需求」| all/flags/reviews/requests | `useNotifications(filter)` | ✅ |
| 14 | CategorySidebar 分類 button | category label | `handleCategorySelect(cat)` → API `category=` param | ✅ |
| 15 | HomePage pagination「下一頁」| next page | `setPage(page + 1)` | ✅ |
| 16 | HomePage pagination「上一頁」| prev page | `setPage(page - 1)` | ✅ |
| 17 | SearchResultsPage form submit | search action | `navigate(/search?q=...)` | ✅ |

**Bug BB (LOW / visual consistency)**：
`RiskFilterSidebar` TIER_DOT 顏色與 `RiskBadge` 語意色彩不一致：
側邊欄 NONE dot = gray `#A8A49C`（應為 success-green `#6FD8B0`）；
LOW dot = green `#6FD8B0`（應為 info-blue `#B0D5F2`）。
元件 comment 明確宣告「對齊 RiskBadge 的顏色」但值錯誤，造成使用者看到 sidebar dot 顏色與卡片 badge 不符。

修復：`TIER_DOT.NONE = 'bg-[#6FD8B0]'`、`TIER_DOT.LOW = 'bg-[#B0D5F2]'`（與 RiskBadge fg 對齊）。
319/319 Vitest PASS。

### Tick 99 Summary
- Round 52: 17 checks / **1 bug (BB) — 視覺語意色彩 dot 修復**

---

## Tick 100 — Round 53: API projection field completeness (2026-05-08)

Cut axis: **API projection field completeness**（同 entity 跨 endpoint 欄位一致性）

| # | Entity | 比較端點 | 欄位一致性 | 結果 |
|---|--------|---------|----------|------|
| 1 | Skill | GET /skills (list) | id/name/desc/author/category/latestVersion/riskLevel/status/downloadCount/averageRating/reviewCount/aclEntries/ownerId/createdAt/updatedAt ✓ | ✅ |
| 2 | Skill | GET /skills/{id} (detail) | 同上 + 6 個 S142b fields（verified/latestVersionPublishedAt/license/compatibility/versionCount/openFlagCount）| ✅ by design |
| 3 | Skill S142b gap | list vs detail | 6 個 S142b 欄位僅 detail endpoint 填充（enrichDetail()），list 返回預設值（false/null/0/[]）。前端 Skill type 宣告這些欄位但 list context 不使用 → by design（N+1 avoidance，避免 20 筆 × 額外查詢）| ✅ 設計決定 |
| 4 | SemanticSearchResult | GET /search/semantic | Backend record 與 frontend type 完全對齊：id/name/desc/author/category/latestVersion/riskLevel/downloadCount/score | ✅ |
| 5 | SkillCollection | GET /collections (list) | summary: id/name/desc/category/skillCount/installCount/maxRiskLevel/createdAt — 匹配 frontend SkillCollection interface | ✅ |
| 6 | SkillCollection | GET /collections/{id} (detail) | detail: id/name/desc/category/ownerId/installCount/createdAt/skills — 匹配 frontend CollectionDetail | ✅ |
| 7 | CollectionSkillSummary | collections detail.skills | backend record(id/name/category/riskLevel/latestVersion) ↔ frontend interface ✓ | ✅ |
| 8 | SkillRequest | GET /requests (list) + GET /requests/{id} | 同一個 RequestResponse record(id/title/desc/requesterId/status/claimerId/fulfilledSkillId/voteCount/createdAt/updatedAt) | ✅ |
| 9 | OverviewStats.TopSkill | GET /analytics/overview | backend record(name,author,downloads) ↔ frontend {name,author?,downloads} ✓（author optional per S100e guard）| ✅ |
| 10 | SkillVersion | GET /skills/{id}/versions | frontend type(id/skillId/version/fileSize/fileCount/publishedAt) 是 backend 的子集（backend 多 storagePath/frontmatter 不暴露給 UI）| ✅ |
| 11 | FlagsSummary | GET /me/flags-summary | backend: Map.of("openCount", count) ↔ frontend: {openCount: number} ✓ | ✅ |
| 12 | Notification | GET /notifications | backend record(id/category/title/body/skillId/refEventId/readAt/createdAt) ↔ frontend interface ✓ | ✅ |

**0 bugs — 全部欄位投影一致（或有設計文件說明差異）。**

### Tick 100 Summary
- Round 53: 12 checks / **0 bugs** — API projection field completeness 全通過

---

## Tick 101 — Round 54: Anonymous vs authenticated flow (2026-05-08)

Cut axis: **Anonymous vs authenticated flow**（未登入與登入後 UI 差異比對；auth gate 完整性）

| # | 元件 / 頁面 | anonymous UI | authenticated UI | auth gate 正確？ | 結果 |
|---|-----------|-------------|-----------------|----------------|------|
| 1 | AppShell header — `AuthArea` | 顯「登入」按鈕（S139 AC-1）→ 點擊跳 OAuth | 顯 avatar dropdown（含我的技能 / 登出） | useAuth 3 state 切換 ✓ | ✅ |
| 2 | AppShell header — 鈴鐺圖示 | 不顯示（S139 + S096h1 條件 render）| 顯示 + unread badge poll | `isAuthenticated` gate ✓ | ✅ |
| 3 | MySkillsPage | EmptyState invite + 登入按鈕（`!author` gate）| 完整 dashboard（hero + metric cards + skill list）| `me.sub` 缺值 → early return ✓ | ✅ |
| 4 | NotificationsPage | EmptyState invite + 登入按鈕（`auth.status === 'anonymous'` early return）| 通知列表 + filter chips + 全部已讀 + 設定 | `useAuth.status` gate ✓ | ✅ |
| 5 | PublishPage submit | 點「發布技能」跳 OAuth（lazy gate in `handleSubmit`）| 執行上傳 mutation | `auth.status !== 'authenticated'` → `auth.login()` ✓ | ✅ |
| 6 | RequestBoardPage「發起新需求」| AuthGatedButton → OAuth redirect | 開 CreateRequestModal | AuthGatedButton ✓ | ✅ |
| 7 | CollectionsPage「建立集合」| AuthGatedButton → OAuth redirect | 開 CreateCollectionModal | AuthGatedButton ✓ | ✅ |
| 8 | FlagsQueuePage — Resolve/Dismiss | AuthGatedButton → OAuth redirect（flags list 仍可讀，Feature First §2.1）| 執行 PATCH mutation | AuthGatedButton ✓；intentional 無頁面 gate（MVP 所有人可看 flags list） | ✅ |
| 9 | SkillDetailPage — 訂閱按鈕（StarButton） | AuthGatedButton → OAuth redirect | subscribe/unsubscribe mutation | AuthGatedButton ✓（`isOwner=true` 時隱藏按鈕）| ✅ |
| 10 | SkillDetailPage — AddVersionForm | 不顯示（`isOwner` = false when me=undefined）| 顯示（owner 才有 `isOwner=true`）| `isOwner = !!skill && !!me && ownerId===me.sub` gate ✓ | ✅ |
| 11 | ReviewsPanel 0-reviews「撰寫評論」| 應 → OAuth redirect | 開 ReviewForm modal | **Bug BC** — 使用 plain `<button>` 而非 AuthGatedButton，anonymous 可開 form；submit 才得 401 | ❌ |
| 12 | ReviewsPanel N-reviews「撰寫評論」| 應 → OAuth redirect | 開 ReviewForm modal | **Bug BC** 同上 | ❌ |

**Bug BC (MEDIUM / auth gap)**：
`ReviewsPanel` 兩處「撰寫評論」按鈕使用 plain `<button>` 未套 `AuthGatedButton`。
anonymous 使用者可開啟 ReviewForm modal，輸入評論後 submit → backend 回 401 → 顯示錯誤訊息。
與 S139 lazy gate 一致性原則不符（其他寫入動作均走 AuthGatedButton）。

修復：
1. `ReviewsPanel` 加 `import { AuthGatedButton }` — 兩個寫入 CTA 換成 `<AuthGatedButton>`
2. 0-reviews EmptyState primaryAction 移除（不傳 onClick），改在 EmptyState 外獨立渲染 `AuthGatedButton`
3. `ReviewsPanel.test.tsx`：
   - 加 `vi.mock('../hooks/useAuth')` + `mockUseAuth.mockReturnValue(authenticated)` in `beforeEach`
   - `mockFetchByUrl` 補 `/api/v1/me` handler
319/319 Vitest PASS。tsc clean。

### Tick 101 Summary
- Round 54: 12 checks / **1 bug (BC) — ReviewsPanel 寫入 CTA 缺 auth gate 修復**

---

## Tick 102 — Round 55: Negative deep-link (2026-05-08)

Cut axis: **Negative deep-link**（`/skills/null` / 不存在 author / 超長 query string）

| # | 測試 URL / 情境 | 預期行為 | 實際行為 | 結果 |
|---|----------------|---------|---------|------|
| 1 | `/skills/null` | 404 error state "找不到此技能" | `useSkill('null')` fires → backend 404 → SkillDetailPage "找不到此技能" | ✅ |
| 2 | `/skills/undefined` | 404 error state | 同 #1，string 'undefined' → 404 | ✅ |
| 3 | `/skills/null/null` | 404 error state | `useSkillByAuthorAndName('null','null')` → 404 → "找不到此技能" | ✅ |
| 4 | `/skills/` (trailing slash) | 匹配 `/skills` route → HomePage | React Router：trailing slash stripped，match `/skills` → HomePage | ✅ |
| 5 | `/skills/a/b/c` (三段路徑) | NotFoundPage | 不匹配任何 route → `<Route path="*" />` → NotFoundPage | ✅ |
| 6 | `/skills/null/diff` | 錯誤 state（略帶誤導）| `useVersions('null')` error → "技能版本不足 2 個，無法比較"（message 略誤導；skill 不存在時仍安全）| ✅ note |
| 7 | `/publish/validate?id=` (empty) | ErrorState "缺少 skill id 參數" | `!skillId` guard at line 61 → correct ErrorState | ✅ |
| 8 | `/publish/review?id=` (empty) | ErrorState | `!skillId` guard → ErrorState | ✅ |
| 9 | `/publish/failed?state=B` (no id) | StateBHighRiskReview，隱藏 id 行 | `id=null` → `{id && <p>技能 ID</p>}` 不顯 id 行；不 crash | ✅ |
| 10 | `/search?q=` (empty q) | EmptyState invite（不觸發 API）| `!query.trim()` → EmptyState invite，`enabled: false` | ✅ |
| 11 | `/search` (no q param) | 同 #10 | `searchParams.get('q')` = null → `query = ''` → same | ✅ |
| 12 | `/search?q=` + 10000 chars | 觸發 API，error state 或正常結果 | `encodeURIComponent(query)` 正確編碼；若 backend 拒絕長查詢 → "搜尋失敗，請重試" | ✅ |
| 13 | `fetchSkillByAuthorAndName` URL encoding | `encodeURIComponent` 正確 | `return apiFetch(\`/skills/${encodeURIComponent(author)}/${encodeURIComponent(name)}\`)` ✓ | ✅ |
| 14 | `fetchSkillById` URL encoding | UUID IDs 安全（僅含 hex + `-`）| template literal 無 encodeURI，但 UUID charset 不需 encoding；非 UUID 手工 URL 照樣 404 | ✅ note |

**Note #6**: `VersionDiffPage` 在 skill 不存在時顯示 "技能版本不足 2 個，無法比較"（`if (error || ...)`）— message 語意略誤導（應為 "技能不存在"）。MVP 邊緣情境，不開 bug；polish 列 backlog。

**Note #14**: `fetchSkillById` 未用 `encodeURIComponent`，但 backend skill ID 為 UUID（hex + `-`）無需 encoding；`fetchSkillByAuthorAndName` 已正確 encode。

**0 functional bugs — 全部 deep-link 邊緣情境安全降級。**

### Tick 102 Summary
- Round 55: 14 checks / **0 bugs** — Negative deep-link 全通過（2 個 polish notes 列 backlog）
