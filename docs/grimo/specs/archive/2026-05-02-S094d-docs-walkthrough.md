# S094d — Docs Walkthrough page (`/docs/your-first-skill`)

> **Status**: shipped
> **Type**: META S094 sub-spec 2/4 (XS, pure FE, post-S094c)
> **Estimate**: XS / 5 pts
> **Source**: `docs/grimo/ui/prototype/docs_page_write_your_first_skill.html` + `docs/grimo/ui/README.md` ll.245-279 + DESIGN.md

## §1 Goal

Skills Hub 第一份開發者 docs entry — 把 frontmatter validation / semantic search indexing / risk tier classification 三個核心機制在一頁內讓作者建立心智模型（per README ll.245-279 「整個 docs 頁其實做了一件比純 reference 更重要的事」）。Landing page / empty states / onboarding 都會 link 來這頁。

對齊 prototype 的 6 個 main sections + sidebar IA + meta row。

## §2 Approach

**Markdown parser vs hand-rolled JSX**：

| Approach | Pros | Cons |
|----------|------|------|
| (A) `react-markdown` + `.md` content | 內容容易編輯（純 markdown）；未來多 docs 頁面 reuse | 增 ~30KB gz dep；單頁靜態內容 over-engineer；styling 需 plugin/customizer |
| ⭐ (B) Hand-rolled JSX with helper components | 0 dep；inline lint；JSX 與 component reuse 一致；單頁 lean | 內容變更需動 code（contributor friction） |

選 (B) — XS scope 不引 dep；僅單頁（Overview / SKILL.md spec / 等其他 docs 都是 future），未來真有多頁時再評估 markdown parser。Helper components（`H2`, `P`, `Code`, `CodeBlock`, `Callout`, `FieldCard`, `CompareCard`, `RiskRow`）皆 inline 在 page 檔，不抽 shared module（YAGNI；下一頁再考慮）。

**Sidebar IA**：保留 prototype 的 4 group 完整結構（Getting started / Reference / Publishing / API），但只「Your first skill」是 active link，其他 placeholder（dimmed / 不可點 / `title="Coming soon"`）。理由：使用者一眼看到完整 docs IA 預期 — 即使內容未填，discovery 路徑明確。

**AppShell nav 加「文件」link**：path `/docs/your-first-skill`（指第一篇 walkthrough；未來 `/docs` 改 index 頁時更新）。

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | Render title + breadcrumbs + meta row | "Write your first skill" + "Getting started" + "5 min read" + "agentskills.io v1.2" 全顯 |
| AC-2 | 6 main sections renders with anchor heading | `# minimum / bundle / required / description / risk` H2 sections + final CTA block 全在 |
| AC-3 | 3 risk tiers (LOW/MEDIUM/HIGH) | 各帶 semantic 顏色 pill + 解說 |
| AC-4 | Required fields (name + description) | FieldCard ×2，required tag ×2，分別 64 / 1024 char limit |
| AC-5 | Upload your bundle CTA → /publish | Link to `/publish` route |
| AC-6 | AppShell nav 加「文件」 link | 在 navbar visible |
| AC-7 | Frontend tests 23 → 28 PASS | 5 new YourFirstSkillPage tests |
| AC-8 | Build size 不超 380KB JS | budget guard |

## §4 Implementation

```
frontend/src/
├── pages/docs/
│   ├── YourFirstSkillPage.tsx      ← page content (~280 LOC JSX + helpers)
│   └── YourFirstSkillPage.test.tsx ← 5 vitest cases
├── components/
│   ├── DocsLayout.tsx              ← AppShell + sidebar + 680px main col
│   └── DocsSidebar.tsx             ← 4 IA groups; only active page is link
├── App.tsx                         ← + Route /docs/your-first-skill
└── components/AppShell.tsx         ← + 「文件」 nav link
```

Helper components inline 在 `YourFirstSkillPage.tsx`：`Dot` / `H2` / `P` / `Code` / `CodeBlock` / `Callout` / `FieldCard` / `CompareCard` / `RiskRow`。每個約 5-15 LOC，符合 single-page scope；不抽出 module（YAGNI）。

## §5 Test plan

- `npm test` — 預期 23 → 28 PASS（5 new test：title+meta / 6 sections / 3 risk tiers / fields / CTA link）
- `npm run build` — 預期 ≤ 380KB JS（既有 358KB + 約 +15KB JSX page + helpers）
- Smoke: navigate `/docs/your-first-skill` → 視覺檢查 sections 完整；點「文件」nav → highlight；CTA「Upload your bundle」→ `/publish`

## §6 Verification

實際結果 §7。

## §7 Result

- **Frontend tests**: 23 → 28 PASS / 0 fail（5 new YourFirstSkillPage tests AC-1/2/3/4/5）
- **JS bundle**: 358 → 372KB (+14KB；JSX page + 9 inline helpers + lucide icons)
- **CSS bundle**: 35.1 → 36.7KB (+1.6KB；DESIGN.md hex tokens inline)
- **Build time**: 166ms（無 regression）
- **Components shipped**:
  - `DocsLayout.tsx` — reusable for future docs pages（sub-spec extension）
  - `DocsSidebar.tsx` — 4 IA groups full structure, 只 active 一條為 link
  - `YourFirstSkillPage.tsx` — single-page content
- **AC coverage**: AC-1/2/3/4/5 verified by vitest; AC-6 manual nav check; AC-7/8 build pass
- **Navigation entry**: AppShell `文件` link 加進 navLinks; `/docs/your-first-skill` route 已 wire to App.tsx

ship as **v2.70.0** (M88b / META S094 sub-spec 2/4 完成)。

**META status**: 4 sub-specs progress 2/4 ✓ — next ship S094a My Skills Author Dashboard (M / backend + FE; first sub-spec needs backend touch).
