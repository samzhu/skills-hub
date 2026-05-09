# ADR-007: Browser E2E via Playwright + e2e/ workspace + Pattern 1 fixture seeding

> Status: **Accepted** (2026-05-07)
> Extends: PRD Decision Log（無對應條目；MVP 階段 Layer 3 用「手動操作 golden path」）
> Triggered by: 加入第一個 browser E2E 自動化，填補 `qa-strategy.md` Layer 3 + `verifying-quality` Step 4 Testability gate 的「應該驗證但無自動化」空洞
> Implementation: `feat(e2e)` commit 31727db（`playwright-expert` skill + `e2e/` bootstrap）+ S140 critical path backfill spec

---

## 1. Context

到 2026-05-07 為止 Skills Hub QA 三層中 Layer 3 僅以「啟動 dev server，手動操作 golden path」描述 — 沒有任何自動化 browser flow。同時：

- `verifying-quality` skill Step 4 Testability gate 邏輯上會把 UI spec 的 AC 標 `UNTESTABLE` → 應 `REJECT-BLOCKED`，但實務未啟動（同 session 自我 verify 的盲點）
- `planning-tasks` Phase 4 Step 1.5「E2E artifact verification」對 browser specs 是抽象描述 — 沒 tool / location / artefact format
- PRD critical path P1–P6 + Quality Score 全 ship，但每次手動驗證重複成本高
- 後續 UI spec（如 in-flight S139 login UI、未來 P7-P9 Collections / Request Board / Notifications）會持續踩同空洞

需要決定：browser E2E 工具？workspace 位置？fixture seeding 策略？跨 skill 整合介面？

## 2. Decision

採 Playwright（latest 1.59.1+）作為 browser E2E 工具，獨立 `e2e/` workspace，fixture 走 **Pattern 1（backend test API endpoint）** seeding，由 `playwright-expert` skill 統一管理 BOOTSTRAP / DESIGN / VERIFY 三個流程節點。

| 決策面 | 選擇 | Rationale |
|---|---|---|
| **Tool** | Playwright 1.59.1 | 跨瀏覽器原生、免費 sharding、官方 MCP、官方免費 trace viewer（trace.playwright.dev，純前端不上傳）；vs Cypress 需付費平行、vs Selenium 維護降溫 |
| **Workspace 位置** | `e2e/`（repo root）| E2E 同時依賴前後端，不屬任一側；CI 可獨立 shard；e2e/.gitignore managed block 蓋 artefacts |
| **Fixture pattern** | **Pattern 1**：backend `@Profile({"local","dev","e2e"})` `TestDataController`，**走 `SkillCommandService.create()`**，**禁止**直接 INSERT | 對齊 ADR-002 充血聚合 + Modulith Outbox：直接 INSERT 會繞過 `@DomainEvents` interceptor → outbox 不發 event → audit log 漏行 → read-side projection 失同步 |
| **Cloud platform** | **不採用** | Microsoft Playwright Testing 付費 pay-as-you-go + 將於 2026-03-08 retire；trace.playwright.dev 免費已滿足查看需求；3 個 happy-path scope 不需雲端平行 |
| **Browser binary** | `chromium-headless-shell`（`--only-shell`）only | 比完整 Chromium 小 4×（92 MiB vs 350+ MiB）；Playwright 1.49+ `chromium` project default 已走 headless shell |
| **Trace policy** | `on-first-retry`（CI default per playwright.dev/docs/ci-intro）| 通過時 artefact 微小，retry 才有完整 trace；100+ MB cap 觀察 |
| **Skill 介面** | `playwright-expert` 三 mode（BOOTSTRAP/DESIGN/VERIFY）+ `e2e/results/evidence.json` 契約檔給 `verifying-quality` 讀 | 跨 skill 不直接呼 Playwright；責任邊界 per `playwright-expert/references/caller-protocol.md` |

## 3. Consequences

**正面：**

- Layer 3 從手動變自動；V07（`cd e2e && npx playwright test --grep @happy-path`）成為 critical-path regression gate（S140 ship 後正式 enforced）
- `verifying-quality` Mandatory E2E gate 對 browser specs 委派 `playwright-expert` VERIFY，不再為 `REJECT-BLOCKED`
- `planning-tasks` Phase 2 對 browser specs 委派 `playwright-expert` DESIGN，產出 spec test + missing locator/seed findings 變 task
- Fixture 走 domain layer 維持 CQRS 一致性 — read-side projection + audit log + outbox 全保留
- Trace 本機 `npx playwright show-trace` 或 trace.playwright.dev 免費 + offline 查看，無 cloud platform 依賴

**負面 / Trade-off：**

- 新增 `e2e/` workspace + `node_modules` + `chromium-headless-shell` cache（~92 MiB）— 接受成本換 regression coverage
- 第一次 cold start 慢（Spring Boot + Testcontainers pgvector pull 90–150 s；cached 後 ~30 s）— `reuseExistingServer: !process.env.CI` 緩解本機 dev 痛點
- backend `TestDataController` 必須嚴格 `@Profile` 限制，production absolutely never expose — S140 design phase 必須在 SecurityConfig + integration test 雙保險
- Playwright 版本月更節奏快 — `ensure-latest.sh` 的 `--upgrade` 由 user opt-in（per intent-b decision），不主動升級避免 CI flaky
- ~~`bootRun -x processAot` workaround per `qa-strategy.md` Known Limitations~~ — 已過時（2026-05-09 確認 `processAot` + `bootRun` 全綠 after S148e + S166a）；Recipe A 改回 bare `./gradlew bootRun`，AOT 全程跑保留 prod-only bug 早期捕捉能力（per S158 教訓）

**Out of scope（後續另起 spec）：**

- Cross-browser（Firefox / WebKit）— chromium-only 至需要時加 project
- Visual regression baseline（`toHaveScreenshot()`）— 留 visual spec
- Mobile responsive E2E
- a11y E2E
- Microsoft Playwright Testing / Azure App Testing cloud cluster — 規模未到
- ACL multi-role fixture（涉及多 user role seeding，scope 較大）

## 4. Implementation references

- `playwright-expert` skill：`.claude/skills/playwright-expert/SKILL.md`
- Fixture pattern：`playwright-expert/references/fixtures-patterns.md`（4 patterns + 7 state profile + per-AC decision protocol）
- Caller protocol：`playwright-expert/references/caller-protocol.md`（cross-skill contract + CI artefact convention）
- webServer config recipe：`playwright-expert/references/webserver-recipes.md` Recipe A（bare `bootRun`，AOT 全程跑）
- e2e workspace bootstrap commit：`31727db`
- S140 critical path backfill spec：`docs/grimo/specs/2026-05-07-S140-e2e-critical-path-backfill.md`（📐 in-design — `/planning-spec S140` next）
- V07 Verification Command Registry：`docs/grimo/qa-strategy.md`
