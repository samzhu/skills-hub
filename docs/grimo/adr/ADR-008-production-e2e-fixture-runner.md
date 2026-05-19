# ADR-008: Production Image E2E Fixture Runner

> Status: **Accepted** (2026-05-19)
> Supersedes: ADR-007 fixture seeding Pattern 1 (`/internal/test/*`)
> Keeps: ADR-007 Playwright choice, `e2e/` workspace, V07 browser gate
> Spec: `docs/grimo/specs/2026-05-19-S202-production-e2e-fixture-runner.md`

---

## 1. Context

`backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/TestDataController.java` currently ships from the production source set and exposes `/internal/test/reset`, `/internal/test/seed/skill`, and `/internal/test/seed/download-event` when matching profiles are active.

`e2e/tests/_fixtures.ts` depends on those routes before browser tests run. This made V07 easy to bootstrap, but the production artifact still contains destructive test support code. S202 changes the target: browser E2E should exercise the codebase-built production image, not a test-flavored backend.

Current Google login implementation also matters here:

- `frontend/src/hooks/useAuth.ts` redirects to `/oauth2/authorization/skillshub?returnTo=...`.
- `AuthRedirectConfig` stores `returnTo` in `HttpSession` and redirects after OAuth success.
- `SecurityConfig` only enables that path when `skillshub.security.oauth.login.enabled=true`.
- `/api/v1/me` and `AuthArea` depend on OIDC `email/name/picture` claims for the logged-in UI.

So S202 needs two auth surfaces: API seed calls use mock OAuth Bearer JWTs; browser login tests use OAuth2 Login session cookies saved by Playwright storage state.

## 2. Decision

Adopt a production-image E2E runner owned by `e2e/`:

| Decision surface | Choice |
|---|---|
| App under test | `skillshub:e2e-local` tag built from the production packaged app image |
| Runtime stack | `e2e/compose.e2e.yaml` starts disposable pgvector DB, mock OAuth server, and app image |
| Fixture seed | External TypeScript runner in `e2e/fixtures`, not backend `/internal/test/*` routes |
| Aggregate data | Use production `/api/v1/*` APIs with mock OAuth Bearer JWT |
| Projection data | Guarded direct SQL only for read-side rows without production write API |
| Browser auth | Playwright setup runs mock OAuth2 Login and saves `playwright/.auth/*.json` |
| Artifact gate | Scan `bootJar` and Docker image filesystem for forbidden E2E support classes/resources |

ADR-007 remains accepted for tool/workspace choice. Its Pattern 1 fixture seeding decision is superseded by this ADR.

## 3. Consequences

Positive:

- V07 browser tests run against a production packaged image and production static frontend.
- Production artifact no longer contains reset/seed endpoints.
- Multi-user API scenarios still use real Spring Security JWT decoding and `CurrentUserProvider`.
- Browser login scenarios test the same OAuth2 Login/session path as current Google login implementation, with mock OAuth instead of a real Google consent screen.

Trade-offs:

- E2E setup now needs Docker Compose, image build, and Playwright setup projects.
- Mock OAuth has a URI/issuer POC requirement because the app runs inside Docker while Playwright/browser runs on the host.
- Projection fixtures still need guarded SQL for data without production write APIs, such as download counters, quality scores, and embeddings.

Out of scope:

- Cloud Run profile parity.
- Real Google OAuth consent flow.
- GCS / Cloud SQL Auth Proxy / Secret Manager deploy smoke.
- Browser visual regression and cross-browser expansion.
