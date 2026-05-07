# Caller Protocol — Cross-Skill Contract

Read just-in-time when a caller invokes this skill, or before the
final report in any mode. Defines the file-based contract that lets
upstream workflow skills consume Playwright output without parsing
Playwright internals.

## When this skill is the callee

Three upstream callers are supported. Each invokes a specific mode
with a defined input and reads a defined output.

| Caller | Mode | Input | Output read |
|---|---|---|---|
| User direct | BOOTSTRAP | repo root path | stdout JSON line |
| `planning-tasks` Phase 2 | DESIGN | spec id | `e2e/tests/<spec-id>-*.spec.ts` files |
| `verifying-quality` Step 5 | VERIFY | spec id | `e2e/results/evidence.json` |

The skill MUST NOT mutate caller state outside `e2e/`. All side
effects on the spec file are performed by the caller after reading
the contract output.

## evidence.json schema (VERIFY output)

Stable contract — `verifying-quality` parses this and never inspects
Playwright JSON directly.

```json
{
  "spec_id": "S012",
  "run_at": "2026-05-07T01:23:45Z",
  "exit_code": 0,
  "playwright_version": "1.59.1",
  "stats": {
    "total": 4,
    "passed": 4,
    "failed": 0,
    "flaky": 0,
    "skipped": 0,
    "duration_ms": 18420
  },
  "tests": [
    {
      "title": "AC-1: Upload valid zip returns 201 @S012 @ac-1 @happy-path",
      "ok": true,
      "tags": ["@S012", "@ac-1", "@happy-path"],
      "duration_ms": 4210,
      "trace_paths": [],
      "error": null
    }
  ],
  "report_json": "results/report.json",
  "html_report": "playwright-report/index.html"
}
```

`render-evidence.sh` produces this file deterministically. Schema
changes are versioned by adding fields, never renaming or removing
them; callers tolerate unknown fields.

## How callers find this skill

agentskills.io has no programmatic invoke API. Cross-skill use works
through three patterns, listed in order of preference.

### Pattern 1 — Caller cites this skill by name

The caller's SKILL.md mentions this skill explicitly:

```
If the spec needs E2E acceptance, hand off to playwright-expert in
VERIFY mode with the spec id. Read e2e/results/evidence.json after
control returns.
```

Agent loads both skills via progressive disclosure and follows the
hand-off naturally.

### Pattern 2 — User says a trigger phrase

The user types something matching this skill's positive triggers
("跑 e2e", "playwright", "驗收測試"). The agent enters this skill
directly without an upstream caller.

### Pattern 3 — Filesystem signal

The caller checks for `e2e/playwright.config.ts`. If absent, route to
this skill in BOOTSTRAP mode first; if present, route to DESIGN or
VERIFY as needed.

## Responsibility boundary

Avoid duplicating logic with `verifying-quality`. The skills divide
work as follows:

| Concern | playwright-expert | verifying-quality |
|---|---|---|
| Install / upgrade Playwright | YES (BOOTSTRAP) | NO |
| Translate AC → spec test | YES (DESIGN) | NO |
| Run E2E + collect trace | YES (VERIFY) | NO — delegates here |
| Decide if §3 ACs are covered | NO | YES — reads evidence.json |
| Decide if shipping is blocked | NO | YES |
| Write to spec §7 | NO | YES |

If `verifying-quality` ever inlines Playwright commands (instead of
delegating), the boundary is broken and one of the two skills must
be revised.

## Trace evidence policy

| Trace mode | When | Why |
|---|---|---|
| `on-first-retry` | default for VERIFY (officially documented at playwright.dev/docs/ci-intro) | tiny artefact when green; full trace on the first failed attempt |
| `retain-on-failure-and-retries` | flaky-test investigation (Playwright 1.59) | compares green and red traces side-by-side |
| `on` | local debugging only | trace.zip can reach 100+ MB per test for complex apps; never enable in CI |

VERIFY mode never reconfigures the trace policy mid-run. To switch
modes, the caller passes `--trace <mode>` through to BOOTSTRAP
upgrade and re-renders the config.

## Trace viewer — viewing the evidence

Two equivalent ways, both free, both Microsoft official:

1. Local CLI — opens trace in a Playwright-launched viewer, no network:
   ```
   npx playwright show-trace e2e/test-results/<test-folder>/trace.zip
   ```
2. Web — drag the `.zip` onto the static PWA at trace.playwright.dev.
   Microsoft documents that this viewer "loads the trace entirely in
   your browser and does not transmit any data externally," so it is
   safe for traces that contain confidential URLs or payloads.

Local debug therefore needs no upload step at all — gitignored
artefacts on disk plus `show-trace` is the full loop. CI artefacts
are only for retrospective inspection from another machine.

## CI artefact convention

When the caller is a CI orchestrator (GitHub Actions / GitLab CI),
upload the following on every run except cancellation. The
playwright.dev/docs/ci-intro example uses `if: ${{ !cancelled() }}`
(skip on manual cancel) and `actions/upload-artifact@v5`:

```yaml
- name: Upload Playwright artefacts
  if: ${{ !cancelled() }}
  uses: actions/upload-artifact@v5
  with:
    name: playwright-evidence-${{ github.run_id }}
    path: |
      e2e/playwright-report/
      e2e/test-results/
      e2e/results/
    retention-days: 14
```

Always upload `playwright-report/` and `test-results/` together. The
HTML report links each failure's `trace.zip` by relative path under
`test-results/`; uploading only the report directory leaves the trace
links broken. `e2e/results/evidence.json` is the primary contract
output and `report.json` is the full reporter dump.

Retention 14–30 days is sufficient — long-term proof lives in the
spec file's §7, not in CI artefacts.

## Cloud execution platforms — out of scope

Microsoft Playwright Testing on Azure (cloud parallel execution,
consumption-billed) is paid and **scheduled to retire on 2026-03-08**;
its successor Azure App Testing is also paid (custom quote). This
skill targets local + GitHub Actions runners only — neither cloud
platform is required for the Skills Hub three-happy-path scope. The
free trace viewer at trace.playwright.dev is unrelated to those
paid services and remains the recommended evidence-inspection tool.

## Failure routing

VERIFY exit code semantics for the caller:

| exit | Meaning | Caller action |
|---|---|---|
| 0 | All happy-path tests passed | proceed |
| 1 | One or more tests failed | read `tests[].error`, route back to `/planning-tasks` for fix tasks |
| 2 | Argument error | report bug in caller's invocation |
| 4 | Playwright JSON missing | something killed the run before reporter wrote — re-run |
| 5 | Evidence write failed | filesystem issue — escalate to user |

The caller decides whether to block shipping; this skill never
decides that.
