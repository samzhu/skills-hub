# Loop E2E Test Coverage Log

> Persistent log to survive session boundary — read on takeover, append on each new ship.
> Latest tick: 64 (2026-05-01) — Round 21 flag flow lifecycle → Bug AI ship S075 v2.53.0 (M71)
>   tick 48: data integrity 100% (downloads/sequence/orphans)
>   tick 49: modulith boundaries 0 violations
>   tick 50: cleaned 7 dev storage orphans; storage 與 DB 100% 一致
>   tick 52 (manual real-skill smoke test, 2026-05-01): 上傳 5 個 anthropics/skills 真實 SKILL.md 全 201；DB 5 skills + 5 versions + 21 events + 5 vectors；docx click→下載 round-trip OK；outbox 0 pending；UI 列表 + risk badge 全顯示。**0 new bugs** — system 對真實 SKILL.md 完全相容（subfolder zip auto-normalize 由 S053 處理；anthropics frontmatter `name + description + license` 三欄 validator 接受）。
>   tick 53 (long E2E test session 6 rounds, 2026-05-01):
>     R1 Suspend/Reactivate: HTTP 200 → SUSPENDED → list 隱藏 → vector 清空；reactivate → PUBLISHED → vector 重建；already-PUBLISHED reactivate → 409 STATE_CONFLICT ✓
>     R2 多版本: PUT v1.1.0 + v2.0.0 OK；duplicate 1.1.0 → 409 VERSION_EXISTS；UI 版本歷史 tab 顯 3 版本，每版獨立下載 link；non-existent /versions/9.9.9/download → 404 NOT_FOUND ✓
>     R3 Search: keyword pdf/word document/中文/empty/不存在 全 OK（trim fallback all S044）；semantic create-word-doc → docx 第一、Anthropic-API → claude-api 第一；empty q → 400；pagination beyond → empty content ✓
>     R4 Upload edge: not-zip / corrupted / empty / no-SKILL.md / bad-name(uppercase) / missing-desc / desc>1024 / no-frontmatter — 全 400 VALIDATION_ERROR；訊息分對。Note: corrupted/empty/not-zip 都 fall through 到 "No YAML frontmatter found"，可優化但非 bug ✓
>     R5 Navigation: **發現 Bug AF** — `/skills` 沒 route → 整頁空白；任何 unmatched URL 都空白（無 NotFound fallback）。back/forward / 切 tab 正常。
>     R6 Concurrency: 5 並發同名 upload → 1 success + 4 conflict (S051 DuplicateKey 409)；DB 1 entry；outbox 自動 drain。同 skill 同版本並發 5 → 1 success + 4 conflict 同樣。 ✓
>     **Bug AF (CRITICAL)**: 寫 S071 spec（XS/3）→ 修 App.tsx 加 `/skills` alias + `*` NotFoundPage fallback → 11 frontend tests / 0 fail（10→11）→ lint 0 / build 228ms → ship v2.49.0 (M67)。
>   tick 54 (long E2E test session Round 7-10, 2026-05-01):
>     R7 Publish UI: browser 構造 minimal STORED zip（DataView CRC32 + LFH + CDH + EOCD）+ React-controlled input setter（Object.getOwnPropertyDescriptor value setter + input event）+ submit；happy path 201 + redirect detail；conflict path 顯 i18n localized error「此名稱已被使用，請換一個名稱。」 ✓
>     R8 Analytics: /analytics 顯總技能 43 / 總下載 41 / 本週新增 43（rolling 7-day = 全部）/ Top 10 排行 ✓
>     R9 Risk assess: dangerous SKILL.md (allowed-tools=Bash + rm -rf + /etc/passwd + secret) → riskLevel=HIGH + 4 SARIF findings (META_EXFIL_PATTERN / DANGEROUS_COMMAND_RM_RF / SENSITIVE_PATH_PASSWD / GENERIC_BEARER) ✓
>     R10 Flag flow: **發現 Bug AG** — `type="bogus"` 任意接受 + 5000-char description 接受。又發現次要 issue（同 user 不 dedup, non-existent skill 回 400 而非 404, admin endpoint 不存在 — 都歸 known/low priority 跳過）。
>     **Bug AG (MEDIUM)**: 寫 S072 spec（XS/3）→ 加 `ALLOWED_TYPES` 6-type 白名單 + `DESCRIPTION_MAX=500` → 288 backend tests / 0 fail（286→288）→ restart backend → 真實 curl 6/6 AC PASS → ship v2.50.0 (M68)。
>   tick 55 (long E2E test session Round 11 baseline + extension, 2026-05-01):
>     baseline: 64 skills total / 44 PUBLISHED + 2 SUSPENDED + 18 DRAFT / 11 flags / 0 outbox pending / 62 vector_store / 314 domain_events ✓
>     上傳 4 個更多 anthropic skills（frontend-design / theme-factory / xlsx / webapp-testing）全 201 / LOW risk / outbox drain ✓
>     **0 new bugs** — system 對 anthropic skill registry 第二批仍 100% 相容
>   tick 56 (loop cron 10m fc4a79bb, Round 12-14, 2026-05-01):
>     R12 ACL Lifecycle (10 cases): default *:read + creator user grants ✓；POST `user:alice:read` + `group:dev:write` 201 ✓；type=USER (uppercase) → 400 「ACL type must be one of [role, user, group] (got: USER)」(case-sensitive) ✓；DELETE `user:alice:read` 204 ✓；DELETE 不存在 grant → 409 STATE_CONFLICT ✓；POST 重複 grant 同 tuple → 409 STATE_CONFLICT「ACL entry already exists」(non-idempotent design) ✓；GET ACL on bogus skill → **200 [] (intentional per `SkillAclQueryService.listEntries` line 39 docstring)**；POST/DELETE on bogus skill → 400 VALIDATION_ERROR「Skill not found」(REST: 應為 404 但 message 已給對 — low priority)。`/me` + `/admin/echo` → 200 lab-user/admin ✓。
>     R13 Pagination & Sort (11 cases): page=999 beyond → empty + page.totalPages=5 ✓；size=0/-5 → coerce 20 ✓；page=-1/abc → coerce 0 ✓；size=10000 → 自動 clamp 2000 ✓；sort=name,asc 200 ✓；sort=bogusField/「DROP TABLE」silent ignore（Spring 屬性白名單防 SQL injection）✓。Page 元資料嵌套於 `page` field（Spring Boot 4 慣例）— 我的 first-attempt parser 漏看，補對後一切正常。
>     R14 Categories + Search combos + Detail edges (10 cases): /categories 8 個分類含 count ✓；keyword=docx → 1 結果 ✓；keyword+category 雙過濾正確（docx&Documents=1, docx&DevOps=0）✓；keyword=' OR 1=1-- → 0（properly parameterized）✓；keyword 2000 字 → 200 0 結果 ✓；detail 不存在 UUID → 404 NOT_FOUND「Skill not found: ...」✓；malformed UUID → 同 404 ✓；空 path segment `/skills/` → Spring static-resource 404（無自訂 handler 但不洩漏）✓。**Discovery**: param 名是 `keyword` 非 `q`（OpenAPI 已標註）— 用 `?q=...` 會被 Spring silent drop 全回所有 skills（第一次測試的假警報）。
>     **0 new bugs** — 三個 round 涵蓋 31 cases 跨 ACL CRUD / pagination / sort / 搜尋 / detail edges 全 PASS。
>   tick 57 (loop cron 10m fc4a79bb, Round 15 hand-craft 3rd-party SKILL.md variants, 2026-05-01):
>     R15 (10 variants): minimal / extended / allowed-tools list / license / yaml-pipe-multiline / markdown-desc / emoji-cjk / capitalized-key (rev) / xss-desc (rev) / shell-inj-tools (rev) — 預期 7 PASS + 3 FAIL。實測：
>     **R15.3 allowed-tools list 形狀全部 400** — 即使 `- Read` 單一條目也被拒（msg: "invalid token: [Read]"）。**發現 Bug AH (HIGH)**：`SkillValidator` 對 `allowed-tools` 只支援空白分隔字串，但 canonical agentskills.io spec + 我們本機 `.claude/skills/handover|planning-project|deep-research|...` 9 個 anthropic skills 全用 list 形狀 → 任何使用者複製 canonical SKILL.md 上傳都被拒。
>     根因：line 126-127 `allowedTools.toString().split("\\s+")`，ArrayList toString 為 "[Read, Bash]"，split 出 `[Read,` / `Bash]` 全不過 ALLOWED_TOOL_TOKEN_REGEX。
>     **Bug AH (HIGH)**: 寫 S073 spec（XS/3）→ 用 Java type pattern matching `if (allowedTools instanceof List<?> list)` 分流 list/scalar → `SkillValidatorTest` 加 3 個 S073 test（block / flow / list-injection）→ 291 backend tests / 0 fail（288 → 291）→ 重啟 backend → 真實 curl R15.3 zip → 201 ✓ → ship v2.51.0 (M69)。
>     **為何 tick 52/55 9 個 anthropic skills 全 PASS**：那 batch（docx/xlsx/pdf/claude-api/skill-creator/...）frontmatter 沒有 `allowed-tools` 欄位，剛好繞過 bug。Round 15 hand-craft 才暴露此 latent regression。
>   tick 58 (loop cron 10m fc4a79bb, Round 16 canonical Anthropic regression sweep, 2026-05-01):
>     R16 (6 cases — direct user-facing scenario validating S073)：把 `.claude/skills/` 下 5 個 canonical Anthropic SKILL.md (handover/planning-project/deep-research/retro/takeover) 整檔包 zip 上傳，加上 1 個反例（Handover capitalized）。
>     - 5/5 canonical skills → 201 PUBLISHED（包含 `description: >` folded scalar / `metadata:` nested object / `argument-hint: "[status]"` / block list `allowed-tools` / 多種 tags 全相容）✓
>     - 5/5 outbox drain（pending=0）+ vector_store entry created + risk=LOW（正確分類為 workflow skill）✓
>     - 反例 16.6 `name: Handover` 大寫 → 400「Field 'name' fails regex ^[a-z0-9-]{1,64}$ (got: Handover)」✓
>     - keyword "handover" → 正確列出 handover + takeover（互引用匹配）✓
>     - semantic 中文 "工作交接" → handover + takeover top 2 結果（跨語言 embedding 正確運作）✓
>     **0 new bugs** — S073 fix 在 user-facing canonical 場景全 pipeline GREEN（upload → outbox → vector store → keyword search → 中文 semantic search → detail page）。
>   tick 59 (loop cron 10m fc4a79bb, Round 17 download bytes integrity, 2026-05-01):
>     R17 (6 cases — 上傳/下載 byte-exact 保證的核心測試)：
>     - 17.1 minimal zip (296 bytes) round-trip → SHA256 match ✓ + Content-Disposition `attachment; filename=r17-roundtrip-minimal-1.0.0.zip` (S061) ✓
>     - 17.2 multi-file zip (51KB; SKILL.md + references/lookup.md + binary 51200-byte blob) round-trip → SHA256 match（binary 不變）✓
>     - 17.3 同一 skill multi-version：PUT v1.1.0 → `/download` 取 latest = v1.1.0 ✓；`/versions/1.0.0/download` = 原始 v1.0.0 ✓；`/versions/1.1.0/download` = v1.1.0 ✓（三條路徑互不污染）
>     - 17.4 反例：SUSPENDED skill download → **403 SKILL_SUSPENDED**「Skill is suspended and cannot be downloaded」（403 區分 vs missing 404，design 正確）✓
>     - 17.5 反例：non-existent version → 404 NOT_FOUND「Version 9.9.9 not found」✓
>     - 17.6 邊緣：3 次連續 download 同一 latest → SHA256 完全一致 ✓（GCS / 本地 storage 無 byte-drift）
>     **0 new bugs** — byte 層級 round-trip 在單檔/多檔/含 binary/多版本 場景全保證；suspend/missing version 邊界正確區分。
>   tick 60 (loop cron 10m fc4a79bb, Round 18 frontend publish-flow with S073, 2026-05-01):
>     R18 (3 cases — 從 user-facing UI 視角驗 S073)：
>     - 18.1 正例：在 `/publish` 頁面 build minimal STORED zip（list-style `allowed-tools: [Read, Edit, Bash(git:*)]`）+ React-controlled input setter（`Object.getOwnPropertyDescriptor(...).set` + dispatchEvent）+ submit → 「發佈成功！Skill ID: 216ade4c...」+「查看技能 →」link 出現 ✓
>     - 18.2 邊緣：點「查看技能 →」→ 跳到 `/skills/{id}` detail page → 顯示 `低風險` badge + `已發佈` + 3 tabs（概要 / 版本歷史 / 風險評估）+ description 完整 + 安裝指引（PUBLISHED gating per S047）✓
>     - 18.3 反例：`Name:` 大寫 frontmatter → 400 → FE 顯示「發佈失敗 zip 套件驗證失敗，請確認格式正確」（i18n localized）✓
>     **發現**：FE i18n 把所有 VALIDATION_ERROR 都對應到「zip 套件驗證失敗」generic 訊息，不顯示具體 field（如「Missing required field: name」）。為 UX 簡潔的 design choice（per S066 i18n coverage），非 bug；但記為 tech debt — 改善方向是讓 FE 抽 backend `message` 欄位顯示具體欄位名（保 i18n 框架不變）。
>     **遇到狀況**：第一次 JS build zip 漏寫 LFH header 後的 `data` segment（114 bytes 太小）→ backend 回 400「Invalid zip file: cannot read package contents」（S049 zip 解析錯誤訊息正確）。修正後 308 bytes → 201。
>     **0 new bugs** — UI 端 publish flow 對 S073 list-style 完全相容；reverse case i18n 訊息 mapping 正確；download 與 detail page 渲染無誤。
>   tick 61 (loop cron 10m fc4a79bb, Round 19 multipart limit + SUSPEND/REACTIVATE lifecycle, 2026-05-01):
>     R19 (5 cases — multipart limits + 完整 state machine 對稱性)：
>     - 19.1 正例：1MB zip upload → 201 ✓
>     - 19.2 邊緣：9.5MB zip (close to 10MB limit) → 201 ✓
>     - 19.3 反例：12MB zip (over limit) → **413 PAYLOAD_TOO_LARGE「Upload size exceeds the 10 MB limit」**（dedicated error type，no stack trace）✓
>     - 19.4 完整 lifecycle：upload → PUBLISHED + listed + dl=200 + vec=1 → POST /suspend `{reason}` 200 → SUSPENDED + 不在 list + dl=403 + **vec=0**（async listener 清掉）→ POST /reactivate `{reason}` 200 → PUBLISHED + listed + dl=200 + **vec=1**（async listener 重建）✓
>     - 19.5 反例：re-suspend SUSPENDED → **409 STATE_CONFLICT「Cannot suspend skill in SUSPENDED status」**（state machine 兩個方向都有 guard，對稱於 R1 reactivate-PUBLISHED 反例）✓
>     **0 new bugs** — multipart 10MB limit clean-error；S033 vector store invariant 在 suspend/reactivate 兩個方向都正確維護；state machine 兩端封閉。
>   tick 62 (loop cron 10m fc4a79bb, user-driven feature 「skill 明細頁面瀏覽各檔案內容」, 2026-05-01):
>     User 在 Round 19 中段 drop 兩個新需求：(a) 參考 DESIGN.md / docs/prototype 優化畫面（多 spec scope，分批做）、(b) skill 明細頁面瀏覽各檔案內容（高 user value，可獨立 ship）。本 tick 做 (b) 的 backend API foundation。
>     **S074 — Skill Files Browser API**：
>     - 新增 `GET /skills/{id}/files` (list) + `GET /skills/{id}/files/{*path}` (read single) — Spring 6+ wildcard pattern 捕獲子路徑
>     - `FileBrowserService`：fail-fast findById + SUSPENDED guard（共用 `SkillSuspendedException` → 403）；ZipInputStream enumerate；read-only metadata（**不**觸發 `SkillDownloadedEvent`，瀏覽 ≠ 下載）
>     - **Zip-slip 防禦** 雙層：list 階段 skip + log warn；read 階段拋 `IllegalArgumentException` → 400；tomcat URL-layer 也擋 `..%2F` 為 defense-in-depth
>     - **單檔上限 1 MB**：超過拋新增的 `FileTooLargeException` → 413 PAYLOAD_TOO_LARGE（與 multipart 上傳上限區分；message 不同讓 i18n 可分流）
>     - **MIME inference**：18 副檔名 → text/markdown / json / yaml / py / sh / png 等；未知 fallback application/octet-stream
>     - `FileBrowserServiceTest` +7 unit tests（zip-slip / MIME / null/blank / case）
>     - 291 → 298 backend tests / 0 fail
>     **Smoke test 7/7 AC PASS**：list multi-file zip → 5 entries；read SKILL.md/binary/nested 全 200；non-existent → 404；SUSPENDED → 403；path traversal → 400 (Tomcat layer)；1.5MB file → 413「File 'big.bin' is 1560576 bytes, exceeds preview limit of 1048576 bytes」
>     **Cosmetic note**：`SkillSuspendedException` message「Skill is suspended and cannot be downloaded」對 /files endpoint 略不貼切（message 該改成 「is suspended and not accessible」），polish round 處理。
>     ship v2.52.0 (M70)。FE 渲染（檔案瀏覽器 UI）留 S076。
>   tick 63 (loop cron 10m fc4a79bb, Round 20 S074 deeper coverage, 2026-05-01):
>     R20 (6 cases — HEAD / OPTIONS / multi-version / concurrency / DRAFT 反例)：
>     - 20.1 正例：multi-version skill `pdf` (3 versions, 12 files) /files → 200 + latest version 12 entries (reference.md / forms.md / scripts/*.py 等) ✓
>     - 20.2 反例：DRAFT skill (no PUBLISHED) /files → 404「No versions found for skill: ...」（fail-fast in `loadZipBytes`）✓
>     - 20.3 邊緣：HEAD /files/SKILL.md → 200 + Content-Type + Content-Length 與 GET 一致 + **body=0 bytes**（Spring 自動處理 GET handler 為 HEAD，spec compliant）✓
>     - 20.4 邊緣：OPTIONS /files → 200 + `Allow: GET,HEAD,POST,PUT,DELETE,OPTIONS,TRACE,PATCH`（Spring 預設）；**無** `Access-Control-*` headers — dev vite proxy 同 origin OK；production CORS 配置留 platform-level tech debt（不限 S074）
>     - 20.5 邊緣：5 並發 readers 同檔 → 5/5 同 HTTP 200 + 同 size + 同 head bytes，unique_signatures=1 ✓ 無 race
>     - 20.6 反例：bogus UUID /files → 404 NOT_FOUND ✓
>     **0 new bugs** — S074 endpoint 在 multi-version / DRAFT / HEAD / OPTIONS / 並發場景全 robust。
>     **Bonus discovery**：anthropics/pdf skill 包 12 檔案含 reference.md + 6 個 Python scripts — 檔案瀏覽器對這類 multi-script skill UX value 很高，user 可預覽 script 內容才決定下載。
>   tick 64 (loop cron 10m fc4a79bb, Round 21 flag flow lifecycle, 2026-05-01):
>     R21 (6 cases — flag list / accumulation / dedup / status / bogus skill / 並發場景)：
>     - 21.1 正例：POST flag 後 GET /flags → 200 + 1 entry，**發現 entry keys 包含 `"new": true`** — Spring Data JDBC `Persistable.isNew()` framework artifact，不該在 API contract（**Bug AI**）
>     - 21.2 邊緣：同 user 同 skill 同 type 連 flag 5 次 → DB 5 筆 (no dedup, 與 R10 觀察一致)
>     - 21.3 邊緣：accumulate 後 GET /flags → 6 entries grouped by type（copyright:5, spam:1）✓
>     - 21.4 邊緣：flag 累積後 skill status 仍 PUBLISHED（flags 是 passive signal 非 enforcement，符合 MVP design）
>     - 21.5 反例：GET /flags 對 bogus UUID → 200 + `[]`（與 ACL endpoint 同 design choice — 已 known tech debt，不再記）
>     - 21.6 邊緣：所有 flags status='OPEN'（沒 admin endpoint 改 status；正常 MVP design — admin review queue 是 future spec）
>     **Bug AI (LOW)**：完全平行於 Bug AA / S063（Skill aggregate `isNew()`）— 上次 fix 沒覆蓋獨立的 `FlagReadModel`。寫 S075 spec（XS/3）→ `FlagReadModel.isNew()` 加 `@JsonIgnore` → `FlagControllerTest` 加 1 個 S075 test (`getFlagsExcludesIsNewArtifact` assert `$[0].new` doesNotExist) → 298 → 299 backend tests / 0 fail → 重啟 backend → 真實 curl GET `/flags` → 6 entries 全部 7 domain fields，無 `new` ✓ → ship v2.53.0 (M71)。

## Coverage Summary (as of v2.46.0)

### Backend ✓ Covered
- **All 21 endpoints** probed: GET/POST/PUT/DELETE for skills/versions/acl/flags/me/admin/analytics/categories/search/upload/download/suspend/reactivate
- **Aggregate validation** S041~S058: name regex, description trim+blank+1024, category trim+blank, author non-blank, version semver, ACL tuple type/principal/permission, flag type/description
- **Default-error 5 layers**: S045 yaml strip + 405 / S049 ZipException → 400 / S051 DuplicateKey → 409 / S052 BodyNotReadable → 400 / S057 DataIntegrity catch-all → 400
- **API JSON cleanup** S062/S063: SkillVersion + Skill `isNew()` `@JsonIgnore`; storagePath hidden
- **PUBLISHED-only visibility** S031/S033/S059: list/categories/analytics/semantic 全 filter status
- **Suspend/Reactivate flow**: 完整 state machine 邊界（S030 STATE_CONFLICT）
- **Upload formats** S053: 3 types canonical zip structure（root/subfolder/plain .md）
- **Download** S061: filename `{skillName}-{version}.zip` ; multi-version verified
- **UTF-8 / 中文 / emoji**: name regex limit ASCII, description/category/author 接受 UTF-8

### Frontend ✓ Covered
- **HomePage**: keyword (S043 `LIKE` cat) / S044 trim / S046 semantic→keyword fallback / S060 truthy badge guard
- **SkillCard**: status badge (S028/S060 truthy guard) / risk level / S047 install guide PUBLISHED only
- **CategorySidebar**: click filter (DevOps/Testing 分頁正確)
- **SkillDetailPage**: 3 tabs / suspend banner (S035) / S039 friendly 404 / 安裝指引 conditional / version list / S041 ApiError.is HMR-safe
- **PublishPage**: drop file (zip+md per S053) / S048 .txt reject / S067 version pattern / S068 maxLength category=50 / author=255 / S040 i18n mutation error
- **FileDropZone**: extension+size guard / drag+click both funnel
- **AddVersionForm**: real upload v1.1.0 verified, history retained
- **i18n coverage**: 12/12 backend codes (S066 補 METHOD_NOT_ALLOWED)
- **QueryClient**: S064 4xx skip console + S065 networkMode=always + retry skip 4xx

### Real Chrome E2E Happy Path (Tick 40 fully verified)
- 構建 minimal valid ZIP via DataView (PK\x03\x04 STORED method)
- Drop file → form fill → submit → 201 + 「發佈成功」
- Click 「查看技能」 → SkillDetailPage 渲染
- Download via vite proxy → bytes round-trip 一致

### Bugs Found & Fixed
- A: keyword whitespace (S044)
- B: stack trace leak (S045)
- C: semantic dead-end (S046)
- D: skip
- E: install guide for DRAFT (S047)
- F: a11y — accept later
- G: drag-drop bypass .zip (S048)
- H: corrupt zip msg (S049)
- I: dup name 500+SQL (S051)
- J: HttpMessageNotReadable method sig leak (S052)
- K: skip
- L: ACL invalid permission (S055)
- M: ACL null prefix
- N: ACL type missing (S055)
- Q/R/T: version validation (S056)
- T-2: long category 500+SQL (S057)
- V: flag NPE (S058)
- W: semantic shows DRAFT (S059)
- X: SkillCard semantic badge (S060)
- Y: download filename (S061)
- Z: storagePath leak (S062/S063)
- AA: Skill isNew artifact (S063)
- AB: console pollution (S064)
- AC: React Query paused state (S065 hotfix v2.43.0)
- AD: outbox stuck SkillAclGrantedEvent pre-S055 (S069 v2.47.0)
- AE: vector_store orphans for pre-S033 SUSPENDED skills (S070 v2.48.0 Flyway V7)
- AF: App.tsx 缺 `/skills` route + 無 NotFound wildcard → unmatched URL 整頁空白 (S071 v2.49.0)
- AG: Flag endpoint 缺 type 白名單與 description 長度上限 → bogus type / 5000-char description 接受 (S072 v2.50.0)
- AH: `SkillValidator` 對 `allowed-tools` YAML list 形狀（canonical Anthropic）全 400；用 ArrayList.toString() 餵 regex 切出 `[Read,` 等不合法 token (S073 v2.51.0)
- AI: `FlagReadModel.isNew()` 序列化為 `"new": true` 洩漏到 GET /flags API 回應；與 Bug AA / S063 同 root cause（Spring Data JDBC `Persistable` framework hook）但 Skill 修了沒覆蓋 Flag (S075 v2.53.0)

### Known Tech Debt (low priority)
- DB 既有畸形 entries（畸形 ACL/version "foo" 等）需 future migration
- riskAssessment.sourceEventId 暴露（idempotency UUID，無敏感資訊）
- Tomcat HTML 400 page on `%2F` path traversal（low impact，攻擊面已擋）
- analytics「本週新增」rolling 7 days（vs calendar week）— 文字選擇
- ACL endpoints REST status code 不一致（tick 56 R12）：GET on bogus skill → 200 [] (intentional)；POST/DELETE on bogus skill → 400 VALIDATION_ERROR；REST 慣例應全為 404。改 GET 為 404 為 breaking change（frontend 可能依「empty list」語意），暫保留。
- ACL DELETE non-existent grant → 409 STATE_CONFLICT (state-machine 哲學) vs 404 NOT_FOUND (REST 慣例)。語意可辯，保留現狀。
- FE i18n VALIDATION_ERROR 訊息過於 generic（tick 60 R18.3）：所有 backend validation error 都對應到「zip 套件驗證失敗，請確認格式正確」，不顯示具體 field（如 "Missing required field: name" / "Field 'name' fails regex"）。UX 改善方向是把 backend `message` 欄位作為 fallback subtitle 顯示（i18n 框架不變）。
- `SkillSuspendedException` message 對 /files endpoint 略不貼切（tick 62 S074）：寫死「cannot be downloaded」，在新增的 file-browser 場景下 message 不準。改為 generic「is suspended and not accessible」可同時適用 /download + /files 路徑。
- Production CORS 配置未啟用（tick 63 R20.4）：OPTIONS /files 回 `Allow:` header 但無 `Access-Control-*`。dev vite proxy 同 origin OK，production 跨 origin（例如靜態前端不同 host）需 `WebMvcConfigurer.addCorsMappings` 或 `@CrossOrigin`。Platform-level 問題，不限 S074；ship 上 Cloud Run 前必補。

## Current Health (Tick 45 baseline)
- **Backend tests**: 286 / 0 fail
- **Frontend tests**: 10 / 0 fail
- **verify-all.sh**: V01-V06 全 PASS（line coverage 82.3%）
- **Total skills DB**: 36 PUBLISHED + 18 DRAFT + 2 SUSPENDED
- **No active bugs**

## Next Tick Suggestions
- 監控 cron 運行情況
- E2E 已飽和；新 bug 須從 user-reported / production telemetry 找
- 維持 health check baseline；如新 bug 才 ship
