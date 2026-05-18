# Automation Prompt - Development Loop

將以下內容貼到 Codex App project automation。建議設定：

- Project: `/Users/samzhu/workspace/github-samzhu/skills-hub`
- Execution environment: `worktree`
- Cadence: active development 可用 10-30 minutes；一般維護可用 daily。
- Model / reasoning effort: default coding model；大型 spec 再調高。

```text
This wake-up is one development loop tick for /Users/samzhu/workspace/github-samzhu/skills-hub.

Use Codex App background worktree execution. This automation is a development orchestrator, not a production site audit and not a deployment runner.

Read and follow:
1. AGENTS.md
2. .codex/loop.dev.md
3. docs/grimo/PRD.md
4. docs/grimo/specs/spec-roadmap.md
5. docs/grimo/CHANGELOG.md

This automation must treat repo files as the only state. Do not reuse the previous tick's suggested NEXT_SKILL unless the current repo snapshot still proves it.

Canonical workflow:
`$defining-product` → `$planning-project` → `$planning-spec SNNN`
→ `$planning-tasks SNNN` ↔ `$implementing-task`
→ `$verifying-quality SNNN`
→ `$shipping-release SNNN`

`local implementation PASS` / task PASS means the next workflow step is `$verifying-quality SNNN`, not `$shipping-release SNNN`. Choose `$shipping-release SNNN` only after QA PASS / local release PASS / ready-to-ship evidence exists.

After the first reads, take a fresh development-state snapshot before choosing NEXT_SKILL:
- re-read docs/grimo/specs/spec-roadmap.md Active / planned rows for status changes such as a spec moving from in-design to Plan / in-progress;
- list docs/grimo/tasks/ and match task files back to their SNNN spec;
- read the matching active spec doc when roadmap or task files mention an active SNNN.

Before selecting any new planned or active spec, run the Release Completeness Gate from .codex/loop.dev.md:
- scan root docs/grimo/specs/*.md, docs/grimo/tasks/, docs/grimo/CHANGELOG.md, docs/grimo/specs/spec-roadmap.md, and git tags;
- if a spec has QA PASS / local release PASS / ready-to-ship evidence but is not archived, still has task files, has no CHANGELOG release entry, has no roadmap shipped/archived row, or is missing the expected release tag, choose `$shipping-release SNNN`;
- if a spec only has local implementation PASS, task PASS, ⏳ QA, independent QA pending, or next `$verifying-quality SNNN`, choose `$verifying-quality SNNN`;
- do not move to a later planned spec until that older release-ready spec is archived by `$shipping-release`.

Do exactly one development unit:
- infer exactly one NEXT_SKILL from repo files using .codex/loop.dev.md;
- invoke that `$skill-name` explicitly;
- verify the smallest relevant result;
- commit only this tick's own changes when safe.

Do not inspect the production site in this loop.
Do not create production bug findings in this loop.
Do not deploy to production in this loop.
Do not modify docs/grimo/site-audit/** unless the user explicitly asks to convert a finding into a dev spec.
Do not execute specs marked Status: Auto-Draft or automation_status: auto-draft. These are site-audit drafts and require human approval before dev-loop may plan or implement them.

If running in a Codex background worktree, do not block ordinary implementation on Local checkout dirty files. Only run Dirty Overlap Gate before merge, release, deploy, or handoff back to Local, exactly as described in .codex/loop.dev.md.
Do not delete the current Codex App execution worktree. If this tick creates any additional child worktree, clean it up before exit according to .codex/loop.dev.md.
Treat docs/grimo/specs/spec-roadmap.md as a shared roadmap ledger. Normal roadmap/task/spec additions are not a runtime blocker for $shipping-release; follow .codex/loop.dev.md Shared Roadmap Policy instead of returning BLOCKED solely because roadmap is dirty.

Never stage or commit unrelated user changes. If repo files conflict about NEXT_SKILL, report the conflicting file paths and return BLOCKED instead of guessing.

End with exactly one EXIT label from .codex/loop.dev.md: DONE, WIP, BLOCKED, or WALL-HIT.
Return DONE only when the Release Completeness Gate is clean after this tick. If a spec is QA PASS / local release PASS and still needs `$shipping-release`, return WIP or BLOCKED and name `$shipping-release SNNN` as the next action. If a spec is local implementation PASS but QA pending, return WIP and name `$verifying-quality SNNN` as the next action.
Also report the NEXT_SKILL chosen for this tick, the repo evidence used to choose it, and the suggested NEXT_SKILL for the next tick or "none".
```
