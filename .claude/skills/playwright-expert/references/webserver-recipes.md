# webServer Recipes

Read just-in-time during BOOTSTRAP Step 3 (config rendering). Pick one
recipe based on the project's stack signature, then substitute the
markers in `assets/playwright-config-template.ts`.

## Detection rules

Inspect the repo before choosing a recipe:

| Signal | Recipe |
|---|---|
| `backend/build.gradle.kts` exists + `frontend/vite.config.*` exists | A — Spring Boot + Vite (recommended) |
| `frontend/vite.config.*` only (no backend in repo) | B — Vite only |
| `docker-compose*.yml` at repo root with named services for app + db | C — Docker Compose (CI hermetic) |
| Mono-package — frontend served by backend at runtime | D — Single-server |

If multiple match, prefer A → C → D → B (most production-realistic first).

## Recipe A — Spring Boot + Vite

The default for Skills Hub. `bootRun` is preferred over `bootTestRun`
because the latter pins Testcontainers and is markedly slower; Spring
Boot already auto-starts `compose.yaml` at boot when one is present.

Markers:

```
BACKEND_COMMAND     = ./gradlew bootRun -x processAot
BACKEND_CWD         = ../backend
BACKEND_HEALTH_URL  = http://localhost:8080/actuator/health
FRONTEND_COMMAND    = npm run dev
FRONTEND_CWD        = ../frontend
FRONTEND_URL        = http://localhost:5173
```

Pre-flight checks (verify before substituting markers):

- **actuator/health endpoint must be reachable**. Grep
  `application.yaml` (and any active profile yaml) for
  `management.endpoints.web.exposure.include` — the value must
  contain `health` or `*`. If the endpoint is not exposed, the
  webServer block will poll forever. Skills Hub already exposes 7
  actuator endpoints by default; greenfield projects often do not.
- **Docker Compose auto-starts via `spring-boot-docker-compose`**.
  When `backend/compose.yaml` exists, Spring Boot will run
  `docker compose up` itself during the bootRun startup phase. Do
  NOT add a separate `docker compose up` step; that double-starts
  containers and races on port binding. Confirm the project has
  `spring-boot-docker-compose` on the classpath (not just the
  Compose file).

Notes:
- `-x processAot` is required while the GraalVM native-build plugin
  pre-existing bug stands (see `docs/grimo/qa-strategy.md` Known
  Limitations). Drop the flag once the AOT config / OpenTelemetry
  switch ships in its own spec. Re-check the project's QA strategy
  doc before each new project bootstrap; this flag is not universal.
- **`timeout: 180_000` for backend** — sized for the first-ever run
  on a clean machine (~90–150 s for Spring Boot 4 cold + first
  Docker image pull of pgvector + first Flyway migration set). Once
  the Docker images are cached and the backend warm classes are
  present, subsequent cold starts measure ~10–30 s in Skills Hub
  (verified 2026-05). Keep the 180_000 timeout — first-run cost is
  paid once but the budget must accommodate it.
- `reuseExistingServer: !process.env.CI` lets the developer keep
  `./gradlew bootRun` warm in another terminal between runs.
- If `actuator/health` returns 503 during DB warmup, the webServer
  block treats that as not-ready and keeps polling; this is correct.

## Recipe B — Vite only

Frontend-only POC or skill prototypes. Drop the Backend block from the
config template entirely; do not leave it with placeholder markers.

```
FRONTEND_COMMAND    = npm run dev
FRONTEND_CWD        = ../frontend
FRONTEND_URL        = http://localhost:5173
```

## Recipe C — Docker Compose (CI hermetic)

Use when the spec requires a full container assembly verification, or
in CI where the developer's local DB is unavailable. Bring the stack up
once via Compose; Playwright targets the published port.

```
BACKEND_COMMAND     = docker compose up --wait
BACKEND_CWD         = ..
BACKEND_HEALTH_URL  = http://localhost:8080/actuator/health
FRONTEND_COMMAND    = (omit — frontend bundled into backend image)
```

`--wait` blocks until each service's healthcheck passes, removing the
need for a separate URL probe. Add a teardown step in CI after the
test run: `docker compose down --volumes`.

## Recipe D — Single-server

Backend serves the built frontend (Spring Boot static resources or
similar). Only one webServer entry — point at the backend.

```
BACKEND_COMMAND     = ./gradlew bootRun
BACKEND_CWD         = ../backend
BACKEND_HEALTH_URL  = http://localhost:8080/actuator/health
FRONTEND_URL        = http://localhost:8080
```

## Auth handling

Skills Hub MVP runs Spring Security as `permitAll`, so E2E specs do
not authenticate. When OAuth2 ships, switch to a Playwright global
setup that obtains a token once and saves it to `.auth/state.json`,
then add `use: { storageState: 'e2e/.auth/state.json' }` to the
config. Do not commit the state file — add it to `.gitignore`.

## Anti-patterns

- Hardcoding `bootTestRun` instead of `bootRun` — it adds Testcontainers
  startup overhead the user already pays for in unit tests.
- Setting `timeout: 30_000` on the Spring Boot block — first-cold-start
  exceeds this on most laptops; raise to ≥ 120_000.
- Omitting `cwd` and relying on a leading `cd ../backend &&` in the
  command string — this breaks Windows Playwright runs and obscures
  the working directory in error messages.
