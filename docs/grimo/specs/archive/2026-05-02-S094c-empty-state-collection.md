# S094c — Empty State Collection (4 tones)

> **Status**: shipped
> **Type**: META S094 sub-spec 1/4 (XS-first / 共享 component 抽取先)
> **Estimate**: XS / 5 pts
> **Source**: `docs/grimo/ui/prototype/empty_state_collection_four_tones.html` + `docs/grimo/ui/README.md` ll.221-243 + DESIGN.md

## §1 Goal

把 4 種空狀態（seeding / invitational / redirecting / celebratory）抽取成共享的 `EmptyState` 元件，先行 ship 提供給後續 sub-specs（S094a MySkills 用 invite + S094b SearchResults 用 redirect 完整版）reuse。同時取代 HomePage 既有 generic 「找不到符合的技能 / 試試不同的關鍵字或分類」與語意搜尋 0 結果的 inline empty。

每種 tone 不只是「換顏色」— 是不同的 voice 與資訊密度（per README ll.234-241）：
- **seed**: 空殼平台 → 激勵第一次貢獻（ghost preview cards 預演成熟狀態）
- **invite**: 個人空殼 → 邀請加入（horizontal step flow 預演發布工作流）
- **redirect**: 搜尋失敗 → 導流（query echo 確認系統聽到 + 4 條 suggestion）
- **clear**: 沒事可做 → 安撫（綠色 check + 7d 統計 + 低調 audit log link）

## §2 Approach

**Single-component-multi-tone** vs sibling-component：

| Approach | Pros | Cons |
|----------|------|------|
| ⭐ Single `EmptyState` with `tone` prop + 4 internal sub-renderers | 單一 import、API 收斂、後續 caller 用 `tone="..."` 切換清楚 | tone 結構不同 → 每 tone 自己 sub-renderer，elem-internal 接近 4 個 component |
| 4 個 independent components (`SeedEmpty` / `InviteEmpty` / ...) | 每個 component 自己 prop 表清晰 | 4 個 import；caller 需 conditional render；tone 變更時換 component name |

選 (A) — caller 心智模型「`<EmptyState tone="redirect" .../>`」一致，內部 dispatch 雖然多但 maintainability 高。每個 tone 仍是獨立 sub-renderer function（`SeedTone` / `InviteTone` / ...），分隔清楚。

**Reuse posture**:
- Primary CTA 套 `BeamFrame`（既有 component / DESIGN.md primary action）
- Secondary CTA 用 inline Tailwind class hairline border（同 SkillCard / MetricCard pattern；不抽 shadcn Button 避免依賴）
- Icons 用 `lucide-react`（既有 dep）
- Color tokens 用 inline-style hex 對齊 DESIGN.md 4-tier semantic（同 S087 status pill / S088 progress bar pattern）

**捨棄**:
- shadcn Card primitive — 既有 SkillCard / MetricCard 改寫過程已棄用（hairline border + custom radius 比包裝層直接）
- Pure CSS gradient — DESIGN.md 禁止 gradient（只 landing page hero 例外，已 deferred）

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | `<EmptyState tone="seed" eyebrow=... headline=... sub=... primaryAction={...}>` | 渲染 eyebrow with pulse dot + h2 headline + sub paragraph + BeamFrame-wrapped primary button + 4 dashed ghost cards |
| AC-2 | `<EmptyState tone="invite" headline=... sub=...>` | 渲染 upload icon + h2 + sub + 4-step horizontal flow (Zip/Auto-scan/Publish/Track) |
| AC-3 | `<EmptyState tone="redirect" query=... headline=... sub=... suggestions={[...]}>` | 渲染 query echo（mono font）+ h2 + sub + suggestions list 每項 text+hint+→ |
| AC-4 | `<EmptyState tone="clear" headline=... stats={[...]} auditLink={...}>` | 渲染綠色 check halo + h2 + sub + 3 stats divider + audit link |
| AC-5 | optional fields omitted | 不 crash；無 prop errors |
| AC-6 | HomePage keyword 0 results (no query) | 顯示 seed tone「技能庫等著被開啟」 + 「發布第一個技能」CTA |
| AC-7 | HomePage keyword 0 results (with query) | 顯示 redirect tone with 3 suggestions + query echo |
| AC-8 | HomePage semantic 0 results | 顯示 redirect tone with 3 suggestions + query echo |
| AC-9 | Frontend tests 18 → 23 PASS / 0 fail | regression check |
| AC-10 | Build 不超 365KB JS | size budget |

## §4 Implementation

`frontend/src/components/EmptyState.tsx`:
- Export `EmptyState`, `EmptyStateProps`, `EmptyStateTone` types
- Props: `tone | headline | sub? | eyebrow? | query? | suggestions? | stats? | primaryAction? | secondaryAction? | auditLink?`
- 4 internal sub-renderers (`SeedTone` / `InviteTone` / `RedirectTone` / `ClearTone`)
- Shared `Container` + `PrimaryButton` (BeamFrame) + `SecondaryButton` (hairline border)

`frontend/src/components/EmptyState.test.tsx`:
- 5 vitest cases: AC-1 ~ AC-5

`frontend/src/components/SkillCardGrid.tsx`:
- 加 `query?: string` prop
- 0-results path: `query` 非空 → redirect tone；query 空 → seed tone

`frontend/src/pages/HomePage.tsx`:
- semantic 0-results path: replace inline empty with `<EmptyState tone="redirect" query={query} ...>`
- pass `query` to `SkillCardGrid` for keyword 0-results tone differentiation

## §5 Test plan

- `npm test` (frontend) — 預期 18 → 23 PASS（5 new EmptyState tests）
- `npm run build` — 預期 ≤ 365KB JS（既有 351KB + EmptyState 約 +5-10KB）
- 手動 smoke：`/` 頁面 + 輸入不存在 keyword（如 `xxxyyy`）→ 顯示 redirect tone；切 semantic mode 同樣
- 真正 fresh DB 場景（seed tone）目前難測（DB 已有 135 skills），integration 留待 S094a MySkills 場景 trigger

## §6 Verification

實際結果填於 §7。

## §7 Result

- **Frontend tests**: 18 → 23 PASS / 0 fail（5 new EmptyState tests AC-1/2/3/4/5）
- **JS bundle**: 351KB → 358KB（+7KB；BeamFrame 已 inline，新增主要是 4 sub-renderer + lucide icons import）
- **CSS bundle**: 32.7KB → 35.1KB（+2.4KB；DESIGN.md hex tokens inline）
- **Build time**: 200ms → 166ms（with HMR cache；no regression）
- **Integration verified**:
  - HomePage 0-results (keyword + query) → redirect tone ✓
  - HomePage 0-results (keyword + no query) → seed tone ✓
  - HomePage 0-results (semantic) → redirect tone ✓
  - SkillCardGrid receives `query` prop ✓
- **Tones not yet integrated**:
  - `invite` tone — for S094a MySkills (PRD P6) future
  - `clear` tone — for S094e Admin (PRD B6 deferred) future
  - 兩個 tone 元件已 build complete + tested；當 sub-spec ship 時 caller 直接 `<EmptyState tone="..."/>` 即可

ship as **v2.69.0** (M88a / META S094 sub-spec 1/4 完成)。

**META status**: 4 sub-specs progress 1/4 ✓ — next ship S094d Docs Walkthrough。
