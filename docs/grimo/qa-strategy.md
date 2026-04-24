# Skills Hub — QA Strategy

## Verification Pipeline

### PR Gate

```bash
# Backend
./gradlew test                    # JUnit 5 + Testcontainers
./gradlew modulithTest            # Spring Modulith module boundary verification

# Frontend
cd frontend && npm test           # Vitest
```

### Coverage

```bash
# Backend (JaCoCo)
./gradlew jacocoTestCoverageVerification
# Threshold: 80% line coverage on new code

# Frontend (Vitest coverage)
cd frontend && npm run coverage
# Threshold: 80% line coverage on new code
```

### Architecture / Boundary

```bash
# Spring Modulith 自動驗證 module 依賴邊界
./gradlew test --tests "*ModularityTests*"
```

Spring Modulith 的 `ApplicationModules.verify()` 確保：
- Module 之間沒有非法的直接依賴
- 跨 module 通訊只透過 public API 或事件
- 沒有循環依賴

### API Contract

```bash
# SpringDoc OpenAPI 產生 + 驗證
# API spec 自動從 code 產生，endpoint: /v3/api-docs
```

### SBOM

```bash
# CycloneDX 自動產生 Software Bill of Materials
./gradlew cyclonedxBom
```

---

## Three-Layer Verification

### Layer 1: Automated Tests

| 類型 | 工具 | 說明 |
|------|------|------|
| Unit Test | JUnit 5 / Vitest | 單一 class/function 的邏輯驗證 |
| Integration Test | Spring Boot Test + Testcontainers | 跨元件整合（DB, GCS） |
| Module Test | Spring Modulith `@ApplicationModuleTest` | 單一 module 的完整測試（含 DB） |
| API Test | MockMvc / WebTestClient | REST API 請求/回應驗證 |
| Frontend Component Test | Vitest + React Testing Library | React 元件渲染 + 互動 |

### Layer 2: Integration Verification

| 驗證項目 | 方式 |
|----------|------|
| Firestore MongoDB 相容性 | Testcontainers + MongoDB image（開發階段）; Firestore Emulator（CI） |
| GCS 整合 | Testcontainers + GCS emulator |
| Spring Modulith 邊界 | `ApplicationModules.verify()` |
| API 文件一致性 | SpringDoc 自動產生，人工抽驗 |

### Layer 3: Manual / E2E

| 驗證項目 | 方式 |
|----------|------|
| UI 功能驗證 | 啟動 dev server，手動操作 golden path |
| 跨瀏覽器 | Chrome + Safari（主要目標） |
| 上傳/下載流程 | 端對端手動測試（含大檔案、異常格式） |
| 風險評估準確度 | 準備已知危險/安全的 skill 樣本驗證 |

---

## AC-to-Test Contract

每個 spec 的 acceptance criteria（AC）必須對應測試：

```java
// Backend example
@Test
@DisplayName("AC-1: 用關鍵字搜尋技能 - 回傳 name 或 description 含關鍵字的 skills")
@Tag("AC-1")
void searchByKeyword_returnsMatchingSkills() {
    // ...
}
```

```typescript
// Frontend example
describe('AC-1: 用關鍵字搜尋技能', () => {
  it('should return skills matching keyword in name or description', () => {
    // ...
  });
});
```

一個 spec 被視為 "covered" 的條件：每個 AC id 至少有一個對應的測試。

---

## Development Environment

### Local Dev

```bash
# 啟動後端 + 依賴服務（Grafana LGTM via Docker Compose）
./gradlew bootTestRun    # 使用 TestcontainersConfiguration

# 啟動前端 dev server（hot reload）
cd frontend && npm run dev
```

### Testing with Firestore

- 開發階段：使用 Testcontainers + MongoDB image（模擬 Firestore MongoDB 相容行為）
- CI 階段：使用 Firestore Emulator
- Staging：連接實際 Firestore Enterprise instance
