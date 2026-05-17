# Automation Prompt - Production Site Audit Loop

將以下內容貼到 Codex App automation。建議設定：

- Project: `/Users/samzhu/workspace/github-samzhu/skills-hub`
- Execution environment: `worktree`
- Cadence: 30-60 minutes during active hardening；daily for maintenance。
- Browser: 可用 Chrome plugin 時優先使用 Chrome；否則用 Browser 驗證 public flow。

```text
This wake-up is one production site audit tick for /Users/samzhu/workspace/github-samzhu/skills-hub.

Target site: https://skillshub-644359853825.asia-east1.run.app/

Use Codex App background worktree execution when available. This automation is docs-only: it checks one shipped production flow, records evidence, and writes one finding or no-bug result. It is not a development loop and not a deployment runner.

Read and follow:
1. AGENTS.md
2. .codex/loop.site-audit.md
3. docs/grimo/PRD.md
4. docs/grimo/specs/spec-roadmap.md
5. docs/grimo/CHANGELOG.md

Then read relevant shipped specs in docs/grimo/specs/archive/ and latest docs/grimo/site-audit/ findings/results only when .codex/loop.site-audit.md says they are relevant.

Do exactly one audit unit using .codex/loop.site-audit.md:
- choose one shipped production flow and report the repo evidence used to choose it;
- open the production target site;
- operate the UI with Chrome plugin when available, otherwise Browser for public/anonymous flows;
- collect the evidence required by .codex/loop.site-audit.md;
- write exactly one docs-only finding, Auto-Draft production bug spec, or no-bug result.

Do not modify backend/**, frontend/**, e2e/**, migrations, scripts, build/deploy config, or lockfiles.
Do not run $planning-tasks, $implementing-task, $verifying-quality, or $shipping-release.
Do not deploy.
Do not treat Local runtime dirty files as blockers because this loop is docs-only.
Do not modify docs/grimo/specs/spec-roadmap.md.
If creating a production bug spec, mark it Status: Auto-Draft and automation_status: auto-draft. Do not create task files. Do not mark it planned or active. Dev-loop must not execute it until a human changes it to Approved-for-Dev or adds an executable roadmap row.

If browser/Chrome tools are unavailable, do not pretend UI verification happened. Write a tool-unavailable docs-only note only when useful, then return BLOCKED.

Only stage docs-only output created by this tick.
Commit one docs-only audit result when safe.

End with exactly one EXIT label from .codex/loop.site-audit.md: FINDING, NO-BUGS, BLOCKED, or WALL-HIT.
Also report the flow checked, the repo evidence used to choose it, the evidence collected, and the next suggested flow.
```
