---
version: stable
name: Skills Hub
description: >
  Enterprise-internal AI agent skills registry. UI balances developer-tool
  transparency (event names exposed, risk rule IDs visible, similarity scores
  shown) with modern dark-mode craft. Built for engineers who read changelogs
  but appreciate intentional design.

# ── Source of Truth ─────────────────────────────────────────────────────────
#
# This file is the stable, single UI design index. It owns:
# 1. global tokens and component rules,
# 2. route-to-page inventory,
# 3. prototype mapping for each frontend page,
# 4. design-source cleanup rules for future specs and code comments.
#
# Historical S081/S085-S088/S094 specs and snake_case prototype filenames remain
# archive records only. They must not be cited as the active design source for new
# implementation work unless the citation clearly says "historical".
source_of_truth:
  canonical:
    stable_ui_index: "docs/grimo/ui/DESIGN.md"
    tokens_and_components: "docs/grimo/ui/DESIGN.md"
    page_mockups: "docs/grimo/ui/prototype/Skills Hub *.html"
    route_reality: "frontend/src/App.tsx"
    design_history: "docs/grimo/specs/archive/2026-05-02-S096-ui-v2-dark-theme-meta.md"
    parity_history: "docs/grimo/specs/archive/2026-05-02-S098-prototype-completeness-audit.md"

  superseded:
    - "docs/grimo/ui/README.md — file is not present; do not cite README line numbers"
    - "docs/prototype/*.html — old path from S084; use docs/grimo/ui/prototype/"
    - "snake_case prototype names such as my_skills_author_dashboard.html, semantic_search_results_page.html, empty_state_collection_four_tones.html, platform_analytics_dashboard_admin_view.html"
    - "S081 light theme tokens — replaced by S096b dark theme"

  rule:
    - "When frontend routes or page-level design change, update Page Inventory in this file in the same spec."
    - "Frontend comments should cite either this Page Inventory or the exact `Skills Hub *.html` prototype filename."
    - "If a prototype and current implementation disagree, check `frontend/src/App.tsx` for route reality and record the design decision here."
    - "Do not add a second UI index. Keep this file as the stable design entry point."

# ── Theming ────────────────────────────────────────────────────────────────
theme: dark

colors:
  # Backgrounds — layered dark surfaces
  bg:           "#08080A"      # page background
  bg-2:         "#0F0F12"      # card surface
  bg-3:         "#171719"      # elevated surface, code block
  bg-4:         "#1E1E22"      # highest elevation

  # Text
  ink:          "#EEECEA"      # primary text
  ink-2:        "#A8A49C"      # secondary text
  ink-3:        "#5E5B55"      # tertiary / placeholder / disabled

  # Borders — hairlines only
  line:         "rgba(255,255,255,0.06)"   # default card border
  line-2:       "rgba(255,255,255,0.10)"   # stronger border, inputs on focus

  # Accent — AI / semantic / selected state
  accent:       "#7F77DD"      # purple — means "system is reasoning"
  accent-soft:  "rgba(127,119,221,0.12)"
  accent-mid:   "rgba(127,119,221,0.18)"
  accent-text:  "#C9C5F2"

  # Success — low risk / approved / pass / growth
  green:        "#1D9E75"
  green-soft:   "rgba(29,158,117,0.14)"
  green-mid:    "rgba(29,158,117,0.20)"
  green-text:   "#6FD8B0"

  # Warning — medium risk / pending / caution
  amber:        "#EF9F27"
  amber-soft:   "rgba(239,159,39,0.14)"
  amber-mid:    "rgba(239,159,39,0.20)"
  amber-text:   "#FAC775"

  # Danger — high risk / rejected / blocked / flag
  red:          "#E24B4A"
  red-soft:     "rgba(226,75,74,0.14)"
  red-mid:      "rgba(226,75,74,0.20)"
  red-text:     "#F2A6A6"

  # Info — links / secondary brand / k8s-infra
  blue:         "#378ADD"
  blue-soft:    "rgba(55,138,221,0.14)"
  blue-text:    "#B0D5F2"

  # Category palette — identity, never severity
  # DevOps=purple, Testing=teal, Docs=coral, Data=amber, Security=pink, Infra/Frontend/Design=blue
  cat-devops:        "rgba(127,119,221,0.18)"
  cat-devops-text:   "#C9C5F2"
  cat-infra:         "rgba(55,138,221,0.18)"    # covers: infra / infrastructure / frontend / design
  cat-infra-text:    "#B0D5F2"
  cat-testing:       "rgba(29,158,117,0.18)"
  cat-testing-text:  "#9FE1CB"
  cat-docs:          "rgba(226,75,74,0.18)"
  cat-docs-text:     "#F2A6A6"
  cat-data:          "rgba(239,159,39,0.18)"
  cat-data-text:     "#FAC775"
  cat-security:      "rgba(217,56,138,0.18)"
  cat-security-text: "#F0A2C5"

typography:
  # All pages import Inter + JetBrains Mono from Google Fonts
  sans: "'Inter', system-ui, sans-serif"
  mono: "'JetBrains Mono', ui-monospace, monospace"
  serif-italic: "Source Serif Pro italic"   # ✦ sparkle only

  scale:
    display:     { size: 64px, weight: 500, tracking: "-0.03em" }
    headline-lg: { size: 36px, weight: 500, tracking: "-0.02em" }
    headline-md: { size: 28px, weight: 500, tracking: "-0.012em" }
    headline-sm: { size: 22px, weight: 500, tracking: "-0.005em" }
    title:       { size: 18px, weight: 500, tracking: "-0.005em" }
    body-lg:     { size: 15px, weight: 400, lineHeight: 1.65 }
    body-md:     { size: 14px, weight: 400, lineHeight: 1.6 }
    body-sm:     { size: 13px, weight: 400, lineHeight: 1.55 }
    label:       { size: 12px, weight: 500 }
    caption:     { size: 11px, weight: 400, lineHeight: 1.45 }
    mono-md:     { size: 12px, weight: 400, lineHeight: 1.75 }
    mono-sm:     { size: 11px, weight: 500 }

radius:
  sm:   4px
  md:   8px
  lg:   12px
  xl:   16px
  pill: 999px

spacing:
  # 4px base
  xs:  4px
  sm:  8px
  md:  12px
  lg:  16px
  xl:  22px
  2xl: 26px
  3xl: 40px
  page-pad: 26px
  card-pad: 14px

# ── BorderBeam ──────────────────────────────────────────────────────────────
beam:
  description: >
    The single animated motion primitive in the entire product.
    Implemented via `border-beam@1.0.1` npm package (BeamFrame component).
    A colorful gradient ring rotates around the border of one primary element
    per visible viewport. Scarcity is what makes it meaningful.

  implementation: "border-beam npm package — BeamFrame wrapper"
  params:
    colorVariant: "colorful"   # full rainbow spectrum
    size: "md"                 # full border glow preset
    duration: 1.96             # seconds per rotation
    strength: 0.7              # beam intensity 70%
    theme: "dark"              # works with #08080A page background

  allowed_surfaces:
    - Hero search bar (Landing, Homepage)
    - Primary CTA button — one per page
    - FileDropZone (Publish Step 1)
    - Featured / top-match skill card (Landing popular skills, /browse semantic mode)

  forbidden:
    - Metric cards
    - Nav items or topbar
    - Sidebar rows
    - Secondary or ghost buttons
    - Any decorative container

  exceptions:
    - "LandingPage may show BeamFrame on the hero CTA, first popular SkillCard, and final CTA because S096e1 accepted the marketing-page exception. Other app pages keep one active BeamFrame per visible viewport."

# ── Elevation & Depth ───────────────────────────────────────────────────────
elevation:
  description: >
    No drop shadows anywhere. Hierarchy via three techniques only:
    1. Hairline borders (0.5px in `line` color)
    2. Tonal layers (bg → bg-2 → bg-3)
    3. BorderBeam (see above)

  glow_surfaces:
    hero:  "radial-gradient at 20% 40% rgba(127,119,221,.12) + 80% 20% rgba(217,56,138,.08) + 55% 80% rgba(29,158,117,.07)"
    final_cta: "radial-gradient rgba(127,119,221,.14) at top"
    description: "Two named glow surfaces only. Everywhere else is flat."

# ── Components ──────────────────────────────────────────────────────────────
components:

  nav:
    height: 56px              # h-14 in Tailwind (3.5rem)
    padding: "0 24px"         # px-6 in Tailwind
    background: "rgba(8,8,10,.95)"
    border-bottom: "0.5px solid line"
    logo: "text 'Skills Hub' — no gradient circle"
    note: "Publish link in nav is a plain text link, no BeamFrame"

  card:
    background: bg-2
    border: "0.5px solid line"
    radius: xl (16px)
    padding: "14–18px"

  card-featured:
    treatment: BorderBeam
    background: bg-2
    inner-radius: "xl (16px)"
    note: "Used for top search result in SearchResultsPage (featured prop on SkillCard)"

  input:
    background: bg-2
    border: "0.5px solid line-2"
    radius: md
    padding: "9px 12px"
    focus: "border-color rgba(127,119,221,.5), box-shadow 0 0 0 2px rgba(127,119,221,.08)"

  required-field:
    applies-to:
      - "PublishPage required fields: 技能名稱, Skill 套件 / SKILL.md 內容, 分類"
      - "SkillEditPage required fields: 分類, upload mode Skill 套件"
    required-mark: "Visible `*` or small dot with `aria-hidden=true`, plus adjacent sr-only text `必填`"
    inline-error: "Use short zh-TW field messages such as `請填寫分類`, `請貼上 SKILL.md 內容`, `請選擇 zip 或 SKILL.md`, `分類不可空白`"
    a11y: "When an error is visible, set `aria-invalid=true` on the input/textarea/dropzone wrapper and include the error id in `aria-describedby`; preserve any help-text id in the same attribute."
    disabled-action-rule: "Disabled primary buttons must not be the only explanation; the responsible field needs inline text."
    palette-rule: "Required marks and inline errors use the danger text token only. Do not add required-state colors to the category palette or risk palette."

  search-hero:
    treatment: BorderBeam
    inner: "background bg-2"
    mode-toggle: "Semantic (accent dot) / Keyword"
    note: "SearchBar component — BeamFrame wraps input; used in Landing and Homepage"

  risk-pill:
    shape: pill
    padding: "2px 8px"
    note: "4-tier system (RiskBadge component). Tiers map to SKILL scanner output."
    none:   { bg: green-soft,  text: green-text,  label: "無風險",  note: "no findings + no capability declaration" }
    low:    { bg: blue-soft,   text: blue-text,   label: "低風險",  note: "0 findings with capability, or findings all LOW" }
    medium: { bg: amber-soft,  text: amber-text,  label: "中風險",  note: "scripts present, no dangerous patterns" }
    high:   { bg: red-soft,    text: red-text,    label: "高風險",  note: "dangerous command or sensitive path" }

  quality-status-indicator:
    component: "ScoreStatusIndicator / WarningStatusIndicator"
    applies-to: "Skill detail Quality tab dimension rows only"
    dot-size: "12px circle; text label is always visible, never color-only"
    palette-rule: "This is a quality score status, not risk-pill and not category palette. Do not reuse purple full-score dots here."
    axis-total-rule: "Quality tab rainbow line and axis header number use axis.totalScore only; row status uses dimension score."
    validation:
      pass: { text: "通過 100/100", color: green-text, input: "score = 100" }
      warn: { text: "注意 {score}/100", color: amber-text, input: "score = 1..99" }
      fail: { text: "需修正 0/100", color: red-text, input: "score = 0" }
      warnings: { text: "提醒 {count}", color: amber-text, input: "warnings: string[]" }
    implementation_activation:
      full: { text: "滿分 3/3", color: green-text, input: "score = 3" }
      acceptable: { text: "可接受 2/3", color: amber-text, input: "score = 2" }
      weak: { text: "偏弱 1/3", color: red-text, input: "score = 1" }
      missing: { text: "缺失 0/3", color: red-text, input: "score = 0" }

  status-pill:
    note: "Shown only when status ≠ PUBLISHED (published skills show no pill in SkillCard)"
    draft:      { bg: "rgba(239,159,39,0.14)", text: amber-text, label: "草稿" }
    suspended:  { bg: "rgba(226,75,74,0.14)",  text: red-text,   label: "已停用" }
    pending:    { bg: amber-soft,  text: amber-text }
    verified:   { bg: blue-soft,   text: blue-text  }

  beam-btn:
    background: "#EEECEA"
    color: "#08080A"
    padding: "8–11px 16–22px"
    radius: "calc(md - 1.5px)"
    note: "Inner element of BeamFrame. Always light on dark."

  ghost-btn:
    background: transparent
    border: "0.5px solid line-2"
    color: ink-2
    radius: md

  danger-btn:
    border: "0.5px solid rgba(226,75,74,.3)"
    color: red-text
    background: transparent

  icon-tile:
    radius: "5–10px depending on size"
    sizes: { sm: 24px, md: 30px, lg: 40px, xl: 52px }
    colors: "category palette — never risk palette"
    category-map: >
      devops → cat-devops; testing/uitest → cat-testing;
      docs/documents/documentation → cat-docs;
      data/analytics/ai/'data & etl' → cat-data;
      security → cat-security;
      infra/infrastructure/frontend/design → cat-infra;
      unknown → default (bg-3, ink-2)

  sparkline:
    description: "SVG polyline — no chart library dependency"
    default-color: accent (#7F77DD)
    callers-should: "pass green (#1D9E75) for positive trend, red (#E24B4A) for negative"
    width: "60px default"
    note: "data length is flexible; auto-scales to max value"

  similarity-badge:
    note: "Shown as % pill (not a bar) — format: '{score*100}% 相符'"
    bg: green-soft
    text: "#9FE1CB"

  stepper:
    done:   { bg: green-soft,   text: green-text, icon: ✓ }
    active: { bg: ink,          text: bg }
    future: { bg: bg-3,         text: ink-3, border: line-2 }
    line-done: green at 40% opacity
    line-neutral: line-2

  ai-intent-card:
    bg: "rgba(127,119,221,.07)"
    border: "rgba(127,119,221,.2)"
    label: "UNDERSTOOD YOUR INTENT · pulse dot in accent"
    concepts: "removable chips in accent-soft"
    sparkle: "✦ in Source Serif Pro italic — AI signal only"

  activity-feed-dot:
    published:  green
    flagged:    red
    created:    accent/purple
    reviewed:   amber
    system:     bg-4

# ── Page Inventory ──────────────────────────────────────────────────────────
pages:
  - component: "LandingPage"
    route: "/"
    auth: false
    prototype: "docs/grimo/ui/prototype/Skills Hub Landing.html"
    status: "implemented"
    beam: ["Browse CTA", "first popular SkillCard", "final CTA"]
    note: "Marketing-page exception to the one-beam app-page rule."

  - component: "HomePage"
    route: "/browse"
    aliases: ["/skills"]
    auth: true
    prototype: "docs/grimo/ui/prototype/Skills Hub Homepage.html"
    status: "implemented"
    beam: ["hero search bar (SearchBar)"]
    note: "Browse and semantic search now live on this page; there is no separate SearchResultsPage route."

  - component: "SkillDetailPage"
    routes: ["/skills/:id", "/skills/:author/:name"]
    canonical: "/skills/:author/:name"
    auth: true
    prototype: "docs/grimo/ui/prototype/Skills Hub Skill Detail v2.html"
    fallback_prototype: "docs/grimo/ui/prototype/Skills Hub Skill Detail.html"
    status: "implemented"
    beam: ["Download CTA via PageHeader"]

  - component: "SkillEditPage"
    route: "/skills/:id/edit"
    auth: true
    prototype: "inherits Publish Step 1 / Step 2 editing flow patterns"
    status: "implemented"
    beam: []

  - component: "VersionDiffPage"
    route: "/skills/:id/diff"
    intended_canonical_route: "/skills/:author/:name/diff?from=&to="
    auth: true
    prototype: "docs/grimo/ui/prototype/Skills Hub Version Diff.html"
    status: "implemented"
    beam: []
    note: "Current route is id-based in App.tsx; canonical author/name diff route is documented intent, not wired."

  - component: "PublishPage"
    route: "/publish"
    auth: true
    prototype: "docs/grimo/ui/prototype/Skills Hub Publish Step 1.html"
    status: "implemented"
    note: "Step 1 — FileDropZone"
    beam: ["FileDropZone border"]

  - component: "PublishValidatePage"
    route: "/publish/validate"
    auth: true
    prototype: "docs/grimo/ui/prototype/Skills Hub Publish Step 2.html"
    status: "implemented"
    note: "Step 2 — auto-poll + auto-navigate to /publish/review"
    beam: []

  - component: "PublishReviewPage"
    route: "/publish/review"
    auth: true
    prototype: "docs/grimo/ui/prototype/Skills Hub Publish Flow.html"
    status: "implemented"
    note: "Step 3 — publish result with frontmatter + risk scan summary"
    beam: ["Publish CTA"]

  - component: "PublishFailedPage"
    route: "/publish/failed"
    auth: true
    prototype: "docs/grimo/ui/prototype/Skills Hub Publish Failures.html"
    status: "implemented"
    note: "S199: State A first screen uses structured findings to show concrete SKILL.md cause, next step, and raw backend title."
    beam: ["Re-upload CTA (State A)", "View security report CTA (State B)"]

  - component: "AnalyticsPage"
    route: "/analytics"
    auth: true
    prototype: "docs/grimo/ui/prototype/Skills Hub Analytics.html"
    status: "implemented"
    beam: []
    note: "Metric cards must not use BeamFrame."

  - component: "MySkillsPage"
    route: "/my-skills"
    auth: true
    prototype: "docs/grimo/ui/prototype/Skills Hub My Skills.html"
    status: "implemented"
    beam: ["Publish new skill CTA"]

  - component: "FlagsQueuePage"
    route: "/flags"
    auth: true
    role: admin/reviewer
    prototype: "docs/grimo/ui/prototype/Skills Hub Admin Review.html"
    status: "implemented as reviewer flag queue"
    beam: []
    note: "Production route is /flags. Prototype name still says Admin Review; /admin/review is not routed."

  - component: "CollectionsPage"
    route: "/collections"
    auth: true
    prototype: "docs/grimo/ui/prototype/Skills Hub Collection.html"
    status: "implemented"
    beam: []

  - component: "CollectionDetailPage"
    route: "/collections/:id"
    auth: true
    prototype: "derived detail page; no standalone prototype"
    status: "implemented"
    beam: []

  - component: "RequestBoardPage"
    route: "/requests"
    auth: true
    prototype: "docs/grimo/ui/prototype/Skills Hub Request Board.html"
    status: "implemented"
    beam: []
    note: "S196 implemented: page has two primary tabs — `瀏覽需求` and `我要開需求`; create uses an inline create form; browsing keeps voting, detail links, and comments."

  - component: "RequestDetailPage"
    route: "/requests/:id"
    auth: true
    prototype: "derived detail page; no standalone prototype"
    status: "implemented"
    beam: []
    note: "S200 implemented: header meta renders requesterDisplayName/requesterHandle via getDisplayName(...); missing display data shows only the date and never renders raw requesterId."

  - component: "NotificationsPage"
    route: "/notifications"
    auth: true
    prototype: "docs/grimo/ui/prototype/Skills Hub Notifications.html"
    status: "implemented"
    beam: []

  - component: "YourFirstSkillPage"
    route: "/docs/your-first-skill"
    auth: true
    prototype: "docs/grimo/ui/prototype/Skills Hub Docs.html"
    status: "implemented"
    beam: ["Upload your bundle CTA at article end"]

  - component: "docs/* (OverviewPage, RiskTiersPage, SkillMdSpecPage, FrontmatterPage, BundleStructurePage, UploadValidatePage, VersioningPage, SemanticSearchPage, RestApiPage, EventPayloadPage, RiskScannerScopePage)"
    routes: ["/docs", "/docs/:slug"]
    auth: true
    prototype: "docs/grimo/ui/prototype/Skills Hub Docs.html"
    status: "implemented"
    note: "/docs redirects to /docs/overview."
    beam: []

  - component: "GroupsPage"
    route: "/groups"
    auth: true
    prototype: "no standalone UI prototype; follows app shell, table, and tree controls"
    status: "implemented"
    beam: []

  - component: "AuthDebugPage"
    route: "/auth-debug"
    auth: false
    prototype: "dev-only; no production UI prototype"
    status: "implemented"
    note: "Dev-only — shows OAuth token info; only meaningful with real-oauth backend profile"
    beam: []

  - component: "Security risk UI prototypes"
    routes: ["inline in publish/review/security/report contexts"]
    auth: true
    prototypes:
      - "docs/grimo/ui/prototype/Skills Hub Security Risk Lights UI.html"
      - "docs/grimo/ui/prototype/Skills Hub Security Report Issue Code UI.html"
      - "docs/grimo/ui/prototype/Skills Hub Security Reason UI.html"
    status: "reference prototypes for risk reason/report UI, not standalone routes"
    beam: []

# ── Risk System ─────────────────────────────────────────────────────────────
risk:
  tiers:
    none:
      condition: "No scripts/ directory AND no capability declarations AND 0 findings"
      publish: "Immediate auto-publish"
      color: green
      note: "Does not mean certified safe — scanner only checks known patterns"

    low:
      condition: "Capability declared but 0 dangerous findings, OR findings all LOW"
      publish: "Immediate auto-publish"
      color: blue

    medium:
      condition: "scripts/ present, 1–2 MEDIUM findings, no HIGH"
      publish: "Immediate with warning badge visible to installers"
      color: amber

    high:
      condition: "Any HIGH finding"
      publish: "Blocked — enters human review queue (/flags)"
      color: red

  key_rules:
    - id: "rule:rce.curl-pipe-bash"
      severity: HIGH
      pattern: "curl ... | bash"
    - id: "rule:shell.rm-rf"
      severity: HIGH
      pattern: "rm -rf on user directories"
    - id: "rule:sensitive.path.aws-credentials"
      severity: HIGH
      pattern: "~/.aws/credentials"
    - id: "rule:sensitive.path.ssh"
      severity: HIGH
      pattern: "~/.ssh/"
    - id: "rule:shell.chmod-777"
      severity: MEDIUM
      pattern: "chmod 777"
    - id: "rule:net.external-domain"
      severity: INFO
      pattern: "External URLs in scripts"

# ── Domain Events ────────────────────────────────────────────────────────────
events:
  - SkillCreated
  - SkillBundleExtracted
  - SkillFrontmatterValidated
  - SkillRiskScanStarted
  - SkillRiskAssessed         # { level: none|low|medium|high, findings[] }
  - SkillVersionPublished
  - SkillDownloaded
  - SkillFlagged
  - SkillReviewCompleted      # { action: approve|reject|request-changes, note }
  - SkillVersionDeprecated

# ── Do's & Don'ts ────────────────────────────────────────────────────────────
rules:
  do:
    - "Use BorderBeam on exactly ONE primary action per visible viewport"
    - "Show event names in chrome — SkillVersionPublished, SkillRiskAssessed, etc."
    - "Use mono font for: version numbers, event names, rule IDs, file paths"
    - "Disable (not hide) blocked actions — grayed Publish button next to Submit-for-review"
    - "Animate the pulse dot only for genuinely active states (pending, scanning)"
    - "Use ✦ (Source Serif Pro italic) only for AI-inference moments"
    - "Distinguish empty-state tones: seeding ≠ no-results ≠ all-clear"
    - "Keep risk palette strictly separate from category palette"
    - "Keep quality-status-indicator colors scoped to Quality tab score states"
    - "Map frontend/design/infra categories to cat-infra token (same blue)"

  dont:
    - "Add drop shadows — ever"
    - "Use gradients except the BorderBeam and the two named hero glows"
    - "Put beam on metric cards, nav, sidebar, or secondary buttons"
    - "Mix risk colors (green/amber/red) with category color chips"
    - "Use purple ScoreDot semantics for Quality tab full scores; full quality status is green `滿分 3/3`"
    - "Use serif font anywhere except ✦ sparkle"
    - "Show more than one beam element simultaneously"
    - "Write marketing copy in product chrome ('unlock your potential' etc.)"
    - "Invent new risk pill colors — only none/low/medium/high/pending/published/verified/draft/suspended"
    - "Use shadows to create depth — use tonal layers instead"
    - "Treat NONE and LOW risk as the same — NONE has no capability, LOW has capability declared"
