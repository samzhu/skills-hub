---
version: alpha
name: Skills Hub
description: An enterprise AI agent skills registry. The UI balances developer-tool transparency (event names exposed, technical metadata visible) with consumer-product warmth (rounded surfaces, generous whitespace, soft neutrals). Built for people who read changelogs but appreciate good craft.
colors:
  # Neutrals — warm off-white surface with deep ink text
  primary: "#181818"
  secondary: "#5C5C5C"
  tertiary: "#888780"
  surface: "#FFFFFF"
  surface-secondary: "#F5F4ED"
  border: "#E0DDD3"
  border-strong: "#C8C5BB"

  # Accent — AI, search, personalization, focus
  accent: "#7F77DD"
  accent-soft: "#EEEDFE"
  accent-mid: "#AFA9EC"
  accent-deep: "#3C3489"
  accent-text: "#26215C"

  # Info — secondary brand, links, k8s/infra category
  info: "#378ADD"
  info-soft: "#E6F1FB"
  info-deep: "#0C447C"

  # Success — low risk, approved, pass, growth
  success: "#1D9E75"
  success-soft: "#EAF3DE"
  success-mid: "#9FE1CB"
  success-deep: "#27500A"
  success-text: "#085041"

  # Warning — medium risk, pending review, caution
  warning: "#EF9F27"
  warning-soft: "#FAEEDA"
  warning-mid: "#FAC775"
  warning-deep: "#633806"
  warning-text: "#412402"

  # Danger — high risk, rejected, blocked, decline
  danger: "#E24B4A"
  danger-soft: "#FCEBEB"
  danger-mid: "#F7C1C1"
  danger-deep: "#791F1F"
  danger-text: "#501313"

  # Category palette — identity (not severity). Used for skill icons and category mix.
  category-devops: "#EEEDFE"
  category-devops-deep: "#3C3489"
  category-infra: "#E6F1FB"
  category-infra-deep: "#0C447C"
  category-testing: "#E1F5EE"
  category-testing-deep: "#085041"
  category-docs: "#FAECE7"
  category-docs-deep: "#712B13"
  category-data: "#FAEEDA"
  category-data-deep: "#633806"
  category-security: "#FBEAF0"
  category-security-deep: "#72243E"

typography:
  display:
    fontFamily: Inter
    fontSize: 36px
    fontWeight: 500
    lineHeight: 1.15
    letterSpacing: -0.01em
  headline-lg:
    fontFamily: Inter
    fontSize: 26px
    fontWeight: 500
    lineHeight: 1.2
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Inter
    fontSize: 22px
    fontWeight: 500
    lineHeight: 1.25
    letterSpacing: -0.005em
  headline-sm:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: 500
    lineHeight: 1.3
  title:
    fontFamily: Inter
    fontSize: 15px
    fontWeight: 500
    lineHeight: 1.35
  body-lg:
    fontFamily: Inter
    fontSize: 14.5px
    fontWeight: 400
    lineHeight: 1.7
  body-md:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: 400
    lineHeight: 1.55
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: 400
    lineHeight: 1.5
  label-md:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: 500
    lineHeight: 1.4
  label-caps:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: 500
    letterSpacing: 0.05em
  caption:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: 400
    lineHeight: 1.45
  mono-md:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: 400
    lineHeight: 1.75
  mono-sm:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: 500
  mono-xs:
    fontFamily: JetBrains Mono
    fontSize: 10px
    fontWeight: 500
    letterSpacing: 0.04em
  serif-italic:
    fontFamily: Source Serif Pro
    fontSize: 14px
    fontWeight: 400
    fontFeature: '"ital" 1'

rounded:
  none: 0
  xs: 3px
  sm: 4px
  md: 8px
  lg: 12px
  xl: 16px
  pill: 999px
  full: 9999px

spacing:
  base: 4px
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  xl: 22px
  2xl: 26px
  3xl: 40px
  4xl: 48px
  page-pad: 26px
  card-pad: 14px
  card-pad-lg: 16px

components:
  # Primary CTA — the only CTA that wears the beam border
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.md}"
    padding: 9px 16px
    typography: "{typography.body-md}"
  button-primary-hover:
    backgroundColor: "#000000"

  # Secondary action — neutral, no border-beam
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.primary}"
    rounded: "{rounded.md}"
    padding: 9px 16px
    typography: "{typography.body-md}"

  # Ghost — text-only, used for Back / Cancel / low-priority links
  button-ghost:
    backgroundColor: transparent
    textColor: "{colors.secondary}"
    padding: 8px 12px
    typography: "{typography.body-md}"

  # Destructive — reject, withdraw, decline
  button-destructive:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.danger-deep}"
    rounded: "{rounded.md}"
    padding: 8px 14px

  # Affirmative inline — accept-risk, mark-as-pass
  button-affirmative:
    backgroundColor: "{colors.success-soft}"
    textColor: "{colors.success-text}"
    rounded: "{rounded.md}"
    padding: 4px 10px

  # Pills — risk tier badges. Same shape, different color encoding.
  pill-low-risk:
    backgroundColor: "{colors.success-soft}"
    textColor: "{colors.success-deep}"
    rounded: "{rounded.pill}"
    padding: 2px 9px
    typography: "{typography.caption}"
  pill-medium-risk:
    backgroundColor: "{colors.warning-soft}"
    textColor: "{colors.warning-deep}"
    rounded: "{rounded.pill}"
    padding: 2px 9px
  pill-high-risk:
    backgroundColor: "{colors.danger-soft}"
    textColor: "{colors.danger-deep}"
    rounded: "{rounded.pill}"
    padding: 2px 9px
  pill-pending:
    backgroundColor: "{colors.warning-soft}"
    textColor: "{colors.warning-deep}"
    rounded: "{rounded.pill}"
    padding: 2px 9px
  pill-published:
    backgroundColor: "{colors.success-soft}"
    textColor: "{colors.success-text}"
    rounded: "{rounded.pill}"
    padding: 2px 9px
  pill-verified:
    backgroundColor: "{colors.info-soft}"
    textColor: "{colors.info-deep}"
    rounded: "{rounded.pill}"
    padding: 2px 9px

  # Card — the primary content surface
  card:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.lg}"
    padding: "{spacing.card-pad-lg}"
  card-featured:
    backgroundColor: "{colors.border}"
    rounded: "{rounded.lg}"
    padding: 1px
  card-callout-info:
    backgroundColor: "{colors.accent-soft}"
    textColor: "{colors.accent-text}"
    rounded: "{rounded.md}"
    padding: 12px 14px
  card-callout-warn:
    backgroundColor: "{colors.warning-soft}"
    textColor: "{colors.warning-text}"
    rounded: "{rounded.md}"
    padding: 12px 14px
  card-callout-danger:
    backgroundColor: "{colors.danger-soft}"
    textColor: "{colors.danger-text}"
    rounded: "{rounded.md}"
    padding: 10px 12px

  # Inputs
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.primary}"
    rounded: "{rounded.md}"
    padding: 8px 12px
    typography: "{typography.body-md}"
  input-search-beam:
    backgroundColor: "{colors.border}"
    rounded: "{rounded.lg}"
    padding: 1.5px

  # Skill icon — square tile that holds initials per category
  icon-tile-md:
    rounded: "{rounded.md}"
    size: 30px
  icon-tile-lg:
    rounded: "{rounded.md}"
    size: 40px
  icon-tile-xl:
    rounded: "{rounded.lg}"
    size: 52px

  # Code block
  code-block:
    backgroundColor: "{colors.surface-secondary}"
    textColor: "{colors.primary}"
    rounded: "{rounded.md}"
    padding: 12px 14px
    typography: "{typography.mono-md}"
---

# Skills Hub Design System

## Overview

Skills Hub is the UI for an enterprise AI agent skills registry. The audience is internal — engineers publishing reusable SKILL.md bundles for their team and platform leads governing what's safe to install. They read changelogs, run terminals, and live in monospace, but they appreciate craft. The design walks a deliberate line: **developer-tool transparency without admin-tool ugliness**.

Three convictions shape every screen:

**Show the machinery.** When a button writes a `SkillVersionPublished` event, the UI says so. Loading states reveal what's running. Risk findings include rule IDs. Search results show similarity scores. This isn't ornamentation — it's how engineers build trust in a system. Marketing language ("unlock your potential") is forbidden; technical language ("Embeddings via Gemini · 0.94 similarity") is welcome.

**Severity uses color, identity uses color, but never the same colors.** The risk tier system (green/amber/red) and the category system (six neutral-warm tints) are kept strictly disjoint. A purple skill icon never means "high risk" — purple is the DevOps category. An amber pill never means "data category" — amber means "pending review." Mix them up and the entire visual language collapses.

**Every primary action wears the beam.** A single animated conic gradient runs along the border of every page's main CTA — Browse, Publish, Approve, Continue, Download. Nothing else gets it. This is the one piece of motion the whole product carries, and it stays scarce so it stays meaningful.

The product feels closer to Linear or Vercel than to Salesforce — flat surfaces, hairline borders, generous whitespace, no drop shadows, no skeuomorphic depth.

## Colors

The palette has three layers: **neutrals** that build the canvas, **semantic colors** that encode meaning, and a **category palette** that distinguishes content type without judgment.

The neutrals lean warm. The surface is pure white but the secondary surface (`#F5F4ED`) is a soft limestone — warmer than gray, cooler than cream. Text uses near-black `#181818` rather than pure black to soften the contrast against the warm surface. Borders are hairlines (0.5px) in `#E0DDD3` — visible enough to define a card edge, quiet enough to disappear in dense layouts.

The semantic system has four roles, each with the same internal structure (soft background → mid → deep → text):

- **Accent** (`#7F77DD` purple) is the AI / personalization signal. It marks semantic search, intent inference, the live-pulse dot, the onboarding sparkle, and selected states throughout. When a user sees purple, the meaning is "the system is reasoning about you."
- **Success** (`#1D9E75` green) is low-risk skills, approved decisions, passing scans, growth metrics, and the "all clear" tone. Used for both data (sparklines trending up) and decisions (Accept risk button).
- **Warning** (`#EF9F27` amber) is the medium-risk tier and the pending-review state. The pulse animation on a pending dot is amber. Where green says "go" and red says "stop," amber says "this needs a human eye."
- **Danger** (`#E24B4A` red) is high-risk findings, blocked publishes, rejected decisions, and declining metrics. Used sparingly — when red appears, the user should look.

The **category palette** uses six muted tints (`#EEEDFE` to `#FBEAF0`) paired with darker text colors. These are stable per-category — DevOps is always purple, Testing is always teal, Documentation is always coral. A user learns the mapping once and uses it forever as a wayfinding shortcut. The category palette is intentionally desaturated; it must never compete with the semantic palette for attention.

## Typography

Inter for everything except code and the AI sparkle.

The type scale is conservative. Display (36px) appears once per page, on hero headlines. Headline-md (22px) is the standard page title. Body-md (13px) is the default for chrome — buttons, navigation, table rows. Body-lg (14.5px) is reserved for prose-heavy reading like docs and intro paragraphs. The product never goes smaller than caption (11px); anything smaller becomes hard to scan and triggers low-density alarm.

**Mono is functional, not decorative.** JetBrains Mono is used for: version numbers (`v2.1.0`), event names (`SkillVersionPublished`), rule IDs (`rule:rce.curl-pipe-bash`), file paths (`scripts/bootstrap.sh`), code blocks, and inline `<code>` references. Monospace inside running prose signals "this string has technical meaning — copy it exactly." Mono-xs in label-caps style is used on metric labels in dashboards.

**One serif italic, one purpose.** Source Serif Pro italic is used exclusively for the AI sparkle character (`✦`) on the AI intent card, the onboarding greeting, and empty-state preview boxes. It's the only place in the entire system where a serif appears, and it consistently signals "the system is doing something thoughtful here." Don't use it anywhere else.

Letter spacing is tight on display sizes (-0.01em) and slightly open on label-caps (0.05em uppercase). Line height is generous on body-lg (1.7) for docs reading and tighter on body-md (1.55) for UI chrome.

## Layout

The product runs on a desktop-first 1024–1280px canvas with a 26px page padding and 12–14px gaps between cards. Density is high but not punishing — comparable to Linear's issue list, lighter than a SQL admin tool.

**Typical page anatomy:** thin top bar (12–14px vertical pad, hairline border-bottom) → page hero with headline + meta + range/CTA on the right (22–28px vertical pad) → optional metric strip → main content. Pages with sidebars use a 168–248px left rail (docs uses 168, admin queue uses 248) and the rest fluid.

**Card vs section.** Cards have hairline borders and 14–16px padding. Sections inside a card use 0.5px dividers, never additional cards (avoid the "Russian doll" of nested cards). Inside reading content (docs, callouts), use horizontal dividers between subsections rather than wrapping each in its own card — the page should read like a document, not a stack of widgets.

**Spacing scale is 4px-based.** xs (4) for inline gaps inside chips, sm (8) for icon-to-text, md (12) for related controls, lg (16) for grouped sections, xl (22) for between-section breathing room, 2xl (26) for page padding, 3xl–4xl for hero zones. Don't invent values between scale steps.

## Elevation & Depth

There are no drop shadows in this product. Hierarchy is conveyed through three other techniques, in order of preference:

1. **Hairline borders** (0.5px in `border` color). The card sits a single pixel above its background. This is the default lift.
2. **Tonal layers.** A page on warm white surface (`#FFFFFF`) with sections on `#F5F4ED` creates depth without shadow. Cards stay white inside grey sections; reverse for inverted moments.
3. **Beam border animation.** Reserved for primary CTAs and featured cards. A conic-gradient ring rotates along the outer 1.5px padding of the element, with the gradient stops set so only a portion (~60° of arc) is visible at a time. The animation runs at 4–5s per rotation. This is the **only** ambient motion in the product. Implementation: outer wrapper with `padding: 1.5px` and a rotating conic-gradient `::before` pseudo-element; inner content sits on `surface` with `border-radius` adjusted by the padding amount.

A featured radial glow appears in two places: the landing-page hero (faint purple/green ellipses at `rgba(127, 119, 221, 0.06)` and `rgba(29, 158, 117, 0.04)`) and the final CTA section. These are the only gradient surfaces in the product. Everywhere else uses flat fill.

## Shapes

The shape language is **soft-but-precise**: rounded corners that are unmistakable but never bouncy. Four common values:

- **`xs` (3px)** — small inline pills like `rule:` badges and severity tags inside code blocks
- **`sm` (4px)** — code blocks, kbd hints, internal pills inside cards
- **`md` (8px)** — buttons, inputs, callouts, code-block containers, icon tiles
- **`lg` (12px)** — main cards, panels, hero containers, sidebars
- **`pill` / `full` (999px+)** — status pills (Low / Med / High / Pending / Verified / Published) and tag chips

The radius increases with the size of the element. A button (`md`) sits inside a card (`lg`) sits inside a page frame (`lg`). This creates a subtle nesting where larger surfaces are softer than smaller ones — the opposite of what naive systems do.

**Tilted card pattern.** The landing-page hero uses 4 stacked cards with light rotations (-0.8°, 0°, +0.5°, -0.3°) and small horizontal offsets (-12px to +8px). One of the four wears the beam border to anchor the eye. This is the only place tilted cards appear; everywhere else cards are perfectly axis-aligned.

## Components

Components are organized into four families. Each family has one canonical visual treatment; variants change color/state but never structure.

### Buttons

Three roles: **primary**, **secondary**, **ghost**. Primary is the only one with the beam-border treatment, and there should be at most one primary button per visible viewport. Secondary uses a 0.5px border around white surface. Ghost has no fill or border — use it for back / cancel / "skip this step." Two specialized roles exist: **destructive** (red border on white, used for Reject / Withdraw) and **affirmative** (soft green fill, used for Accept-risk on individual findings).

### Pills

Three risk tiers, two state pills, one verified pill. All share the same shape (`pill` rounded, 2px–9px padding, caption-sized text) and only differ in color encoding. Risk pills use the soft/deep semantic pair. The pending pill animates a 1.8s ease-in-out pulse on its leading dot to signal "actively waiting" — without animation, a pending state feels indistinguishable from "stuck."

### Cards

The base card has hairline border, 14–16px padding, and `lg` rounded corners. Two specialized variants: **featured card** wraps the standard card in a 1px gradient padding for the rotating beam ring, used to highlight one item per surface (the top match in semantic search, the recommended skill on the homepage). **Callout cards** use semantic soft fills (accent-soft for info, warning-soft for warnings, danger-soft for errors) with matching deep-text color and a 12px padding tighter than a regular card.

### Inputs

Search inputs come in two flavors: **inline** (5px padding, secondary surface, used in compact filter bars) and **hero search-beam** (12px padding, white surface, wrapped in the beam-border treatment). The hero search bar is reserved for the homepage and the dedicated search page. A semantic/keyword toggle pill sits on the right side of every search input, with the active mode getting a small purple dot to reinforce "this is in AI mode."

### Icon Tiles

Square tiles that hold 2-letter initials, sized at `md` (30px), `lg` (40px), or `xl` (52px). Tile color comes from the category palette (DevOps purple, Testing teal, etc.) — never from the risk palette. The tile size scales with the importance of the surface: `md` for list rows, `lg` for header chrome, `xl` for detail-page heroes.

### Stepper

A horizontal sequence of dots connected by hairline lines. Three states: **done** (green-soft fill with green-text checkmark), **active** (filled black with white text), **future** (white with grey border, plain number). The connecting lines mirror the state of the right-hand step: the line into a "done" step is mid-green; into a "fail" step is mid-red; into a "warn" step is mid-amber. Used in onboarding and publish flows.

### Activity Feed Row

A 6px colored leading dot + content + right-aligned relative time (mono, tabular-nums). The dot color encodes event type: green = published, red = flagged, purple = created, amber = review-action. This pattern repeats anywhere `domain_events` are surfaced — admin queue, analytics dashboard, audit logs.

## Do's and Don'ts

### Do

- **Do use the beam border on exactly one primary CTA per surface.** It loses its meaning the moment a second one appears.
- **Do expose technical surface in chrome.** Show event names in success messages (`SkillCreated event written`), show similarity scores on search results (`0.94`), show rule IDs on findings (`rule:rce.curl-pipe-bash`).
- **Do treat empty states as their own UX problem.** A fresh-platform empty state (seeding tone) is fundamentally different from a no-search-results empty state (redirecting tone) and an all-clear queue (celebratory tone). One template doesn't fit all.
- **Do animate the live-pulse dot for genuinely-active states.** Pending review, live data feeds, and "system is thinking" use the 1.8–2s pulse. Don't animate things that aren't actually moving.
- **Do use mono inside running prose for technical strings.** Path names, command names, version numbers, and event names all signal "copy this verbatim."
- **Do disable, don't hide, blocked actions.** A grayed-out Publish button next to a Submit-for-review button tells users "the system isn't allowing this path right now" — hiding it makes them wonder if they're using the product wrong.
- **Do use the warm secondary surface (`#F5F4ED`) under content cards** to create tonal depth instead of shadows.
- **Do show three suggestions on no-results screens, ranked by likelihood of usefulness.** Retry similar query → fallback to browse → request from team → publish it yourself.

### Don't

- **Don't add drop shadows or skeuomorphic depth.** Every shadow opens a slippery slope toward 2014-era SaaS UI. Use borders and tonal layers instead.
- **Don't mix the risk palette and the category palette in the same metaphor.** A purple "high risk" pill or a red "DevOps" icon will silently destroy the entire color system's legibility.
- **Don't use gradients except for the beam border and the two named hero glows.** Flat fills only. The beam earns its specialness by being almost the only animated/gradient element.
- **Don't write marketing copy in chrome.** "Unlock your potential" / "Empower your team" — these belong in nobody's product. Use functional prose with concrete verbs.
- **Don't put more than two emojis or sparkles on a screen.** The serif `✦` sparkle is reserved for AI moments. Don't garnish other surfaces with it.
- **Don't make Cancel / Back buttons compete with primary CTAs.** Cancel is always ghost or text-link. The primary action keeps the beam.
- **Don't stack callouts.** If a section needs three callouts, restructure the section. Stacked callouts read as "the system is yelling."
- **Don't invent new pill colors.** The seven defined pills (low/med/high risk + pending + published + verified + draft) cover every status the product needs. Adding a new color here means adding a new mental model for the user.
- **Don't break the section order in DESIGN.md updates.** The canonical Overview → Colors → Typography → Layout → Elevation → Shapes → Components → Do's order matters for agent parsing.
- **Don't use serif fonts anywhere except the `✦` sparkle character.** A second serif role would turn the sparkle from "AI signal" into "decoration."
- **Don't show specific numbers in disordered-data states.** When data is loading or null, prefer `—` over `0` — zeros mislead.