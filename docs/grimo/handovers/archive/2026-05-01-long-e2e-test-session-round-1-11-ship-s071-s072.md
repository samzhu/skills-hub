---
topic: "Long E2E test session — Round 1-11 + ship S071/S072 (v2.49.0/v2.50.0)"
session_type: "development"
status: "in_progress"
date: "2026-05-01"
---

# Handover: Long E2E test session — Round 1-11 + ship S071/S072 (v2.49.0/v2.50.0)

## Layer 1 — Portable Summary

> 此 session user 指示「進行完整 E2E 測試」+ 自己開 spec 修 bug + ship + 下一輪繼續。已跑完 11 個測試 round，找到並修復 2 個 bug（一前端 critical / 一後端 medium）。系統現在比 takeover 時更健康。

### Completed

- **Round 1 Suspend/Reactivate**：HTTP 200 → SUSPENDED → 列表隱藏 → vector 清空；reactivate → PUBLISHED → vector 重建；already-PUBLISHED reactivate → 409 STATE_CONFLICT ✓
- **Round 2 多版本**：PUT v1.1.0 + v2.0.0 OK；duplicate 1.1.0 → 409 VERSION_EXISTS；UI 版本歷史 tab 顯 3 版本，每版獨立下載 link；non-existent /versions/9.9.9/download → 404 ✓
- **Round 3 Search**：keyword pdf/word document/中文/empty/不存在 全 OK（trim fallback all S044）；semantic create-word-doc → docx 第一、Anthropic-API → claude-api 第一；empty q → 400；pagination beyond → 空 ✓
- **Round 4 Upload edge**：8 個 cases (not-zip/corrupted/empty/no-SKILL.md/bad-name/missing-desc/desc>1024/no-frontmatter) 全 400 VALIDATION_ERROR ✓
- **Round 5 Navigation**：發現 Bug AF（unmatched URL 整頁空白）；back/forward 正常
- **Round 6 Concurrency**：5 並發同名 upload → 1 success + 4 conflict 409；5 並發同版本 PUT → 1 success + 4 conflict；DB 各只有 1/2 entries；outbox 自動 drain ✓
- **Round 7 Publish UI**：browser 構造 minimal STORED zip via DataView (CRC32 + LFH + CDH + EOCD)，React-controlled input setter (Object.getOwnPropertyDescriptor + input event) 灌欄位 + submit；happy path 201 + redirect detail；conflict path i18n localized error「此名稱已被使用」 ✓
- **Round 8 Analytics**：總技能 / 總下載 / 本週新增 / Top 10 排行 全顯示 ✓
- **Round 9 Risk assessment**：dangerous SKILL.md (allowed-tools=Bash + rm -rf + /etc/passwd + secret) → riskLevel=HIGH + 4 SARIF findings (META_EXFIL_PATTERN / DANGEROUS_COMMAND_RM_RF / SENSITIVE_PATH_PASSWD / GENERIC_BEARER) ✓
- **Round 10 Flag flow**：發現 Bug AG（type 沒白名單 + description 沒長度限制）；type > 20 chars 已 reject；non-existent skill → 400（誤分類但 reject）
- **Round 11 baseline + extension**：64 skills / 0 outbox pending / 314 events 全綠；4 個 anthropic skills (frontend-design/theme-factory/xlsx/webapp-testing) 全 201 LOW risk ✓
- **Bug AF (CRITICAL) → S071 v2.49.0 (M67) ship**：App.tsx 加 `/skills` alias + `*` NotFoundPage fallback；新 NotFoundPage 包 AppShell；新 App.test.tsx；11 frontend tests / 0 fail (10→11)；lint 0 / build 228ms；commit `3543ba8`
- **Bug AG (MEDIUM) → S072 v2.50.0 (M68) ship**：FlagService 加 `ALLOWED_TYPES` 6-type 白名單 + `DESCRIPTION_MAX=500`；FlagControllerTest 加 2 個 reject test；288 backend tests / 0 fail (286→288)；真實 backend curl 6/6 AC PASS；commit `fd1019b`
- **Total skills coverage**：9 個真實 anthropic skills 上傳並驗證（skill-creator/pdf/mcp-builder/claude-api/docx + frontend-design/theme-factory/xlsx/webapp-testing）

### Decisions

| Decision | Why | Alternatives Rejected |
|----------|-----|----------------------|
| S071 NotFoundPage 包 `<AppShell>` | navbar 一致 → user 永遠能回主功能 | 純 404 plain text — 失去 navigation 入口、user 卡死 |
| S071 `/skills` alias 用 HomePage（複用 element） | HomePage 本身已是 listing — alias zero cost | 改 brand link 從 `/` 改到 `/skills` — 影響太多既有書籤 |
| S072 6 個 fixed flag type 白名單 | 對齊一般社群審核分類；admin reporting 可分類 | free-form text — admin review 困難；過嚴 ENUM (`SECURITY` 大寫) — client 負擔；ENUM 7+個 — 過細無實際 use case |
| S072 description 上限 500 | flag 描述本質是「指控」，500 字足夠；超過大概率 attacker 灌水 | 1024（同 skill description）— 過鬆；100 — 真實 abuse 可能不夠 |
| 不修 Bug 同 user dedup（spam vector）| dev mode 全 anonymous，dedup logic 等真認證後才有意義 | 加 (skillId, anonymous, type) cooldown — premature；admin block — 待 admin endpoint 設計 |
| 不修 Bug Flag non-existent skill 回 400 而非 404 | reject 行為已正確；errorCode 分類 polish 屬 nice-to-have | 改 GlobalExceptionHandler 加 catch DataIntegrity → 404 — 影響其他路徑風險 |
| 不修 Bug `/api/v1/admin/flags` 缺 | handover known tech debt：admin endpoint 待設計 | 設計 admin module — out of scope |
| 不修 Bug Round 4「corrupted/empty/not-zip 共享 generic 'No YAML frontmatter' 訊息」 | 結果 reject 已正確（400），訊息 polish 為 nice-to-have | 加 ZipException 顯式 catch — 預期收益不對等 |

### Next Steps

1. **若 user 觸發新 cron tick（`388844f0` 每 10 分鐘）或新 prompt「繼續測試」**：可繼續找 bug。候選方向：
   - **真實 ACL flow**：grant `user:bob:read` / `role:admin:write` / `group:eng:read`，確認 acl_entries 寫入 + audit event + revoke flow
   - **剩 8 個 anthropic skills 上傳**：algorithmic-art / brand-guidelines / canvas-design (2.5MB) / doc-coauthoring / internal-comms / slack-gif-creator / web-artifacts-builder / claude-api（已測但可加版）
   - **同 author 多 skill / 跨 author 衝突**
   - **HMR ApiError instanceof 失效**（handover 提的 known issue）— 找具體 reproducer 測試
   - **Network failure scenarios**：mock listener throw → outbox stuck → republish task 自動 drain
   - **Skill description with shell-special chars / yaml-attack patterns**
   - **Frontend test coverage 補強**（11 tests，HomePage/SkillDetailPage 都沒 unit test）
2. **若 Chrome / backend 重啟**：
   - Backend：`cd backend && SPRING_PROFILES_ACTIVE=local,dev nohup ./gradlew bootRun -x processAot > /tmp/backend.log 2>&1 &`
   - Frontend：`cd frontend && npm run dev > /tmp/frontend.log 2>&1 &`
   - 等 backend 起來：`until curl -sf http://localhost:8080/actuator/health > /dev/null; do sleep 5; done`
3. **若需 cron 重設**：tick 51 cron `388844f0` 仍在；新 session 須重設

### Lessons Learned

- **DataView 構造 minimal STORED ZIP（手刻 LFH + CDH + EOCD + CRC32）** 是真實 Chrome publish UI test 的可靠技法（tick 40 verified；本 session R7 再次 verify）。Skip compression(STORED method, 0x00 flag) 即可；CRC32 用 IEEE polynomial 0xEDB88320 標準演算法
- **React-controlled input 灌值** 必須用 `Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, val)` + `dispatchEvent(new Event('input', {bubbles:true}))`；直接 `input.value = X` 在 React 會被覆寫；descriptor.set 解構成 fn 變數會 "Illegal invocation"，必須 `setter.call(input, val)`
- **Radix UI tab 切換** 真的需要完整 PointerEvent + MouseEvent 序列：`['pointerdown','pointerup','mousedown','mouseup','click'].forEach(...)`；純 `.click()` 不 work
- **直接 navigate /skills 時 React 沒 mount**（一開始 reload 看到 root empty）— 後來發現是 routing bug 而非 timing issue（Bug AF）
- **Spring Web 對 query param 不接收的 endpoint silently ignore**：`GET /api/v1/skills/{id}/download?version=9.9.9` 返 latest 不是 404（正確 endpoint 是 `/versions/{version}/download`）— 一開始誤判為 bug
- **Spring Data Page response shape**：`{content: [...], page: {size, number, totalElements, totalPages}}`；`totalElements` 在 sub-object 不在 top-level
- **Bash xargs `-P5 -I{} bash -c '...' _ {}`** 在 zsh 環境會「upload_one: command not found」即使 `export -f`；改用 `for+&+wait` pattern 更穩
- **Spring Boot 4 multipart upload 對 corrupted zip** 不會 throw ZipException，反而 fall through 到 SKILL.md not found / empty content 路徑 — 攻擊者上傳 corrupted zip 不會觸發 specific error，只會看到 generic message。reject 對的，但 polish 待加
- **Aggregate validation pattern 三例**（S055 ACL / S057 Skill / S072 Flag）：任何「字串選一個語意分類」欄位 → 白名單；任何「描述/原因」欄位 → length cap；service 層 throw IllegalArgumentException，由 GlobalExceptionHandler 轉 400 — 已標準化
- **Anthropic skills frontmatter `license` 欄位** validator 接受（不在 REQUIRED_FIELDS = `{name, description}`，optional pass）— S053 subfolder zip auto-normalize 對 anthropics 的 `skill-name/SKILL.md` 結構運作正常

### Session Summary

User takeover 後給的指示是「進行完整 E2E 測試 + 自己開 spec 修 bug + ship + 下一輪繼續」。Session 從 baseline 健康（system 100% 全綠 per tick 50 milestone）開始，分 11 個 round 系統性探查：suspend/reactivate / 多版本 / search / upload edge / navigation / concurrency / publish UI / analytics / risk assessment / flag flow / extension。Round 5 找到 Bug AF（unmatched URL 整頁空白），Round 10 找到 Bug AG（flag type 缺白名單）— 兩個都依 `/planning-spec` 簡化版流程：spec → fix → test → 真實 verify → CHANGELOG → roadmap → commit → archive。Spec S071 v2.49.0 (M67) frontend / Spec S072 v2.50.0 (M68) backend 都已 ship。System 現在比 takeover 時多 2 個 fix + 9 個真實 anthropic skill coverage + 4 個新 test。下一個 session 接手後若繼續測試，可從 Next Steps #1 候選清單挑方向。

---

## Layer 2 — Environment Details

| Property | Value |
|----------|-------|
| Branch | `main` |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub` |
| Test Status | backend 288 / 0 fail; frontend 11 / 0 fail; lint 0 errors; build 228ms |

### Uncommitted Changes

```
 M .claude/loop.md
 M .claude/progress/loop-e2e-test-coverage.md
```

`.claude/loop.md` 是 skill 觸發產生的 transient 檔；可忽略 / git checkout 還原。
`.claude/progress/loop-e2e-test-coverage.md` 是 tick 53/54/55 的 progress log（已寫入但未 commit）— 下個 session 接手後合理選擇是否要 commit（next session 也會繼續寫 tick 56+）。

### Recent Commits

```
fd1019b feat(security): ship S072 — Flag type allowlist + description length cap (M68 完成 v2.50.0)
3543ba8 feat(frontend): ship S071 — App routing /skills alias + NotFound fallback (M67 完成 v2.49.0)
9a79e81 docs: tick 50 milestone + storage hygiene cleanup
d87088e docs: tick 48-49 progress (data integrity + modulith health verified)
fb5a426 docs: update tick 47 progress + Bug AE logged
```

### Key Files

- `.claude/progress/loop-e2e-test-coverage.md` — **READ FIRST**：累積 55 ticks test coverage / bug ledger A-AG / known tech debt / next-tick 建議
- `docs/grimo/CHANGELOG.md` — 最新 v2.50.0 (S072)；上推可看 S043-S072 完整時間線
- `docs/grimo/specs/spec-roadmap.md` — Phase 4 已到 M68，總 story points ~518
- `docs/grimo/specs/archive/2026-05-01-S071-app-routing-notfound-fallback.md` — S071 spec full design
- `docs/grimo/specs/archive/2026-05-01-S072-flag-type-allowlist-description-cap.md` — S072 spec full design
- `frontend/src/App.tsx` — `/skills` alias + `*` wildcard route（S071 新加）
- `frontend/src/pages/NotFoundPage.tsx` — 包 AppShell + 404 + 回首頁連結（S071 新加）
- `frontend/src/App.test.tsx` — NotFoundPage 渲染合約測試（S071 新加）
- `backend/src/main/java/io/github/samzhu/skillshub/security/FlagService.java` — `ALLOWED_TYPES` Set + `DESCRIPTION_MAX=500` validation（S072 修改）
- `backend/src/test/java/io/github/samzhu/skillshub/security/FlagControllerTest.java` — `rejectInvalidType` + `rejectLongDescription` 兩個 case（S072 新加）
- `/tmp/real-skills-test/zips/` — 9 個 anthropic skill ZIPs (skill-creator/pdf/mcp-builder/claude-api/docx + frontend-design/theme-factory/xlsx/webapp-testing)
- `/tmp/real-skills-test/anthropics-skills/` — clone 的 anthropics/skills repo（剩 8 個未上傳：algorithmic-art / brand-guidelines / canvas-design (2.5MB) / doc-coauthoring / internal-comms / slack-gif-creator / web-artifacts-builder）

### Restart Commands

```bash
# Backend (with secrets + LAB mode)
cd backend && SPRING_PROFILES_ACTIVE=local,dev nohup ./gradlew bootRun -x processAot > /tmp/backend.log 2>&1 &
until curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; do sleep 5; done

# Frontend
cd frontend && npm run dev > /tmp/frontend.log 2>&1 &

# Verify all
bash scripts/verify-all.sh

# DB inspection (Cloud SQL emulator local)
docker exec backend-pgvector-1 psql -U myuser -d mydatabase -tAc "SELECT count(*) FROM skills;"
```

### Cron State

- Cron `388844f0` (every 10 min) 仍在 — recurring /loop fire；新 session 觸發時會 fire
- Tools loaded this session: WebSearch, WebFetch, TaskCreate/Update, ToolSearch, Bash, Edit/Write/Read, mcp__claude-in-chrome__*

### Backend Process

Backend 在這 session 重啟過一次（為了套用 S072 FlagService validation）— 用 `nohup ./gradlew bootRun -x processAot &`。當前 PID 與 log 在 `/tmp/backend.log`。frontend dev server 沒重啟（HMR 即可）。
