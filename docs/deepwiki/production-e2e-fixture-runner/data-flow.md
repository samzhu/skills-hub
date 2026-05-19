# 關鍵資料流程圖

## 1. Production Image Build

```text
frontend npm build
  └─ cloudbuild.yaml:52-54
       │
       ▼
copy frontend/dist -> backend/src/main/resources/static
  └─ cloudbuild.yaml:64-66
       │
       ▼
backend bootBuildImage
  └─ cloudbuild.yaml:95-97
       │
       ▼
skillshub:<sha>
  ├─ production backend classes
  ├─ production resources
  └─ frontend static assets
```

Boundary: production image 不包含 fixture runner。Spring Boot 官方 `bootBuildImage` 從 application archive 建 OCI image，因此 archive 掃描是第一道 gate。

---

## 2. E2E Environment Setup

```text
Playwright "setup fixtures" project
  ├─ assert POST /internal/test/reset -> 404
  │    └─ process/network boundary: production backend HTTP
  │
  ├─ run fixture runner reset
  │    └─ process boundary: Node -> Gradle/Java CLI
  │
  ├─ fixture runner creates DB/schema or truncates e2e DB
  │    └─ persistence boundary: PostgreSQL
  │
  ├─ fixture runner seeds skills
  │    ├─ preferred: POST /api/v1/skills/upload
  │    └─ fallback: service-level command inside runner only
  │
  ├─ fixture runner seeds download projection
  │    └─ direct SQL: download_events + skills.download_count
  │
  └─ writes e2e/results/fixtures.json
       └─ file boundary: fixture manifest
```

Local source references:

- `e2e/tests/_fixtures.ts:36-84` 是現有 reset/seed 流程。
- `SkillCommandController.java:95-127` 是正式 upload API。
- `SkillCommandService.java:105-165` 是完整 skill publish write path。
- `AnalyticsService.java:40-62` / `70-86` 說明 analytics 需要 `download_events` 與 `skills.download_count`。

---

## 3. Browser Test Reads Fixture Manifest

```text
Playwright chromium project
  ├─ depends on setup fixtures
  │
  ├─ read e2e/results/fixtures.json
  │    └─ file boundary
  │
  ├─ page.goto('/skills/{id}')
  │    └─ browser -> frontend route
  │
  ├─ frontend apiFetch('/skills/{id}')
  │    └─ frontend/src/api/client.ts:104-115
  │
  └─ backend GET /api/v1/skills/{id}
       └─ production backend only
```

Frontend uses relative API base (`frontend/src/api/client.ts:1-5`), so Vite mode proxies to backend (`frontend/vite.config.ts:17-37`), while production static mode calls same origin.

---

## 4. Release E2E Gate

```text
CI build stage
  ├─ build skillshub:<sha>
  ├─ assert jar/image no E2E files
  └─ build optional skillshub-e2e-fixture:<sha>

CI e2e stage
  ├─ start PostgreSQL ephemeral
  ├─ start skillshub:<sha>
  ├─ start frontend target
  ├─ Playwright setup project invokes fixture runner
  ├─ Playwright browser tests
  └─ teardown drops DB/schema/container

CI deploy stage
  └─ deploy only skillshub:<sha>
```

Security boundary: deploy stage never references fixture image/tag. This follows OWASP secure-by-default: production deploy cannot accidentally expose fixture operations because they are not part of the production artifact.

