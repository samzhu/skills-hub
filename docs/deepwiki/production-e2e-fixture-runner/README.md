# Production E2E Fixture Runner 深度分析

> **定位：** 用正式 production image 跑瀏覽器 E2E，測試資料由 app 外部的 fixture runner / service 準備。  
> **範圍：** Skills Hub backend + frontend + Playwright + Cloud Build。  
> **狀態：** S202 方案 D 研究稿，取代 `TestDataController` in-app fixture endpoint。

---

## 一句話總結

`backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/TestDataController.java` 目前讓 app 自己提供 `/internal/test/reset` 與 seed endpoint；方案 D 改成瀏覽器測試打正式 app image，資料 reset/seed 由外部 fixture runner 管。這符合 Gradle `main` source set 是 production code、Spring Boot `bootBuildImage` 從正式 archive 產 image、Playwright setup project 可在測試前準備環境的官方模型。

---

## 文件索引

| 文件 | 內容 |
|------|------|
| [architecture.md](./architecture.md) | 目標架構、模組邊界、artifact / network / credential 分離 |
| [backend-production-image.md](./backend-production-image.md) | 正式 backend image 的包版邊界與應移除的 E2E 程式 |
| [fixture-runner.md](./fixture-runner.md) | 外部 fixture runner/service 的責任、API/CLI、資料寫入策略 |
| [frontend-playwright.md](./frontend-playwright.md) | Playwright project dependencies、前端 dev/prod 兩種 E2E 模式 |
| [data-flow.md](./data-flow.md) | 端到端流程圖：build、setup、seed、test、teardown |
| [design-decisions.md](./design-decisions.md) | 設計決策、被否決替代方案、對 Skills Hub 的採用建議 |

---

## 技術棧一覽

| 層面 | 技術選擇 | 依據 |
|------|---------|------|
| Production backend image | Spring Boot `bootBuildImage` | `cloudbuild.yaml:95` 正式包版跑 `bootBuildImage`；Spring Boot 官方說該 task 從 jar/war 建 OCI image。 |
| Backend runtime | Spring Boot 4.0.6 + Java 25 + Gradle 9.4.1 | `backend/build.gradle.kts:1-25`。 |
| DB | PostgreSQL 16 / pgvector | `backend/compose.yaml:2-18` 使用 `pgvector/pgvector:pg16`；`application.yaml:35-56` 設 PostgreSQL datasource skeleton。 |
| Frontend E2E target | Vite dev server 或 production static assets | `e2e/playwright.config.ts:72-79` 目前啟 Vite；`cloudbuild.yaml:56-66` 正式 build 會把 frontend dist 複製到 backend static。 |
| E2E orchestration | Playwright project dependencies | Playwright 官方建議 project dependencies 作 setup/teardown，並可進 HTML report / trace。 |
| Fixture environment | Disposable DB/schema 或 fixture runner controlled DB access | Docker/Testcontainers 官方定位是為測試啟動 throwaway real services；OWASP 建議 secure by default / least privilege。 |

---

## 官方資料來源

| 主題 | URL | 本設計使用方式 |
|---|---|---|
| Gradle source sets | https://docs.gradle.org/current/userguide/java_plugin.html | `src/main/java` 是 production source；測試支援程式不該放 main。 |
| Spring Boot `bootRun` | https://docs.spring.io/spring-boot/gradle-plugin/running.html | 現有 `bootRun` 跑 main runtime classpath；E2E 不該靠改 main profile 加測試 endpoint。 |
| Spring Boot `bootBuildImage` | https://docs.spring.io/spring-boot/gradle-plugin/packaging-oci-image.html | 正式 image 從正式 jar/war 產生，fixture 不混入。 |
| Spring `@Profile` | https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/annotation/Profile.html | `@Profile` 只控制 bean 是否註冊，不是包版邊界。 |
| Spring Boot AOT | https://docs.spring.io/spring-boot/gradle-plugin/aot.html | AOT 在 build time 產生 bean view；正式 native image 更不能混測試 bean。 |
| Playwright setup/teardown | https://playwright.dev/docs/test-global-setup-teardown | 用 setup project/teardown project 準備資料，優於 in-app test endpoint。 |
| Playwright API testing | https://playwright.dev/docs/api-testing | Fixture runner 可用 APIRequestContext 或 Node `fetch` 呼叫正式 API。 |
| Testcontainers | https://docs.docker.com/testcontainers/ | 測試依賴應以 throwaway real services 啟動。 |
| OWASP Secure by Default | https://devguide.owasp.org/en/04-design/02-web-app-checklist/01-secure-by-default/ | 正式 app 不包含 debug/test endpoint；不靠設定錯誤才安全。 |
| OWASP Authorization Cheat Sheet | https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html | 採 deny-by-default / least privilege；fixture credential 不可接 prod DB。 |

---

## 與 Skills Hub 的關聯

此研究直接服務 `docs/grimo/specs/2026-05-19-S202-production-e2e-fixture-runner.md`。目前 `e2e/tests/_fixtures.ts:36-84` 直接呼叫 `/internal/test/*`，而 `backend/src/main/java/.../TestDataController.java:77-194` 實作 reset/seed。方案 D 的核心改動是：

1. 正式 backend image 仍由 `cloudbuild.yaml:95-97` 產出，但正式 artifact 掃描必須確認沒有 `TestDataController` / `E2E*Config` / `application-e2e.yaml`。
2. Playwright `e2e/playwright.config.ts:44-49` 加 setup/teardown projects，`chromium` project depend on setup project。
3. `_fixtures.ts` 不再呼叫 `/internal/test/*`；改呼叫 fixture runner 或讀 setup project 寫出的 fixture manifest。
4. Fixture runner 以 ephemeral DB/schema 或 fixture-only DB user 工作，不能取得 production DB credentials。
