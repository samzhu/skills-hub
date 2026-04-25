# Spring Boot 設定檔最佳化 — 研究報告

## 研究目標

分析 Spring Boot 設定檔的最佳實務，結合 `springboot-config-organizer` 技能的雙層 Profile 設計原則，
評估 Skills Hub 專案現有配置的改善空間。

## 參考來源

| 來源 | 用途 |
|------|------|
| [springboot-config-organizer](https://github.com/samzhu/agent-skills/tree/main/skills/springboot-config-organizer) v1.2.0 | 雙層 Profile 設計原則、機敏值管理策略、設定檔模板 |
| [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html) | 官方配置載入順序、優先級規則 |
| [Spring Boot Profiles](https://docs.spring.io/spring-boot/reference/features/profiles.html) | Profile 啟用策略、Profile Groups |
| [Spring Cloud GCP](https://github.com/GoogleCloudPlatform/spring-cloud-gcp/blob/main/README.adoc) | GCP 特定配置屬性 |
| [Spring Boot Application Properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) | 完整屬性列表 |

## 技術棧

| 技術 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 4.0.6 | 應用框架 |
| Spring Cloud GCP | 8.0.2 BOM | GCP 整合（Storage, Firestore） |
| Spring AI | 2.0.0-M4 | Gemini embedding（語意搜尋） |
| Spring Data MongoDB | BOM | Firestore Enterprise（MongoDB 驅動） |
| Java | 25 | 程式語言 |
| Gradle | 9.4.1 | 建置工具 |

## 文件索引

| 文件 | 內容 |
|------|------|
| [dual-layer-profile-design.md](dual-layer-profile-design.md) | 雙層 Profile 設計原則 + Spring Boot 配置載入順序 |
| [secrets-management.md](secrets-management.md) | 本地機敏值 + GCP Secret Manager 策略 |
| [spring-cloud-gcp-config.md](spring-cloud-gcp-config.md) | GCP 特定配置屬性與最佳實務 |
| [data-flow.md](data-flow.md) | 配置解析流程圖 + Profile 組合矩陣 |
| [design-decisions.md](design-decisions.md) | 決策表 + Skills Hub 改善分析 |

## Skills Hub 現況 Gap 分析摘要

### 已做到（符合最佳實務）

- 雙層 Profile 設計：基礎設施層（`local`, `gcp`）× 行為層（`dev`, `prod`）
- `src/main/resources/` vs `config/` 分層正確
- `spring.profiles.default: local,dev` 零配置開發
- `config/application-secrets.properties` 機敏值分離 + `.gitignore` 排除
- 統一屬性名稱前綴 `skillshub-` 部分已採用

### 待改善

| # | 問題 | 嚴重度 | 說明 |
|---|------|--------|------|
| 1 | 屬性命名不一致 | 中 | `skillshub-mongodb-uri` vs `SKILLSHUB_VECTOR_STORE` vs `GCP_PROJECT_ID` 混用三種命名風格 |
| 2 | 缺少 `lab` 環境 profile | 低 | 用戶需要 `profiles.active=lab,gcp` 但無 `config/application-lab.yaml` |
| 3 | `application-local.yaml` 職責過重 | 中 | 混合了 Docker Compose 啟用、GCP 自動配置排除、AI embedding 禁用，三類不同關注點 |
| 4 | GCP 環境未用 Secret Manager 整合 | 中 | `application-gcp.yaml` 直接用 `${GCP_PROJECT_ID}` 環境變數，未利用 `spring.config.import=gcp-secretmanager://` |
| 5 | `.example` 檔案含真實格式但缺說明 | 低 | `application-secrets.properties.example` 可加更多說明 |
| 6 | 設定檔區塊註解不完整 | 低 | 部分配置缺少設計意圖說明 |
