# S181 shipping blocked; S182 403 evidence

Date: 2026-05-16
Tick: `skills-hub-production-debug-loop` at `2026-05-15T19:33:30Z`

## One work unit

This tick did not ship S181 because `docs/grimo/specs/spec-roadmap.md` already has unrelated local edits for S179/S180, and `docs/grimo/specs/2026-05-15-S179-publish-author-anonymous-login-hint.md` is still untracked. Shipping S181 would need roadmap/changelog/archive edits, so committing that release now would risk mixing unrelated user changes.

Instead, this tick records fresh Chrome + Cloud Run evidence for the next bug unit: authenticated validate page still fails, but the failing status is 403, not the old 409.

## Repo state

Unrelated local changes present before this note:

```text
 M docs/grimo/specs/spec-roadmap.md
?? docs/grimo/specs/2026-05-15-S179-publish-author-anonymous-login-hint.md
```

Tracked S181 task files still exist and are PASS:

```text
docs/grimo/tasks/2026-05-15-S181-T01-state-conflict-log-evidence.md
docs/grimo/tasks/2026-05-15-S181-T02-deploy-log-evidence.md
```

S181 release cleanup still needed after unrelated local changes are split:

- archive `docs/grimo/specs/2026-05-15-S181-authenticated-state-conflict-observability.md`
- delete `docs/grimo/tasks/2026-05-15-S181-*.md`
- update `docs/grimo/specs/spec-roadmap.md`
- update `docs/grimo/CHANGELOG.md`
- run the release gate required by `$shipping-release`

## Production bug evidence

URL:

```text
https://skillshub-644359853825.asia-east1.run.app/publish/validate?id=028cecf1-3326-4327-bbe9-28b4e6fab6d5
```

Chrome steps:

1. Opened the validate URL in the existing Chrome tab.
2. Reloaded the page after S181 was already deployed to revision `skillshub-00030-rd2`.
3. Read page text and Chrome console through the Chrome plugin.

Expected:

- Authenticated user can continue the publish validation flow, or the page shows a precise permission / not-found message.

Actual:

- Page is authenticated: the nav no longer shows `登入`.
- Page still shows:

```text
無法載入 skill (id=028cecf1-3326-4327-bbe9-28b4e6fab6d5) — 可能仍在處理或已被刪除
```

Chrome console:

```text
[]
```

Cloud Run request logs:

```text
2026-05-15T19:35:05.180481Z GET /api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5 403 trace=projects/cfh-vibe-lab/traces/3c56db568a97b06fc895dbb3d34bb6bf span=4e3400e546d2c92b
2026-05-15T19:35:05.180920Z GET /api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5/bundle-info 403 trace=projects/cfh-vibe-lab/traces/ecc111dfd7c7cf4bc895dbb3d34bb78a span=0e5496b1f401e8a3
2026-05-15T19:35:05.334711Z GET /api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5/bundle-info 403 trace=projects/cfh-vibe-lab/traces/3c169b24b0043df1c895dbb3d34bb327 span=cc2609053d47f852
2026-05-15T19:35:05.334794Z GET /api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5 403 trace=projects/cfh-vibe-lab/traces/8345466a4b421db7c895dbb3d34bb191 span=31a0d7632dcfb4a2
```

Cloud Run application log query:

```text
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="skillshub" AND timestamp>="2026-05-15T19:33:00Z" AND textPayload:"State conflict"' --project=cfh-vibe-lab --limit=20
[]
```

## Impacted specs / tests

- S181 is complete for 409 observability: no new `State conflict` log exists because no 409 was produced.
- S180 AC-S180-4 remains user-visible blocked: validate page still cannot load the skill.
- The next spec should be S182 or equivalent: authenticated 403 on skill detail and bundle-info for `028cecf1-3326-4327-bbe9-28b4e6fab6d5`.
- Test gap: there is no current production-facing test proving that a just-published skill's author can read `/api/v1/skills/{id}` and `/api/v1/skills/{id}/bundle-info` during the validate page flow after OAuth login.

## Next tick recommendation

If the unrelated roadmap/S179 local changes are still present, do not ship S181. Start a small planning-spec for the authenticated 403 path and explicitly avoid roadmap edits, or wait until the dirty roadmap is split and then run `$shipping-release` for S181.
