# AC → Playwright Spec Translation Guide

Read just-in-time during DESIGN Step 2. Drives the rules used to fill
`assets/spec-test-template.ts` from a spec's §3 acceptance criteria.

## Source of truth

The spec file at `docs/grimo/specs/<date>-<spec-id>-<slug>.md`. Read
the entire §3 section. Each AC has the shape:

```
### AC-N: <headline verb phrase>
- Given <pre-state>
- When  <action>
- Then  <observable outcome>
```

Headlines without explicit Given/When/Then still translate — extract
the implicit pre-state from the spec's §1 Problem and §2 Approach
sections.

## File and test naming

| Element | Convention | Example |
|---|---|---|
| Spec file | `tests/<spec-id>-<short-slug>.spec.ts` | `tests/S012-skill-upload.spec.ts` |
| describe block | `<spec-id> — <spec headline>` | `S012 — Upload SKILL.md zip` |
| test() title | `AC-N: <verbatim AC headline>` | `AC-1: Upload valid zip returns 201` |
| Tags | `@<spec-id> @ac-N @happy-path \| @edge` | `@S012 @ac-1 @happy-path` |

The verbatim AC headline matters — `verifying-quality` audits whether
every AC in §3 has a matching test. String match is the cheapest audit.

## test.step structure

One `test()` per AC. Three `test.step()` calls inside, one per BDD
clause. The step name is the BDD clause as written.

```typescript
test('AC-1: Upload valid zip returns 201 @S012 @ac-1 @happy-path', async ({ page }) => {
  await test.step('Given the user is on /skills/new', async () => { ... });
  await test.step('When the user selects a valid zip and clicks 上傳', async () => { ... });
  await test.step('Then the page shows 上傳成功', async () => { ... });
});
```

If an AC has more than one Given or Then clause, chain them with `and`
inside the same step:

```typescript
await test.step('Given the user is signed in and on /skills/new', ...);
```

Do not split into more than three steps — the BDD-clause-to-step
mapping is what makes the HTML report readable; extra steps blur the
gate-keeping intent.

## Locator priority

Per Playwright official guidance, prefer locators in this order:

1. `getByRole('button', { name: '上傳' })`
2. `getByLabel('SKILL.md zip')`
3. `getByText('上傳成功', { exact: true })`
4. `getByTestId('upload-submit')` — only when 1–3 are not feasible
5. CSS selector — last resort, requires a comment justifying it

Hard-coded XPath, deep CSS chains, and `:nth-child` are forbidden;
they break on every UI tweak.

When the UI does not yet expose a stable role/label/test-id, do not
invent a CSS selector. Stop and emit a finding for the implementer:
"AC-N requires `data-testid="<id>"` on element X" — add it to the
spec's §6 task plan instead of writing a fragile spec.

## Tag taxonomy

| Tag | Purpose |
|---|---|
| `@<spec-id>` | Run only this spec's tests: `npx playwright test --grep @S012` |
| `@ac-<N>` | Run a single AC: `--grep @ac-1` |
| `@happy-path` | The minimal proof an AC works — required for the VERIFY mode gate |
| `@edge` | Boundary, error, or rare-path AC — runs but does not gate ship |
| `@flaky` | Known-flaky pending root-cause; included in retry policy only |

Every test must carry exactly one of `@happy-path` or `@edge`. The
VERIFY mode default filter is `--grep @happy-path` — edge tests run
in a separate, non-blocking job.

## What to skip

- ACs that describe pure backend behavior (database constraint, event
  publication, internal service contract) — these belong in JUnit /
  Modulith Scenario tests. Mark them `// covered by backend test` in
  the §6 task plan and omit from the Playwright spec.
- ACs that describe component-level visual behavior (focus order,
  disabled states without UI flow) — these belong in Vitest + RTL.
- Performance ACs — Playwright's `expect.soft` is not a load-test tool.

The DESIGN output must include a one-line note for each skipped AC
explaining where it is covered.
