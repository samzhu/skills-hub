# S148: Bug — GraalVM Native Image 缺少 JudgeResponse Reflection Config 導致全面 503

> Spec: S148 | Size: S(5) | Status: 📐 in-design
> Date: 2026-05-08（更新 2026-05-08 — 範圍擴展）
> Origin: site audit 2026-05-08 — skill 上傳後後端全面回 503；`gcloud logging read` 確認根因：`UnsupportedFeatureError: Record components not available for record class io.github.samzhu.skillshub.score.judge.JudgeResponse`
>
> **2026-05-08 範圍擴展**：deployment audit 同日發現 **第 2 個** GraalVM reflection metadata gap — `/actuator/configprops` 直接回 500：
> ```
> Cannot reflectively invoke method 'public io.github.samzhu.skillshub.SkillshubProperties$Storage SkillshubProperties.storage()'.
> To allow this operation, add the following to the 'reflection' section of 'reachability-metadata.json'...
> ```
> 兩個 case 共同 root cause：**GraalVM AOT processing 沒涵蓋全部需要反射的 class**。本 spec 範圍從「修一個 JudgeResponse」擴展為「補齊 AOT hints + 加 build-time 驗證機制」，避免下一個 reflection-using class 又出包。

---

## 1. Goal

修復 GraalVM native image 中 `JudgeResponse` 記錄類缺少反射配置的 bug，使 skill 上傳後品質評分能正常執行，消除後端全面 503 現象。

**非目標：**
- 不改 LLM judge prompt 或評分邏輯
- 不改 Modulith outbox 重試機制整體設計

---

## 2. Root Cause

### 2.1 錯誤鏈

```
skill 上傳
  → SkillVersionPublishedEvent 寫進 outbox
    → QualityScoreListener.on() @Async("qualityExecutor")
      → QualityScoreService.evaluateAndPersist()
        → QualityJudge.judgeImplementation()
          → ChatClient.call().entity(JudgeResponse.class)
            → Spring AI BeanOutputConverter → Jackson ObjectMapper.readValue(json, JudgeResponse.class)
              → Class.getRecordComponents()  ← GraalVM native 缺反射 metadata
                → UnsupportedFeatureError: Record components not available for record class
                   io.github.samzhu.skillshub.score.judge.JudgeResponse
```

### 2.2 503 成因分析

`UnsupportedFeatureError` 繼承 `java.lang.Error`（非 `RuntimeException`）：

1. Spring Modulith `@ApplicationModuleListener` 的異常攔截機制只捕 `Exception`，`Error` 類型直接逃逸
2. 逃逸的 `Error` 使 outbox event 留在 `PROCESSING` 狀態（completion_date = NULL）
3. `IncompleteEventRepublishTask`（每分鐘）不斷重投 → 每次都觸發相同 `Error`
4. Virtual threads 承受重投壓力，但每次重投佔用 `qualityExecutor` pool 槽位直到拋出
5. 結合 GraalVM native 對 `Error` 傳播行為的差異，container health check 陷入不穩定 → Cloud Run 回傳 503

### 2.3 為何 JVM 模式沒問題

JVM 模式下 `Class.getRecordComponents()` 由 JDK 反射機制正常支援；GraalVM native image build 時 AOT processor 沒有遇到 `JudgeResponse.class` 的反射存取路徑（因為 `entity(Class<?>)` 是泛型、執行時才傳入 class），故未自動產生 metadata。

### 2.4 受影響的類別

| 類別 | 問題 |
|------|------|
| `JudgeResponse` | 頂層 record，缺反射 metadata |
| `JudgeResponse.DimensionScore` | 巢狀 record，同上 |

---

## 3. Fix Approach

### 3.1 GraalVM 反射註冊（主要修復）

在 `score` module 建立一個 `@Configuration` + `@RegisterReflectionForBinding`，明確告知 Spring AOT 為這兩個記錄類產生反射 metadata：

```java
// score/ScoreNativeConfig.java
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({JudgeResponse.class, JudgeResponse.DimensionScore.class})
class ScoreNativeConfig {
    // 僅供 AOT hint 產生用，無 bean 宣告
}
```

`@RegisterReflectionForBinding` 是 Spring Framework 提供的語意化 annotation，設計用途即是 Jackson data-binding 場景。它為目標類型及其巢狀類型產生 `DECLARED_FIELDS`、`DECLARED_METHODS`、`DECLARED_CONSTRUCTORS` 三種反射 hint，覆蓋 Jackson 的 record 反序列化所需全部路徑。

### 3.2 QualityScoreListener 防護性錯誤處理（次要修補）

`Error` 類型不應被 outbox 無限重試（非暫時性故障）。補 `catch (Throwable t)` 分支：

```java
@ApplicationModuleListener
@Async("qualityExecutor")
void on(SkillVersionPublishedEvent event) {
    if (service.alreadyScored(event.sourceEventId())) {
        log.atDebug()
                .addKeyValue("sourceEventId", event.sourceEventId())
                .log("[quality] skip duplicate sourceEventId");
        return;
    }
    try {
        service.evaluateAndPersist(event);
    } catch (Error e) {
        // Error (e.g. UnsupportedFeatureError) 不可重試 — 記錄後吞掉，避免 outbox 無限重投
        log.atError()
                .addKeyValue("sourceEventId", event.sourceEventId())
                .addKeyValue("error", e.getClass().getSimpleName())
                .setCause(e)
                .log("[quality] non-retryable Error in quality evaluation — skipping outbox retry");
        // 不 re-throw：讓 Modulith 將此 publication 標為 completed（而非 stuck）
    }
    // RuntimeException 仍 re-throw → outbox retry（設計不變）
}
```

> ⚠ S148 修復後，`UnsupportedFeatureError` 不再發生，此防護分支不會觸發。但設計原則不變：`Error` 類型不應被重試機制無限循環。

---

## 4. Acceptance Criteria

```
Scenario: GraalVM native image 品質評分正常完成
  Given 在 native image 環境（Cloud Run）上傳一個有效 SKILL.md
  When QualityJudge.judgeImplementation() / judgeActivation() 呼叫 entity(JudgeResponse.class)
  Then Jackson 成功將 LLM JSON 回應反序列化為 JudgeResponse
  And 沒有 UnsupportedFeatureError 拋出
  And skill_scores 資料表寫入 3 行（VALIDATION / IMPLEMENTATION / ACTIVATION）

Scenario: 上傳 skill 後 API 保持可用
  Given 上傳一個有效的 skill zip
  When 等待非同步品質評分任務完成
  Then 後續 GET /api/v1/skills/{id} 回傳 200
  And 不出現 503 Service Unavailable

Scenario: 反序列化單元測試
  Given 一段模擬 LLM 回應的 JSON 字串（含 scores 陣列 + verdict）
  When Jackson ObjectMapper.readValue(json, JudgeResponse.class)
  Then 成功建立 JudgeResponse 物件
  And scores 清單長度正確
  And DimensionScore.dimension / score / reasoning 欄位值正確
```

---

## 5. Files to Change

| 檔案 | 變動 |
|------|------|
| `backend/src/main/java/.../score/ScoreNativeConfig.java` | 新增（`@RegisterReflectionForBinding` for `JudgeResponse` + `DimensionScore`） |
| `backend/src/main/java/.../score/QualityScoreListener.java` | 補 `catch (Error e)` 防護分支 |
| `backend/src/test/java/.../score/JudgeResponseDeserializationTest.java` | 新增（驗證 Jackson 反序列化 record 不拋錯） |

---

## 6. Test Plan

- [ ] `JudgeResponseDeserializationTest`：正確 JSON → `JudgeResponse` 物件，無例外
- [ ] `JudgeResponseDeserializationTest`：`DimensionScore` 巢狀結構 (dimension / score / reasoning) 欄位全部映射正確
- [ ] `JudgeResponseDeserializationTest`：少一個必要欄位的 JSON → graceful error（不 crash JVM）
- [ ] 手動驗證（LAB）：上傳 skill 後 `GET /api/v1/skills/{id}` 回 200
- [ ] 手動驗證（LAB）：Cloud Run logs 無 `UnsupportedFeatureError`
- [ ] 手動驗證（LAB）：`skill_scores` 確認有 3 行 (VALIDATION / IMPLEMENTATION / ACTIVATION)
