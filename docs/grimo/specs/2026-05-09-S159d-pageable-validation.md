# S159d: Pageable 非法值拒收 — `page<0` / `size<=0` / `size>100` 一律 400

> Spec: S159d | Size: XS(2) | Status: 📐 in-design
> Date: 2026-05-09
> Origin: 拆自 S159 META §2.2b — query API hardening 子 spec；S159a (v4.43.0) 已 ship 未知 query 參數 fail-fast，此 spec 補 Pageable 數值範圍

---

## 1. Goal

**一句話：** API 收到 `?page=-1` 或 `?size=999999` 時直接 400，不再讓 Spring 預設「-1 當 0」、「999999 觸發 OOM 風險」過去。

**為什麼重要：**
- `?size=999999&sort=createdAt` 會觸發大量 DB row fetch + 反序列化，Cloud Run instance 可能 OOM
- `?page=-1` Spring 預設 silently 變 0，user 看到資料但邏輯不對 — debug 困難
- 對齊 S159a（未知 param 400）的 fail-fast 哲學

**非目標：**
- 不改 sort 欄位 allowlist（另一個議題）
- 不加 cursor-based pagination（重型，未來 spec）

---

## 2. Approach

### 2.1 現況

`SkillQueryController` `@GetMapping("/skills")` 收 `Pageable pageable`，由 Spring Data Web `PageableHandlerMethodArgumentResolver` 解析。預設行為：
- `page<0` → 強制變 0（silent）
- `size<=0` → 用 default size（silent）
- `size > maxPageSize`（預設 2000）→ clamp 到 maxPageSize（silent，不 400）

### 2.2 設計

走 **`PageableHandlerMethodArgumentResolverCustomizer`** bean — Spring 官方 customization point，不需自寫 resolver。

```java
@Configuration
class PageableConfig {
    @Bean
    PageableHandlerMethodArgumentResolverCustomizer pageableCustomizer() {
        return resolver -> {
            resolver.setMaxPageSize(100);    // size > 100 → 後續 validate 觸發 400（見 §2.3）
            resolver.setOneIndexedParameters(false);  // page=0-based 對齊既有 contract
        };
    }
}
```

但 `setMaxPageSize(100)` 仍是 silent clamp。要真正 400 fail-fast 必須**自訂** resolver wrapper 或在 controller 加 validation。

選 **controller-level validation**（最少侵入）：

```java
@GetMapping("/skills")
public Page<Skill> search(@Valid @PageableDefault(size = 20) Pageable pageable) {
    if (pageable.getPageNumber() < 0) throw new InvalidPageableException("page must be >= 0");
    if (pageable.getPageSize() <= 0)  throw new InvalidPageableException("size must be > 0");
    if (pageable.getPageSize() > 100) throw new InvalidPageableException("size must be <= 100");
    // ...
}
```

或更乾淨：寫 `PageableValidator` static helper，所有 controller `pageable` 進來先過。

`InvalidPageableException` extends `IllegalArgumentException`；既有 `GlobalExceptionHandler` 已有 `@ExceptionHandler(IllegalArgumentException.class)` → 400 ErrorResponse。

### 2.3 範圍

掃 controller 凡接 `Pageable`：
- `SkillQueryController` (`/skills`)
- `MyCollectionsController` 若有
- `CollectionsPageController` 若有
- `RequestBoardController` 若有

每個 controller 起手處 call `PageableValidator.validate(pageable)`。

### 2.4 為何不用 Bean Validation `@Valid` on Pageable

`Pageable` 是 Spring 介面，不是 record，無法直接 annotate `@Min`/`@Max`。要用 `@Valid` 必須包一層 DTO，反而複雜。Static helper validator 更直接。

---

## 3. Acceptance Criteria

```
AC-1: page<0 → 400
  Given GET /api/v1/skills?page=-1
  Then HTTP 400 + ErrorResponse{ "error": "INVALID_PAGEABLE", "message": "page must be >= 0" }

AC-2: size<=0 → 400
  Given GET /api/v1/skills?size=0
  Then HTTP 400 + INVALID_PAGEABLE

AC-3: size>100 → 400（防 OOM）
  Given GET /api/v1/skills?size=101
  Then HTTP 400 + INVALID_PAGEABLE

AC-4: 合法 pageable 不影響
  Given GET /api/v1/skills?page=0&size=20
  Then HTTP 200 + Page<Skill>
  And page=2&size=50 也 200

AC-5: 預設值（無 param）不影響
  Given GET /api/v1/skills（無 page/size param）
  Then HTTP 200 + 預設 page=0, size=20

AC-6: 多 controller sweep
  Given 任何 endpoint 接 Pageable
  Then 違規 param 一律 400（不能 controller A 防 controller B 不防）
```

**驗證指令：** `cd backend && ./gradlew test`（含新 `PageableValidatorTest` + `SkillQueryControllerPageableTest`）

---

## 4. Files to Change

### Backend production code

| 檔案 | 變動 |
|------|------|
| `backend/src/main/java/.../shared/web/PageableValidator.java` | **新增** — static helper `validate(Pageable)` |
| `backend/src/main/java/.../shared/api/InvalidPageableException.java` | **新增** — extends IllegalArgumentException |
| `backend/src/main/java/.../shared/api/GlobalExceptionHandler.java` | 確認 `IllegalArgumentException` handler 已涵蓋；若需專屬 ErrorResponse code 加 `@ExceptionHandler(InvalidPageableException.class)` |
| `backend/src/main/java/.../skill/query/SkillQueryController.java` | `@GetMapping("/skills")` 起手 call `PageableValidator.validate(pageable)` |
| 其他接 Pageable 的 controller | 同上 sweep |

### Backend test

| 檔案 | 變動 |
|------|------|
| `backend/src/test/java/.../shared/web/PageableValidatorTest.java` | **新增** — 6 case 覆蓋 AC-1~5 |
| `backend/src/test/java/.../skill/query/SkillQueryControllerPageableTest.java` | **新增** — `@WebMvcTest` slice 跑 4 個 invalid + 2 個 valid |

---

## 5. Test Plan

### 5.1 自動化（gradlew test）

| AC | 驗證方式 |
|----|---------|
| AC-1~3 | `SkillQueryControllerPageableTest` mockMvc.perform(get).param 三 case 各驗 400 + INVALID_PAGEABLE |
| AC-4~5 | 同上但 valid + 預設驗 200 |
| AC-6 | sweep 後跑既有所有 controller test，確認 valid 行為不變 |

### 5.2 手動

無 — 純 API contract 改動，自動化覆蓋足夠。
