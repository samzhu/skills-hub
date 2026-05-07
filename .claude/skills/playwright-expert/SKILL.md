---
name: playwright-expert
description: >
  Playwright E2E expert that bootstraps an e2e workspace at the latest
  version, translates spec acceptance criteria into Playwright spec
  files with BDD step structure, and runs acceptance verification
  producing JSON report plus trace evidence at the contract path
  e2e/results/evidence.json. Invoked as a sub-capability by upstream
  workflow skills (planning-tasks for E2E task design;
  verifying-quality for hermetic acceptance gate). Use when the
  request mentions 'playwright', 'e2e', 'browser test',
  'acceptance test', 'set up playwright', 'add e2e tests',
  'run acceptance tests', 'upgrade playwright', '驗收測試', '跑 E2E',
  'playwright 設定', '升級 playwright', or when an upstream skill
  needs browser-based acceptance test design or execution. Don't use
  for backend unit tests (JUnit, Testcontainers, Spring Modulith
  Scenario), frontend component tests (Vitest, React Testing
  Library), performance or load testing, or manual QA scripts.
compatibility: Requires Node 18+, npm, jq, Docker (for Spring Boot Testcontainers when applicable), and network access to the npm registry. Outputs land under e2e/ at the repo root.
metadata:
  author: samzhu
  version: 0.1.0
  category: workflow-automation
  pattern: context-aware-routing
allowed-tools:
  - Read
  - Glob
  - Grep
  - Bash
  - Write
  - Edit
  - WebFetch
---

# Playwright Expert

Owns Playwright E2E for the project: environment bootstrap (always
latest on first install, opt-in upgrade thereafter), translation of
spec acceptance criteria into runnable browser tests, and acceptance
verification with JSON-encoded evidence at a stable contract path.
The skill does not decide whether shipping is blocked — it produces
evidence; upstream skills decide. All work lands under `e2e/` at the
repo root; nothing else is mutated.

## Mode Selection

The first decision in every invocation. Pick by signal — the user's
phrasing OR the upstream caller's intent.

| Signal | Mode |
|---|---|
| "set up playwright", "add e2e", "playwright 設定", `e2e/` does not exist | BOOTSTRAP |
| "upgrade playwright", "升級 playwright", `e2e/` exists and user asks for newer version | BOOTSTRAP with `--upgrade` |
| "design e2e for S0NN", `planning-tasks` hand-off, spec id provided | DESIGN |
| "run e2e for S0NN", "驗收測試", `verifying-quality` hand-off, spec id provided | VERIFY |
| Mixed signals or no spec id | Ask the user which mode, or which spec id to target |

Do not run more than one mode per invocation. Each mode hands control
back to the caller; the caller decides what comes next.

## Important — defaults the skill does NOT change

* Browsers: install only the chromium headless shell variant
  (`--only-shell`). Full Chromium is not installed unless the caller
  explicitly requests it. This avoids the multi-hundred-MB download
  that frequently stalls.
* Trace mode: `on-first-retry` for VERIFY (officially documented at
  playwright.dev/docs/ci-intro). Screenshot `only-on-failure` and
  video `retain-on-failure` are community convention rather than
  Microsoft's documented defaults — adjust per spec if needed.
* Local trace viewing: `npx playwright show-trace <trace.zip>` opens
  a fully local viewer (no network). The static PWA at
  trace.playwright.dev is also Microsoft-official and explicitly
  loads traces in-browser without uploading data. Either path is
  free; cloud Playwright Testing services are paid and out of scope.
* Working directory: every script call cds into `e2e/` and stays
  there. The skill never writes outside `e2e/`.
* Gitignore: `ensure-latest.sh` writes a managed block to
  `e2e/.gitignore` automatically (idempotent, marker-fenced) — do
  not edit between the markers.

## Procedures

### BOOTSTRAP mode

Use when no `e2e/` exists, or when the user asks to upgrade.

1. Detect current state. Check whether `e2e/playwright.config.ts`
   exists. If absent, this is a first install. If present, this is
   an upgrade — proceed only if the invocation carried `--upgrade`.
   Otherwise stop and tell the user the workspace is already set up;
   point them at DESIGN or VERIFY.
2. Initialize the workspace skeleton. If first install, create
   `e2e/`, `e2e/tests/`, `e2e/results/`, and a minimal
   `e2e/package.json` with `name: e2e-workspace`, `private: true`,
   `type: module`, and an empty `devDependencies`. The
   `e2e/.gitignore` is created idempotently by the install script in
   Step 3; do not write it manually here.
3. Run the deterministic install script:
   `bash scripts/ensure-latest.sh --e2e-dir <repo>/e2e`
   Pass `--upgrade` only when the caller asked. Capture the JSON
   line printed to stdout — record it as the install evidence.
4. Render the config. Read `references/webserver-recipes.md` to
   identify the right recipe for the repo (Spring Boot + Vite is the
   default for this project). Before substituting markers, read the
   project's QA strategy / known limitations doc (e.g.
   `docs/grimo/qa-strategy.md` § Known Limitations) and bake any
   command-level workaround the project requires into the marker
   value — for example `./gradlew bootRun -x processAot` rather than
   bare `./gradlew bootRun` if a build flag is documented as
   required. Copy `assets/playwright-config-template.ts` to
   `e2e/playwright.config.ts` and substitute every `{{ MARKER }}`
   per the chosen recipe. Leave no marker behind.
5. Author a placeholder smoke test at `e2e/tests/smoke.spec.ts` that
   navigates to `baseURL` and asserts the document has a non-empty
   title. Tag the placeholder `@smoke @bootstrap` (NOT `@happy-path`)
   so it is excluded from the project's E2E gate (V07 / equivalent
   uses `--grep @happy-path`). The placeholder exists so
   `npx playwright test --list` returns at least one test — used by
   the audit step — without polluting acceptance evidence.
6. Verify by running `npx playwright test --list` from `e2e/`. The
   command must list at least the placeholder smoke test. If it
   does not, stop and surface the error verbatim.
7. Report to the caller: installed version, action (`install` /
   `upgrade` / `noop`), config recipe used, path to
   `playwright.config.ts`. Hand control back.

### DESIGN mode

Use when a caller (typically `planning-tasks` Phase 2) needs E2E
spec files generated from a spec's acceptance criteria.

1. Read the spec file `docs/grimo/specs/*-<spec-id>-*.md`. Locate
   the §3 acceptance criteria block in full.
2. Read `references/ac-translation-guide.md` to identify the
   naming convention, locator priority, tag taxonomy, and skip
   rules. Then read `references/fixtures-patterns.md` to identify
   the four seeding patterns, the state taxonomy, and the
   per-AC fixture decision protocol.
3. Decide skip-or-translate per AC. For each AC that translates:
   - Identify the implicit starting state profile (empty / single /
     paged / full / mixed-visibility / multi-role / boundary).
   - Check whether the seeding mechanism for that profile exists
     (production API in OpenAPI, OR backend test endpoint under
     `@Profile({"local","dev","e2e"})`).
   - If the seeding mechanism is missing, emit a finding line
     "AC-N requires backend seed endpoint
     `POST /internal/test/seed/<entity>`" exactly the same way as
     a missing locator finding. Do NOT seed via UI clicks.
   ACs covered by backend or component tests are recorded in a
   one-line note for the caller (so the spec's task plan can
   reference them) and omitted from Playwright. Every other AC
   translates to one `test()` block tagged with its profile
   (e.g. `@profile-empty`).
4. Render the spec test file at
   `e2e/tests/<spec-id>-<short-slug>.spec.ts`. Start from
   `assets/spec-test-template.ts`; populate the markers per the
   guide. One file per spec id; multiple `test()` blocks inside the
   single `describe()`.
5. Stop short of guessing locators when the UI lacks stable
   roles / labels / test ids. For each missing locator, emit a
   finding line "AC-N requires `data-testid="<id>"` on element X"
   so the caller can add it to the spec's task plan.
6. Verify the rendered file compiles by running
   `npx playwright test --list --grep @<spec-id>` from `e2e/`. The
   list must contain one entry per non-skipped AC.
7. Report to the caller: list of files created, list of skipped
   ACs with the reason, list of missing locators (if any). Hand
   control back. Do not run the tests in DESIGN mode.

### VERIFY mode

Use when a caller (typically `verifying-quality` Step 5) needs the
acceptance gate executed for a specific spec.

1. Confirm the workspace is bootstrapped. If
   `e2e/playwright.config.ts` is missing, stop and tell the caller
   to run BOOTSTRAP first. Do not auto-bootstrap mid-VERIFY.
2. Confirm the spec test file exists at
   `e2e/tests/<spec-id>-*.spec.ts`. If absent, stop and tell the
   caller to run DESIGN first. Do not auto-design mid-VERIFY.
3. Run the gate from `e2e/`:
   `npx playwright test --grep @<spec-id> --grep-invert @edge`
   Capture the exit code. The reporter writes
   `results/report.json` and `playwright-report/` automatically per
   the rendered config.
4. Translate the run into the contract evidence file:
   `bash scripts/render-evidence.sh --e2e-dir <repo>/e2e --spec-id <id> --exit-code <n>`
   Read `references/caller-protocol.md` for the schema and exit
   code semantics. The script is the single producer of
   `evidence.json`; do not write to it manually.
5. Report to the caller the absolute path of `evidence.json`, the
   one-line stdout summary from the script, and the original
   Playwright exit code. Hand control back. The caller decides
   shipping; this skill does not.

## Examples

**Positive — BOOTSTRAP first install**:
> User: "幫我在這個專案加上 Playwright E2E"
> Skill: detect no `e2e/` → enter BOOTSTRAP → run `ensure-latest.sh`
> with no `--upgrade` flag → render Spring Boot + Vite recipe →
> author smoke placeholder → run `--list` → report installed
> version and config path. Does not run the smoke test.

**Positive — DESIGN hand-off from planning-tasks**:
> Caller: `/planning-tasks` Phase 2 for spec S012 needs E2E task
> files; cites `playwright-expert` in DESIGN mode with `--spec-id S012`.
> Skill: read S012 spec §3 → translate AC-1, AC-3 to
> `tests/S012-skill-upload.spec.ts` → mark AC-2 skipped (covered by
> JUnit `SkillRepositoryTest`) → emit one missing-locator finding
> ("AC-3 needs `data-testid="upload-status"`") → run `--list --grep @S012`
> → report to `planning-tasks`. Does not run the tests.

**Positive — VERIFY hand-off from verifying-quality**:
> Caller: `/verifying-quality` Step 5 for spec S012; cites
> `playwright-expert` in VERIFY mode with `--spec-id S012`.
> Skill: confirm config + spec test exist → run `npx playwright test
> --grep @S012 --grep-invert @edge` → run `render-evidence.sh` → return
> evidence.json path + summary line + exit code. `verifying-quality`
> reads `evidence.json` and decides PASS / REJECT-FIX.

**Negative — should NOT trigger**:
> User: "為這個 service 寫 unit test"
> Skill: defer — JUnit + Vitest cover unit tests; `playwright-expert`
> is for browser-driven acceptance only.

> User: "這個 service 怎麼用 @WebMvcTest 寫 controller test"
> Skill: defer — Spring slice tests are out of scope.

> User: "我要做 load test 看 100 個 concurrent user"
> Skill: defer — Playwright is not the right tool for load testing;
> recommend k6 or Gatling.

## Error Handling

* `ensure-latest.sh` exits `2`: argument error. Cause: caller passed
  a non-existent `--e2e-dir` or unknown flag. Recovery: re-check the
  invocation and re-run.
* `ensure-latest.sh` exits `3`: npm registry unreachable. Cause:
  network down or proxy mis-configured. Recovery: surface the
  message verbatim, ask the user to confirm connectivity. Do not
  retry silently.
* `ensure-latest.sh` exits `4`: `npm install @playwright/test`
  failed. Cause: npm or registry error, possibly disk space.
  Recovery: read stderr from the script, fix the underlying npm
  issue, then re-run BOOTSTRAP.
* `ensure-latest.sh` exits `5`: `npx playwright install --only-shell
  chromium` failed. Cause: browser binary download blocked or disk
  full. Recovery: try `npx playwright install chromium` manually
  outside the skill to surface the underlying error; treat as a
  user-actionable failure, do not retry from here.
* `npx playwright test --list` lists zero tests in BOOTSTRAP step 6.
  Cause: the placeholder file was not written, or the testDir
  marker substitution failed. Recovery: re-render the config and
  the placeholder, then re-run the list command.
* DESIGN finds the spec file but its §3 has no recognizable AC
  blocks. Cause: spec is still in the §1-§2 design phase, ACs not
  written yet. Recovery: stop and tell the caller the spec is not
  ready for DESIGN; do not invent ACs.
* DESIGN cannot select a stable locator for an AC. Cause: the UI
  does not expose a role / label / test id for the target element.
  Recovery: emit a missing-locator finding (do NOT fall back to a
  CSS chain) and continue with the next AC. The caller adds the
  test id to the spec's task plan.
* VERIFY finds `e2e/playwright.config.ts` missing. Cause:
  BOOTSTRAP was never run. Recovery: stop, tell the caller to run
  BOOTSTRAP first. Do not auto-bootstrap.
* VERIFY finds no spec test file matching `tests/<spec-id>-*.spec.ts`.
  Cause: DESIGN was never run for this spec, or the spec id is
  wrong. Recovery: stop, tell the caller to run DESIGN first or
  re-check the spec id.
* `render-evidence.sh` exits `3`: `jq` missing. Cause: `jq` not
  installed on this host. Recovery: surface the install command
  (`brew install jq` on macOS) to the user; do not retry.
* `render-evidence.sh` exits `4`: Playwright JSON report missing or
  malformed. Cause: the test run was killed before the reporter
  flushed, or the config was edited to remove the `json` reporter.
  Recovery: re-render the config from
  `assets/playwright-config-template.ts`, re-run VERIFY.
* Playwright run hangs in the `webServer` block waiting for the
  Spring Boot health URL. Cause: backend cold start exceeds the
  timeout (typical on first run with Testcontainers pgvector pull).
  Recovery: raise `timeout` in the Backend block to 180_000 in
  `playwright.config.ts`; if that is already the value, ask the
  user to confirm Docker is running and the actuator endpoint is
  reachable.

## Escalate

Tasks outside this skill's scope:

* New backend test infrastructure (Testcontainers, Modulith
  Scenario) → escalate to `verifying-quality` and `planning-spec`.
* Spec design (writing §3 ACs) → escalate to `planning-spec`.
* Deciding whether failing E2E blocks shipping → return to
  `verifying-quality`; this skill never blocks ship.
