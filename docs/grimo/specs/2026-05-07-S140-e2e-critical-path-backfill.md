# S140: E2E Critical Path Backfill

> Spec: S140 | Size: M(12–14, tentative) | Status: 📐 in-design
> Date: 2026-05-07

---

## 1. Goal

PRD Critical Path P1–P6 + Quality Score (S135b) 已全部 ship，但 user-visible browser flow **目前只有後端 integration test + 手動 golden-path 操作**。`qa-strategy.md` Layer 3 已從「手動」改為「Playwright via `/playwright-expert` VERIFY」，V07 也已 enroll — 但 e2e/ workspace 內**只有 placeholder smoke**，沒有任何對應 PRD critical path 的 happy-path spec。

本 spec backfill 那段空白：用 `/playwright-expert` DESIGN 模式從 PRD §Critical Path 拆 6 支 happy-path Playwright spec，搭配 backend `TestDataController`（`@Profile({"local","dev","e2e"})` only）提供 fixture seeding。Ship 後 V07 真正成為 critical-path regression gate。

**為何現在：**

- `e2e/` workspace 已 BOOTSTRAP（v4.17.x ready）；`playwright-expert` skill 含 fixtures-patterns + ac-translation-guide 已 ship
- 後續每個 UI spec 若都要重新驗證 critical path 是否 regression，成本高且重複
- 一個 backfill spec 把 P1–P6 lock 住，後續 spec 只加增量 happy path（與既有 6 支共用 fixture helper）
- 跟 S139 login UI（in-flight）解耦：S139 ship 後再用增量 spec 加「lazy auth gate」happy path，不阻擋本 backfill

**簡單講：**

為 PRD critical path 寫 6 支 happy path Playwright spec，加上 backend `TestDataController` 兩個 endpoint（`POST /internal/test/reset` + `POST /internal/test/seed/skill`）在非 production profile 提供 deterministic fixture seeding（per `playwright-expert/references/fixtures-patterns.md` Pattern 1）。

**非目標：**

- 不 backfill bug-fix / polish spec（S028–S080 等 100+ 個 polish spec 都已被 unit / integration test 覆蓋）— 只 cover PRD §Critical Path
- 不做 cross-browser / mobile responsive E2E（chromium-only per skill default）
- 不做 visual regression baseline（`toHaveScreenshot()` 留給專屬 spec）
- 不做 a11y E2E（留給專屬 spec）
- 不 backfill S139 login UI lazy auth gate（in-flight；S139 ship 後做增量 spec）
- 不重新設計 fixture seeding pattern — 採 `playwright-expert` 已決定的 Pattern 1（backend test API endpoint）

---

## 2. Approach

### 2.1 整體 pattern

`playwright-expert` DESIGN mode 從 **PRD §Critical Path SBE acceptance criteria** 拆 happy path（NOT 從個別 archived spec 的 §3，因 critical path 是跨多 spec 的 user journey）。Fixture seeding 採 Pattern 1（backend test API），per `playwright-expert/references/fixtures-patterns.md`。

### 2.2 6 支 happy path（對應 PRD P1–P6 + Quality Score）

| # | Path | spec test file | fixture profile |
|---|---|---|---|
| 1 | Browse + keyword search（P1） | `tests/critical-path-browse-search.spec.ts` | `paged`（10 skills，混合 risk / category） |
| 2 | Skill detail page（P1） | `tests/critical-path-skill-detail.spec.ts` | `single`（含 file list + version history） |
| 3 | Upload + publish flow（P2 + P3） | `tests/critical-path-publish.spec.ts` | `empty`（fresh state） |
| 4 | Download zip（P4） | `tests/critical-path-download.spec.ts` | `single`（已 publish + low-risk） |
| 5 | Semantic search（P5） | `tests/critical-path-semantic-search.spec.ts` | `paged`（10 skills，含 docker / testing 分類） |
| 6 | Analytics dashboard（P6） | `tests/critical-path-analytics.spec.ts` | `single` + 預先 seed 5 個 download events |

每支 spec 的 `test()` 數量 = 該 path 在 PRD §SBE 列出的 Scenario 數（上面列的下界 1，可能拆 2-3 個）。

### 2.3 Backend `TestDataController`（new infrastructure）

`@Profile({"local","dev","e2e"})` 限定，production profile **絕對不暴露**：

- `POST /internal/test/reset` — wipe `skill_aggregate / vector_store / download_events / domain_events / event_publication` 5 張表；保持 schema 不動
- `POST /internal/test/seed/skill` — body: `{ author, name, version, tags, riskLevel, category, ... }`；**透過 `SkillCommandService.create()`**（不直接 INSERT），維持 `@DomainEvents` outbox + audit listener invariant

**關鍵原則：seed 走 domain layer，不繞過聚合**。直接 INSERT 會破壞 `domain_events` audit log + Modulith outbox（per ADR-002），讓 read-side projection 失同步。Pattern 2 (direct DB) 因此明確 reject。

### 2.4 V07 變身為 critical-path regression gate

V07（`cd e2e && npx playwright test --grep @happy-path`）目前只跑 placeholder smoke（`@bootstrap` tag，已被 grep 排除）。本 spec ship 後，V07 變成 6 支 critical-path test 的 regression gate — 這是 V07 真正的設計目的兌現。

### 2.5 後續增量規劃

- S139 ship → 增量 spec 加 `tests/critical-path-login-gate.spec.ts`（lazy auth gate happy path）
- 未來 P7 Collections / P8 Request Board / P9 Notifications ship → 各自增量 happy path
- ACL（S114a Owner+Viewer）visibility 行為：另起 spec 補（涉及多 user role fixture，scope 較大）

---

## 3. Acceptance Criteria

> 待 `/planning-spec S140` 完成 — 從 §2.2 表格 + PRD P1–P6 SBE 提煉每支 spec 的具體 AC。每支 spec 至少 1 個 `test()` block tagged `@<spec-id> @ac-N @happy-path @profile-<profile>`。

預計 7 個 AC：
- AC-1 ~ AC-6：每支 happy path 一個 AC
- AC-7：V07 enrolled — `npx playwright test --grep @happy-path` exit 0；evidence.json 6 個 test 全 `ok: true`

---

## 4. Interfaces / API Design

> 待 `/planning-spec S140` 完成。需 spec：
> - `TestDataController` endpoint signature + request/response schema
> - `SeedSkillRequest` DTO field 列表（與 `SkillCommandService.create()` 對齊）
> - `application-e2e.yaml` 開關（profile 啟用時的 datasource / log level / scanner 行為）
> - Playwright `e2e/tests/_fixtures.ts`（從 `playwright-expert/assets/fixtures-helper-template.ts` 衍生）

---

## 5. File Plan

> 待 `/planning-spec S140` 完成。預計：
>
> **Backend（新增）：**
> - `backend/src/main/java/.../testsupport/TestDataController.java`
> - `backend/src/main/java/.../testsupport/SeedSkillRequest.java`
> - `backend/src/main/resources/application-e2e.yaml`（如需）
> - `backend/src/test/java/.../testsupport/TestDataControllerTest.java`（unit cover）
>
> **e2e/（新增）：**
> - `e2e/tests/_fixtures.ts`（共用 helper）
> - `e2e/tests/critical-path-browse-search.spec.ts`
> - `e2e/tests/critical-path-skill-detail.spec.ts`
> - `e2e/tests/critical-path-publish.spec.ts`
> - `e2e/tests/critical-path-download.spec.ts`
> - `e2e/tests/critical-path-semantic-search.spec.ts`
> - `e2e/tests/critical-path-analytics.spec.ts`

---

## 6. Task Plan

> 待 `/planning-tasks S140` 完成。預計 task 圖：
>
> 1. Backend infra task — 加 `TestDataController` + `application-e2e.yaml`
> 2. Playwright fixtures helper task — 從 template 衍生 `e2e/tests/_fixtures.ts`
> 3. 6 個 happy-path spec task（每支一個，由 `playwright-expert` DESIGN 產出）
> 4. V07 evidence task — 跑 `npx playwright test --grep @happy-path` 收 evidence.json，更新 §7

---

## 7. Implementation Results

> 待 ship 後填。
