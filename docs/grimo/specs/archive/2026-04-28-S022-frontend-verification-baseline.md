# S022: Frontend Verification Baseline — `@vitest/coverage-v8` + setupTests + 真實 component / hook test + ESLint root-cause + V06 enrollment

> Spec: S022 | Size: S(8) | Status: ✅ Done
> Date: 2026-04-28
> Depends: S020 ✅（Verification Command Registry + `scripts/verify-all.sh` 為 V06 enrollment 前提；S014 ✅ codebase 真實 component / hook 為測試對象）
> Blocks: 後續 frontend spec 加新 component / hook 即享有 80% line coverage gate；qa-strategy.md L23-25「80% line coverage on new code」實作落地
> Driver: S020 T2 ship 時暴露的 frontend baseline gap — `vitest "No test files found, exit 1"` + `react-refresh/only-export-components` 兩處 ESLint 錯誤；S020 採 Option A 最小修正（`smoke.test.ts` placeholder + 2 處 `eslint-disable-next-line`），明文標 S022 補。

---

## 1. Goal

把 frontend 從「能 run vitest 但無真實測試 + ESLint 用 inline disable 繞過 cva 與 component 同檔的 HMR 警告」升級到「有 8% baseline `coverage gate + setupTests + jest-dom matcher + 1 個 component test + 1 個 hook test 兩種 pattern 樣板 + ESLint 中心化 config（移除 inline disable）」，並把整套接入 `scripts/verify-all.sh` 成為 V06 CRITICAL gate。

**簡單講**：S020 ship 時 frontend 沒任何真實測試也沒 coverage gate；本 spec 一次補齊 testing baseline，讓未來 frontend spec 加 component / hook 自動套上 80% line coverage 門檻（鏡像 backend S019 JaCoCo gate 模式）。

```
┌── 現況（S020 ship 後、本 spec 之前）─────────────────────────┐
│  frontend/                                                    │
│  ├── vite.config.ts            無 test: block                │
│  ├── package.json              vitest 4.1.5 已裝             │
│  │                             @testing-library/react 16 已裝│
│  │                             @testing-library/jest-dom 6 已裝│
│  │                             jsdom 29 已裝                  │
│  │                             ❌ @vitest/coverage-v8 未裝   │
│  ├── src/setupTests.ts         ❌ 不存在                      │
│  ├── src/smoke.test.ts         ✅ 8 行 placeholder（S020）   │
│  ├── components/ui/badge.tsx:48 ❌ inline eslint-disable     │
│  └── components/ui/tabs.tsx:89  ❌ inline eslint-disable     │
│                                                              │
│  scripts/verify-all.sh         V01-V05 enrolled              │
│  └─ V06 (frontend coverage)    ❌ 未 enroll（S020 T2 暫存）  │
│                                                              │
│  qa-strategy.md L23-25         「80% line coverage on new    │
│                                 code」宣告但未實作落地        │
└──────────────────────────────────────────────────────────────┘
                              ↓ S022（本 spec）
┌── 目標 ─────────────────────────────────────────────────────┐
│  frontend/                                                    │
│  ├── vite.config.ts            ✅ test: { coverage: {        │
│  │                                provider:'v8',             │
│  │                                thresholds:{ lines:80 }    │
│  │                              } }                          │
│  ├── package.json              ✅ +@vitest/coverage-v8@4.1.5│
│  ├── src/setupTests.ts         ✅ jest-dom/vitest 引入       │
│  ├── src/smoke.test.ts         ❌ 移除（被 SkillCard +       │
│  │                                useSemanticSearch 取代）   │
│  ├── components/SkillCard.test.tsx       ✅ NEW             │
│  ├── hooks/useSemanticSearch.test.ts     ✅ NEW             │
│  ├── components/ui/badge.tsx:48 ✅ 移除 eslint-disable      │
│  └── components/ui/tabs.tsx:89  ✅ 移除 eslint-disable      │
│                                                              │
│  eslint.config.js              ✅ allowExportNames 中心化   │
│                                  ['badgeVariants',           │
│                                   'tabsListVariants']        │
│                                                              │
│  scripts/verify-all.sh         V01-V06 enrolled             │
│  └─ V06 (frontend coverage)    ✅ npm test -- --coverage   │
│                                  CRITICAL gate；vitest       │
│                                  threshold→exit code         │
│                                                              │
│  qa-strategy.md                ✅ §Verification Command      │
│                                  Registry 加 V06 row +       │
│                                  baseline coverage % 寫入    │
│  ▶ ./scripts/verify-all.sh                                  │
│  ▶ V06: PASS（含 line coverage % 顯示在 stdout）             │
└──────────────────────────────────────────────────────────────┘
```

**Done when**:
- `cd frontend && npm test -- --coverage` exit 0 + line coverage ≥ 80%（`@vitest/coverage-v8` 4.1.5；`thresholds.lines: 80`）
- `SkillCard.test.tsx` + `useSemanticSearch.test.ts` 各至少 2 個 `@DisplayName` 風格 test（即 `describe(...)`/`it(...)` AC-N 對齊）
- `cd frontend && npm run lint` exit 0；`badge.tsx` + `tabs.tsx` 內 `eslint-disable-next-line react-refresh/only-export-components` 兩行被移除
- `scripts/verify-all.sh` Summary 顯示 `V06=PASS`；exit 0
- `docs/grimo/qa-strategy.md` §Verification Command Registry 新增 V06 row（CRITICAL；skip-if `frontend/node_modules` 不存在）+ §Coverage section 把「80% line」與 V03 / V06 cross-link

---

## 2. Approach

S spec — 純 frontend infra + 兩 pattern 樣板測試。**Phase 2 Research 已完成**（兩個 parallel agents：vitest 4 coverage config + eslint-plugin-react-refresh 規則 options；研究結論皆 Validated）。POC: not required（無 hypothesis 級不確定）。

### 2.1 關鍵設計決策（4 項）

| # | 決策 | 選擇 | 理由 | 否決 |
|---|------|------|------|------|
| 1 | ESLint root-cause 路線 | **B. ESLint config `allowExportNames` 中心化 override**；移除 badge.tsx / tabs.tsx 兩處 `eslint-disable-next-line`；`eslint.config.js` 加 `'react-refresh/only-export-components': ['warn', { allowConstantExport: true, allowExportNames: ['badgeVariants', 'tabsListVariants'] }]` | 2026 industry standard for shadcn + Vite + ESLint（[Discussion #5933](https://github.com/shadcn-ui/ui/discussions/5933)、Issue #1534/#7736/#8489）；保留 shadcn CLI re-scaffold lifecycle（拆檔會被 `npx shadcn add` 覆寫）；HMR safety net 對其他 non-component export 仍生效；中心化好維護 | A. 拆檔 `badge.tsx` + `badge.variants.ts`：架構純粹但 shadcn CLI 覆寫 silent breakage；多檔多 import；後續每加 cva 元件都要拆 |
| 2 | Coverage threshold 範圍 | **project-wide `thresholds.lines: 80`** + **`coverage.include` whitelist 鎖定「有對應 test 的 source 檔」**（aggregate across listed files；非 perFile）| 鏡像 backend S019 JaCoCo `BUNDLE` LINE coverage 模式（cross-stack aggregate consistency）；frontend baseline 0 tests（不像 backend S019 已 88% baseline 從 115 tests 累積）— 若 include 全 `src/**`，2 個新 test 對 ~25 檔 coverage 必遠低 80% AC-1 立即 fail；改 include whitelist 為「漸進加入 gate」模式：後續 frontend spec 加 test 時 append 到 include list；threshold 對 tested files 維持 80% aggregate；untested files 不算入分母（gate 對 tested code 仍有意義）| perFile：每檔都 80%（過度激進；單一低 utility break build）；include 全 src/**：baseline 0 → fail；無 threshold：失去 gate 意義 |
| 3 | First-batch test target | **`SkillCard` (component) + `useSemanticSearch` (hook)** 各一；smoke.test.ts 移除（被取代）| (a) 兩種 test pattern 樣板 — component render+screen+MemoryRouter / hook QueryClientProvider+vi.mock；(b) 都有非 trivial assertable 行為（SkillCard：Link 包覆 / RiskBadge 整合 / 條件 score badge；useSemanticSearch：`enabled: query.trim().length > 0` empty guard / queryKey 組合）；(c) 對齊 spec-roadmap §M17 row 30 原案 | RiskBadge + useSkill：更純但缺 `enabled` 條件邏輯 + Link 包覆 — 樣板 expressivity 較弱 |
| 4 | V06 enrollment 命令形式 | **單一 V06 CRITICAL `cd frontend && npm test -- --coverage`**；不額外加 INFO V07 顯示 % | vitest `--coverage` 啟用 `text` reporter 自動 inline 印 coverage table 到 stdout（被 verify-all.log 完整捕捉）；threshold 不過 → exit 1 → CRITICAL FAIL；無需 backend 那種「V01 跑 + V02 解 CSV INFO 顯示」分拆模式 — 因為 gradle test 不會 inline 印 jacoco %（jacoco 是 finalizer task），但 vitest 會。Cross-stack 工具差異不應強行 mirror | A. V06 + V07 鏡像 backend：vitest 已 inline 印 % 重複顯示無價值；INFO 多一行 noise |

### 2.2 與既有架構的契合

| 維度 | 現況 | S022 變動 |
|------|------|-----------|
| `frontend/package.json` devDependencies | `vitest@4.1.5` / `@testing-library/react@16.3.2` / `@testing-library/jest-dom@6.9.1` / `jsdom@29.0.2` 已裝；`@vitest/coverage-v8` 未裝 | `npm install --save-dev @vitest/coverage-v8@4.1.5` — exact-pin（lockstep peer-dep 強制；mismatch 觸發 `ERESOLVE`，[issue #8797](https://github.com/vitest-dev/vitest/issues/8797)）|
| `frontend/vite.config.ts` | 無 `test:` block；vitest 跑 default | 加 `test: { globals: true, environment: 'jsdom', setupFiles: ['./src/setupTests.ts'], coverage: { provider: 'v8', reporter: ['text','html','json-summary'], thresholds: { lines: 80 } } }` + `import { defineConfig } from 'vitest/config'`（取代 vite/config）|
| `frontend/src/setupTests.ts` | 不存在 | 新建 1 行 `import '@testing-library/jest-dom/vitest'` — vitest-specific subpath（非 deprecated bare path；jest-dom 6.x +）|
| `frontend/src/smoke.test.ts` | 8 行 placeholder（S020 Option A） | 刪除（被真實 SkillCard + useSemanticSearch test 取代）|
| `frontend/src/components/SkillCard.test.tsx` | 不存在 | 新建；`@testing-library/react` `render` + `screen` + `MemoryRouter` wrapper（因 SkillCard 包 `<Link>`）；至少 4 個 `it(...)` 對齊 AC-2/3 |
| `frontend/src/hooks/useSemanticSearch.test.ts` | 不存在 | 新建；`@testing-library/react` `renderHook` + `QueryClientProvider` wrapper + `vi.mock('@/api/search')`；至少 4 個 `it(...)` 對齊 AC-4/5 |
| `frontend/src/components/ui/badge.tsx` | L48 `// eslint-disable-next-line react-refresh/only-export-components` | 移除該行 |
| `frontend/src/components/ui/tabs.tsx` | L89 同上 inline disable | 移除該行 |
| `frontend/eslint.config.js` | 用 `reactRefresh.configs.vite` preset；無 rule override | 加 `rules: { 'react-refresh/only-export-components': ['warn', { allowConstantExport: true, allowExportNames: ['badgeVariants', 'tabsListVariants'] }] }` 中心化 override |
| `scripts/verify-all.sh` | V01-V05 enrolled（S020）；V04 跑 `npm test`，V05 跑 `npm run lint`；無 V06 | V04 `npm test` 維持不變（vitest 自身 PASS gate）；新加 **V06 `npm test -- --coverage`** CRITICAL `run_skip_if`（skip-if `frontend/node_modules` 不存在；同 V04/V05）；V06 在 V05 後 |
| `docs/grimo/qa-strategy.md` §Verification Command Registry | V01-V05 主 table | 新加 V06 row：`cd frontend && npm test -- --coverage` / CRITICAL / Skip-if `frontend/node_modules` 不存在 / Notes 含 vitest threshold gate + coverage % inline；§Coverage 段把「80% line」與 V03 (backend JaCoCo) / V06 (frontend vitest) cross-link |
| `docs/grimo/qa-strategy.md` §AC-to-Test Contract | 含 backend `@DisplayName("AC-N: ...")` + frontend `describe('AC-N: ...')` 規約 | 不動（樣板測試直接套用既有 frontend `describe('AC-N: ...')` 規約）|

### 2.3 Source of Truth Map

| # | 主題 | Source | 對應 spec 章節 |
|---|------|--------|--------------|
| R1 | `@vitest/coverage-v8` 4.x exact-pin lockstep + ERESOLVE | [vitest issue #8797](https://github.com/vitest-dev/vitest/issues/8797) + [npmjs.com/@vitest/coverage-v8](https://www.npmjs.com/package/@vitest/coverage-v8) | §2.2 dep 安裝 |
| R2 | `vite.config.ts` `test:` block 標準語法 + `vitest/config` defineConfig | [vitest.dev/config](https://vitest.dev/config/) + [vitest.dev/config/coverage](https://vitest.dev/config/coverage) | §4.1 vite.config.ts |
| R3 | Coverage reporter 選項列表（text/html/json-summary/json/lcov/clover/cobertura；無 CSV）| vitest 官方 docs + Istanbul ref | §2.1 #4（V06 KISS） |
| R4 | Threshold gate exit code 行為（threshold 不過 → exit 1）| [vitest discussion #5249](https://github.com/vitest-dev/vitest/discussions/5249) | §2.1 #4 |
| R5 | Project-wide vs per-file threshold 語法 + recommended default | [vitest.dev/config/coverage](https://vitest.dev/config/coverage) | §2.1 #2 |
| R6 | `@testing-library/jest-dom/vitest` non-deprecated subpath（v5.16.x+ 引入） | [github.com/testing-library/jest-dom](https://github.com/testing-library/jest-dom) | §4.2 setupTests.ts |
| R7 | `tsconfig.app.json verbatimModuleSyntax: true` 對 test file `import type` 影響 | tsconfig 設定 + vitest type-import docs | §2.1 — 不需特殊處理（test files 用值-import 為主） |
| R8 | `eslint-plugin-react-refresh` `Options` interface 4 個 keys | [src/index.ts](https://raw.githubusercontent.com/ArnaudBarre/eslint-plugin-react-refresh/main/src/index.ts) + [README](https://github.com/ArnaudBarre/eslint-plugin-react-refresh/blob/main/README.md) | §2.1 #1 |
| R9 | `allowConstantExport` 不涵蓋 `cva()` CallExpression（hardcoded Set 只有 4 種 AST 型別）| [Issue #84](https://github.com/ArnaudBarre/eslint-plugin-react-refresh/issues/84) + source | §2.1 #1（為何用 `allowExportNames` 而非單純 `allowConstantExport`）|
| R10 | `allowExportNames` 只接 exact string，無 regex（[Issue #83](https://github.com/ArnaudBarre/eslint-plugin-react-refresh/issues/83) 仍 open，2025-04 開到 2026-04 無 merge） | plugin source | §2.1 #1（為何 hardcode `'badgeVariants'` / `'tabsListVariants'` 兩條） |
| R11 | shadcn/ui community consensus — `cva` + component 同檔；rule disable 主流 = config-level `allowExportNames` | [shadcn-ui Discussion #5933](https://github.com/shadcn-ui/ui/discussions/5933) + Issues #1534/#7736/#8489 | §2.1 #1 |
| R12 | `reactRefresh.configs.vite` preset 內容 = `'error'` + `allowConstantExport: true` only；override `rules:` 必須 re-state `allowConstantExport: true`（rule config 完全替換）| plugin source | §4.3 eslint.config.js |
| R13 | Existing components / hooks 真實 list — SkillCard / useSemanticSearch 為 roadmap §M17 row 30 原案 | `frontend/src/components/` + `frontend/src/hooks/` 列出 | §2.1 #3 |

### 2.4 Research Sufficiency Gate

| 設計決策 | 信心 | 證據 |
|---------|------|------|
| `@vitest/coverage-v8@4.1.5` exact-pin 為唯一可行版本 | **Validated** | R1 npm peerDeps + 已知 ERESOLVE issue |
| vite.config.ts test block + coverage v8 config 為 vitest 4 標準語法 | **Validated** | R2 vitest 官方 docs |
| `@testing-library/jest-dom/vitest` subpath 為 vitest-specific 非 deprecated 路徑 | **Validated** | R6 jest-dom GitHub README |
| `allowExportNames` 不支援 regex；hardcode 兩名為唯一可行 | **Validated** | R10 plugin Issue #83 + source；2026 仍 open |
| Option B（`allowExportNames` config）為 2026 shadcn 主流 | **Validated** | R11 多個 shadcn issues + maintainer 立場 + community consensus |
| Project-wide threshold 為新專案 baseline 推薦 | **Validated** | R5 vitest 官方 docs 推薦 |
| vitest threshold gate exit code 1 = CI hard gate | **Validated** | R4 vitest discussion |
| `verbatimModuleSyntax: true` 對測試檔影響可控（值-import 為主） | **Validated** | R7 tsconfig + vitest types 邏輯推導 |

**POC: not required**。所有 8 條設計決策皆 Validated（research agents 直接從 source / official docs / GitHub issues 證實）。

---

## 3. SBE Acceptance Criteria

> AC-naming contract: frontend `describe('AC-N: ...', () => { it(...) })`（per qa-strategy.md §AC-to-Test Contract frontend example）；evidence-only AC 沿用 S019/S020 build-evidence 例外（per qa-strategy.md S021 補丁待加 — 即「build/config spec = evidence-only AC」明文落地）。

**AC-1**: `@vitest/coverage-v8@4.1.5` 安裝且 vitest config wire 完成
- Given `frontend/package.json` devDependencies 含 `@vitest/coverage-v8: ^4.1.5`（exact lockstep；npm install fail 則 spec block）
- And `frontend/vite.config.ts` `test.coverage` block 含 `provider: 'v8'` + `thresholds.lines: 80` + `reporter` 含至少 `text` + `html` + `json-summary` + `include` whitelist 含 `'src/components/SkillCard.tsx'` + `'src/hooks/useSemanticSearch.ts'`（per spec §4.1 — 漸進加入 gate 模式；後續 frontend spec append）
- When T2+T3 完成後 `cd frontend && npm test -- --coverage`（注意 T1 單獨完成時若還沒任何 test 檔，AC-1 evidence 由 vitest config 落地 + npm install OK 為證；threshold gate exit 0 由整 spec 完成後才驗）
- Then exit code 0 + stdout 顯示 coverage table（vitest `text` reporter inline）+ `coverage/coverage-summary.json` 產出 + line coverage ≥ 80%（aggregate over include list）
- Evidence: `cat frontend/package.json | grep '@vitest/coverage-v8'` 命中；`grep -A 12 'coverage:' frontend/vite.config.ts` 含 `provider: 'v8'`、`thresholds`、`lines: 80`、`include`

**AC-2**: `setupTests.ts` 引入 jest-dom matcher；`describe('AC-2: SkillCard 渲染', ...)` 至少 2 個 it 通過
- Given `frontend/src/setupTests.ts` 含 `import '@testing-library/jest-dom/vitest'`
- And `frontend/vite.config.ts` `test.setupFiles` 引用該檔
- And `frontend/src/components/SkillCard.test.tsx` 含 `describe('AC-2: SkillCard 渲染', () => {...})`
- When `cd frontend && npm test -- SkillCard`
- Then 至少 2 個 it 通過（覆蓋：(a) `name` / `description` / `author` / `category` 文字渲染；(b) `latestVersion` 顯示為 `v{ver}` 格式；(c) `<Link>` 帶 `to={'/skills/{id}'}`；(d) `RiskBadge` 整合；(e) 條件 `score` badge 「`XX% 相符`」）
- And test wrapper 包 `<MemoryRouter>` 解 `<Link>` 環境依賴
- Evidence: `npm test -- SkillCard` 輸出 `Test Files 1 passed` + `Tests N passed`（N ≥ 2）

**AC-3**: SkillCard test 含 jest-dom matcher 使用範例
- Given AC-2 test file
- When 讀 test 程式
- Then 至少使用 `expect(...).toBeInTheDocument()` 一次（demonstrate jest-dom matcher 整合 — 後續 spec 直接 copy pattern）
- Evidence: `grep "toBeInTheDocument\|toHaveAttribute\|toHaveClass\|toHaveTextContent" frontend/src/components/SkillCard.test.tsx` 至少 1 命中

**AC-4**: `describe('AC-4: useSemanticSearch hook', ...)` 至少 2 個 it 通過
- Given `frontend/src/hooks/useSemanticSearch.test.ts` 含 `describe('AC-4: useSemanticSearch hook', () => {...})`
- And test wrapper 包 `QueryClientProvider`（new `QueryClient` per test 避免 cache pollution）
- And `vi.mock('@/api/search')` 攔截 `fetchSemanticSearch`
- When `cd frontend && npm test -- useSemanticSearch`
- Then 至少 2 個 it 通過（覆蓋：(a) 空 query（`""` / `"   "`）→ `result.current.fetchStatus === 'idle'`，`fetchSemanticSearch` 不被呼叫；(b) 非空 query → `fetchSemanticSearch` 被呼叫 1 次 + `result.current.data` 等於 mock 回傳；(c) queryKey 含 query 字串確保不同 query 各自獨立 cache）
- Evidence: `npm test -- useSemanticSearch` 輸出 `Test Files 1 passed` + `Tests N passed`（N ≥ 2）

**AC-5**: `enabled: query.trim().length > 0` 邏輯有測試覆蓋
- Given AC-4 test file
- When 讀 test 程式
- Then 至少 1 個 it 標題或 BDD 描述含「空 query 不觸發」/「empty query disables」/ `enabled: false` 行為驗證
- Evidence: `grep -E "空 query|empty.*query|enabled.*false|fetchStatus.*idle" frontend/src/hooks/useSemanticSearch.test.ts` 至少 1 命中

**AC-6**: ESLint `allowExportNames` 中心化 override；inline `eslint-disable` 移除
- Given `frontend/eslint.config.js` `rules:` 區段含 `'react-refresh/only-export-components': ['warn', { allowConstantExport: true, allowExportNames: ['badgeVariants', 'tabsListVariants'] }]`
- And `frontend/src/components/ui/badge.tsx` 不含 `eslint-disable-next-line react-refresh/only-export-components`
- And `frontend/src/components/ui/tabs.tsx` 同上不含
- When `cd frontend && npm run lint`
- Then exit 0；無 `react-refresh/only-export-components` warning（在 badge.tsx / tabs.tsx 都不應觸發）
- Evidence: `grep "eslint-disable-next-line react-refresh" frontend/src/components/ui/badge.tsx frontend/src/components/ui/tabs.tsx` 0 命中；`npm run lint` exit 0

**AC-7**: V06 加入 `scripts/verify-all.sh` registry；verify-all.sh exit 0
- Given `scripts/verify-all.sh` 含 `run_skip_if "V06" "..." "..." "(cd '${REPO_ROOT}/frontend' && npm test -- --coverage)"`（位置 V05 之後）
- And skip-if 條件為 `[ ! -d '${REPO_ROOT}/frontend/node_modules' ]` 同 V04/V05 風格
- When `./scripts/verify-all.sh` 從 `node_modules` 已 install state 跑
- Then Summary 區段含 `V06=PASS`；exit 0；CRIT_FAIL 仍為 0
- And `verify-all.log` 含 `=== ... V06 [CRITICAL/skip-if-unavailable] cd frontend && npm test -- --coverage ===` section header + vitest stdout coverage table inline
- Evidence: `./scripts/verify-all.sh && tail -10 verify-all.log` 顯示 6 條 Results

**AC-8**: `qa-strategy.md` §Verification Command Registry 加入 V06 row + Coverage section cross-link
- Given `docs/grimo/qa-strategy.md` §Verification Command Registry 主 table 多一 row：`| V06 | cd frontend && npm test -- --coverage | CRITICAL | frontend/node_modules 不存在 | vitest threshold lines:80 gate；coverage table inline 印於 stdout |`
- And §Coverage（L23-25 附近）原文「80% line coverage on new code」段加 sentence「Backend 由 V03（JaCoCo gate；S019）執行；Frontend 由 V06（vitest threshold；S022）執行；兩者 cross-stack 相同 80% line 標準」
- When `grep -nE "V06|frontend.*coverage|vitest.*threshold" docs/grimo/qa-strategy.md`
- Then 至少 3 命中（V06 row + Coverage cross-link 段 + S022 reference）
- Evidence: grep 命中 + human review

### 驗收命令

per qa-strategy.md（per S020 verify-all.sh registry）：

```bash
# 主驗：全 stack（含本 spec V06）
./scripts/verify-all.sh
# Pass: exit 0；Summary 顯示 V06=PASS；前 5 條維持 PASS

# 副驗：單獨 frontend coverage
cd frontend && npm test -- --coverage
# Pass: exit 0；stdout 顯示 coverage table；line coverage ≥ 80%

# 副驗：lint clean（確認 inline disable 移除後 ESLint 仍過）
cd frontend && npm run lint
# Pass: exit 0；無 react-refresh/only-export-components warning
```

**Pass 條件**: 主驗 verify-all.sh exit 0 + V06=PASS；coverage line ≥ 80%；frontend lint clean；human review 確認 SkillCard.test.tsx + useSemanticSearch.test.ts 覆蓋 AC-2/3/4/5。

---

## 4. Interface / API Design

本 spec 純 frontend infra + test 樣板；無新 production API。以下為 config / setup / test code 規範範本。

### 4.1 `frontend/vite.config.ts` 升級

```typescript
import path from 'node:path'
import { defineConfig } from 'vitest/config'   // 取代 vite/config — 啟用 test: 區段型別
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    proxy: {
      '/api/v1': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    // ambient describe/it/expect — 測試檔不需 import vitest API
    globals: true,
    // SkillCard 用 DOM API（render → screen.getByText） — 必 jsdom
    environment: 'jsdom',
    // 全域 setup — jest-dom matcher 在所有 test 啟用
    setupFiles: ['./src/setupTests.ts'],
    coverage: {
      provider: 'v8',
      // text: stdout inline 印 % table（V06 同時當 INFO 顯示）；
      // html: 本機 debug 用；json-summary: machine-parseable for verify-all.sh tail 顯示用（暫不解；保留未來 V07 可能性）
      reporter: ['text', 'html', 'json-summary'],
      // include whitelist：鎖定「有對應 test 的 source 檔」— frontend baseline 0 tests，
      // 若 include 全 src/**，2 個新 test 對 ~25 檔 coverage 必遠低 80%（per spec §2.1 #2 決策）。
      // 採「漸進加入 gate」模式：後續 frontend spec 加 test 時 append 到本 list；
      // threshold 對 tested files 維持 80% aggregate；untested files 不算入分母。
      include: [
        'src/components/SkillCard.tsx',
        'src/hooks/useSemanticSearch.ts',
      ],
      // BUNDLE 模式：aggregate across listed (include) files；對齊 backend S019 JaCoCo 模式
      thresholds: {
        lines: 80,
      },
    },
  },
})
```

### 4.2 `frontend/src/setupTests.ts` 新建

```typescript
// vitest-specific subpath（jest-dom 6.x；非 deprecated bare path）— 為 vitest 的 expect 注入 toBeInTheDocument 等 matcher
import '@testing-library/jest-dom/vitest'
```

### 4.3 `frontend/eslint.config.js` 升級

```javascript
import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
    // shadcn/ui CLI scaffolding 慣例：cva variants 與 component 同檔便於 `npx shadcn add` 升級不破壞拆檔結構。
    // react-refresh/only-export-components 規則對 cva CallExpression 不視為 constant（allowConstantExport
    // 不涵蓋；plugin source `constantExportExpressions` 只列 4 種 AST 型別 — Literal / Unary / Template / Binary）。
    // allowExportNames hardcode 兩個目前用到的 cva 變數；plugin Issue #83 仍 open（無 regex 支援）。
    // 未來新增 cva 元件需擴充本 array。
    rules: {
      'react-refresh/only-export-components': [
        'warn',
        {
          // re-state — overriding `rules:` 完全替換 preset config
          allowConstantExport: true,
          allowExportNames: ['badgeVariants', 'tabsListVariants'],
        },
      ],
    },
  },
])
```

### 4.4 `frontend/src/components/SkillCard.test.tsx` 樣板

```typescript
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { SkillCard } from './SkillCard'
import type { Skill } from '@/types/skill'

const mockSkill: Skill = {
  id: 'skill-001',
  name: 'k8s-helper',
  description: 'Kubernetes 排錯助理...',
  author: 'samzhu',
  category: '雲端維運',
  latestVersion: '0.3.1',
  riskLevel: 'LOW',
  status: 'PUBLISHED',
  downloadCount: 42,
  // ... per Skill 型別補完
}

describe('AC-2: SkillCard 渲染', () => {
  // SkillCard 包 <Link> — 必 MemoryRouter wrap 解 router context
  const renderCard = (skill: Skill, score?: number) =>
    render(
      <MemoryRouter>
        <SkillCard skill={skill} score={score} />
      </MemoryRouter>,
    )

  it('顯示 skill 基本欄位（name / author / description / category）', () => {
    renderCard(mockSkill)
    expect(screen.getByText('k8s-helper')).toBeInTheDocument()
    expect(screen.getByText('samzhu')).toBeInTheDocument()
    expect(screen.getByText(/Kubernetes 排錯助理/)).toBeInTheDocument()
    expect(screen.getByText('雲端維運')).toBeInTheDocument()
  })

  it('latestVersion 顯示為 `v{semver}` 格式', () => {
    renderCard(mockSkill)
    expect(screen.getByText('v0.3.1')).toBeInTheDocument()
  })

  it('Link 包覆整張卡片 → /skills/{id}', () => {
    renderCard(mockSkill)
    const link = screen.getByRole('link')
    expect(link).toHaveAttribute('href', '/skills/skill-001')
  })

  it('條件 score badge — 傳入 score 顯示「XX% 相符」', () => {
    renderCard(mockSkill, 0.873)
    expect(screen.getByText('87% 相符')).toBeInTheDocument()
  })

  it('條件 score badge — 不傳 score 不顯示相符 badge', () => {
    renderCard(mockSkill)
    expect(screen.queryByText(/% 相符/)).not.toBeInTheDocument()
  })
})
```

### 4.5 `frontend/src/hooks/useSemanticSearch.test.ts` 樣板

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useSemanticSearch } from './useSemanticSearch'
import * as searchApi from '@/api/search'

vi.mock('@/api/search')

const mockFetchSemanticSearch = vi.mocked(searchApi.fetchSemanticSearch)

const createWrapper = () => {
  // 每個 test 獨立 QueryClient — 避免 cache 污染相鄰 test
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

describe('AC-4: useSemanticSearch hook', () => {
  beforeEach(() => {
    mockFetchSemanticSearch.mockReset()
  })

  it('空 query（""）→ enabled: false → fetchSemanticSearch 不被呼叫', () => {
    renderHook(() => useSemanticSearch(''), { wrapper: createWrapper() })
    expect(mockFetchSemanticSearch).not.toHaveBeenCalled()
  })

  it('純空白 query（"   "）→ trim().length === 0 → enabled: false', () => {
    renderHook(() => useSemanticSearch('   '), { wrapper: createWrapper() })
    expect(mockFetchSemanticSearch).not.toHaveBeenCalled()
  })

  it('非空 query → fetchSemanticSearch 被呼叫 1 次 + data 為 mock 回傳', async () => {
    const mockResult = [{ skill: { id: 'a' }, score: 0.9 }]
    mockFetchSemanticSearch.mockResolvedValue(mockResult as any)
    const { result } = renderHook(
      () => useSemanticSearch('Kubernetes'),
      { wrapper: createWrapper() },
    )
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(mockFetchSemanticSearch).toHaveBeenCalledOnce()
    expect(mockFetchSemanticSearch).toHaveBeenCalledWith('Kubernetes')
    expect(result.current.data).toEqual(mockResult)
  })

  it('queryKey 含 query 字串 — 不同 query 各自獨立 cache', async () => {
    mockFetchSemanticSearch.mockResolvedValue([] as any)
    const { result: r1 } = renderHook(
      () => useSemanticSearch('Docker'),
      { wrapper: createWrapper() },
    )
    const { result: r2 } = renderHook(
      () => useSemanticSearch('Kubernetes'),
      { wrapper: createWrapper() },
    )
    await waitFor(() => expect(r1.current.isSuccess).toBe(true))
    await waitFor(() => expect(r2.current.isSuccess).toBe(true))
    // 兩次不同 query → API 呼叫兩次（cache 不共享）
    expect(mockFetchSemanticSearch).toHaveBeenCalledTimes(2)
  })
})
```

### 4.6 `scripts/verify-all.sh` V06 entry（插入 V05 之後 + Summary 之前）

```bash
# V06: frontend coverage gate (S022) — vitest threshold lines:80 → exit 1 if below
run_skip_if "V06" "cd frontend && npm test -- --coverage" \
  "[ ! -d '${REPO_ROOT}/frontend/node_modules' ]" \
  "(cd '${REPO_ROOT}/frontend' && npm test -- --coverage)"
```

### 4.7 `qa-strategy.md` §Verification Command Registry 主 table 加 V06 row

| V06 | `cd frontend && npm test -- --coverage` | CRITICAL | `frontend/node_modules` 不存在 | vitest `coverage.thresholds.lines: 80` gate；text reporter inline 印 coverage table 到 stdout；S022 落地 |

§Coverage 段（L23-25 附近）增句：

> Backend 由 V03（`./gradlew jacocoTestCoverageVerification`，S019 ship）執行 80% line coverage gate；Frontend 由 V06（`npm test -- --coverage`，S022 ship）執行同 80% line coverage gate。Cross-stack 相同 LINE coverage 標準（80%）；不同實作（JaCoCo BUNDLE / vitest project-wide）。

---

## 5. File Plan

| File | Action | Description |
|------|--------|-------------|
| `frontend/package.json` | modify | devDependencies 加 `@vitest/coverage-v8: ^4.1.5`（exact-pin lockstep）|
| `frontend/package-lock.json` | modify | npm install 後自動更新 |
| `frontend/vite.config.ts` | modify | 改 `import { defineConfig } from 'vitest/config'`；加 `test: { globals, environment, setupFiles, coverage: { provider: 'v8', reporter, thresholds: { lines: 80 }, exclude } }`（per §4.1）|
| `frontend/src/setupTests.ts` | new | 1 行 `import '@testing-library/jest-dom/vitest'`（per §4.2）|
| `frontend/src/smoke.test.ts` | delete | 被真實 test 取代（S020 Option A placeholder 本 spec 收尾） |
| `frontend/src/components/SkillCard.test.tsx` | new | AC-2/3 樣板測試；至少 5 個 it（per §4.4）|
| `frontend/src/hooks/useSemanticSearch.test.ts` | new | AC-4/5 樣板測試；至少 4 個 it（per §4.5）|
| `frontend/src/components/ui/badge.tsx` | modify | 移除 L48 `// eslint-disable-next-line react-refresh/only-export-components` 整行 |
| `frontend/src/components/ui/tabs.tsx` | modify | 移除 L89 同上 |
| `frontend/eslint.config.js` | modify | 加 `rules: { 'react-refresh/only-export-components': [...]` 中心化 override（per §4.3）|
| `scripts/verify-all.sh` | modify | V05 後 V06 entry（per §4.6）|
| `docs/grimo/qa-strategy.md` | modify | §Verification Command Registry 主 table 加 V06 row（per §4.7）+ §Coverage 段加 cross-link 句 + §AC-to-Test Contract 補「build/config spec = evidence-only AC」例外（per spec-roadmap §M17 待補項）|
| `docs/grimo/specs/spec-roadmap.md` | modify | (i) Active Work table S022 status `🔲 Planning → ⏳ Design → ⏳ Plan → ⏳ Dev → ✅`；(ii) §M17 detail table 對齊；(iii) ship 後 §Status Summary 進度 `3/4 → 4/4` + M17 Status `✅ v1.1.1 (date)`；(iv) Phase 2.5 milestone collapse |
| `docs/grimo/specs/2026-04-28-S022-frontend-verification-baseline.md` | new | 本 spec 檔（initial = §1-§5；§6/§7 由 `/planning-tasks` + `/shipping-release` 補）|

### 不動的檔案

| File | 原因 |
|------|------|
| `frontend/tsconfig.app.json` | `verbatimModuleSyntax: true` 與測試檔值-import 為主，不需特殊處理 — Phase 2 R7 結論 |
| `frontend/tsconfig.json` / `tsconfig.node.json` | 不影響 vitest（vitest 用 vite resolve；不走 tsc 編譯）|
| 其他 components / hooks / api / store | 本 spec 只測 SkillCard + useSemanticSearch 樣板；其他目標留給後續 frontend feature spec 在加新功能時順便加 test |
| backend/* | 純 frontend spec；backend 不動 |

### 不在本 spec 範圍

- 補測其他既有 9 components / 6 hooks — 留給後續 frontend feature spec（每加 1 component 順便 1 test）；本 spec 純 baseline 樣板
- MSW（Mock Service Worker）整合 — `vi.mock` 對單一 hook test 已足；MSW 是 integration test 規模（多 hook 共享 mock）才有價值，留 Backlog
- E2E（Playwright / Cypress） — 留 Backlog
- React Testing Library `userEvent` 互動測試 — `SkillCard` 純 presentational，不需互動測試；後續加互動 component 時引入
- 視覺回歸測試（visual regression） — Backlog
- Accessibility 自動測試（axe-core） — Backlog
- Bundle size budget gate — 屬 build performance spec 範圍

---

## 6. Task Plan

> POC: not required（spec §2.4 全 Validated；4 條設計決策已逐一從 source / official docs / GitHub issues 證實）
> E2E smoke task: integrated into T4（無獨立 task — T4 GREEN 條件含 `./scripts/verify-all.sh` 全綠，等於 spec-level integration seam test：vitest + @vitest/coverage-v8 + 真實 test files + ESLint clean + verify-all.sh registry routing 全鏈一次 exercise）

### Task Index

| Task | Title | ACs | Target Files | Depends |
|------|-------|-----|--------------|---------|
| **T1** | Frontend test infra — `@vitest/coverage-v8` + `vite.config.ts test` block + `setupTests.ts` + `smoke.test.ts` cleanup | AC-1（config + install evidence；coverage gate 由 T4 final smoke 一起驗）| `frontend/package.json` / `frontend/package-lock.json` / `frontend/vite.config.ts` / `frontend/src/setupTests.ts`（new）/ `frontend/src/smoke.test.ts`（delete）| — |
| **T2** | `SkillCard.test.tsx` — component render + jest-dom matcher 樣板 | AC-2, AC-3 | `frontend/src/components/SkillCard.test.tsx`（new）| T1 |
| **T3** | `useSemanticSearch.test.ts` — hook test + `vi.mock` + `QueryClientProvider` 樣板 | AC-4, AC-5 | `frontend/src/hooks/useSemanticSearch.test.ts`（new）| T1 |
| **T4** | ESLint root-cause（`allowExportNames` config + 移除 inline disable）+ V06 enrollment + qa-strategy doc-sync — final integration smoke | AC-6, AC-7, AC-8 + AC-1 final gate | `frontend/eslint.config.js` / `frontend/src/components/ui/badge.tsx` / `frontend/src/components/ui/tabs.tsx` / `scripts/verify-all.sh` / `docs/grimo/qa-strategy.md` | T1, T2, T3 |

4 tasks 對齊 8 ACs（spec S(8)；Lead Engineer 標準：S → 3-4 tasks）。T1 為 infra；T2/T3 平行 test 樣板；T4 dual-purpose（ESLint config + V06 + docs + 整合 smoke gate）。T2/T3 互不依賴（不同檔），但都 depend T1（vitest config 須先 wire）。

### Granularity 理由

- **T1 不單跑 npm test** — vitest 4 default `passWithNoTests: false`；smoke 刪除後若 T2/T3 還沒落地會 exit 1；T1 證據限於 install + config + setup 檔內容正確 + vitest binary 可執行
- **T2 + T3 平行可能** — 不同 test 檔互不影響；實作時可同 RED 同 GREEN
- **T4 GREEN 即 spec E2E 整合驗證** — 跑 `./scripts/verify-all.sh` exit 0 + Summary V01-V06 全 PASS = 把整 stack 一次 exercise；不需獨立 E2E task

### POC Findings

POC: not required。spec §2.4 全 Validated（8 條決策從 R1-R13 source / official docs / GitHub issues 直接證實）：

| 設計決策 | 信心 |
|---------|------|
| `@vitest/coverage-v8@4.1.5` exact-pin lockstep | Validated |
| vite.config.ts test block + coverage v8 config | Validated |
| `@testing-library/jest-dom/vitest` subpath | Validated |
| `allowExportNames` 不支援 regex；hardcode 兩名為唯一可行 | Validated |
| Option B（`allowExportNames` config）為 2026 shadcn 主流 | Validated |
| Project-wide threshold + `coverage.include` whitelist 漸進 gate 模式 | Validated |
| vitest threshold gate exit code 1 = CI hard gate | Validated |
| `verbatimModuleSyntax: true` 對測試檔影響可控 | Validated |

### Phase 2 Pre-Task Refinement（記錄）

設計階段選 `thresholds.lines: 80` project-wide 對齊 backend BUNDLE 模式，但未明確處理「frontend baseline 0 tests vs backend baseline 88% from 115 tests」差異；Phase 2 task 創建前發現直接 include 全 src/** 會立即 fail（2 test 檔對 ~25 source 檔 coverage % 必遠低 80%）。

修正：保留「project-wide aggregate；非 perFile」設計意圖，加入 `coverage.include` whitelist 鎖定「有對應 test 的 source 檔」漸進 gate 模式（spec §2.1 #2 + §4.1 已 inline 修訂）。後續 frontend spec 加 test 時 append 到 include list；threshold 對 tested files 維持 80% aggregate；untested files 不算入分母（gate 對 tested code 仍有意義；不需 retroactively backfill）。

### Task Files

- `docs/grimo/tasks/2026-04-28-S022-T1-infra-coverage-setup.md`
- `docs/grimo/tasks/2026-04-28-S022-T2-skillcard-component-test.md`
- `docs/grimo/tasks/2026-04-28-S022-T3-usesemanticsearch-hook-test.md`
- `docs/grimo/tasks/2026-04-28-S022-T4-eslint-v06-docs.md`

---

## 7. Implementation Results

### Verification Summary

| 驗證 | 命令 | 結果 |
|------|------|------|
| 主驗（pre-flight gate）| `./scripts/verify-all.sh` | exit 0；V01=PASS / V02=INFO（backend LINE 88.1%） / V03=PASS / V04=PASS / V05=PASS / **V06=PASS**；CRIT_FAIL=0 |
| AC-1 副驗 | `cd frontend && npm test -- --coverage` | exit 0；threshold lines:80 PASS；`coverage-summary.json` total = `lines: { total: 3, covered: 3, pct: 100 }`（include whitelist 兩檔全綠）|
| AC-2/3 副驗 | `cd frontend && npm test -- SkillCard` | 6/6 it 通過；`toBeInTheDocument` / `toHaveAttribute` / `not.toBeInTheDocument` jest-dom matcher 11 處使用 |
| AC-4/5 副驗 | `cd frontend && npm test -- useSemanticSearch` | 4/4 it 通過；`空 query` / `enabled: false` 關鍵字命中 6 處 |
| AC-6 副驗 | `cd frontend && npm run lint` | exit 0；`grep "eslint-disable-next-line react-refresh" frontend/src/components/ui/{badge,tabs}.tsx` → 0 命中 |
| AC-8 副驗 | `grep -nE "V06\|frontend.*coverage\|vitest.*threshold" docs/grimo/qa-strategy.md` | 4 命中（≥3 required）；`grep "Build / Config\|evidence-only" docs/grimo/qa-strategy.md` → 2 命中 |

### E2E Artifact Verification

**已執行 — by T4 GREEN gate**：`./scripts/verify-all.sh` exit 0 + V01-V06 全 PASS = spec-level integration seam test，涵蓋：
- `@vitest/coverage-v8@4.1.5` 安裝後可被 vitest binary 載入（V06 跑得起來）
- `vite.config.ts test:` block 被 vitest 認可（globals / environment / setupFiles / coverage 全部生效）
- `setupTests.ts` 被 vitest 載入；jest-dom matcher 在 SkillCard test 可用（toBeInTheDocument 通過）
- `coverage.include` whitelist 限縮 + `thresholds.lines: 80` gate 同時生效（aggregate over SkillCard + useSemanticSearch = 100% > 80%）
- ESLint config `allowExportNames` override 在無 inline disable 情境下仍 lint clean
- `verify-all.sh` registry routing 正確（V06 entry 對齊 V04/V05 `run_skip_if` pattern）
- backend 既有 V01-V05 不受 frontend 變動影響（backend coverage 88.1% 維持）

整套 integration seam 在無外部 stub 下 exercise 完整鏈路。

### Implementation Notes（divergences captured）

1. **檔名 `.test.tsx` vs spec §5 `.test.ts`**（T3 useSemanticSearch）— spec §4.5 樣板使用 JSX (`<QueryClientProvider client={client}>`)，`.ts` 副檔名 TypeScript 無法 parse JSX。改 `.test.tsx` 為 React Testing Library 標準慣例。Spec §5 file plan 行誤，已透過此 §7 紀錄；不另回填 §5（spec living document 原則：§7 為 ground truth，下一個 frontend hook test spec 直接採 `.tsx` 慣例）。

2. **`SemanticSearchResult` 型別形狀差異**（T3 mock 資料）— spec §4.5 樣板示意 `[{ skill: { id: 'a' }, score: 0.9 }]` 巢狀結構為 design-time 估計；actual `frontend/src/types/skill.ts` `SemanticSearchResult` 為 flat 9 fields（無 `skill` 巢狀）— mock 資料依 actual interface 補齊。

3. **`coverage.include` whitelist 漸進加入 gate 模式**（pre-task-creation refinement 已寫入 §2.1 #2 + §4.1） — design 階段 §2.1 #2 picked project-wide threshold mirror backend BUNDLE，但未明確處理「frontend baseline 0 tests vs backend baseline 88% from 115 tests」差異；Phase 2 task creation 前 challenge approach 發現直接 include 全 `src/**` 會 fail（2 test 對 ~25 source 檔 coverage 必遠低 80%）。修正：保留「project-wide aggregate；非 perFile」設計意圖，加入 `coverage.include` whitelist 鎖定有對應 test 的 source 檔。後續 frontend spec 加 test 時 append 到本 list；threshold 對 tested files 維持 80% aggregate；untested files 不算入分母。

4. **AC-1 phrasing 調整**（spec §3 AC-1） — design 階段 AC-1 BDD 寫「`cd frontend && npm test -- --coverage` exit 0」隱含 T1 完成即可驗證。實際因 `passWithNoTests: false` vitest 4 default 行為 + smoke.test.ts 移除後 T1 alone 無 test 檔，T1 跑 npm test 會 exit 1。改 AC-1 phrasing 為「T2+T3 完成後 ... threshold gate exit 0 由整 spec 完成後才驗」；T1 evidence 限於 install + config + setup 落地正確 + vitest binary 可執行；coverage gate exit 0 由 T4 final integration smoke 驗證。

5. **qa-strategy.md L17 `npm run coverage` stale ref**（T4 順帶修） — 既有 §Coverage block 寫「`cd frontend && npm run coverage`」但 `package.json` 無此 script。T4 替換為「`cd frontend && npm test -- --coverage`」對齊 V06 實際命令。屬 super-stale-cleanup 範圍但因屬 §Coverage block（V06 cross-link sentence 鄰近區），same-file edit 順帶處理避免讀者混亂。

6. **§「不 enroll 的命令」L85 `npm run coverage` row 保留不動** — 此 row（S020 ship 時的 stale state 描述）technically 仍 valid（`npm run coverage` script 仍未加入 package.json，V06 採 `npm test -- --coverage` 不同形式），但 reasoning 過時（S022 已裝 `@vitest/coverage-v8`）。T4 BDD 未涵蓋此 row 修訂；屬 super-stale-cleanup 範圍，留 future spec 處理。

### Tech Debt Surfaced

1. **`coverage.include` whitelist 維護成本**（spec §2.1 #2 + §4.1）：每加新 frontend component / hook test 需手動 append 到 `vite.config.ts` `coverage.include` array。未來 frontend test 規模成長後，可考慮：(a) 改 glob pattern（`'src/**/*.tsx'` 全收，threshold 隨 baseline 動態）；(b) 改 `coverage.thresholds.perFile: true` 從一開始就 per-file gate；(c) include 規則由 CI/CD 自動推導（掃描有對應 .test.tsx 的 source 檔）。三種方案各有 tradeoff，留 Backlog 觀察 baseline 演化後決定。

2. **shadcn `cva` 元件 ESLint allowExportNames 維護**（spec §2.1 #1 + §4.3）：每加新 shadcn `cva` 元件（如 `buttonVariants` / `cardVariants`）需手動加到 `eslint.config.js` `allowExportNames` array。受 `eslint-plugin-react-refresh` Issue #83 限制（無 regex 支援）。Issue 仍 open，無 ETA。短期解：增加新元件時 review；長期解：upstream Issue resolve 後改 regex pattern。

3. **`@vitest/coverage-v8` 4.1.5 lockstep maintenance**：vitest 升版（如 4.2 → 5.0）必須同 commit 升 `@vitest/coverage-v8` 同版號（peer-dep ERESOLVE 強制；spec R1）。已在 vite.config.ts 註解但未在 package.json scripts 加 lockstep 檢查工具。後續 Renovate/Dependabot 配置 spec 可加 group rule（`@vitest/*` 一齊升）。

4. **§Coverage block 與 V06 row 內容部分重複**：qa-strategy.md L20-30 §Coverage 段 + L69 主 table V06 row 都寫 "80% line coverage" + "vitest coverage" — 未來若 threshold 調整需兩處同改。考慮把 V06 row 詳細語意精簡 + Coverage block 為單一權威，但此屬 docs refactor 非 S022 scope。

5. **§「不 enroll 的命令」L85 row 描述 stale**（per Implementation Note #6）：`npm run coverage | package.json 無此 script + @vitest/coverage-v8 未裝；待獨立 frontend coverage spec` 中後半段（`@vitest/coverage-v8` 未裝）已 obsolete。下一個 docs touching spec 可順帶清理。

### AC Results

| AC | 描述 | 結果 | 證據 |
|----|------|------|------|
| AC-1 | `@vitest/coverage-v8@4.1.5` 安裝 + vite.config wire 完成 | ✅ pass | T1 install + config 落地；T4 final smoke V06 exit 0 + coverage `pct: 100` on include whitelist |
| AC-2 | `SkillCard.test.tsx` describe('AC-2: ...') 至少 2 個 it 通過 | ✅ pass | 6/6 it 通過（基本欄位 / `v{ver}` / latestVersion null edge / Link href / 條件 score badge 顯示 / 條件 score badge 不顯示）|
| AC-3 | jest-dom matcher 使用範例至少 1 處 | ✅ pass | 11 處使用（`toBeInTheDocument` × 6 + `toHaveAttribute` × 1 + `not.toBeInTheDocument` × 4）|
| AC-4 | `useSemanticSearch.test.tsx` describe('AC-4: ...') 至少 2 個 it 通過 | ✅ pass | 4/4 it 通過（空 query / 純空白 / 非空觸發 + arg + data / queryKey isolation 雙呼叫）|
| AC-5 | `enabled: query.trim().length > 0` 邏輯有測試覆蓋 | ✅ pass | it 標題「空 query」/「`enabled: false` 不觸發」/「不被呼叫」共 6 處關鍵字命中 |
| AC-6 | ESLint `allowExportNames` 中心化；inline `eslint-disable` 移除 | ✅ pass | `eslint.config.js` rules override 落地；badge.tsx + tabs.tsx 共 0 處 inline disable；`npm run lint` exit 0 |
| AC-7 | V06 入 `scripts/verify-all.sh`；verify-all.sh exit 0 | ✅ pass | V06 entry 落地（V05 後）；Summary `V01=PASS V02=INFO V03=PASS V04=PASS V05=PASS V06=PASS` |
| AC-8 | `qa-strategy.md` V06 row + Coverage cross-link + AC-to-Test evidence-only 例外 | ✅ pass | grep 命中 4 處（V06/frontend coverage/vitest threshold）+ 2 處（Build / Config evidence-only）|

### Pending Verification

無。所有 8 ACs 已透過 vitest run + lint + verify-all.sh + grep 多重 evidence 驗證；下一步由獨立 QA subagent 確認。

---

<!-- §7.QA Review by independent subagent: appended below by /verifying-quality -->

## 7.QA Review (Round 1)

> Reviewer: independent QA subagent | Date: 2026-04-28 | Protocol: /verifying-quality

### Verdict: PASS (with inline MINOR fixes applied)

### 主驗 — `./scripts/verify-all.sh`

```
V01=PASS  V02=INFO(88.1%)  V03=PASS  V04=PASS  V05=PASS  V06=PASS
exit 0；CRIT_FAIL=0
```

兩次獨立執行（修復前 / 修復後）均全綠。

### AC 逐項核對

| AC | Spec Claim | QA Evidence | QA Result |
|----|------------|-------------|-----------|
| AC-1 | `@vitest/coverage-v8@4.1.5` 安裝；vite.config wire 完整；coverage `lines: 100%` on include whitelist | `package.json` L35 `"@vitest/coverage-v8": "^4.1.5"`；`vite.config.ts` `provider:'v8'`+`thresholds.lines:80`+`include` whitelist 兩檔；`coverage-summary.json` `lines.pct=100` | ✅ PASS |
| AC-2 | `SkillCard.test.tsx` `describe('AC-2: SkillCard 渲染', ...)` ≥2 it 通過 | 獨立跑 `npm test -- SkillCard`：6/6 it 通過；`describe('AC-2: SkillCard 渲染', ...)` L21 確認；`<MemoryRouter>` wrapper L25 確認 | ✅ PASS |
| AC-3 | jest-dom matcher ≥1 處使用 | `SkillCard.test.tsx` grep 命中 9 處 `toBeInTheDocument`（L32/33/34/35/40/45/56/61）+ 1 `toHaveAttribute`（L51）+ `not.toBeInTheDocument`（L45/61） | ✅ PASS |
| AC-4 | `useSemanticSearch.test.tsx` `describe('AC-4: ...')` ≥2 it 通過 | 獨立跑 `npm test -- useSemanticSearch`：4/4 it 通過；`describe('AC-4: useSemanticSearch hook', ...)` L25 確認；`QueryClientProvider` wrapper L20 確認；`vi.mock('@/api/search')` L10 確認 | ✅ PASS |
| AC-5 | `enabled: query.trim().length > 0` 邏輯有測試覆蓋 | it 標題 L30「空 query（""）→ enabled: false → 不被呼叫」/ L35「純空白 query → enabled: false 不觸發」— 合計 ≥3 關鍵字命中 | ✅ PASS |
| AC-6 | `allowExportNames` 中心化；inline disable 移除；`npm run lint` exit 0 | `eslint.config.js` L28-35 rules override 確認；`badge.tsx`/`tabs.tsx` grep `eslint-disable-next-line react-refresh` → 0 命中（exit 1）；lint exit 0 確認 | ✅ PASS |
| AC-7 | V06 入 `scripts/verify-all.sh`；verify-all.sh exit 0 | `verify-all.sh` L98-102 V06 entry；`run_skip_if` pattern 對齊 V04/V05；verify-all.sh exit 0 確認 | ✅ PASS |
| AC-8 | `qa-strategy.md` V06 row + Coverage cross-link + evidence-only 例外 | `grep -nE "V06\|frontend.*coverage\|vitest.*threshold"` → 4 命中（≥3 required）；`grep "Build / Config\|evidence-only"` → 2 命中（L146/L148）| ✅ PASS |

### Design Drift 核對（§2/§4 vs actual code）

- **§2.1 #2 `coverage.include` whitelist 漸進 gate**：`vite.config.ts` L32-35 `include:['src/components/SkillCard.tsx','src/hooks/useSemanticSearch.ts']` 完全對齊；§4.1 spec 樣板一致。✅
- **§4.5 mock 資料形狀 vs actual `SemanticSearchResult`**：spec §4.5 樣板用巢狀 `{ skill: { id: 'a' }, score: 0.9 }`；actual `useSemanticSearch.test.tsx` L41-53 改用 flat `SemanticSearchResult` interface（9 fields + score）— 對齊 `frontend/src/types/skill.ts` L68-89；§7 Implementation Note #2 已正確標記此 design-time 估計 divergence。✅
- **§7 Implementation Note #1（`.ts` vs `.tsx`）**：`useSemanticSearch.test.tsx` 使用 JSX；副檔名改 `.tsx` 正確；spec §5 file plan 行誤在 §7 已記錄，不回填 §5（living document 原則）。✅

### Code Quality Check

- `eslint.config.js`：design-intent comment 完整解釋 cva CallExpression / Issue #83 / shadcn CLI lifecycle（L21-26）。✅
- `SkillCard.test.tsx` / `useSemanticSearch.test.tsx`：`describe('AC-N: ...')` 格式符合 qa-strategy.md §AC-to-Test Contract frontend 規約。✅
- mock data 對齊 actual interface：`SkillCard.test.tsx` mockSkill 含 `createdAt`/`updatedAt`（Skill interface 必填欄位）；`useSemanticSearch.test.tsx` mockResults 使用 `SemanticSearchResult[]` 型別。✅

### 發現問題與修正

**MINOR-1（已 inline 修正）：`coverage/` 目錄未排除於 ESLint + `.gitignore`**

- 問題：`frontend/vite.config.ts` `html` reporter 產出 `coverage/block-navigation.js`、`coverage/prettify.js`、`coverage/sorter.js` 含 `/* eslint-disable */` 指令；`eslint.config.js` `globalIgnores` 只列 `['dist']`；`.gitignore` 未含 `coverage`。V06 執行後若獨立執行 `npm run lint` 會出現 3 個「Unused eslint-disable directive」警告（非 error；lint exit 仍 0）。AC-6 通過（react-refresh/only-export-components 無警告），但 warnings noise 是潛在維護隱患。
- 修正：`eslint.config.js` `globalIgnores` 改為 `['dist', 'coverage']`；`frontend/.gitignore` 新增 `coverage` 行。
- 驗證：修正後 `npm run lint` exit 0，0 warnings；`./scripts/verify-all.sh` exit 0 V01-V06 全 PASS。
- 檔案：`frontend/eslint.config.js` L9；`frontend/.gitignore` L13。

**MINOR-2（已 inline 修正）：`verify-all.sh` header 注解 stale**

- 問題：`scripts/verify-all.sh` L10 注解 `# 跑全部 V01-V05`，S022 後實際跑 V01-V06。
- 修正：改為 `# 跑全部 V01-V06`。
- 驗證：cosmetic 修正；verify-all.sh 功能不受影響。
- 檔案：`scripts/verify-all.sh` L10。

### 已知 Tech Debt（未修正，登記供後續 spec）

1. **`qa-strategy.md` L85「不 enroll 的命令」stale row**（spec §7 Implementation Note #6）：`@vitest/coverage-v8 未裝` 部分已 obsolete；屬 super-stale-cleanup，下一個 docs-touching spec 順帶清理。
2. **spec-roadmap.md S022 狀態未更新**：spec-roadmap.md L27/L58/L129 仍顯示 `⏳ Dev`/`3/4`；此更新屬 `/shipping-release` 工作流程，非 QA 範圍。
3. **`coverage.include` whitelist 維護成本**（spec §7 Tech Debt #1）：每加新 frontend test 需手動 append；後續考慮 glob 或 perFile 模式。

### 最終裁決

**PASS** — 8 ACs 全部獨立驗證通過；2 MINOR 問題已 inline 修正（`coverage/` ESLint ignore + `.gitignore`；verify-all.sh 注解更新）；AC ground truth 對齊；無假性通過。可進入 `/shipping-release`。
