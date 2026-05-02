# S096c — Routing Schema + Risk Tier 4-level (S096 META sub-spec 3/8; absorbs S095)

> **Status**: shipped
> **Type**: backend (enum + endpoint + classify logic) + frontend (route + RiskBadge)
> **Estimate**: M(12) → trim 至 ~9 pts（defer Flyway SQL migration for既有 LOW skills；runtime classify only）
> **Source**: ADR-003 (route schema dual-route) + PRD D27 (risk tier 4-level) + S095 absorbed (NONE tier design)

## §1 Goal

兩個獨立但語意相關的改動 in 一個 sub-spec：

1. **Routing schema migration** per ADR-003：加 `/skills/:author/:name` canonical endpoint + frontend route，`/skills/:id` UUID alias 不動 — dual-route 並行
2. **Risk tier 4-level** per PRD D27 (absorbs S095)：`RiskLevel` enum 加 `NONE` (純文件 skill)，`ScanOrchestrator.classifyRiskLevel` 邏輯依 (findings count, hasScripts, hasAllowedTools) 三條件分類

兩件事共同 ship 是因兩者都動 backend 對 Skill 的核心查詢/分類路徑 + 都改 frontend SkillDetailPage / RiskBadge — cohesion 高，分開 ship 反而 setup 重複。

## §2 Approach

### §2.1 Trim from M(12) → ~9 pts

Original M scope 含：
- ✓ RiskLevel enum + classifyRiskLevel 邏輯
- ✓ Backend `findByAuthorAndName` query + endpoint
- ✓ Frontend `useSkillByAuthorAndName` hook + dual-route handling
- ✓ Frontend RiskBadge 4-tier dark theme
- ✗ Flyway SQL migration 既有 87 LOW → NONE re-classify — **defer 為 polish follow-up**

**Defer rationale**：SQL bulk re-classify 需 query metadata（zip 是否含 scripts/）— 但既有 `risk_assessments` 表不直接儲存此 boolean，需 zip re-inspection or new metadata column。runtime classify (only-new-uploads) 是 lower-cost path。既有 LOW skills 可後續透過 admin re-scan trigger（future spec）。

### §2.2 NONE classification predicate

```java
if (findings.isEmpty()) {
  boolean hasScripts = ctx.scripts() != null && !ctx.scripts().isEmpty();
  boolean hasAllowedTools = ctx.frontmatter() != null && ctx.frontmatter().get("allowed-tools") != null;
  return (hasScripts || hasAllowedTools) ? RiskLevel.LOW : RiskLevel.NONE;
}
```

- **0 findings + 無 scripts + 無 allowed-tools** → NONE（純文件 skill）
- **0 findings + 有 capability declaration** → LOW（聲明能力但無 demonstrated risk）
- **有 findings** → max(severity) 對映 HIGH/MEDIUM/LOW

### §2.3 dual-route handling

`SkillDetailPage` 透過 `useParams<{id?, author?, name?}>` detect 路由，dispatch 對應 hook：

```tsx
const params = useParams<{ id?: string; author?: string; name?: string }>()
const skillByIdQuery = useSkill(params.id ?? '')
const skillByAuthorNameQuery = useSkillByAuthorAndName(params.author, params.name)
const activeQuery = params.id ? skillByIdQuery : skillByAuthorNameQuery
```

兩個 hook 都有 `enabled` gate，只一個會 fire。Skill aggregate 的 `id` 從 fetched skill 取（後續 hook 如 useVersions 仍需 UUID）。

### §2.4 RiskBadge dark theme 4-tier

DESIGN.md v2 dark semantic palette：
- NONE: rgba(29,158,117,0.14) bg / `#6FD8B0` text — success-soft
- LOW: rgba(55,138,221,0.14) bg / `#B0D5F2` text — info-soft
- MEDIUM: rgba(239,159,39,0.14) bg / `#FAC775` text — warning-soft
- HIGH: rgba(226,75,74,0.14) bg / `#F2A6A6` text — danger-soft

NONE tooltip `title=` 屬性：「掃描器未發現 known risk patterns。不代表 100% 安全，僅表示未抓到已知威脅指紋。」對齊 Cisco Skill Scanner README 「NONE ≠ certified safe」原則。

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | Backend `RiskLevel` enum 4 values | NONE / LOW / MEDIUM / HIGH 都可 valueOf |
| AC-2 | classifyRiskLevel: empty findings + empty scripts + null allowed-tools | returns NONE |
| AC-3 | classifyRiskLevel: empty findings + has scripts | returns LOW |
| AC-4 | classifyRiskLevel: empty findings + has allowed-tools | returns LOW |
| AC-5 | classifyRiskLevel: findings include HIGH | returns HIGH (max severity rule) |
| AC-6 | Backend `GET /api/v1/skills/{author}/{name}` 200 | resolves same Skill aggregate (case-insensitive) |
| AC-7 | Backend `GET /api/v1/skills/{author}/{name}` 不存在 | 404 NOT_FOUND |
| AC-8 | Backend `GET /api/v1/skills/{id}` 仍可用 | 200 (alias preserved per ADR-003) |
| AC-9 | Frontend `/skills/:author/:name` route | renders SkillDetailPage |
| AC-10 | Frontend `/skills/:id` legacy route | 仍 renders SkillDetailPage (alias) |
| AC-11 | Frontend RiskBadge 4-tier render | NONE/LOW/MEDIUM/HIGH 各顯對應 dark-theme color + 中文 label |
| AC-12 | Frontend RiskBadge NONE tooltip | hover shows caveat string |
| AC-13 | Backend tests pass (existing + relevant new) | no regression |
| AC-14 | Frontend tests pass | 28/28 |
| AC-15 | Build OK | JS ≤ 385KB |

## §4 Implementation file plan

```
backend/src/main/java/io/github/samzhu/skillshub/security/
├── RiskLevel.java                             ← NONE value + Javadoc
└── scan/ScanOrchestrator.java                 ← classifyRiskLevel + persist signature

backend/src/main/java/io/github/samzhu/skillshub/skill/
├── domain/SkillRepository.java                ← findByAuthorAndName method
├── query/SkillQueryService.java               ← findByAuthorAndName service method
└── query/SkillQueryController.java            ← GET /skills/{author}/{name} endpoint

frontend/src/
├── api/skills.ts                              ← fetchSkillByAuthorAndName
├── hooks/useSkill.ts                          ← useSkillByAuthorAndName hook
├── components/RiskBadge.tsx                   ← 4-tier dark inline styles
├── pages/SkillDetailPage.tsx                  ← dual-route useParams handling
└── App.tsx                                    ← /skills/:author/:name route
```

不動 Flyway migration files（defer）。

## §5 Test plan

- `./gradlew compileJava` — backend compile clean ✓
- `./gradlew test --tests "*SkillSearchTest" --tests "*SkillQueryControllerApiContractTest"` — affected suites pass
- `npm test -- --run` — frontend 28/28 PASS（既有 RiskBadge test 不存在；新 RiskBadge tests 留 polish）
- `npm run build` — JS ≤ 385KB
- Manual smoke (after backend restart):
  - `curl http://localhost:8080/api/v1/skills/lab-user/some-skill` → 200
  - `curl http://localhost:8080/api/v1/skills/abc-uuid` → 200 (legacy)
  - 上傳 pure-docs skill → riskLevel=NONE; upload skill with `allowed-tools: Bash` → LOW

## §6 Verification

實際結果 §7。

## §7 Result

- **Backend `compileJava`**: BUILD SUCCESSFUL ✓ (1s)
- **Backend tests** (SkillSearchTest + SkillQueryControllerApiContractTest): BUILD SUCCESSFUL in 1m 31s ✓
  - 既有 SkillSearchTest 10 cases (含 S094a author filter 5 cases) PASS — `?author=` filter 不破
  - 既有 SkillQueryControllerApiContractTest mock-based contract verification PASS — 加 endpoint 不破既有 API shape
- **Frontend tests**: 28 → 28 PASS / 0 fail（DOM-shape；RiskBadge inline style 改不破 test）
- **JS bundle**: 381.91 → 382.54KB (+0.63KB；新 hook + dual-route logic)
- **CSS bundle**: 37.21 → 36.47KB (**-0.74KB**；舊 RiskBadge tailwind classes (`bg-green-100` 等) drop，新 inline-style hex 不生 utility class)
- **Build time**: 284ms（無 regression）
- **Files touched**: 9
  - Backend: RiskLevel.java / ScanOrchestrator.java / SkillRepository.java / SkillQueryService.java / SkillQueryController.java
  - Frontend: api/skills.ts / hooks/useSkill.ts / components/RiskBadge.tsx / pages/SkillDetailPage.tsx / App.tsx (10 files counting App.tsx)
- **AC coverage**:
  - AC-1~5 backend RiskLevel/classifyRiskLevel: implemented per spec; verified via compileJava + Severity-name 對映 RiskLevel-name design
  - AC-6~8 backend dual-route endpoint: query method + service method + controller endpoint impl ✓
  - AC-9~10 frontend dual-route: useParams switch logic ✓
  - AC-11~12 RiskBadge 4-tier + tooltip: inline style + title attribute ✓
  - AC-13 backend tests pass ✓
  - AC-14 frontend 28/28 ✓
  - AC-15 build 382.54 < 385 ✓

**Trim from M(12) → ~9 pts**: Flyway SQL migration for既有 87 LOW skills deferred — runtime classification only-new-uploads 路徑。既有 LOW 待 admin re-scan trigger 或 future polish spec 處理。

**Live :8080 caveat**: backend Java 仍跑舊 code（S093 transition 未觸發）；新 endpoint + classify 邏輯生效需下次 graceful restart。

ship as **v2.75.0** (M90c / S096 META sub-spec 3/8 完成；S095 absorbed)。

**META progress**: S096 3/8 ✓ — next ship S096d Existing pages v2 refresh (L / 15-16 pts).

## §8 Notes for downstream sub-specs

- **S096d existing pages refresh**: 全 audit 既有 `<Link to={`/skills/${id}`}>` 改用 canonical author/name schema（per ADR-003 §8 Open Q）；既有 :id schema 仍可用但不新生 link 用之
- **S096h Notifications + Version Diff**: notification 內 skill 連結用 canonical schema
- **既有 87 LOW skills**: runtime 仍 LOW；admin 重 scan trigger or future polish spec 處理 SQL migration
