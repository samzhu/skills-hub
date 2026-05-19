# Backend Production Image

## 現況

正式 Cloud Build 路徑在 `cloudbuild.yaml:95-97`：

```bash
./gradlew --no-daemon -x test bootBuildImage \
  --imageName=${_IMG_PATH}:${_TAG} \
  -Pspring.profiles.active=gcp,aot,lab
```

`backend/build.gradle.kts:1-17` 套用 Spring Boot、GraalVM Native、git properties、JaCoCo。`backend/build.gradle.kts:130-136` 會把 `spring.profiles.active` 傳給 `ProcessAot`，所以 native image 的 build-time profile 對 bean view 有影響。Spring Boot AOT 官方文件也說 AOT 會在 build time 產生 runtime beans 的 persistent view。

`TestDataController` 目前在 production source tree：

```text
backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/TestDataController.java
backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/E2EEmbeddingConfig.java
backend/src/main/java/io/github/samzhu/skillshub/score/judge/E2EQualityJudgeConfig.java
backend/src/main/resources/application-e2e.yaml
```

這違反方案 D 的 artifact boundary。

---

## Production Image Contract

正式 image 必須滿足：

| Contract | 驗證 command | 預期 |
|---|---|---|
| 不含 E2E support class/resource | `jar tf build/libs/*.jar | rg 'TestDataController|E2E|application-e2e'` | 無輸出 |
| 不暴露 test endpoint | `curl -i -X POST /internal/test/reset` | 404 |
| 只使用正式 `/api/v1` API | Playwright tests 不 import `/internal/test` base URL | `rg "/internal/test" e2e/tests` 無輸出 |
| Native build 不 bake E2E profile | `cloudbuild.yaml` / Gradle properties 不含 `e2e` | `rg "e2e" cloudbuild.yaml backend/build.gradle.kts` 不出現在 production image task |

---

## 應移除或搬離 main 的檔案

| 檔案 | 現在用途 | 方案 D 處置 |
|---|---|---|
| `TestDataController.java` | `POST /internal/test/reset`、`seed/skill`、`seed/download-event` | 刪除 production app 內 controller；邏輯移到 fixture runner。 |
| `SeedSkillRequest.java` | in-app fixture request DTO | 移到 fixture runner module/package。 |
| `SeedDownloadEventRequest.java` | in-app fixture request DTO | 移到 fixture runner module/package。 |
| `E2EEmbeddingConfig.java` | `e2e` profile deterministic embedding bean | 不放 production app；fixture 要求可透過 DB seed deterministic embedding 或測正式 embedding fallback 行為。 |
| `E2EQualityJudgeConfig.java` | `e2e` profile deterministic quality judge | 不放 production app；fixture 可 seed score table 或讓 UI 驗「評分計算中」。 |
| `application-e2e.yaml` | e2e behavior profile | 不放 production resources；E2E environment 用 container env / external config 管。 |

---

## 後端 API 與 fixture 的關係

### Public API 可 seed 的部分

`SkillCommandController.uploadSkill()` 在 `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java:95-127` 接 multipart：

```java
@PostMapping("/upload")
ResponseEntity<Map<String, String>> uploadSkill(
        @RequestParam("file") MultipartFile file,
        @RequestParam("skillName") String skillName,
        @RequestParam(name = "version", required = false) String version,
        @RequestParam("category") String category,
        @RequestParam(name = "visibility", required = false, defaultValue = "PUBLIC") Visibility visibility)
```

此 controller 會從 `currentUserProvider.current()` 取 user，再呼叫 `SkillCommandService.uploadSkill()`。service 在 `SkillCommandService.java:105-165` 做完整 write path：normalize zip、extract SKILL.md、validate、storage upload、save aggregate、publish version。

因此 fixture runner 若要 seed skills，優先策略是：

1. 用 API 建 skill，確保 outbox/audit/scan/quality path 與 production 一致。
2. 若需要固定 author display，runner 先建立 user row 或用 mock auth flow。
3. 如果 API 太慢，再只對 read-side projection 做 direct SQL，不直寫 aggregate state。

### Direct SQL 可接受的部分

`AnalyticsService.getOverview()` 在 `backend/src/main/java/io/github/samzhu/skillshub/analytics/AnalyticsService.java:40-62` 讀 `skills` 與 `download_events`。`getTopSkills()` 在 `AnalyticsService.java:70-86` 依 `skills.download_count` 排序。現有 `TestDataController.seedDownloadEvent()` 在 `TestDataController.java:159-194` 直寫 `download_events` 並 update `skills.download_count`。

所以 fixture runner 可保留這個資料模型，但不放在 app endpoint 裡：

```sql
INSERT INTO download_events (...)
UPDATE skills SET download_count = download_count + :delta WHERE id = :skillId
```

這應包在 fixture runner operation，並由 runner tests 驗證 `GET /api/v1/analytics/overview` 回傳符合預期。

---

## Build Pipeline

正式 build stage 建議：

```bash
cd frontend
npm ci
npm run verify
npm run build

rm -rf backend/src/main/resources/static
mkdir -p backend/src/main/resources/static
cp -r frontend/dist/. backend/src/main/resources/static/

cd backend
./gradlew --no-daemon -x test bootBuildImage \
  --imageName=${_IMG_PATH}:${_TAG} \
  -Pspring.profiles.active=gcp,aot,lab

./gradlew assertProductionArtifactClean
```

`assertProductionArtifactClean` 可掃 jar 或 exploded classes/resources。這個 task 不取代 route scan；route scan 要在 image 啟動後做。

---

## Production Image Route Scan

E2E CI 啟正式 image 後，setup project 先確認：

```ts
const res = await request.post('http://localhost:8080/internal/test/reset');
expect(res.status()).toBe(404);
```

這個測試不是為了功能，而是為了證明 production app 不含 fixture endpoint。若回 200，代表 deploy artifact 被污染，整個 E2E run 應 fail fast。

