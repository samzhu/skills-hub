# S099a — OpenAPI 3.1 verification + docs page version note

> **Status**: 📋 planned (Spec-Only-Handoff — written by 2026-05-03 cron Tick 21 mid-session pivot per user redirect; awaits implement tick when backend recovers)
> **Type**: Backend verification + frontend docs page polish
> **Estimate**: XS (2 pts)
> **Triggered by**: 2026-05-03 user mid-tick directive：roadmap 多 backlog，stop 連續 Mode B round；pivot to advance smallest 📋 sub-spec。S099a 是 backlog 中 size 最小（XS=2）+ 最少 ambiguity 的 candidate。

## §1 Goal

S099 META Trust Maturity & Implementation Audit 第 1 個 sub-spec — 驗證 backend `GET /v3/api-docs` 返 OpenAPI 3.1.0 spec（per agentskills.io trust maturity 標準），並在 docs page 加 OpenAPI version note 給 API consumers 看到 standard compliance。

**Background**：
- `backend/build.gradle.kts:48` 用 `springdoc-openapi-starter-webmvc-ui:3.0.2`
- 根據 SpringDoc 官方 changelog：springdoc-openapi 3.0.0+ 預設 OpenAPI version = 3.1.0
- 因此 `/v3/api-docs` JSON top-level 應已含 `"openapi": "3.1.0"` 欄位

**驗證 + docs polish 範圍**：
1. Backend：curl `/v3/api-docs` 確認 `openapi: "3.1.0"`；若否（極不可能），加 `application.yaml` `springdoc.api-docs.version: openapi_3_1`
2. Frontend：在合適 docs page 加版本 note，讓 user 看到 API 對齊 OpenAPI 3.1 standard

**不修**：
- OpenAPI 3.1 specific features（callbacks / webhooks / deprecated path bumps）— scope 超 verification
- /v3/api-docs.yaml 同 endpoint 的 YAML serialization 對齊 — out of scope

**Sibling 關係**：S099 META 第 1 個 sub-spec ship；M93 trust maturity audit 系列 entry point。

## §2 Approach

**Plan A (預期)**：springdoc 3.0.2 default OpenAPI 3.1，無需動 backend config，純 frontend docs note + verification test
**Plan B (fallback)**：若 verification 發現是 3.0.x，加 `application.yaml` `springdoc.api-docs.version: openapi_3_1` 一行，再回 Plan A

**Trim path**：
- 若 implement tick 觸 wall：保 verification + docs note 第 1 步 (`/docs/overview` add version note)；defer 全 docs sub-pages 都 cross-reference OpenAPI version

**Frontend docs page 選擇**：
- `OverviewPage.tsx` (`/docs/overview`) — already entry point；最自然的位置加 `OpenAPI 3.1 標準對齊` 一段
- `RestApiPage.tsx` (`/docs/rest-api`) — 已有，per filename 預期是 REST API reference doc；版本 note 也適合放這

選 OverviewPage 為 primary（ entry visibility 高）；RestApiPage 為 secondary（若實際 doc body 已 detailed，加一句 cross-reference）。

## §3 Acceptance Criteria

| AC | Given | When | Then |
|----|-------|------|------|
| AC-1 | Backend running | `curl http://localhost:8080/v3/api-docs` | response JSON top-level 含 `"openapi": "3.1.0"` 字面 |
| AC-2 | Frontend dev :5173 | navigate `/docs/overview` | 可見「OpenAPI 3.1 標準」相關文字（user 能讀到 API 對齊 OpenAPI 3.1 standard 的說明） |
| AC-3 | Backend test | `./gradlew test --tests *OpenApi*` (若有 existing) 或補 1 個 SpringBootTest | response status 200 + JSON 含 `openapi=3.1.0` |

## §4 File plan

| File | Edit | LOC delta |
|------|------|-----------|
| `backend/src/main/resources/application.yaml` | (Plan B only — 預期不需) | 0 (Plan A) / +2 (Plan B) |
| `frontend/src/pages/docs/OverviewPage.tsx` | 加 OpenAPI 3.1 標準 note 段落（1-2 句中文 + link to `/swagger-ui/index.html`） | ~5 |
| `backend/src/test/java/io/github/samzhu/skillshub/api/OpenApiVersionTest.java` (新建) | SpringBootTest verify `/v3/api-docs` returns openapi=3.1.0 | ~30 |

**測試新增**：
- 新建 backend integration test (Plan A) — 簡 SpringBootTest 跑 MockMvc 對 `/v3/api-docs` GET，assert response JSON 含 `"openapi": "3.1.0"` 字面

**不需動**：
- frontend tests — docs page 純 static content 加 1 段，可選不寫 unit test（per RiskTiersPage 同 doc-only page no-test pattern）

## §5 Test plan

```bash
# Backend (Plan A verification):
cd backend
./gradlew test --tests '*OpenApi*' -x npmBuild
# 或 manual smoke once backend up:
curl -s http://localhost:8080/v3/api-docs | jq '.openapi'
# expect: "3.1.0"

# Frontend smoke:
# Chrome MCP navigate /docs/overview → grep DOM 有「OpenAPI 3.1」字面

# Integration:
# Click footer 「API」link (per S108) → land at /swagger-ui/index.html → header 顯 "OpenAPI 3.1 spec"
```

**Negative case**: response 無 `openapi` 欄位 → test FAIL (degenerate spec)
**Edge case**: response `openapi: "3.0.x"` → trigger Plan B yaml add

## §6 Acceptance verification

Run: backend test + frontend smoke per §5。Ship if all green。

## §7 Result

**Status**: ✅ Shipped 2026-05-03 cron Tick 1（30m loop）— v3.4.12 patch。

**走 Plan B（spec assumption 錯誤）**：發現 `application-local.yaml:67` 已有 `springdoc.api-docs.version: openapi_3_1`（非 spec assumption 的 Plan A default 3.1.0）；SpringDoc 3.0.2 default 仍為 3.0.3，需 explicit 設定才產 3.1.0。Backend yaml 已先在前次 commit 加好（不在本 spec scope）；本 spec 只補 verification test + frontend docs note。

**Implement checklist 完成**:
- [x] Backend OpenApiVersionTest.java 新建 — `@SpringBootTest + @AutoConfigureMockMvc + @TestPropertySource(springdoc.api-docs.enabled=true, version=openapi_3_1)` lock 契約
- [x] `./gradlew test --tests '*OpenApi*' -x npmBuild` PASS — `tests=1 failures=0 errors=0` @ 0.676s（context startup 15.554s total）
- [x] OverviewPage.tsx 加「API 標準對齊」H2 段落 — 1-2 句中文 + inline code `/v3/api-docs` + Swagger UI link
- [x] Chrome MCP smoke `/docs/overview` — H2 list `["三個核心機制","API 標準對齊","下一步"]` ✓；OpenAPI 3.1 + `/v3/api-docs` + swagger-ui link 均 visible ✓
- [x] Frontend typecheck `npx tsc --noEmit` 0 error
- [ ] CHANGELOG v3.4.12 + roadmap row → ✅ + archive（PERSIST 階段執行）

**Verification metrics**:
- Backend: 1 test PASS（OpenApiVersionTest.AC-1/AC-3）
- Frontend: typecheck 0 error；DOM 4/4 assertions PASS（hasSection / hasOpenApi / hasApiDocsPath / hasSwaggerLink）
- LOC delta: backend test +52, frontend page +5（spec 估 ~30 + ~5 — backend 略多但仍 XS scope 內）

## §8 Lesson — roadmap drive vs Mode B drift

Per 2026-05-03 user mid-tick directive：cron 連續多 ticks Mode B audit round 但 roadmap 仍多 📋 backlog 是「Mode B drift」— 應該主動 design backlog spec docs，Mode B 是「無 implementable target」的 fallback 不是 default。

**Cron-bound agent decision tree 修正**（補進 .claude/loop.md polish backlog）：
1. 有 active 📋 / 📐 spec doc → Mode A implement
2. 全 META design state + 📋 sub-specs 無 doc → 主動 /planning-spec 寫 sub-spec doc（Spec-Only-Handoff）
3. 全 spec doc 都 designed/ shipped 才 → Mode B E2E

當前 session Tick 14/19/20 0-bug Mode B rounds 是 step 3 場景，但實際應該回 step 2 寫 backlog spec docs（如本 S099a）。

Sibling lesson 與 S100~S111 的 cut 軸 cross-cutting follow-up 不同 — S099a 是「process-level」correction：cron-bound agent 應 prefer roadmap progression over Mode B drift。
