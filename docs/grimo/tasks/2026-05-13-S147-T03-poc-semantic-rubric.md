# S147-T03: POC semantic rubric

## Spec
S147 — 掃描器語意分析缺口研究

## BDD
Given static rules cannot reliably detect natural-language agent behavior intent
When an isolated corpus POC evaluates unsafe and benign `SKILL.md` samples for prompt injection, credential handling, third-party content exposure, sensitive data exposure, workspace exposure, financial execution, and destructive actions
Then the POC records which rubric rules produce `E004`, `W007`, `W009`, `W011`, `W017`, `W018`, `W019`, and `W020`
And benign read-only docs/search skills do not become high-risk findings
And production scanner code is not changed by this task

## Target Files
- `poc/S147/semantic-rubric/`
- `docs/grimo/specs/2026-05-08-S147-scanner-semantic-gap-research.md`

## Depends On
- S147-T01 PASS

## Status
pending
