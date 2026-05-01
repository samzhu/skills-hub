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

