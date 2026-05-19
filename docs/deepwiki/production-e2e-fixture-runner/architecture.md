# 核心架構

## 目標架構

```text
                           build artifact boundary
┌─────────────────────────────────────────────────────────────────┐
│ skillshub:<sha>                                                  │
│   backend/src/main/java + backend/src/main/resources             │
│   frontend/dist copied into backend static                       │
│   NO TestDataController / NO E2E*Config / NO application-e2e     │
└─────────────────────────────────────────────────────────────────┘

               test environment boundary
┌──────────────────────┐     ┌──────────────────────┐
│ Playwright runner     │────▶│ Frontend URL          │
│ setup project         │     │ http://localhost:5173 │
│ browser projects      │     │ or prod static app    │
└──────────┬───────────┘     └──────────┬───────────┘
           │                            │
           │ fixture command/API         │ /api/v1/*
           ▼                            ▼
┌──────────────────────┐     ┌──────────────────────┐
│ Fixture runner        │────▶│ Production backend    │
│ reset/seed/manifest   │     │ skillshub:<sha>       │
└──────────┬───────────┘     └──────────┬───────────┘
           │ DB admin / fixture user     │ app DB user
           ▼                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ Ephemeral PostgreSQL / schema                                    │
│ destroyed after run                                              │
└─────────────────────────────────────────────────────────────────┘
```

`cloudbuild.yaml:56-66` 已有 production build 的 frontend copy step，`cloudbuild.yaml:95-97` 用 `bootBuildImage` 產 backend image。Spring Boot 官方 `bootBuildImage` 說此 task 以 jar/war 建 OCI image，因此 production artifact 是否乾淨，必須在 jar/image 層驗證。

---

## 模組邊界

| 模組 | 包含 | 不包含 | 引用依據 |
|---|---|---|---|
| Production backend app | `backend/src/main/java` 內正式 controllers/services/domain，`application.yaml` / `application-local.yaml` / `application-gcp.yaml` / `application-aot.yaml` | `TestDataController`、`E2EEmbeddingConfig`、`E2EQualityJudgeConfig`、`application-e2e.yaml` | `backend/src/main/resources/application.yaml:30-33` 現在 default profile 是 `local,dev`，所以不能再讓 main 裡有測試 endpoint。 |
| Fixture runner | reset DB/schema、seed skills/users/download data、等待 outbox/listeners、輸出 fixture manifest | 使用者 UI、正式 `/api/v1` business endpoint implementation | 現在 `_fixtures.ts:36-84` 的三個動作就是 runner 要接走的責任。 |
| Frontend app | 使用 `/api/v1` 相對路徑呼叫 backend | 測試資料 API base | `frontend/src/api/client.ts:1-5` 固定 BASE = `/api/v1`；Vite proxy 在 `frontend/vite.config.ts:17-37`。 |
| Playwright tests | 瀏覽器動作、assertions、用 setup project 的 fixture manifest | 直接清 production app 內部資料 | Playwright 官方推薦 project dependencies 做 setup/teardown。 |

---

## Fixture Runner 形態

最佳設計可接受兩種 runner 形態：

| 形態 | 實際 command | 優點 | 限制 |
|---|---|---|---|
| `e2e/` TypeScript runner | `npx playwright test --project "setup fixtures"` 呼叫 `e2e/fixtures/*` | 不需要開 fixture HTTP port；CI 簡單；不改 backend Gradle project shape | 需要在 `e2e/package.json` 加 DB client dev dependency |
| Private fixture service | `docker run skillshub-e2e-fixture:<sha>`，Playwright setup 呼叫 `POST /fixtures/reset` | 適合 docker compose / remote E2E；可多語言使用 | 必須保證 service 只在 E2E network，不可 public ingress |

不建議讓 production app 自己暴露 `/internal/test/*`。`TestDataController.java:77-194` 現在把 reset、skill seed、download seed 放在 app 內；方案 D 把同樣能力移到 app 外。

---

## Persistence 邊界

Fixture runner 可以碰 DB，但要分級：

| 資料類型 | 建議寫法 | 理由 |
|---|---|---|
| Aggregate state: skills / skill_versions / grants | 優先走正式 API `POST /api/v1/skills/upload` 或 production service-level command exposed only in runner code | `SkillCommandService.uploadSkill()` 在 `backend/src/main/java/.../SkillCommandService.java:105-165` 會 normalize、validate、upload storage、save aggregate、publish events。 |
| Read-side projection: download_events / skills.download_count | 可由 runner direct SQL，但要封裝成 fixture operation | 現有 `TestDataController.java:159-194` 也是 direct insert `download_events` + update `skills.download_count`。 |
| Users / auth fixture | 若 E2E 用 lab mode，可 direct SQL 建 users；若測 OAuth，使用 mock IdP + public auth flow | `backend/config/application-dev.yaml:42-51` 目前 dev LAB 模式會注入 `lab-user`。 |
| Schema reset | 最佳是 throwaway DB/schema；次佳是 external runner truncate all app tables | `backend/compose.yaml:15-16` 目前 dev DB 用 named volume，E2E 不應共用這個持久資料。 |

---

## Artifact 驗證

正式 artifact 檢查必須比 profile test 更硬：

```bash
cd backend
./gradlew clean bootJar
jar tf build/libs/*.jar \
  | rg 'TestDataController|SeedSkillRequest|SeedDownloadEventRequest|E2EEmbeddingConfig|E2EQualityJudgeConfig|application-e2e'
```

預期：無輸出。

啟動 production image 後再做 route 掃描：

```bash
curl -i -X POST http://localhost:8080/internal/test/reset
```

預期：404，且 server log 不出現 `test_data_reset`。這個驗證對應 OWASP deny-by-default / secure-by-default 思路，不是只靠權限擋。
