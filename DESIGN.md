---
version: beta
name: Skills Hub
description: >
  Enterprise-internal AI agent skills registry. UI balances developer-tool
  transparency (event names exposed, risk rule IDs visible, similarity scores
  shown) with modern dark-mode craft. Built for engineers who read changelogs
  but appreciate intentional design.

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
  accent-text:  "#C9C5F2"

  # Success — low risk / approved / pass / growth
  green:        "#1D9E75"
  green-soft:   "rgba(29,158,117,0.14)"
  green-text:   "#6FD8B0"

  # Warning — medium risk / pending / caution
  amber:        "#EF9F27"
  amber-soft:   "rgba(239,159,39,0.14)"
  amber-text:   "#FAC775"

  # Danger — high risk / rejected / blocked / flag
  red:          "#E24B4A"
  red-soft:     "rgba(226,75,74,0.14)"
  red-text:     "#F2A6A6"

  # Info — links / secondary brand / k8s-infra
  blue:         "#378ADD"
  blue-soft:    "rgba(55,138,221,0.14)"
  blue-text:    "#B0D5F2"

  # Category palette — identity, never severity
  # DevOps=purple, Testing=teal, Docs=coral, Data=amber, Security=pink, Frontend=blue
  cat-devops:       "rgba(127,119,221,0.18)"
  cat-devops-text:  "#C9C5F2"
  cat-testing:      "rgba(29,158,117,0.18)"
  cat-testing-text: "#9FE1CB"
  cat-docs:         "rgba(226,75,74,0.18)"
  cat-docs-text:    "#F2A6A6"
  cat-data:         "rgba(239,159,39,0.18)"
  cat-data-text:    "#FAC775"
  cat-security:     "rgba(217,56,138,0.18)"
  cat-security-text:"#DDB0E9"
  cat-frontend:     "rgba(55,138,221,0.18)"
  cat-frontend-text:"#B0D5F2"

typography:
  # All HTML pages import Inter + JetBrains Mono from Google Fonts
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
    A 5-stop colorful conic-gradient ring rotates around the border of
    exactly ONE element per visible viewport. Scarcity is what makes it
    meaningful.

  params:
    colorVariant: "colorful"   # purple→pink→amber→green→blue
    duration: 1.96             # seconds per rotation
    strength: 0.7              # visible arc ~163° out of 360°
    thickness: 1.5             # px padding around inner element
    blur: true                 # ambient glow halo (filter:blur 10px, opacity 0.5)

  gradient: >
    conic-gradient(from 0deg,
      transparent 0deg, transparent 197deg,
      rgba(127,119,221,.95) 230deg,
      rgba(217,56,138,.95)  268deg,
      rgba(239,159,39,.95)  300deg,
      rgba(29,158,117,.95)  332deg,
      rgba(55,138,221,.95)  360deg,
      transparent 360deg)

  allowed_surfaces:
    - Hero search bar (Landing, Homepage)
    - Primary CTA button — one per page
    - FileDropZone (Publish Step 1)
    - Featured / top-match skill card (Homepage)

  forbidden:
    - Metric cards
    - Nav items or topbar
    - Sidebar rows
    - Secondary or ghost buttons
    - Any decorative container

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
    height: 52px
    padding: "0 22px"
    background: "rgba(8,8,10,.95)"
    border-bottom: "0.5px solid line"
    logo: "28×28px gradient circle — purple→pink→amber"

  topbar-publish-btn:
    treatment: BorderBeam
    inner: "background #EEECEA, color #08080A"
    note: "Beam on Publish button in topbar — the one primary action per app"

  card:
    background: bg-2
    border: "0.5px solid line"
    radius: xl (16px)
    padding: "14–18px"

  card-featured:
    treatment: BorderBeam
    background: bg-3
    inner-radius: "calc(xl - 1.5px)"
    note: "Wraps normal card inner. Used for top search result only."

  input:
    background: bg-2
    border: "0.5px solid line-2"
    radius: md
    padding: "9px 12px"
    focus: "border-color rgba(127,119,221,.5), box-shadow 0 0 0 2px rgba(127,119,221,.08)"

  search-hero:
    treatment: BorderBeam
    shape: pill
    inner: "background bg-2"
    mode-toggle: "Semantic (accent dot) / Keyword"
    note: "Reserved for Landing and Homepage hero only"

  risk-pill:
    shape: pill
    padding: "2px 8px"
    low:    { bg: green-soft,  text: green-text }
    medium: { bg: amber-soft,  text: amber-text }
    high:   { bg: red-soft,    text: red-text   }

  status-pill:
    published:  { bg: green-soft,   text: green-text  }
    pending:    { bg: amber-soft,    text: amber-text  }
    draft:      { bg: "rgba(255,255,255,.06)", text: ink-3 }
    verified:   { bg: blue-soft,     text: blue-text   }

  beam-btn:
    background: "#EEECEA"
    color: "#08080A"
    padding: "8–11px 16–22px"
    radius: "calc(md - 1.5px)"
    note: "Inner element of .beam-wrap. Always light on dark."

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
    radius: "8–12px depending on size"
    sizes: { sm: 24px, md: 30px, lg: 36px, xl: 52px }
    colors: "category palette — never risk palette"

  sparkline:
    up: green (1D9E75)
    down: red (E24B4A)
    width: "60px, 14 data points"

  similarity-bar:
    height: 3px
    bg: "rgba(255,255,255,.06)"
    fill: accent (fades to 15% opacity at 0.55 score)

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
  - file: "Skills Hub Landing.html"
    route: "/"
    auth: false
    beam: ["hero search bar", "Browse CTA", "final CTA"]

  - file: "Skills Hub Homepage.html"
    route: "/browse"
    auth: true
    beam: ["hero search bar (full-width)", "featured skill card"]

  - file: "Skills Hub Skill Detail.html"
    route: "/skills/:author/:name"
    auth: true
    beam: ["Download CTA"]

  - file: "Skills Hub Publish Step 1.html"
    route: "/publish/upload"
    auth: true
    beam: ["FileDropZone border"]

  - file: "Skills Hub Publish Step 2.html"
    route: "/publish/validate"
    auth: true
    beam: []  # scanning state — no action needed

  - file: "Skills Hub Publish Flow.html"
    route: "/publish/review"
    auth: true
    beam: ["Publish CTA"]

  - file: "Skills Hub Publish Failures.html"
    route: "/publish/failed"
    auth: true
    beam: ["Re-upload CTA (State A)", "Submit for review CTA (State B)"]

  - file: "Skills Hub Admin Review.html"
    route: "/admin/review"
    auth: true
    role: admin
    beam: ["Approve & publish CTA"]

  - file: "Skills Hub Analytics.html"
    route: "/analytics"
    auth: true
    beam: ["Downloads 30d metric card (featured)"]

  - file: "Skills Hub My Skills.html"
    route: "/my-skills"
    auth: true
    beam: ["Publish new skill CTA"]

  - file: "Skills Hub Collection.html"
    route: "/collections"
    auth: true
    beam: ["Install Collection CTA on featured bundle", "New Collection CTA"]

  - file: "Skills Hub Version Diff.html"
    route: "/skills/:author/:name/diff"
    auth: true
    beam: ["Install latest version CTA"]

  - file: "Skills Hub Request Board.html"
    route: "/requests"
    auth: true
    beam: ["Post a Request CTA", "Submit Request in form"]

  - file: "Skills Hub Onboarding.html"
    route: "/onboarding"
    auth: true
    beam: ["Continue / Get started CTA on each step"]

  - file: "Skills Hub Docs.html"
    route: "/docs/:slug"
    auth: true
    beam: ["Upload your bundle CTA at article end"]

  - file: "Skills Hub Empty States.html"
    route: "(embedded in relevant pages)"
    beam: ["Primary CTA in seeding state", "Publish CTA in new-author state"]

  - file: "Skills Hub Notifications.html"
    route: "/notifications"
    auth: true
    beam: []  # no primary action needed on this page

# ── Risk System ─────────────────────────────────────────────────────────────
risk:
  tiers:
    low:
      condition: "No scripts/ directory OR all scan checks pass"
      publish: "Immediate auto-publish"
      color: green

    medium:
      condition: "scripts/ present, 1–2 MEDIUM findings, no HIGH"
      publish: "Immediate with warning badge visible to installers"
      color: amber

    high:
      condition: "Any HIGH finding"
      publish: "Blocked — enters human review queue"
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
  - SkillRiskAssessed         # { level: low|medium|high, findings[] }
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

  dont:
    - "Add drop shadows — ever"
    - "Use gradients except the BorderBeam and the two named hero glows"
    - "Put beam on metric cards, nav, sidebar, or secondary buttons"
    - "Mix risk colors (green/amber/red) with category color chips"
    - "Use serif font anywhere except ✦ sparkle"
    - "Show more than one beam element simultaneously"
    - "Write marketing copy in product chrome ('unlock your potential' etc.)"
    - "Invent new risk pill colors — only low/medium/high/pending/published/verified/draft"
    - "Use shadows to create depth — use tonal layers instead"
