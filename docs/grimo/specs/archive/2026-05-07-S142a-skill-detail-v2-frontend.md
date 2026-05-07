# S142a — SkillDetailPage v2 Frontend Rework

> **Status**: ✅ shipped v4.22.0 (2026-05-07)
> **Type**: Frontend feature spec
> **Estimate**: M-L (13-15)
> **Depends**: [S142b](2026-05-07-S142b-skill-detail-v2-backend.md) API contract（同 release ship；可平行開發）
> **Source**:
> - `docs/grimo/ui/prototype/Skills Hub Skill Detail v2.html` (2026-05-07 designer 更新)
> - 工程實作說明（user-provided 2026-05-07）— 配色雙語言 / 公式 / Tabs / 資料結構 / BorderBeam 規範
> - [v2 followup decisions](../ui/prototype/v2-followup-questions.md)
> **Replaces / refactors**: S087 SkillHero, S098e hero polish, S135b QualitySection + QualityTab, S082 FilesPanel

---

## §1 Goal

把 SkillDetailPage 整頁 rework 對齊 v2 prototype — page header 重組、3 卡 hero metrics（SkillScore hexagon + Quality + Security）、stat strip、7 tabs、含 7-card sidebar 的 grid layout。

```
v2 SkillDetailPage 結構（per prototype）
┌────────────────────────────────────────────────────────────────┐
│ Nav (固定頂部 — 既有 AppShell)                                 │
├────────────────────────────────────────────────────────────────┤
│ PageHeader (always visible — 不隨 tab 切換)                    │
│  ├─ SkillInfo: icon / name / risk-pill / verified-pill / desc  │
│  ├─ Actions: Star (= Subscribe icon 改 ⭐) + Download CTA       │
│  ├─ HeroMetricsRow: SkillScoreBadge | QualityCard | SecurityCard│
│  └─ StatStrip: Downloads / Rating / Versions / Open flags       │
├────────────────────────────────────────────────────────────────┤
│ TabBar: SKILL.md / Quality / Versions / Reviews / Security /    │
│         Flags / Files                                           │
├────────────────────────────────────────────────────────────────┤
│ Body grid: main 1fr + sidebar 232px                            │
│  ├─ MainContent (tab panels)                                    │
│  └─ Sidebar:                                                    │
│      固定: Install / Sparkline / Details / Compat / VerHistory  │
│      Quality tab: + TableOfContents + Reproducibility           │
│      Security tab: + AuditMetadata                              │
└────────────────────────────────────────────────────────────────┘
```

### Out of Scope (留 S142b / 後續 spec)

| 項目 | 去向 |
|---|---|
| Backend SkillScore composite formula + SecurityReport endpoint + Skill response 6 fields 增補 | [S142b](2026-05-07-S142b-skill-detail-v2-backend.md) |
| Curation workflow（將 verified 改成獨立可審核 flag） | 後續 spec |
| CLI dropdown 多選項展開（npm / pnpm / yarn / bun） | 後續 spec — 目前單一靜態 CLI label |
| Stats `weeklyDelta` 後端計算 | Frontend derive from 既有 14d array slice — backend 不重複 |
| Empty / SUSPENDED / 低分視覺 polish | Spec §B.1 / §B.2 default 進 spec；polish 後續另開 |

---

## §2 Approach

### §2.1 Chosen approach (single approach — design fixed by prototype + engineer doc)

**整頁 rework，新建 16 components；既有 SkillDetailPage.tsx 大幅 refactor；S087/S098e/S135b 對應元件廢棄或重做**。

#### 為何整頁 rework 而不是漸進 polish？
v2 prototype 的 page-head + hero + sidebar 三大區塊都是新結構（不是 polish 而是 layout 重組）：
- MetricCard grid 4 格 → 三卡 hero（SkillScore hexagon + Quality + Security）
- 單欄佈局 → main + 232px sidebar 雙欄 grid
- 7 tab 中 SKILL.md / Security 是新 tab；Quality / Versions / Files 大幅重做
漸進改會導致中途有 mixed-style 視覺，user-facing regression 風險高。

#### 為何不分多個小 spec 漸進 ship？
prototype 整頁設計緊密（hero card 點擊→tab 切換、sidebar contextual cards 隨 tab 變動、配色雙語言一致）；分拆後每個 PR 都會有「半張臉新 / 半張臉舊」的 demo 困擾。整頁 rework + feature flag（`/skills/:author/:name?v=2` query 或 user 設定）保留漸進切換能力。

#### 為何 syntax highlighting 選 shiki 而不是 prism-react-renderer？
`shiki`：ESM、TextMate grammars、靠 lazy-load 控 bundle、輸出 inline-style 不需 CSS class — 對齊 v2 token classes (`tok-key`/`tok-str`/`tok-num`/`tok-kw`) custom theme 容易。
`prism-react-renderer`：runtime light，但需自定 theme 對齊 v2 token classes 較費工。
**Decision**：先嘗試 shiki；如 bundle size 增加 > 50KB（gzip）改 prism。

#### 為何 verified 不從 backend 拉，frontend derive？
S142b 已決定 backend 計算 `verified = (status==='PUBLISHED' && riskLevel != null)` 並回到 Skill response — frontend 只需讀 `skill.verified` boolean。不在 frontend 重複 derive logic（避免 truth source 不一致）。

### §2.2 配色雙語言（per 工程說明 §2 — 不可混用）

**評分系統色（Quality / Skill Score 漸層）**:
```css
background: linear-gradient(90deg, #7F77DD, #D9388A 60%, #EF9F27);
```
用於：SkillScoreBadge SVG arc / Quality 進度條 / Quality section 分數

**語義色（Security 風險 / 業務指標）**:
| 狀態 | Token | 用途 |
|---|---|---|
| None / Pass | `--green` `--green-text` | Security pass、Low risk pill、↑ 成長 |
| Medium / Warn | `--amber` `--amber-text` | Security warning、scripts/ highlight |
| High / Fail | `--red` `--red-text` | Security fail、High risk |

❌ 禁止：把 `--green` 用在評分數字、把評分漸層用在風險 pill。所有新 component 的 `style.color` / token 使用都需在 PR review 過此規則。

### §2.3 Empty / Edge / SUSPENDED 狀態 default（per [v2 followup §B.1](../ui/prototype/v2-followup-questions.md)）

| 情境 | UI default |
|---|---|
| 未評分（Quality 404）| SkillScoreBadge hexagon 顯「—」+ 下方小字「評分計算中」；Quality card grey + 「評分計算中」；Security card 用 risk_level 顯（不 block） |
| SUSPENDED | hero 三卡正常顯；Download CTA 消失（reuse 既有 S028 行為）；Security tab 加紅色 banner |
| DRAFT | 同 SUSPENDED；banner 文案「未發佈」 |
| 沒 reviews | stat-strip Rating「—」；Reviews tab empty state（既有 S098e2）|
| compatibility 8+ | flex-wrap 折行，不做「+N more」|
| Version 50+ | sidebar 顯 latest 4 + 「查看全部」link 切到 Versions tab |
| 長 description | page-head desc `line-clamp-2` + 「展開」 |

### §2.4 BorderBeam 嚴格 viewport singleton（per 工程說明 §9）

`BorderBeam` 只用在 Download CTA。整頁其他位置（hero cards / sidebar / stat-strip）**禁止**加 BorderBeam — 違反 DESIGN.md 規定（一個 viewport 同時只能有一個）。

### §2.5 Research Citations

| Source | 1-line summary |
|---|---|
| `docs/grimo/ui/prototype/Skills Hub Skill Detail v2.html` (2026-05-07) | Page layout / 3-card hero / 7 tabs / sidebar / Files explorer 完整 mockup |
| 工程實作說明（user-provided） | 配色雙語言、Skill Score 公式、SVG arc 計算、tabs panel 對應、Frontmatter syntax token、Quality / Security 資料結構、BorderBeam 規範 |
| `S135b archived spec` | QualitySection / QualityTab 既有 fallback pattern reuse；對齊 axis 中文 label（規格驗證 / 實作品質 / 觸發能力 ←→ Validation / Implementation / Activation） |
| `S082 archived spec` | FilesPanel 既有 API call (`/files`, `/files/{*path}`) reuse；component 廢棄改 FileExplorerPanel |
| `S098e archived spec` | Sparkline 元件 reuse；移到 sidebar |
| [shiki docs](https://shiki.style/) | ESM TextMate grammars + inline-style; bundle-friendly via lazy import |
| [v2 followup decisions](../ui/prototype/v2-followup-questions.md) | D1 verified / D3 Quality 404 fallback / Files explorer / Star 改 icon / 工程 default 列表 |

### §2.6 Sufficiency Gate — Confidence Classification

| Decision | Confidence | Action |
|---|---|---|
| Page layout / hero / tabs / sidebar 結構 | Validated（prototype HTML + 工程說明）| Implement directly |
| 配色雙語言 / SVG arc 計算 / token classes | Validated | Implement directly |
| Files explorer split-pane + scripts/ highlight | Validated（designer 2026-05-07 交付）| Implement directly |
| `verified` derived from S142b | Validated（per S142b §4.2）| Consume |
| `skillScore` from extended /scores response | Validated（per S142b §4.3）| Consume |
| `securityReport` from new /security-report endpoint | Validated（per S142b §4.4）| Consume |
| shiki bundle size 不爆 | Hypothesis（需 T01 POC verify gzipped size delta）| Fallback to prism-react-renderer |
| stat-strip `weeklyDelta` frontend derive accurate | Hypothesis（需驗 14d array slice 公式 `(thisWeek - lastWeek) / lastWeek × 100%`）| Add unit test for compute |

---

## §3 Acceptance Criteria

> 命名格式對齊 `qa-strategy.md` §AC-to-Test Contract — `describe('AC-S142a-N: ...')` (Vitest)。
> Verify command: `cd frontend && npm test`（V04）+ build size check（per §5.4）。

```
Scenario: AC-S142a-1 — PageHeader 顯示 Verified pill (PUBLISHED + scanned)
  Given skill.verified === true (從 S142b)
  When  訪問 /skills/{author}/{name}
  Then  page-head name row 顯 Verified pill (綠勾 icon + "Verified" 字)
  And   pill 樣式對齊 prototype `.verified-pill`（accent-text + 綠勾 SVG）

Scenario: AC-S142a-2 — Verified pill 隱藏 (DRAFT / SUSPENDED / unscanned)
  Given skill.verified === false
  Then  page-head 不渲染 verified-pill（純隱藏，無 placeholder）

Scenario: AC-S142a-3 — Star 按鈕 = Subscribe 行為 + ⭐ icon
  Given user 已登入 + 非 skill author
  When  點 Star 按鈕
  Then  toggle subscribe / unsubscribe（reuse 既有 useIsSubscribed / useSubscribeSkill / useUnsubscribeSkill）
  And   icon 為 ⭐（lucide Star icon）；既有鈴鐺從 PageHeader 拿掉
  And   未登入 user 看到 Star 按鈕但 click trigger AuthGatedButton flow

Scenario: AC-S142a-4 — SkillScoreBadge 渲染 89/100 hexagon ring
  Given GET /scores 回 200 with skillScore=89
  When  渲染 PageHeader
  Then  SVG hexagon ring 出現
  And   arc fill stroke-dashoffset = 314.16 × (1 - 89/100) + 26.2 = 60.76（per 工程說明 §3 公式）
  And   inner text "SKILL SCORE" / "89" / "/100" 各依 SVG <text> 渲染

Scenario: AC-S142a-5 — Quality 404 時 SkillScoreBadge 顯「—」
  Given GET /scores 回 404 QUALITY_NOT_EVALUATED
  When  渲染 PageHeader
  Then  hexagon arc 顯灰色 (rgba(127,119,221,0.12) track 不填)
  And   center 顯「—」(代替數字)
  And   下方小字「評分計算中」(代替 "/100")

Scenario: AC-S142a-6 — HeroMetricsRow 三卡 grid (160px / 1fr / 1fr)
  Given 已評分 + 已掃描的 skill
  When  渲染 PageHeader
  Then  hero row grid-template-columns: 160px 1fr 1fr
  And   Quality card 顯 92% + Validation 100 / Implementation 85 / Discovery 92 breakdown
  And   Security card 顯「Passed」+ 4-segment bar (4 個 #1D9E75 → #6FD8B0 漸層 segments) + Shell·Paths·Secrets·Deps breakdown

Scenario: AC-S142a-7 — QualityCard click 切到 Quality tab + active border
  Given 在 SKILL.md tab
  When  點 Quality card
  Then  Quality tab active；SKILL.md panel 隱藏
  And   QualityCard 加 border-color: rgba(127,119,221,.45) active 樣式
  And   Sidebar 顯 TableOfContents + Reproducibility cards (其他 contextual hidden)

Scenario: AC-S142a-8 — SecurityCard click 切到 Security tab + 4-quad
  Given 在 Quality tab
  When  點 Security card
  Then  Security tab active
  And   panel 顯 Security hero shield (依 overall 顏色) + 4 quad cards (Shell / Paths / Secrets / Deps)
  And   每 quad 顯 status (pass/warn/fail) + detail string from S142b /security-report
  And   Sidebar 顯 AuditMetadata card

Scenario: AC-S142a-9 — Security warn state 4-segment 對應變色
  Given securityReport.checks.paths.status === "warn"
  When  渲染 SecurityCard hero
  Then  4-segment bar 第 2 段 (paths) 變 amber，其他 3 段保持綠色
  And   hero card border 加 rgba(239,159,39,.3)
  And   hero value 顯「1 Issue」+ sub「Sensitive path access detected」

Scenario: AC-S142a-10 — StatStrip 4 cells + ↑ 成長 indicator
  Given GET /stats 回 14d array
  When  渲染 StatStrip
  Then  顯 Downloads / Rating / Versions / Open flags 4 cells
  And   Downloads cell 含「↑ N%」(綠) 或「↓ N%」(紅) — frontend derive: (thisWeekTotal - lastWeekTotal) / lastWeekTotal
  And   Rating cell 顯「{averageRating} / 5 · {reviewCount} reviews」(從 Skill response)
  And   Versions cell 顯「{versionCount}」(從 S142b Skill response)
  And   Open flags cell 顯「{openFlagCount}」(從 S142b Skill response)；> 0 時數字紅色

Scenario: AC-S142a-11 — SKILL.md tab 渲染含 frontmatter syntax highlight
  Given skill 含 frontmatter + body
  When  點 SKILL.md tab
  Then  panel 顯 frontmatter `<pre>` block + tok-key / tok-str / tok-num token classes
  And   description value 用 accent-text 紫色高亮（per 工程說明 §6 — AI embedding 焦點）
  And   --- delimiters 用 ink-3 灰色 + opacity 0.5
  And   下方接 H1 + H2 sections markdown render（reuse 既有 markdown library）

Scenario: AC-S142a-12 — QualityTab 三 section 結構
  Given GET /scores 200
  When  點 Quality tab
  Then  panel 顯 3 section: Validation / Implementation / Discovery
  And   每 section 含 head (title + sub-label + score num + progress bar) + table (Dim / Reasoning / Score)
  And   table 每行 score dot 顏色：accent (full)/ amber (>= 60%) / red (< 60%) per 工程說明 §7
  And   reasoning 預設展開；Show less / Show more 切換 collapse

Scenario: AC-S142a-13 — VersionsTab 改 changelog 卡片
  Given GET /versions 回 N 筆
  When  點 Versions tab
  Then  panel 顯 N 個 changelog-style cards
  And   每 card: ver number + latest badge (only 第一筆) + relative time + changelog 描述 (3-line clamp + Show more)
  And   sidebar VersionHistoryMini 同步顯 latest 4 + "查看全部" link

Scenario: AC-S142a-14 — Files tab split-pane explorer
  Given skill 含多檔案 + scripts/ 目錄
  When  點 Files tab (badge 顯 file count)
  Then  panel grid: 220px file tree + 1fr preview
  And   tree 含 folder collapse/expand
  And   `scripts/` 目錄 amber border-left + "security scan" badge
  And   點 scripts/ 內檔案 → preview header 加 amber security banner
  And   binary file 顯 ft-binary 置中 fallback (icon + "Binary file — preview unavailable")

Scenario: AC-S142a-15 — Sidebar contextual cards 隨 tab 切換
  Given 在 Reviews tab
  When  切到 Quality tab
  Then  sidebar 多顯 TableOfContents + Reproducibility cards
  And   切到 Security tab → 改顯 AuditMetadata card；TableOfContents / Reproducibility 隱藏
  And   切到其他 tab → 兩種 contextual 都隱藏

Scenario: AC-S142a-16 — Install card copy 複製成功 affordance
  Given 在任何 tab
  When  點 Install card 複製按鈕
  Then  navigator.clipboard.writeText("skills-hub install {author}/{name}") 被呼叫
  And   按鈕 icon 切換 checkmark；1500ms 後還原

Scenario: AC-S142a-17 — Sparkline / Details / Compatibility / VersionHistoryMini sidebar 卡渲染
  Given 已有 stats / latestVersion / publishedAt / license / fileSize / fileCount / scripts info
  Then  Sparkline card 顯 30d trend (reuse Sparkline component)
  And   Details card 顯 Published / License / Size / Files / Scripts 5 行
  And   Compatibility card 顯 chips (從 skill.compatibility[])
  And   VersionHistoryMini 顯 latest 4 + 「查看全部」link

Scenario: AC-S142a-18 — BorderBeam 只在 Download CTA
  Given 在任何 tab
  Then  整頁 (page-head + hero + tabs + body + sidebar) 只有 Download CTA 有 BorderBeam wrap
  And   其他 hero card / nav / sidebar 都沒 BorderBeam
```

---

## §4 Interface Design

### §4.1 Component tree（new + modified）

```
frontend/src/pages/SkillDetailPage.tsx                                  [REWRITE — 大幅重組]
frontend/src/pages/SkillDetailPage.test.tsx                             [REWRITE]

frontend/src/components/v2/                                             [NEW dir — v2 專屬 component 集中]
├── PageHeader.tsx                                                      [NEW]
│   ├─ SkillInfo (icon / name / verified-pill / risk-pill / desc / meta)
│   └─ Actions (StarButton + DownloadCTA)
├── SkillScoreBadge.tsx                                                 [NEW] (SVG hexagon + arc + text)
├── HeroMetricsRow.tsx                                                  [NEW] (grid 160 / 1fr / 1fr)
│   ├─ QualityHeroCard (replace S135b QualitySection)
│   └─ SecurityHeroCard (4-segment bar + breakdown)
├── StatStrip.tsx                                                       [NEW]
├── Sidebar.tsx                                                         [NEW] (container; renders cards conditional on activeTab)
│   ├─ InstallCard.tsx                                                  [NEW]
│   ├─ SparklineCard.tsx                                                [NEW] (move from hero)
│   ├─ DetailsCard.tsx                                                  [NEW]
│   ├─ CompatibilityCard.tsx                                            [NEW]
│   ├─ VersionHistoryMini.tsx                                           [NEW]
│   ├─ QualityTOCCard.tsx                                               [NEW]
│   ├─ ReproducibilityCard.tsx                                          [NEW]
│   └─ SecurityAuditCard.tsx                                            [NEW]
├── tabs/
│   ├─ SkillMdTab.tsx                                                   [NEW]
│   ├─ QualityTabV2.tsx                                                 [NEW] (replace S135b QualityTab)
│   ├─ VersionsTabV2.tsx                                                [NEW] (changelog cards)
│   ├─ SecurityTab.tsx                                                  [NEW]
│   └─ FileExplorerPanel.tsx                                            [NEW] (replace S082 FilesPanel — split-pane)
└── shared/
    ├─ ScoreDot.tsx                                                     [NEW] (per 工程說明 §7 tier colors)
    ├─ FrontmatterSyntax.tsx                                            [NEW] (token classes)
    └─ LangBadge.tsx                                                    [NEW] (file extension → lang)

frontend/src/api/
├── scores.ts                                                           [MODIFIED — add skillScore field]
├── security.ts                                                         [NEW — fetchSecurityReport()]
└── skills.ts                                                           [MODIFIED — Skill type 加 6 fields per S142b §4.2]

frontend/src/hooks/
├── useSkillScores.ts                                                   [MODIFIED — return skillScore]
├── useSecurityReport.ts                                                [NEW]
└── (existing) useSkill / useSubscription / useVersions / etc           [REUSE]

frontend/src/types/skill.ts                                             [MODIFIED — Skill interface 加 6 fields]
```

### §4.2 Key types

```typescript
// types/skill.ts
export interface Skill {
  // ... existing fields
  // S142b additions:
  verified: boolean
  latestVersionPublishedAt: string | null   // ISO 8601
  license: string | null
  compatibility: string[]
  versionCount: number
  openFlagCount: number
}

// api/scores.ts
export interface SkillScores {
  // ... existing fields (skillId / skillVersionId / skillVersion / evaluatedAt / evaluatorVersion / validation / implementation / activation / total)
  skillScore: number | null   // S142b composite (null if security not yet scanned)
}

// api/security.ts
export interface SecurityCheck {
  status: 'pass' | 'warn' | 'fail'
  detail: string | null
}

export interface SecurityReport {
  skillId: string
  skillVersionId: string
  skillVersion: string
  scannedAt: string
  engineVersion: string
  ruleSetVersion: string
  overall: 'pass' | 'warn' | 'fail'
  checks: {
    shell: SecurityCheck
    paths: SecurityCheck
    secrets: SecurityCheck
    deps: SecurityCheck
  }
}

/** null = 404 SECURITY_NOT_SCANNED */
export async function fetchSecurityReport(skillId: string): Promise<SecurityReport | null>
```

### §4.3 SkillScoreBadge SVG arc 公式

```typescript
// 工程說明 §3：r=50, 270° arc, gap 在底部
const CIRC = 314.16  // 2π × 50
const GAP_OFFSET = 26.2  // 314.16 × (30/360)

interface SkillScoreBadgeProps {
  score: number | null  // null → 顯「—」
}

function strokeDashoffset(score: number): number {
  return CIRC * (1 - score / 100) + GAP_OFFSET
}

// SVG transform="rotate(135 60 60)" → arc 從左下開始，順時針 270°
```

### §4.4 SecurityCard 4-segment 顏色 mapping

```typescript
const SEG_PASS_COLORS = ['#1D9E75', '#2DB88B', '#4AC9A0', '#6FD8B0']  // 4-step accent gradient

function segmentColor(check: SecurityCheck, index: number): string {
  if (check.status === 'fail')   return 'var(--red)'
  if (check.status === 'warn')   return 'var(--amber)'
  return SEG_PASS_COLORS[index]   // pass → 漸層
}
```

### §4.5 Tab + Sidebar contextual 切換

```typescript
type TabId = 'skillmd' | 'quality' | 'versions' | 'reviews' | 'security' | 'flags' | 'files'

// Sidebar contextual rendering
function Sidebar({ activeTab, scores, securityReport }: ...) {
  return (
    <aside>
      <InstallCard />            {/* always */}
      <SparklineCard />          {/* always */}
      <DetailsCard />            {/* always */}
      <CompatibilityCard />      {/* always */}
      <VersionHistoryMini />     {/* always */}
      {activeTab === 'quality' && <><QualityTOCCard /><ReproducibilityCard scores={scores} /></>}
      {activeTab === 'security' && <SecurityAuditCard report={securityReport} />}
    </aside>
  )
}
```

### §4.6 weeklyDelta 計算（client-side derive）

```typescript
// from 14d array (most recent last)
function weeklyDelta(daily: number[]): number | null {
  if (daily.length < 14) return null
  const thisWeek = daily.slice(-7).reduce((a, b) => a + b, 0)
  const lastWeek = daily.slice(-14, -7).reduce((a, b) => a + b, 0)
  if (lastWeek === 0) return null
  return Math.round((thisWeek - lastWeek) / lastWeek * 100)
}
```

### §4.7 FileExplorerPanel 結構（per designer 2026-05-07 prototype）

```typescript
interface FileNode {
  path: string
  size: number
  type: 'file' | 'directory'
  children?: FileNode[]
}

interface FileExplorerPanelProps {
  skillId: string
}

// Layout:
// .files-tab-layout: grid 220px / 1fr; height: calc(100vh - 300px)
//   <nav class="ft-tree">     ← left: tree with folder expand/collapse
//   <div class="ft-preview">  ← right: header + body
//
// scripts/ detection:
// node.path.startsWith('scripts/') → ft-in-scripts class
// node.path === 'scripts' (top-level dir) → ft-scripts-dir + 'security scan' badge
```

---

## §5 File Plan

### §5.1 New files

```
frontend/src/components/v2/
├── PageHeader.tsx                                                      [NEW]
├── PageHeader.test.tsx                                                 [NEW]
├── SkillScoreBadge.tsx                                                 [NEW]
├── SkillScoreBadge.test.tsx                                            [NEW]
├── HeroMetricsRow.tsx                                                  [NEW]
├── HeroMetricsRow.test.tsx                                             [NEW]
├── QualityHeroCard.tsx                                                 [NEW]
├── QualityHeroCard.test.tsx                                            [NEW]
├── SecurityHeroCard.tsx                                                [NEW]
├── SecurityHeroCard.test.tsx                                           [NEW]
├── StatStrip.tsx                                                       [NEW]
├── StatStrip.test.tsx                                                  [NEW]
├── Sidebar.tsx                                                         [NEW]
├── Sidebar.test.tsx                                                    [NEW]
├── InstallCard.tsx                                                     [NEW]
├── InstallCard.test.tsx                                                [NEW]
├── SparklineCard.tsx                                                   [NEW]
├── DetailsCard.tsx                                                     [NEW]
├── CompatibilityCard.tsx                                               [NEW]
├── VersionHistoryMini.tsx                                              [NEW]
├── QualityTOCCard.tsx                                                  [NEW]
├── ReproducibilityCard.tsx                                             [NEW]
├── SecurityAuditCard.tsx                                               [NEW]
├── tabs/
│   ├── SkillMdTab.tsx                                                  [NEW]
│   ├── SkillMdTab.test.tsx                                             [NEW]
│   ├── QualityTabV2.tsx                                                [NEW]
│   ├── QualityTabV2.test.tsx                                           [NEW]
│   ├── VersionsTabV2.tsx                                               [NEW]
│   ├── VersionsTabV2.test.tsx                                          [NEW]
│   ├── SecurityTab.tsx                                                 [NEW]
│   ├── SecurityTab.test.tsx                                            [NEW]
│   └── FileExplorerPanel.tsx                                           [NEW]
│   └── FileExplorerPanel.test.tsx                                      [NEW]
└── shared/
    ├── ScoreDot.tsx                                                    [NEW]
    ├── FrontmatterSyntax.tsx                                           [NEW]
    └── LangBadge.tsx                                                   [NEW]

frontend/src/api/
└── security.ts                                                         [NEW]

frontend/src/hooks/
└── useSecurityReport.ts                                                [NEW]
```

### §5.2 Modified files

| File | 變更 |
|---|---|
| `frontend/src/pages/SkillDetailPage.tsx` | 大幅 rewrite — 改用新 v2 components；import 重組；既有 SkillHero / MetricCard grid / QualitySection / QualityTab / VersionList 引用拿掉 |
| `frontend/src/pages/SkillDetailPage.test.tsx` | 對齊 17 ACs 重寫 |
| `frontend/src/api/scores.ts` | `SkillScores` interface 加 `skillScore: number \| null` |
| `frontend/src/api/skills.ts` | `Skill` interface 加 6 fields per S142b |
| `frontend/src/hooks/useSkillScores.ts` | return type 多 `skillScore`（從 S142b extended response 取）|
| `frontend/src/types/skill.ts` | `Skill` interface 加 6 fields |
| `frontend/package.json` | 新增 `shiki` dependency（如 T01 POC bundle size OK；否則改 `prism-react-renderer`）|

### §5.3 Deprecated / removed (after S142a ship)

| File | 命運 |
|---|---|
| `frontend/src/components/QualitySection.tsx` (S135b) | 廢棄 — 由 v2/QualityHeroCard 取代；保留 file 但 SkillDetailPage 不 import；後續 cleanup spec 移除 |
| `frontend/src/components/QualityTab.tsx` (S135b) | 廢棄 — 由 v2/tabs/QualityTabV2 取代；同上 |
| `frontend/src/components/MetricCard.tsx` | 不刪 — AnalyticsPage 仍用；SkillDetailPage 不再使用 |
| `frontend/src/components/FilesPanel.tsx` (S082) | 廢棄 — 由 v2/tabs/FileExplorerPanel 取代 |
| `frontend/src/components/VersionList.tsx` (S087) | 廢棄 — 由 v2/tabs/VersionsTabV2 取代；後續 cleanup |
| `frontend/src/pages/SkillDetailPage.tsx` 既有 `SkillHero` inner component | 廢棄 — 由 v2/PageHeader 取代 |

### §5.4 Bundle size budget

- baseline: v4.18.0 ship 時 ~682 KB / gzip 190 KB（per S135b §7）
- S142a 預算：+50 KB gzip（含 shiki + 16 new component；shiki 用 `loadOnce + lang lazy` keep 小）
- T01 POC 必須驗 bundle delta；超過 → 改 prism-react-renderer

---

## §6 Task Plan

> **POC**: not required — all design decisions Validated per §2.6。Shiki bundle size（spec §2.6 唯一 Hypothesis）為 implementation detail，T01 內 inline 驗（NPM install + minimal POC + bundle measurement gate）。
> **Task count**: 6 (consolidated from spec draft 14 — 元件級拆分過細；按 spatial / 功能 region 合併)
> **Pattern**: TDD RED→GREEN→REFACTOR per task。

### Task Index

| Task | Topic | Depends | AC Coverage |
|------|-------|---------|-------------|
| **T01** | Foundation — Types/API/Hooks + shiki POC + bundle gate | — | (foundation, no direct AC) |
| **T02** | PageHeader + Hero region (3 cards + StatStrip + click-to-tab + ScoreDot shared) | T01 | AC-1, 2, 3, 4, 5, 6, 7, 8 (partial), 9, 10 |
| **T03** | Tab content — SKILL.md + Quality v2 + Versions v2 + Security 4-quad | T01, T02 (ScoreDot) | AC-11, 12, 13, 8 (partial) |
| **T04** | FileExplorerPanel (split-pane + scripts/ highlight + binary fallback) | T01 (syntax-highlighter wrapper) | AC-14 |
| **T05** | Sidebar (8 cards: 5 always + 3 contextual) | T01, T02 (useSkillScores) | AC-15, 16, 17 |
| **T06** | SkillDetailPage assembly + edge states + Playwright E2E + bundle audit | T01-T05 + S142b T01-T04 | AC-7, 8 full + AC-18 + Edge states + All re-verify via E2E |

### Execution Order

```
T01 (Foundation)
  ├─→ T02 (Hero region)  ─┐
  │     └─→ T05 (Sidebar) │
  ├─→ T03 (Tabs)          ├─→ T06 (Page assembly + E2E)
  └─→ T04 (File explorer) ┘
```

T01 unblock 4 條 (T02 / T03 / T04 / T05 部分 parallel)；T06 為 final assembly + E2E gate，需要 S142b API live。

### AC-to-Task Coverage Matrix

| AC | Covered by Task |
|----|------------------|
| AC-S142a-1 (Verified pill PUBLISHED) | T02 + T06 |
| AC-S142a-2 (Verified pill DRAFT/SUSPENDED hide) | T02 + T06 |
| AC-S142a-3 (Star = Subscribe + ⭐) | T02 + T06 |
| AC-S142a-4 (SkillScoreBadge 89/100) | T02 + T06 |
| AC-S142a-5 (Quality 404 「—」 fallback) | T02 + T06 |
| AC-S142a-6 (HeroMetricsRow grid) | T02 + T06 |
| AC-S142a-7 (Quality card click → tab) | T02 partial + T06 full |
| AC-S142a-8 (Security card click → tab + 4-quad) | T02 + T03 + T06 |
| AC-S142a-9 (Security warn 4-segment 變色) | T02 + T06 |
| AC-S142a-10 (StatStrip 4 cells + ↑ trend) | T02 + T06 |
| AC-S142a-11 (SKILL.md tab + frontmatter syntax) | T03 + T06 |
| AC-S142a-12 (QualityTab 3-section + reasoning) | T03 + T06 |
| AC-S142a-13 (VersionsTab changelog cards) | T03 + T06 |
| AC-S142a-14 (Files explorer scripts/ highlight + binary fallback) | T04 + T06 |
| AC-S142a-15 (Sidebar contextual cards 隨 tab 切換) | T05 + T06 |
| AC-S142a-16 (Install copy 複製) | T05 + T06 |
| AC-S142a-17 (Sidebar 5 always-visible cards render) | T05 + T06 |
| AC-S142a-18 (BorderBeam viewport singleton) | T06 (audit) |

T06 是 high-leverage gate（assembly + E2E re-verify all ACs + bundle audit + import audit）。

---

## §7 Implementation Results

**Shipped**: v4.22.0 (2026-05-07)

**Tasks completed**: T01 (foundation) → T02 (hero) → T03 (tabs) → T04 (FileExplorer) → T05 (Sidebar) → T06 (page assembly)

**Test coverage**: 318/318 Vitest PASS (+29 new tests across T04/T05/T06)

**TypeScript**: 0 production errors after typecheck

**Component inventory**:
- PageHeader, HeroMetricsRow, StatStrip, SkillScoreBadge, QualityHeroCard, SecurityHeroCard
- SkillMdTab + FrontmatterSyntax, QualityTabV2, VersionsTabV2, SecurityTab
- FileExplorerPanel + LangBadge
- Sidebar + InstallCard, SparklineCard, DetailsCard, CompatibilityCard, VersionHistoryMini, QualityTOCCard, ReproducibilityCard, SecurityAuditCard

**Trim / defer**:
- E2E playwright spec deferred (Playwright backend seed endpoints need extension; follow-up spec)
- VersionHistoryMini uses button (not anchor) for tab navigation (no router dep needed)
- QualityTOCCard smooth-scroll deferred (no DOM IDs set in QualityTabV2 sections yet)

**AC coverage**: AC-S142a-1 through AC-S142a-17 covered by component + page tests; AC-S142a-18 (BeamFrame singleton) covered by PageHeader rendering only one BeamFrame.
