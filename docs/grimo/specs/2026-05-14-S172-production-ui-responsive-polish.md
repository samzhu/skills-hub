# S172 — Production UI Responsive Polish

Status: ✅ Done — local verification + production smoke PASS 2026-05-15；ready for `$verifying-quality` / `$shipping-release`  
Date: 2026-05-14  
Owner: Codex  
Scope: Frontend UI / CSS polish only

## 1. Goal

`https://skillshub-644359853825.asia-east1.run.app/skills/c9eb1d8a-2c73-4fc0-abdd-e8efb6ce4a7b` 在 900px 平板寬度跑出 `scrollWidth=968 / clientWidth=900`，在 390px 手機寬度跑出 `scrollWidth=969 / clientWidth=390`。使用者要讀技能詳情時會同時上下與左右滑，右側「安裝 / 30 天下載趨勢」欄也會被截在畫面外。

本 spec 修正這次 production UI audit 看到的三個任務阻礙：

1. Skill detail 在 tablet/mobile 不得水平溢出，右側 sidebar 必須收進單欄或變成內容下方區塊。
2. AppShell 在 mobile/tablet 不得把 9 個導覽連結硬塞同一列；使用者要能清楚進入「瀏覽 / 發佈 / 文件」等主要頁。
3. Browse 搜尋 0 筆時，不得顯示點了沒反應的「切換到語意搜尋模式」建議；每個空狀態建議都要能實際導向或改變畫面。
4. Collections 建立 modal 不得要求使用者記 UUID；技能應該從「我的技能」下拉選單選取，按「新增」加入技能包，下方已選技能可逐項移除。
5. My Skills lifecycle tabs 不得在 dark theme 裡顯示白底反白按鈕；應改為深色 segmented control，讓 `已發布 (0) / 草稿 (0) / 已停用 (0) / 訂閱 (0)` 可讀但不刺眼。

Ordering note: S171 目前在開發中，但 S172 只碰 frontend UI；沒有 production file 需要 import S171 新增的 backend/shared AI 型別，因此可平行設計，實作時仍要避開同一工作樹未完成改動。

## 2. Context & Evidence

### 2.1 Product Context

`docs/grimo/PRD.md` 的 MVP P1 是「技能瀏覽與搜尋」，P4 是「一鍵安裝」。這次問題剛好卡在兩個 MVP 動作：

- 使用者進 `/browse` 找技能。
- 使用者進 `/skills/{id}` 看 SKILL.md、安裝指令、下載技能。

### 2.2 Production Audit Evidence

Chrome 實際操作 production：

| Page | View | Observed behavior |
|---|---|---|
| `/browse` | desktop 935px | 卡片可點進詳情；搜尋 `docker` 後 0 筆空狀態出現三個建議。 |
| `/browse` | desktop 935px | 點「切換到語意搜尋模式」後 URL 仍是 `/browse`，body 內容不變；這個 row 看起來像 action，但沒有 action。 |
| `/skills/c9eb1d8a-2c73-4fc0-abdd-e8efb6ce4a7b` | desktop 935px | 右側 sidebar 在可視區右緣被截掉，安裝卡只露出左半。 |
| `/collections` | desktop 935px | 空集合頁仍顯示風險篩選 sidebar；不阻擋任務，列為 lower priority polish。 |
| `/collections` → 「建立集合」 | desktop 1092px | Modal 開啟後是裸 overlay + 表單卡片；沒有關閉 icon、沒有 header 說明，`技能 ID 清單` 要求貼 UUID，placeholder 是 `sk-1/sk-2/sk-3`。使用者不會記得 UUID，應從「我的技能」下拉選單挑技能，逐個新增成技能包項目。 |
| `/my-skills` | desktop | Lifecycle tabs `已發布 (0) / 草稿 (0) / 已停用 (0) / 訂閱 (0)` 的 inactive style 是白底反白，在 dark page 上視覺太重，跟 `docs/grimo/ui/DESIGN.md` 的 dark token 不一致。 |
| `/docs/overview` | desktop 935px | 三張 docs feature card 在窄欄會把 `NONE/LOW/MEDIUM/HIGH` 切成多行；不阻擋任務，列為 lower priority polish。 |

固定 viewport Playwright audit：

```text
/skills/{id} @ tablet 900x700:
scrollWidth=968, clientWidth=900, overflowX=true
overflowing element text starts with: 安裝 skills-hub install samzhu18/deep-research...

/skills/{id} @ mobile 390x844:
scrollWidth=969, clientWidth=390, overflowX=true
```

Current code anchors:

| File | Current behavior |
|---|---|
| `frontend/src/pages/SkillDetailPage.tsx:163` | Body grid is inline `gridTemplateColumns: '1fr 232px'`; no breakpoint, so sidebar remains to the right on narrow screens. |
| `frontend/src/components/v2/Sidebar.tsx:22` | Sidebar always renders vertical stack with `borderLeft` and `paddingLeft`; no mobile variant. |
| `frontend/src/components/AppShell.tsx:15` | AppShell now has 9 nav links (`/browse`, `/collections`, `/groups`, `/requests`, `/my-skills`, `/publish`, `/analytics`, `/flags`, `/docs`). |
| `frontend/src/components/AppShell.tsx:60` | Nav is a single flex row with fixed gaps; no collapsed/mobile navigation. |
| `frontend/src/components/SkillCardGrid.tsx:23` | 0-result suggestions include「切換到語意搜尋模式」without passing an href/onClick. |
| `frontend/src/components/EmptyState.tsx:196` | Redirect suggestions render as plain `div`, visually action-like but not keyboard/click actionable. |
| `frontend/src/components/CreateCollectionModal.tsx:49` | Modal uses fixed overlay + plain max-width card; form starts immediately with fields. |
| `frontend/src/components/CreateCollectionModal.tsx:103` | Skill selection is a UUID textarea; helper text says「漂亮的多選選擇器後續版本推出」and placeholder `sk-1` is not a real UUID example. |
| `frontend/src/pages/MySkillsPage.tsx:51` | My Skills page already fetches the signed-in user's skills via `useSkillList({ author: me?.userId, size: 200 })`. |
| `frontend/src/pages/MySkillsPage.tsx:183` | Tab row renders 5 `TabPill`s directly in a wrapping flex row. |
| `frontend/src/pages/MySkillsPage.tsx:372` | `TabPill` active style is `bg-[#181818] text-white`; inactive style is `bg-white text-foreground`, which creates bright white pills on a dark page. |
| `frontend/src/api/skills.ts:274` | `createCollection` still only needs `skillIds: string[]`, so UI can collect selected skills and submit IDs internally. |
| `frontend/src/pages/docs/OverviewPage.tsx:32` | Feature cards switch to 3 columns at `md`; at 900px the text is readable but visually cramped. |

### 2.3 Research Citations

| Source | Useful finding | Design impact |
|---|---|---|
| [W3C WCAG 2.2 — Success Criterion 1.4.10 Reflow](https://www.w3.org/TR/WCAG22/#reflow) | Content should be usable without two-dimensional scrolling at 320 CSS px wide, except content that inherently needs two-dimensional layout. | Skill detail, nav, docs cards are normal page content, not data tables or diagrams; they should reflow instead of forcing body horizontal scroll. |
| [W3C WAI Technique G225](https://www.w3.org/WAI/WCAG21/Techniques/general/G225) | Even horizontally scrollable sections should keep each card/panel fully readable within 320 CSS px. | If tabs or nav keep horizontal scroll, each item must still remain visible/readable; the detail page sidebar should not sit off-canvas. |
| [MDN `flex-wrap`](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/flex-wrap) | `nowrap` flex items stay on one line and may overflow; `wrap` allows items to break into multiple lines. | AppShell nav/sort chips can use wrapping or a menu at narrow widths; do not rely on one long row. |
| [MDN `overflow`](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/overflow) | Overflow scroll containers need explicit sizing, and scroll areas have keyboard/accessibility caveats. | Fix root layout first; use local horizontal scroll only for tabs/code blocks where the content truly needs it. |

## 3. Acceptance Criteria

**AC-S172-1: Skill detail no body horizontal overflow**

Given the production fixture skill `deep-research` exists  
When the user opens `/skills/{deep-research-id}` at 390x844, 768x900, 900x700, and 1440x900  
Then `document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1`  
And the install command card is fully visible without moving the whole page sideways  
And the user can still reach SKILL.md, install command, download CTA, stats, and sidebar metadata by vertical scrolling only.

**AC-S172-2: Skill detail responsive layout**

Given the user opens `/skills/{id}` at width `< 1024px`  
When the page renders  
Then the main tab content and sidebar render as one column  
And the sidebar uses top border / spacing appropriate for stacked content, not a left divider that implies a missing right column.

**AC-S172-3: AppShell mobile navigation is usable**

Given AppShell has 9 navigation links  
When the viewport is 390x844  
Then the header shows brand, a clear primary route affordance, auth area, and a menu button or compact navigation control  
And the full navigation list is reachable by click/keyboard  
And no nav text overlaps, shrinks into unreadable fragments, or pushes auth off-screen.

**AC-S172-4: AppShell tablet navigation does not degrade**

Given the viewport is 768px to 1024px wide  
When the user opens `/browse`, `/publish`, `/docs/overview`, and `/skills/{id}`  
Then the header either wraps cleanly or switches to the same compact nav  
And `scrollWidth <= clientWidth + 1` for each page.

**AC-S172-5: Search empty-state suggestions are real actions**

Given the user opens `/browse`  
When they search `docker` and results are 0  
Then every visible suggestion row is either a button with an actual `onClick` or a link with an `href`  
And the old non-action「切換到語意搜尋模式」row is removed or replaced by a real route/control  
And keyboard users can focus and activate each suggestion.

**AC-S172-6: Search empty-state copy matches current search model**

Given semantic search is auto-selected only when semantic results exist  
When keyword search returns 0  
Then copy must not promise a separate semantic mode unless the UI actually exposes one  
And at least one action lets the user clear the query and browse all skills.

**AC-S172-7: Docs feature cards stay readable at tablet widths**

Given the user opens `/docs/overview` at 768px, 900px, and 1024px  
When the three feature cards render  
Then the cards choose 1 or 2 columns until each card has enough width for terms like `NONE/LOW/MEDIUM/HIGH` and `agentskills.io` to remain readable  
And the page has no body horizontal overflow.

**AC-S172-8: Create collection modal looks intentional**

Given the user opens `/collections`  
When they click「建立集合」  
Then the dialog has a clear title, one-sentence explanation, and close icon/button in the header  
And the form card uses the same dark token surface, 8px-or-less radius, field spacing, and button treatment as the rest of the app  
And the background page is dimmed enough to focus the modal without making the form look detached from the product.

**AC-S172-9: Create collection modal adds skills from a my-skills dropdown**

Given the signed-in user has published skills  
When they open「建立集合」  
Then the modal shows a dropdown sourced from the same「我的技能」data as `MySkillsPage`  
And each option label shows at least skill name, category, and version when available  
And the user can choose one skill and click「新增」to append it to the collection draft  
And already-added skills disappear from the dropdown or are disabled so the same skill cannot be added twice  
And the user never has to see, remember, or type a skill UUID.

**AC-S172-10: Create collection modal handles no selectable skills**

Given the signed-in user has 0 published skills  
When they open「建立集合」  
Then the skill selector shows an empty state explaining「集合只能加入已發布技能」  
And the primary submit button stays disabled  
And there is a visible link/button to `/publish`.

**AC-S172-11: Create collection modal supports removing selected skills**

Given the user has already added skills A and B to the collection draft  
When they click the remove control on skill A  
Then skill A is removed from the selected list  
And skill A becomes selectable in the dropdown again  
And the selected list still shows skill B.

**AC-S172-12: Create collection submits selected skill ids internally**

Given the user selected skills A and B in the modal  
When they click「建立集合」  
Then `createCollection` receives `skillIds: [A.id, B.id]`  
And those IDs are not exposed as an editable textarea in the UI.

**AC-S172-13: Create collection modal responsive behavior**

Given the modal is open at 390x844 and 900x700  
When the user scrolls the form  
Then the dialog fits within the viewport with internal vertical scrolling if needed  
And the primary/secondary actions remain reachable  
And `document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1`.

**AC-S172-14: My Skills lifecycle tabs use dark segmented styling**

Given the user opens `/my-skills`  
When the lifecycle tabs render  
Then inactive tabs use transparent or `bg-2/bg-3` dark surfaces, never `bg-white`  
And active tab uses a subtle selected state (`accent-soft`, stronger border, or small indicator) without full white/black contrast blocks  
And counts such as `(0)` are visually secondary but still readable.

**AC-S172-15: My Skills tabs fit mobile and keyboard use**

Given the viewport is 390x844  
When the user focuses and activates `全部 / 已發布 / 草稿 / 已停用 / 訂閱`  
Then the tab control either wraps cleanly or scrolls inside its own segmented container  
And no tab text overlaps or creates body horizontal scroll  
And focus-visible styling is visible on every tab.

**AC-S172-16: Visual regression guard covers the audited routes**

Given S172 is implemented  
When `cd e2e && npx playwright test --grep @responsive-polish` runs  
Then it opens `/`, `/browse`, `/collections`, `/my-skills`, `/publish`, `/docs/overview`, and `/skills/{id}` at 390, 768, 900, and 1440 widths  
And it asserts no body horizontal overflow on all routes  
And it asserts the `/browse` 0-result suggestions have actionable roles/links  
And it opens the `/collections` create dialog and asserts the dialog itself fits inside the viewport and contains a my-skills dropdown plus selected-skills list, not a UUID textarea  
And it asserts `/my-skills` tab buttons do not include `bg-white` or computed white backgrounds in dark theme.

## 4. Chosen Design

### 4.1 Approach

Use local responsive layout fixes in the components that own the broken layout:

1. `SkillDetailPage` owns the two-column page grid, so it should switch `gridTemplateColumns` by breakpoint or use Tailwind responsive classes.
2. `Sidebar` owns its border/padding, so it should support stacked mode through class names/CSS variables rather than carrying desktop-only `borderLeft` onto mobile.
3. `AppShell` owns the nav list, so it should introduce a compact nav for narrow widths instead of making every page solve header pressure.
4. `EmptyState` should make suggestion rows action-aware, or callers should use explicit `primaryAction` / `secondaryAction` for escape hatches.
5. `CreateCollectionModal` should fetch from the user's own published skills and expose a simple add/remove builder:
   - data: reuse existing `useMe` + `useSkillList({ author: me.userId, size: 200 })` pattern from `MySkillsPage`
   - input: native `<select>` or shadcn-style select/dropdown listing available skills by name/category/version
   - add:「新增」button appends the selected skill into local `selectedSkills`
   - remove: each selected row has an icon button to remove it
   - submit: keep backend `createCollection({ skillIds })` unchanged and map `selectedSkills.map(s => s.id)` internally
   - duplicates: already-selected skills are disabled/hidden in the dropdown.
6. `MySkillsPage` lifecycle tabs should use a local dark segmented control:
   - outer wrapper: `inline-flex/flex-wrap gap-1 rounded-md border border-line bg-bg-2 p-1`
   - inactive tab: transparent/dark surface, `ink-2`, hover `bg-bg-3`
   - active tab: `accent-soft` or `bg-bg-3` + `accent-text` / `ink`, border `line-2`
   - count: separate `<span>` with muted color, not part of the label's visual weight.

### 4.2 Alternatives Considered

| Option | File/behavior | Result | Cost |
|---|---|---|---|
| A. Hide horizontal overflow on `body` | Add `overflow-x-hidden` to global layout | The right sidebar still exists off-screen; user loses content instead of seeing it. | Low time, high UX risk. Rejected. |
| B. Local responsive fixes | Change `SkillDetailPage`, `Sidebar`, `AppShell`, `SkillCardGrid`/`EmptyState` | The page reflows and each broken action becomes real. | Small-to-medium frontend work. Chosen. |
| C. Full design-system nav rewrite | Build route groups, dropdowns, role-aware nav, mobile drawer, active breadcrumbs | Cleans long-term nav scaling. | Larger scope; not needed for this production polish. Deferred. |
| D. Leave UUID textarea but improve copy | Keep textarea, replace `sk-1` placeholder with a UUID-shaped example | Still makes the user leave the modal to find and copy IDs. | Low time, but fails the actual task. Rejected. |
| E. Checkbox multi-select in modal | Reuse `useSkillList({ author: me.userId })`, render all skills with checkboxes | Works for small lists but gets noisy once the user has many skills. | Moderate frontend work. Rejected per user direction. |
| F. Global published-skill search picker | Search every published skill and allow adding any public skill | Good later for team curation, but "我的技能" is the user-stated immediate need and avoids permission ambiguity. | Larger product decision. Deferred. |
| G. Keep MySkills tab pills but swap `bg-white` to `bg-card` | Minimal color fix only | Removes the white flash but still leaves five separate little buttons without a unified tab model. | Low time, partial fix. Rejected. |
| H. Dark segmented tab control | One shared-looking control with active/inactive/count states | Matches current dark design tokens and makes lifecycle filters feel like tabs, not random CTAs. | Small frontend work. Chosen. |
| I. My-skills dropdown + add/remove selected list | Dropdown chooses one skill at a time;「新增」adds to a selected list; each row can be removed | Matches user expectation: build a skill package from recognizable owned skills without UUIDs, while keeping the modal compact. | Moderate frontend work. Chosen. |

### 4.3 File Plan

| File | Change |
|---|---|
| `frontend/src/pages/SkillDetailPage.tsx` | Replace inline fixed two-column grid with responsive one-column / two-column layout. |
| `frontend/src/components/v2/Sidebar.tsx` | Add stacked visual treatment for mobile/tablet; preserve desktop sidebar at large widths. |
| `frontend/src/components/AppShell.tsx` | Add compact navigation behavior below a chosen breakpoint; keep desktop nav unchanged. |
| `frontend/src/components/SkillCardGrid.tsx` | Replace non-action 0-result suggestion with actionable clear/publish/request route behavior. |
| `frontend/src/components/EmptyState.tsx` | Support suggestion `href` / `onClick` or render suggestions as buttons/links when provided. |
| `frontend/src/components/CreateCollectionModal.tsx` | Polish dialog shell and replace UUID textarea with my-skills dropdown + add button + selected-skills removable list. |
| `frontend/src/hooks/useSkillList.ts` | Reuse existing hook inside modal; no backend contract change expected. |
| `frontend/src/pages/MySkillsPage.tsx` | Redesign `TabPill` and tab wrapper into dark segmented lifecycle tabs; remove inactive `bg-white`. |
| `frontend/src/pages/docs/OverviewPage.tsx` | Adjust feature-card grid breakpoint or min-width so docs cards do not become cramped at tablet widths. |
| `frontend/src/**/*.test.tsx` | Add focused component tests for nav compact mode, empty-state actionable suggestions, and detail layout class behavior. |
| `e2e/tests/responsive-polish.spec.ts` | Add viewport overflow/browser behavior checks tagged `@responsive-polish`. |

### 4.4 Low-Fidelity UI Sketches

These sketches are layout contracts, not final pixels. Use existing dark tokens from `docs/grimo/ui/DESIGN.md`; do not introduce white inactive pills, decorative gradients, or a UUID textarea.

**CreateCollectionModal — desktop**

```text
┌──────────────────────────────────────────────────────────────┐
│ 建立集合                                           [ X ]      │
│ 從你已發布的技能挑選幾個，組成可一次安裝的技能包。            │
├──────────────────────────────────────────────────────────────┤
│ 名稱                                                         │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ DevOps Starter Pack                                     │ │
│ └──────────────────────────────────────────────────────────┘ │
│                                                              │
│ 說明（選填）                                                 │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ 給新專案快速完成部署、檢查與文件產生。                   │ │
│ └──────────────────────────────────────────────────────────┘ │
│                                                              │
│ 分類                                                         │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ DevOps                                                   │ │
│ └──────────────────────────────────────────────────────────┘ │
│                                                              │
│ 新增技能                                                     │
│ ┌──────────────────────────────────────────────┐ ┌────────┐ │
│ │ deep-research · Research · v1.0.0        ▾  │ │ 新增   │ │
│ └──────────────────────────────────────────────┘ └────────┘ │
│                                                              │
│ 已選技能 2                                                   │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ DR  deep-research        Research      v1.0.0     [trash]│ │
│ │ DC  docker-compose       DevOps        v2.1.0     [trash]│ │
│ └──────────────────────────────────────────────────────────┘ │
│                                                              │
│                                      [取消] [建立集合]       │
└──────────────────────────────────────────────────────────────┘
```

Behavior details:

- Dropdown only lists the current user's published skills that are not already selected.
-「新增」is disabled until a dropdown option is selected.
-「建立集合」is disabled until name, category, and at least one selected skill exist.
- Remove button is an icon button with `aria-label="移除 {skill.name}"`.

**CreateCollectionModal — no published skills**

```text
新增技能
┌──────────────────────────────────────────────────────────────┐
│ 目前沒有可加入集合的已發布技能。                             │
│ 集合只能加入已發布技能；先發布一個技能後再回來建立集合。      │
│ [前往發布技能]                                                │
└──────────────────────────────────────────────────────────────┘
```

**MySkillsPage lifecycle tabs**

```text
┌──────────────────────────────────────────────────────────────┐
│ 全部 0 │ 已發布 0 │ 草稿 0 │ 已停用 0 │ 訂閱 0              │
└──────────────────────────────────────────────────────────────┘
```

Visual contract:

- Outer container: dark surface + hairline border + 4px inner padding.
- Active tab: subtle selected fill (`bg-3` or `accent-soft`) + stronger text.
- Inactive tabs: transparent/dark only; never `bg-white`.
- Count should be a muted inline badge or muted span, not a full-bright label.

**SkillDetail responsive body**

```text
desktop >= 1024px
┌────────────────────────────────────────────┬─────────────────┐
│ Tabs + SKILL.md / Quality / Files          │ Install / Stats  │
│                                            │ Sidebar cards    │
└────────────────────────────────────────────┴─────────────────┘

tablet/mobile < 1024px
┌────────────────────────────────────────────┐
│ Tabs + SKILL.md / Quality / Files          │
├────────────────────────────────────────────┤
│ Install / Stats / Sidebar cards            │
└────────────────────────────────────────────┘
```

## 5. Task Boundary Hints

| Task | Scope | Verification |
|---|---|---|
| T01 | Skill detail responsive grid + sidebar stacked treatment | Vitest component/class assertions + Playwright no-overflow on `/skills/{id}`. |
| T02 | AppShell compact nav for mobile/tablet | Vitest keyboard/click behavior + Playwright no-overflow on all audited routes. |
| T03 | Browse 0-result actions | Vitest: suggestion rows are links/buttons; click clear query shows all skills. |
| T04 | MySkillsPage lifecycle tab CSS | Vitest/class assertions: no inactive `bg-white`, active/inactive/count states; Playwright screenshot/no-overflow at mobile. |
| T05 | CreateCollectionModal my-skills dropdown builder | Vitest: loads my published skills, dropdown add, duplicate prevention, row remove, empty state, submit selected IDs; Playwright modal fit check. |
| T06 | Docs overview card readability + final responsive e2e | Playwright `@responsive-polish` across 390/768/900/1440. |

NFR sweep:

| Category | Requirement |
|---|---|
| Performance | Compact nav and responsive layout must not add new network calls. |
| Security | N/A — no auth/permission/API behavior change. |
| Reliability | No body horizontal overflow on audited routes at four viewport widths. |
| Usability | All visible action rows are keyboard-focusable and have visible text labels; collection creation must be possible by choosing recognizable skill rows, without copying UUIDs; lifecycle tabs must look like low-noise filters, not primary buttons. |
| Maintainability | Responsive behavior lives in component-owned classes, not global body overflow suppression. |

## 6. Task Plan

POC: not required — S172 uses existing frontend components, React state, Tailwind classes, React Router links, `useMe`, `useSkillList`, `createCollection`, and the already-bootstrapped Playwright workspace. No new package, SDK, framework SPI, or backend contract is introduced.

### 6.1 Task Order

| Task | File | AC mapping | Depends on |
|---|---|---|---|
| T01 | `docs/grimo/tasks/2026-05-14-S172-T01-skill-detail-responsive-sidebar.md` | AC-S172-1, AC-S172-2 | none |
| T02 | `docs/grimo/tasks/2026-05-14-S172-T02-appshell-compact-navigation.md` | AC-S172-3, AC-S172-4 | T01 |
| T03 | `docs/grimo/tasks/2026-05-14-S172-T03-browse-empty-state-actions.md` | AC-S172-5, AC-S172-6 | none |
| T04 | `docs/grimo/tasks/2026-05-14-S172-T04-my-skills-dark-segmented-tabs.md` | AC-S172-14, AC-S172-15 | none |
| T05 | `docs/grimo/tasks/2026-05-14-S172-T05-create-collection-my-skills-picker.md` | AC-S172-8, AC-S172-9, AC-S172-10, AC-S172-11, AC-S172-12, AC-S172-13 | T03 |
| T06 | `docs/grimo/tasks/2026-05-14-S172-T06-docs-and-responsive-e2e.md` | AC-S172-7, AC-S172-16 plus browser verification for AC-S172-1 to AC-S172-15 | T01-T05 |

### 6.2 AC-to-Test Coverage

| AC | Primary task | Test surface |
|---|---|---|
| AC-S172-1 | T01 | `SkillDetailPage.test.tsx` + `S172-responsive-polish.spec.ts` no-overflow route checks |
| AC-S172-2 | T01 | `SkillDetailPage.test.tsx` class/structure check |
| AC-S172-3 | T02 | `AppShell.test.tsx` compact menu role/click/keyboard behavior |
| AC-S172-4 | T02/T06 | `AppShell.test.tsx` + Playwright no-overflow route checks |
| AC-S172-5 | T03 | `EmptyState.test.tsx`, `SkillCardGrid.test.tsx`, Playwright browse empty-state check |
| AC-S172-6 | T03 | `SkillCardGrid.test.tsx` copy/action check |
| AC-S172-7 | T06 | `OverviewPage.test.tsx` + Playwright docs route no-overflow |
| AC-S172-8 | T05 | `CreateCollectionModal.test.tsx` dialog shell check |
| AC-S172-9 | T05 | `CreateCollectionModal.test.tsx` dropdown add flow |
| AC-S172-10 | T05 | `CreateCollectionModal.test.tsx` no published skills state |
| AC-S172-11 | T05 | `CreateCollectionModal.test.tsx` remove/reselect flow |
| AC-S172-12 | T05 | `CreateCollectionModal.test.tsx` mutation payload check |
| AC-S172-13 | T05/T06 | `CreateCollectionModal.test.tsx` viewport-bounded classes + Playwright dialog fit check |
| AC-S172-14 | T04 | `MySkillsPage.test.tsx` class/computed style guard |
| AC-S172-15 | T04/T06 | `MySkillsPage.test.tsx` focus/filter behavior + Playwright mobile tab check |
| AC-S172-16 | T06 | `e2e/tests/S172-responsive-polish.spec.ts` |

### 6.3 Browser E2E Design Note

S172 is a browser/UI spec, so final verification must include Playwright. The E2E task uses the existing `e2e/tests/_fixtures.ts` seed helpers and `profiles.single(request)` pattern from S140; it must not rely on the production-only skill UUID observed during the Chrome audit. Missing stable labels or roles discovered while writing the E2E file become frontend fixes inside T01-T05, not brittle CSS selectors.

Next: `$verifying-quality S172` can add an independent QA section if required; then `$shipping-release` can archive S172 and update changelog/roadmap shipped rows.

## 7. Implementation Results

### 7.1 Task Results

| Task | Result | Evidence |
|---|---|---|
| T01 | PASS | `SkillDetailPage` switches to one-column layout below `lg`; `Sidebar` uses stacked top divider below `lg`. |
| T02 | PASS | `AppShell` compact nav exposes all 9 routes on narrow screens and closes after link click. |
| T03 | PASS | `/browse` 0-result suggestions render as real link/button actions; old「切換到語意搜尋模式」copy removed. |
| T04 | PASS | `/my-skills` lifecycle tabs use dark segmented styling; inactive tabs no longer include `bg-white`. |
| T05 | PASS | `CreateCollectionModal` uses my published skill dropdown, add/remove selected list, and submits `skillIds` internally. |
| T06 | PASS | `OverviewPage` tablet grid and Playwright `@responsive-polish` route checks passed. |

Temporary task files were consolidated into this section and are removed after this update.

### 7.2 Local Verification

Run at `2026-05-14T16:07:59Z`:

```text
./scripts/verify-all.sh
V01=PASS
V02=INFO — LINE coverage = 85.8% (covered=4626 / total=5389)
V03=PASS
V04=PASS
V05=PASS
V06=PASS
V07=PASS
V08a=PASS
V08b=PASS
Verdict: ✅ all CRITICAL passed; exit=0
```

Targeted evidence already recorded by task files:

```text
cd frontend && npm test -- OverviewPage.test.tsx
cd frontend && npm test -- MySkillsPage.test.tsx HeroMetricsRow.test.tsx SkillDetailPage.test.tsx Sidebar.test.tsx CreateCollectionModal.test.tsx
cd e2e && npx playwright test --grep @responsive-polish
```

Observed task results:

- `OverviewPage.test.tsx` PASS.
- 8 frontend component test files / 64 tests PASS.
- `@responsive-polish` Playwright suite PASS: 3 tests across 390, 768, 900, and 1440 widths for `/`, `/browse`, `/collections`, `/my-skills`, `/publish`, `/docs/overview`, and `/skills/{id}`.

### 7.3 Production Smoke Evidence

Chrome opened `https://skillshub-644359853825.asia-east1.run.app/` and audited `/browse`, `/collections`, `/my-skills`, `/publish`, and `/docs/overview` on the live Cloud Run service.

Observed live UI:

| URL | Result |
|---|---|
| `/` | Homepage rendered; console error/warn list was empty. |
| `/browse` | Empty registry state rendered: `尚未有任何技能` and `發布第一個技能`; console error/warn list was empty. |
| `/collections` | Empty collections state rendered; clicking「建立集合」opened the new modal with `新增技能`, `集合只能加入已發布技能`, `前往發布技能`, and `已選技能 0`; no `技能 ID 清單`, `UUID`, or `sk-1` text remained. |
| `/my-skills` | Signed-in user state rendered metrics and lifecycle tabs: `全部0 / 已發布0 / 草稿0 / 已停用0 / 訂閱0`; console error/warn list was empty. |
| `/publish` | Upload form rendered with `上傳檔案 / 貼上文本`, visibility controls, and publish button; console error/warn list was empty. |
| `/docs/overview` | Docs overview rendered; console error/warn list was empty. |

Production data caveat: live DB currently has `0 個技能 · 0 位發佈者`, so this smoke run could not exercise a real skill detail page, download button, or collection picker with selectable skill rows. Local Playwright `@responsive-polish` covers seeded `/skills/{id}` and modal selected-skill flows.

### 7.4 Cloud Run / Cloud Build Evidence

Commands run:

```text
gcloud run revisions list --service=skillshub --region=asia-east1 --project=cfh-vibe-lab --format=json --limit=5
gcloud builds list --project=cfh-vibe-lab --limit=5 --format=json
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="skillshub" AND severity>=ERROR' --project=cfh-vibe-lab --freshness=2h --limit=20 --format=json
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="skillshub" AND resource.labels.revision_name="skillshub-00023-j5q" AND severity>=ERROR' --project=cfh-vibe-lab --freshness=90m --limit=20 --format=json
```

Evidence:

| Item | Result |
|---|---|
| Latest Cloud Run revision | `skillshub-00023-j5q`, Ready=True, Active=True, created `2026-05-14T16:02:14Z`, healthy at `2026-05-14T16:02:42Z`. |
| Latest app image | `asia-east1-docker.pkg.dev/cfh-vibe-lab/skillshub/skillshub@sha256:606de95e75c279f86b1638bfd61c0421691ca0079dea2d0625403789142d6a2d`. |
| Latest Cloud Build | `f920b89a-dfc7-4daf-8786-bf0c5c888af8`, status `SUCCESS`, image tag `20260514-155200`, finished `2026-05-14T16:01:24Z`. |
| Latest revision error logs | `[]` for `skillshub-00023-j5q` with `severity>=ERROR` over the last 90 minutes. |
| Requests from this smoke run | `/collections`, `/api/v1/me`, `/api/v1/skills`, `/api/v1/notifications/unread-count` returned HTTP 200 in Cloud Run request logs. |

The 2-hour service-wide error query still returned older `skillshub-00022-khz` entries before the `00023` deployment:

- `LlmJudgement` native reflection error at `2026-05-14T15:44-15:46Z`.
- `relation "shedlock" does not exist` at `2026-05-14T16:02:09Z` on retired revision `00022-khz`.

Those errors did not appear on latest revision `00023-j5q` after deploy; latest `00023` logs show scheduled publication checks and user/API requests succeeding.

### 7.5 AC Results

| AC | Result | Evidence |
|---|---|---|
| AC-S172-1 | PASS | Local `@responsive-polish` seeded `/skills/{id}` no-overflow checks passed. |
| AC-S172-2 | PASS | `SkillDetailPage.test.tsx` + `Sidebar.test.tsx`; component classes show stacked sidebar below `lg`. |
| AC-S172-3 | PASS | `AppShell.test.tsx`; compact nav exposes all route links. |
| AC-S172-4 | PASS | `@responsive-polish` audited routes no-overflow checks passed. |
| AC-S172-5 | PASS | `EmptyState.test.tsx`, `SkillCardGrid.test.tsx`, and live `/browse` no stale semantic-mode row. |
| AC-S172-6 | PASS | `SkillCardGrid.test.tsx`; clear-query action exists and semantic-mode promise removed. |
| AC-S172-7 | PASS | `OverviewPage.test.tsx` and live `/docs/overview` rendered without console errors. |
| AC-S172-8 | PASS | `CreateCollectionModal.test.tsx`; live modal has title/copy/close-capable dialog shell. |
| AC-S172-9 | PASS | Local modal tests prove dropdown add flow with seeded skills. |
| AC-S172-10 | PASS | Live modal shows no-skill state `集合只能加入已發布技能` and `前往發布技能`; local tests assert disabled submit. |
| AC-S172-11 | PASS | Local modal tests prove remove and reselect behavior. |
| AC-S172-12 | PASS | Local modal tests assert `createCollection({ skillIds })` payload and no UUID textarea. |
| AC-S172-13 | PASS | Local modal viewport classes + `@responsive-polish` dialog fit checks passed. |
| AC-S172-14 | PASS | `MySkillsPage.test.tsx`; live `/my-skills` rendered segmented tabs with no console errors. |
| AC-S172-15 | PASS | `MySkillsPage.test.tsx` focus/filter behavior + `@responsive-polish` mobile tab checks passed. |
| AC-S172-16 | PASS | `cd e2e && npx playwright test --grep @responsive-polish` passed 3 tests. |

### 7.6 QA Review

Current tick re-ran the deterministic repository verification and production smoke checks directly. A separate `$verifying-quality S172` independent review is still appropriate before tagging/changelog/archive if the release process requires a fresh reviewer section, but no failing local command or latest-revision production error remains.

### 7.7 Pending Verification

| Item | Status | Command / next action |
|---|---|---|
| Live skill detail with non-empty production data | POST-RELEASE | Upload or seed one published skill in LAB, then revisit `/skills/{id}` in Chrome and confirm no body horizontal overflow. |
| Independent QA section | Optional before ship | Run `$verifying-quality S172` if the release gate requires a separate QA reviewer note. |
