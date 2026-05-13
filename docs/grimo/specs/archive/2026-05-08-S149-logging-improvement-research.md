# S149: Cloud Run 結構化日誌改善研究

> Spec: S149 | Size: META(research) | Status: ⛔ cancelled 2026-05-13 — 暫不處理（MVP 階段優先功能開發；日誌可觀測性 noise/missing trace ID 痛感未阻塞當前 critical path）
> Date: 2026-05-08
> Origin: site audit 2026-05-08 — `gcloud logging read` 診斷 503 bug 時發現：LAB 生產環境的日誌為純文字格式（Cloud Logging 無法解析 severity）、Spring framework DEBUG 日誌淹沒應用層訊息、缺少請求關聯 ID，業務事件無 structured logging；需研究最佳實踐再決定實作 sub-spec。
>
> **Cancel rationale**（2026-05-13）：MVP feature-first，平台日誌已可用（`gcloud logging read` 仍能診斷 issue）；結構化 / trace ID 屬可觀測性 polish，非當前 critical path blocker。Origin 痛感（503 診斷困難）已透過個案修補解決，未累積成 ship 阻塞。若未來生產流量增加或多人協作 debug 噪音升高再重新立 spec。本研究筆記（§2 現況、§3 RQ、§4-§5 參考資料、§6 sub-spec 草稿）保留於 archive 供未來引用。

---

## 1. Goal

研究 Spring Boot 4 在 Cloud Run / Cloud Logging 環境下的結構化日誌最佳實踐，產出可行的改善建議與後續實作 sub-spec 清單。

**非目標：**
- 不在此 spec 實作任何日誌變更（實作留給後續 sub-spec）
- 不引入集中式日誌平台（如 ELK stack）— GCP Cloud Logging 即為目標平台

---

## 2. 現況診斷（本次 audit 發現）

### 2.1 日誌格式問題

Cloud Run 容器標準輸出為純文字格式（Spring Boot 預設 console pattern）：

```
2026-05-08T15:32:45.123Z  INFO 1 --- [qualityExecutor-1] i.g.s.s.score.QualityScoreListener  : [quality] skip duplicate
2026-05-08T15:32:46.456Z DEBUG 1 --- [http-nio-8080-exec-3] o.s.s.w.FilterChainProxy : /api/v1/skills at position 1...
```

Cloud Logging 收到後無法自動解析 severity — 所有訊息顯示為 `DEFAULT` severity，無法使用 severity filter 篩選。

### 2.2 日誌層級問題

| Profile | 現況 | 問題 |
|---------|------|------|
| LAB | `io.github.samzhu.skillshub: DEBUG` + root 預設 INFO | Spring 的 `FilterChainProxy`、`AnonymousAuthenticationFilter` 等 DEBUG 訊息大量出現（屬 `org.springframework.security.*`），與應用 DEBUG 訊息混雜，難以定位問題 |
| PROD | 僅靠 root 預設 INFO，沒有顯式配置 | 應用層訊息也被 root INFO 覆蓋，可能有重要 INFO 被 framework DEBUG 淹沒 |
| 全環境 | 無 `spring.*` / `org.springframework.*` 顯式層級設定 | 框架層層級混入應用層輸出 |

### 2.3 可觀測性缺失

- **無請求關聯 ID（Correlation / Trace ID）**：無法跨日誌行追蹤單一請求的完整執行路徑
- **無業務事件結構化 logging**：skill 上傳、品質評分、掃描結果等重要業務事件只有少數 INFO 訊息，無 key-value 結構（`addKeyValue` 雖已使用，但未輸出為 JSON）
- **async 任務無 parent context**：`@Async("qualityExecutor")` 執行的品質評分，在日誌中與觸發請求斷開連接，難以追蹤是哪個 skill upload 觸發了哪次評分

---

## 3. 研究問題

### RQ-1：Spring Boot 4 結構化日誌支援

- Spring Boot 3.4 引入 `logging.structured.format.console`（支援 `logstash`、`ecs`、`gelf` 格式），Spring Boot 4.0.x 是否有變更或新增格式？
- Cloud Run / Cloud Logging 建議的 JSON 格式欄位規範是什麼？（`severity` vs `level`、`message`、`logging.googleapis.com/trace`）
- 哪種 Spring Boot structured format 與 Cloud Logging 兼容最好？（ECS vs Logstash vs GCP 自訂）

### RQ-2：日誌層級最佳實踐

- Production / LAB / Dev 三個環境分別應該設什麼層級？（以 Spring Boot Cloud Run 部署為基準）
- `spring.*`、`org.springframework.*`、`io.github.samzhu.skillshub.*` 各應配置哪個層級？
- 如何在 LAB 保留應用層 DEBUG 資訊的同時，過濾掉框架層 DEBUG 噪音？

### RQ-3：請求關聯 ID（Trace Propagation）

- Spring Boot Actuator + Micrometer Tracing 是否自動注入 `traceId` / `spanId` 至 MDC？
- Cloud Logging + Cloud Trace 整合：是否需要 `spring-cloud-gcp-trace` 依賴才能將 `trace_id` 寫進 Cloud Logging JSON 欄位（`logging.googleapis.com/trace`）？
- Async 任務（`@Async`、Modulith `@ApplicationModuleListener`）的 trace context 傳播：是否需要額外配置？

### RQ-4：業務事件 Structured Logging 慣例

- 現有程式碼已使用 `log.atInfo().addKeyValue("key", val).log(...)` 結構化 API — 這些 key-value 在 plain text 格式下是否有輸出？在 JSON 格式下呈現如何？
- 哪些業務事件應該被視為「可觀測的關鍵節點」並添加結構化 logging？（候選：skill.upload、quality.eval.complete、scan.complete、auth.login、subscription.change）
- Log 中應包含哪些標準 key 名稱以方便 Cloud Logging 查詢？

---

## 4. 現有程式碼參考

| 位置 | 說明 |
|------|------|
| `config/application-lab.yaml` | `logging.level.io.github.samzhu.skillshub: DEBUG`（現況） |
| `config/application-prod.yaml` | 無顯式 logging 配置（現況） |
| `src/main/resources/application.yaml` | `# 日誌：root 預設 INFO 即可（不重複寫）`（現況） |
| `score/QualityScoreListener.java` | `log.atDebug().addKeyValue(...).log(...)` — structured API 已使用 |
| `score/QualityScoreService.java` | `log.atInfo().addKeyValue("skillId").addKeyValue("v/i/a").log(...)` — structured API 已使用 |

---

## 5. 參考資料

### 官方文件

| 資源 | URL |
|------|-----|
| Spring Boot Structured Logging | https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured |
| Cloud Logging — 使用結構化日誌 | https://cloud.google.com/logging/docs/structured-logging |
| Cloud Logging — JSON 欄位對應（severity / trace）| https://cloud.google.com/logging/docs/agent/logging/configuration |
| Micrometer Tracing — Spring Boot 整合 | https://docs.micrometer.io/tracing/reference/index.html |
| spring-cloud-gcp-trace | https://googlecloudplatform.github.io/spring-cloud-gcp/reference/html/index.html#stackdriver-trace |

### 研究論文 / 部落格

| 資源 | 重點 |
|------|------|
| Spring Boot 3.4 Structured Logging 公告 | 介紹 logstash / ecs / gelf 格式的設計決策 |
| GCP Best Practices for Cloud Run Logging | Cloud Run 容器日誌的建議格式與 severity mapping |

---

## 6. 預期產出

1. **RQ-1 ~ RQ-4 書面結論**（各 RQ 一段落）
2. **建議的日誌配置矩陣**（profile × 配置項 二維表）
3. **後續實作 sub-spec 清單草稿**，例如：
   - S149a: 結構化 JSON 日誌格式（Cloud Logging 兼容）+ 日誌層級正規化（XS-S）
   - S149b: 請求 Trace ID 傳播（Micrometer Tracing + async context carry）（S）
   - S149c: 關鍵業務事件 structured log 補全（SKILL.upload / quality / scan）（XS）

---

## 7. 完成條件

- [ ] RQ-1 ~ RQ-4 各有書面結論
- [ ] 建議配置矩陣完成（lab / prod × format / level / tracing）
- [ ] sub-spec 清單草稿（標題 + size 估算 + 優先序）
- [ ] 此 spec 歸檔至 `archive/`，已決定實作的 sub-spec 開立至 `specs/`
