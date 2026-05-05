# S135a — Backend Quality Score (Validation + LLM judge + endpoint)

> **Status**: 📐 in-design (sections 1-5 ready；§6 task plan + §7 result 由 /planning-tasks 後續加入)
> **Type**: Backend feature spec
> **Estimate**: M(13-15)
> **Parent META**: [`2026-05-05-S135-meta-skill-quality-score-system.md`](./2026-05-05-S135-meta-skill-quality-score-system.md) — §5 跨 sub 共用設計
> **Depends**: 既有 `Skill` aggregate + `SkillVersionPublishedEvent` (S024 ship) + Spring AI 升級 M4 → M5
> **Sibling**: S135b Frontend Quality Display (awaiting this spec's API contract)
> **Research**: [`research/2026-05-05-tessl-skill-platform-study.md`](../research/2026-05-05-tessl-skill-platform-study.md) §3 Quality 8-dim

---

## §1 Goal

加 per-skill **Quality 軸後端評分** — 對齊 Tessl Registry 8-dim 評分模式，由 publish skill 觸發 async LLM judge，把結果寫入 `skill_scores` 表，提供 `GET /api/v1/skills/{id}/scores` 給前端 hero 進度條 + Quality tab 消費。

```
publish skill v1.2.0
    │
    ▼ (sync TX)
SkillVersionPublishedEvent → event_publication outbox
    │
    ▼ (AFTER_COMMIT, async on qualityExecutor pool)
QualityScoreListener.on(event)
    │
    ├─ Validation (rule-based, 既有 SkillValidator + 9 新 rules)
    ├─ Implementation LLM judge (4 dim × 0-3, Gemini 2.5 Flash)
    └─ Activation     LLM judge (4 dim × 0-3, Gemini 2.5 Flash)
    │
    ▼ (Direction A wide-JSONB)
INSERT 3 rows into skill_scores (per axis × per evaluation)
    │
    ▼ (read path)
GET /api/v1/skills/{id}/scores → 3-axis breakdown JSON
```

**Total score 公式**（per META §5.6 Option A，user 確認 2026-05-05）：

```
Total = 0.2 × Validation + 0.4 × Implementation + 0.4 × Activation
```

### Out of Scope (留 META / 後續 spec)

| 項目 | 去向 |
|---|---|
| Frontend hero 進度條 + Quality tab + SkillCard badge | S135b |
| Weekly recalc cron / Admin manual recalc UI / Score audit log | 後續 spec |
| Impact 軸 | S136 Backlog |
| SKILL.md 渲染 / sidebar / install dropdown polish | S137 UI Polish Pass |

## §2 Approach

### §2.1 Chosen approach (single approach — alternatives challenged)

**Single LLM judge bean with 3-method API（評估三類）+ 獨立 qualityExecutor pool + Direction A schema + sourceEventId idempotency**。

**為何不切多 LLM judge bean（每類一個 bean）**：3 類 prompt 不同但 API 一致；同 bean 不同 method 共用 ChatClient + temperature=0 設定，少 1/3 bean wiring 樣板。

**為何不切同步 Validator + 異步 LLM judge listener**：Validation 已是 publish-time hard gate（既有 `SkillCommandService.validate()` throw 擋 publish）。S135a 的 Validation **不是** 重複 publish-time gate，而是把通過項目 + warning 項目轉成 0-100 score 寫入 `skill_scores`（per Tessl Validation 顯示模式）。所以 publish-time Validation 與 S135a Validation 互不阻塞 — 不需切分。

**為何不用 Direction B（per-dim per-row long table）**：sub-agent #4 對比表已驗證 — Direction A 結構更簡單、reuse 既有 `Map<String,Object>` JSONB converter、避開 `@MappedCollection` delete-and-reinsert 雷（per ADR-002 §2.3 + S014 archived §2.1 決策 #6）。

**為何不共用 `applicationTaskExecutor`**：LLM judge 5-30s/call 比 既有 listener（AuditEventListener / AnalyticsProjection 數毫秒）慢 100-1000x；共用 corePool=2/queue=200 會擠爆其他 listener，也使 publish 流量驟增時 caller-runs 同步阻塞 publisher thread。獨立 `qualityExecutor`（corePool=1, queue=500）做性能隔離。

### §2.2 POC ✅ Completed 2026-05-05 (planning-tasks Phase 1)

[Implementation note 2026-05-05] POC 驗證 spec 原假設 `spring-ai-vertex-ai-gemini` 為錯誤 — 實際專案 build.gradle.kts 線 56-58 用的是 `spring-ai-google-genai` 路徑（Vertex AI 與 Google GenAI 是兩個不同 SDK，不是 rename）。所有 `VertexAiGemini*` class 引用已修正為 `GoogleGenAi*`（見 §4.2 / §4.3）。CLAUDE.md / architecture.md 寫「Gemini (via Vertex AI)」是 stale doc drift — 已加入 §5.2 修正清單。

**POC findings**:

| Q | Result | Source |
|---|---|---|
| Q1: `spring-ai-google-genai` 在 M5 存活？ | ✅ Yes — Maven Central + Spring AI docs 確認 2.0.0-M5 has artifact | <https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html> |
| Q2: `gemini-2.5-flash` 是合法 model identifier？ | ✅ Yes — Google GenAI Chat docs 列為 supported | 同上 |
| Q3: API surface 相容 ChatClient.entity() pattern？ | ✅ Yes — ChatClient 為 provider-agnostic；GoogleGenAiChatModel 透過 `ChatClient.builder(model)` 注入後 `.prompt().user().call().entity(Bean.class)` 呼叫鏈一致 | Spring AI ChatClient docs |
| Q4: temperature / topK / topP / candidateCount builder method 名稱與 Vertex AI 路徑一致？ | ✅ Yes — `GoogleGenAiChatOptions.builder().temperature(0.0f).topK(1).topP(1.0f).candidateCount(1).build()` | docs.spring.io google-genai-chat |
| Q5: `gemini-2.5-flash-001` 形式 snapshot suffix？ | ⚠️ NOT_DOCUMENTED — Google AI docs 只說 stable alias `gemini-2.5-flash`；preview 才用 dated suffix（`gemini-2.5-flash-preview-09-2025`）| <https://ai.google.dev/gemini-api/docs/models> |

**Decision**：採 stable alias `gemini-2.5-flash` + `evaluator_version` column 存 fetched-at timestamp 偵測 drift（per META §5.5 fallback plan）；`-001` 形式 stable snapshot 在 2.5 上不存在。

**Fallback plan 不啟用**：原 Plan B (Vertex AI Java SDK 直接) + Plan C (暫留 M4) 不需要 — Google GenAI 路徑已 verify 可用。

### §2.3 Research Citations

| Source | 1-line summary |
|---|---|
| [Tessl Registry research §3](../research/2026-05-05-tessl-skill-platform-study.md#§3-quality-評分系統精確版) | 3 類 × 8 dim Quality 評分結構（Validation rule-based / Implementation LLM body / Activation LLM description） |
| [Spring AI ChatClient docs](https://docs.spring.io/spring-ai/reference/api/chatclient.html) | M5 主流 `.prompt().user().call().entity(Bean.class)` BeanOutputConverter 內部 |
| [Spring AI LLM-as-Judge guide](https://docs.spring.io/spring-ai/reference/guides/llm-as-judge.html) | `@JsonClassDescription` + `@JsonPropertyDescription` record pattern |
| [Spring AI v2.0.0-M5 release notes](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M5) | M4→M5 breaking changes（Azure removed / VertexAI non-embedding 部分移除 / options merging combineWith()）|
| [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing) | 2.5 Flash $0.30/$2.50 vs Pro $1.25/$10.00 per 1M tokens — 4x cost diff |
| [Gemini model versions](https://ai.google.dev/gemini-api/docs/models) | stable alias vs preview-dated；`-001` suffix pin stable endpoint |
| [Gemini thinking](https://ai.google.dev/gemini-api/docs/thinking) | thinkingBudget 0-32768；rubric scoring 不需 deep thinking → low budget |
| `S091 archived spec` `docs/grimo/specs/archive/2026-05-02-S091-llm-judge-prompt-calibration.md` | Prompt structure 原則 + fixture-based smoke methodology reuse-able；model 設定值 S135a 全新定義 |
| `agentskills.io spec` <https://agentskills.io/specification> | name / description / metadata / allowed-tools 欄位規範 |
| `AuditEventListener.java:44-54` | deterministic UUID + ON CONFLICT DO NOTHING idempotency pattern reuse |
| `ScanOrchestrator` | sourceEventId early-return + swallow 例外 — S135a 採前者不採後者（score missing 比 silent wrong score 易偵測） |
| `S025a archived spec` | `Scenario.publish().andWaitForStateChange().andVerify()` 測試 pattern + `.andWaitAtMost(15s)` LLM-heavy override |
| `S023 archived spec` | Modulith outbox + `@ApplicationModuleListener` AFTER_COMMIT + REQUIRES_NEW |

## §3 Acceptance Criteria

> 命名格式對齊 `qa-strategy.md` §AC-to-Test Contract — `@DisplayName("AC-S135a-N: ...")` + `@Tag("AC-S135a-N")`。
> Verify command: `./gradlew clean test jacocoTestReport`（per `qa-strategy.md` V01）。

```
Scenario: AC-S135a-1 — publish 觸發 3-axis 評分寫入
  Given skill `demo-skill` v1.0.0 SKILL.md 內容合規
  When publish 該版本 (POST /api/v1/skills/{id}/versions)
  Then SkillVersionPublishedEvent 進 outbox
  And async listener 完成後（5s 內）
  And skill_scores 表有 3 rows（axis = VALIDATION / IMPLEMENTATION / ACTIVATION）
  And 每 row 含 dimensions JSONB + total_score (0-100) + evaluator_version

Scenario: AC-S135a-2 — sourceEventId 重複事件不重算（idempotent）
  Given 同 SkillVersionPublishedEvent 因 outbox retry 被投遞 3 次
  When QualityScoreListener 處理
  Then 只第一次跑 LLM judge
  And skill_scores 該 (skillId, sourceEventId, axis) 仍只有 3 rows（每 axis 1 row）

Scenario: AC-S135a-3 — GET /scores 回 3-axis breakdown
  Given skill `demo-skill` 已評分
  When GET /api/v1/skills/{id}/scores
  Then 200 OK
  And response 含 validation / implementation / activation / total 四個 key
  And 每 axis 含 totalScore (0-100) + dimensions {name: {score, reasoning}}
  And total = round(0.2 × V.totalScore + 0.4 × I.totalScore + 0.4 × A.totalScore)

Scenario: AC-S135a-4 — 未評分 skill 回 404 + QUALITY_NOT_EVALUATED
  Given skill `pending-skill` v1.0.0 剛 publish，listener 還沒跑完
  When GET /api/v1/skills/{id}/scores
  Then 404
  And response.error = "QUALITY_NOT_EVALUATED"
  And response.message = "Score will be available shortly after publish."

Scenario: AC-S135a-5 — Validation 規則擴充（hard error）
  Given SKILL.md content 含 600 行（超過 500-line cap）
  When SkillValidator.validate() 執行
  Then result.valid() == false
  And result.errors() 含 "skill_md_line_count: SKILL.md has 600 lines (max 500)"

  Given SKILL.md name = "my--skill"（連續 hyphen）
  When validate
  Then errors 含 "name: consecutive hyphens not allowed"

  Given SKILL.md frontmatter 後沒有 body
  When validate
  Then errors 含 "body_present: SKILL.md has no body content after frontmatter"

Scenario: AC-S135a-6 — Validation 規則擴充（soft warning）
  Given SKILL.md body 沒有 "## Example" heading 也沒有 code fence
  When validate
  Then result.valid() == true（warning 不擋 publish）
  And result.warnings() 含 "body_examples: no example heading or code fence detected"

Scenario: AC-S135a-7 — ValidationResult 4-arg backward compat
  Given 既有 caller `SkillCommandService.validate(...)` 只用 .valid() / .errors()
  When 該 caller 不修改 source code
  Then 編譯通過 + 行為不變
  And ValidationResult.of(valid, metadata, errors) factory 仍可用（warnings 預設 List.of()）

Scenario: AC-S135a-8 — LLM judge reproducibility
  Given 同 SKILL.md content + same evaluator_version
  When QualityJudge.judgeImplementation() 跑 5 次
  Then 5 次的 totalScore 差異 ≤ 5 pt
  And 每 dim 個別 score 差異 ≤ 1
  (verify by 10+ known-score fixture corpus regression suite — see §6 T05)

Scenario: AC-S135a-9 — Listener 用獨立 qualityExecutor pool
  Given 100 個 skill 同時 publish（壓測）
  When QualityScoreListener 處理
  Then qualityExecutor 不爭用 applicationTaskExecutor
  And applicationTaskExecutor 仍能服務 AuditEventListener / AnalyticsProjection（不被 LLM 阻塞）
  (verify by Micrometer thread pool gauge metrics)

Scenario: AC-S135a-10 — LLM 失敗 re-throw → outbox retry
  Given QualityJudge call Gemini 拋 SocketTimeoutException
  When QualityScoreListener 處理
  Then re-throw RuntimeException
  And event_publication.completion_date stays NULL
  And IncompleteEventRepublishTask 後續 retry
  And skill_scores 不寫 partial row（TX rollback）

Scenario: AC-S135a-11 — Total score 公式正確
  Given V.totalScore = 100, I.totalScore = 85, A.totalScore = 92
  When response 計算 total
  Then total = round(0.2 × 100 + 0.4 × 85 + 0.4 × 92) = round(20 + 34 + 36.8) = 91
```

## §4 Interface Design

### §4.1 Module structure（new `score/` module per Modulith pattern）

```
io.github.samzhu.skillshub.score
├── domain/
│   ├── SkillScore.java                     ← @Table("skill_scores") + Persistable<String>
│   ├── SkillScoreRepository.java           ← ListCrudRepository + 2 derived queries
│   └── QualityAxis.java                    ← enum VALIDATION / IMPLEMENTATION / ACTIVATION
├── judge/
│   ├── JudgeResponse.java                  ← record with @JsonClassDescription
│   ├── QualityJudge.java                   ← @Component — Spring AI ChatClient wrapper
│   └── RubricPrompts.java                  ← static system prompts (Implementation + Activation 各一)
├── QualityScoreService.java                ← orchestrate: load skill → run 3 axes → save 3 rows
├── QualityScoreListener.java               ← @ApplicationModuleListener on SkillVersionPublishedEvent
├── QualityScoreController.java             ← GET /api/v1/skills/{id}/scores
└── QualityExecutorConfig.java              ← qualityExecutor bean
```

### §4.2 Key class signatures

```java
// domain/SkillScore.java
@Table("skill_scores")
public class SkillScore implements Persistable<String> {
    @Id private String id;                          // UUID.nameUUIDFromBytes(skillVersionId|axis|sourceEventId)
    @Column("skill_id") private String skillId;
    @Column("skill_version_id") private String skillVersionId;
    @Column("skill_version") private String skillVersion;
    private QualityAxis axis;                       // VARCHAR(20) via enum-to-string converter
    @Column("total_score") private BigDecimal totalScore;   // 0-100
    private Map<String, Object> dimensions;         // JSONB via 既有 MapJsonbConverter
    @Column("evaluated_at") private Instant evaluatedAt;
    @Column("evaluator_version") private String evaluatorVersion;
    @Column("source_event_id") private String sourceEventId;
    @Transient private boolean isNew = false;       // explicit pattern (per SkillVersion)

    public static SkillScore of(String skillId, String skillVersionId, String skillVersion,
                                QualityAxis axis, BigDecimal totalScore,
                                Map<String, Object> dimensions, String evaluatorVersion,
                                String sourceEventId) { ... }
    @PersistenceCreator private SkillScore() {}
    @JsonIgnore @Override public boolean isNew() { return isNew; }
}

// domain/SkillScoreRepository.java
public interface SkillScoreRepository extends ListCrudRepository<SkillScore, String> {
    boolean existsBySourceEventId(String sourceEventId);

    @Query("SELECT DISTINCT ON (axis) * FROM skill_scores " +
           "WHERE skill_id = :skillId ORDER BY axis, evaluated_at DESC")
    List<SkillScore> findLatestBySkillId(@Param("skillId") String skillId);

    @Query("SELECT DISTINCT ON (axis) * FROM skill_scores " +
           "WHERE skill_version_id = :skillVersionId ORDER BY axis, evaluated_at DESC")
    List<SkillScore> findLatestBySkillVersionId(@Param("skillVersionId") String skillVersionId);
}

// judge/JudgeResponse.java
@JsonClassDescription("Quality evaluation result for a SKILL.md axis.")
public record JudgeResponse(List<DimensionScore> scores, String verdict) {
    @JsonClassDescription("Score and reasoning for one evaluation dimension.")
    public record DimensionScore(
        @JsonPropertyDescription("Dimension name (axis-specific enum)")
        String dimension,
        @JsonPropertyDescription("Score 0-3: 0=missing, 1=weak, 2=adequate, 3=excellent")
        int score,
        @JsonPropertyDescription("1-2 sentences explaining the score; shown in UI")
        String reasoning
    ) {}
}

// judge/QualityJudge.java
@Component
public class QualityJudge {
    private final ChatClient client;
    // [Implementation note 2026-05-05 POC]: 專案用 spring-ai-google-genai 路徑（非 Vertex AI）
    // — 實際 import org.springframework.ai.google.genai.GoogleGenAi*
    public QualityJudge(GoogleGenAiChatModel gemini) {
        var opts = GoogleGenAiChatOptions.builder()
            .model("gemini-2.5-flash")          // stable alias；2.5 系列無 -001 snapshot；用 evaluator_version 偵測 drift
            .temperature(0.0f).topK(1).topP(1.0f).candidateCount(1)
            // thinkingBudget — T01 smoke 階段 verify Google GenAI options 是否暴露（or via runtime model property）
            .build();
        this.client = ChatClient.builder(gemini).defaultOptions(opts).build();
    }
    public JudgeResponse judgeImplementation(String skillBody) {
        return client.prompt()
            .system(RubricPrompts.IMPLEMENTATION_SYSTEM)
            .user(u -> u.text("<skill_body>{body}</skill_body>").param("body", skillBody))
            .call().entity(JudgeResponse.class);
    }
    public JudgeResponse judgeActivation(String description) {
        return client.prompt()
            .system(RubricPrompts.ACTIVATION_SYSTEM)
            .user(u -> u.text("<skill_description>{desc}</skill_description>").param("desc", description))
            .call().entity(JudgeResponse.class);
    }
    public String evaluatorVersion() {       // for skill_scores.evaluator_version column
        return "gemini-2.5-flash@2026-05-05-prompt-v1";
    }
}

// QualityScoreListener.java
@Component
class QualityScoreListener {
    private final QualityScoreService service;
    QualityScoreListener(QualityScoreService service) { this.service = service; }

    @ApplicationModuleListener
    @Async("qualityExecutor")    // 獨立 pool (per §4.4)
    void on(SkillVersionPublishedEvent event) {
        if (service.alreadyScored(event.sourceEventId())) {
            log.debug("[quality] skip duplicate sourceEventId={}", event.sourceEventId());
            return;
        }
        service.evaluateAndPersist(event);   // re-throw on failure → outbox retry
    }
}

// QualityScoreService.java
@Service
public class QualityScoreService {
    @Transactional   // REQUIRES_NEW via @ApplicationModuleListener wrapper
    public void evaluateAndPersist(SkillVersionPublishedEvent event) {
        // 1. Validation rule-based score (既有 SkillValidator + 9 new rules)
        var vScore = validationScore(event);
        // 2. Implementation LLM judge
        var iScore = implementationScore(event);
        // 3. Activation LLM judge
        var aScore = activationScore(event);
        // 4. Save 3 rows (idempotent — deterministic UUID + ON CONFLICT DO NOTHING)
        scoreRepo.saveAll(List.of(vScore, iScore, aScore));
    }
    public boolean alreadyScored(String sourceEventId) {
        return scoreRepo.existsBySourceEventId(sourceEventId);
    }
}

// QualityScoreController.java
@RestController
@RequestMapping("/api/v1/skills/{skillId}/scores")
public class QualityScoreController {
    @GetMapping
    public ResponseEntity<ScoreResponse> getScores(@PathVariable String skillId,
                                                    @RequestParam(required = false) String versionId) {
        var rows = (versionId != null)
            ? scoreRepo.findLatestBySkillVersionId(versionId)
            : scoreRepo.findLatestBySkillId(skillId);
        if (rows.isEmpty()) {
            throw new NotEvaluatedException(skillId);   // → 404 QUALITY_NOT_EVALUATED via既有 ErrorResponseHandler
        }
        return ResponseEntity.ok(ScoreResponse.from(rows));   // 計算 total = 0.2V + 0.4I + 0.4A
    }
}
```

### §4.3 API response shape

```json
GET /api/v1/skills/{id}/scores → 200 OK

{
  "skillId": "550e8400-e29b-41d4-a716-446655440000",
  "skillVersionId": "660e8400-...",
  "skillVersion": "1.2.0",
  "evaluatedAt": "2026-05-05T10:30:00Z",
  "evaluatorVersion": "gemini-2.5-flash@2026-05-05-prompt-v1",
  "validation": {
    "totalScore": 100,
    "dimensions": {
      "lineCount":   { "score": 100, "reasoning": "412 / 500 lines" },
      "bodyPresent": { "score": 100, "reasoning": "..." },
      "warnings": ["body_examples: no example heading detected"]
    }
  },
  "implementation": {
    "totalScore": 85,
    "dimensions": {
      "conciseness":         { "score": 2, "reasoning": "Some redundancy between sections" },
      "actionability":       { "score": 3, "reasoning": "Concrete, specific guidance with file refs" },
      "workflowClarity":     { "score": 3, "reasoning": "Sequential 3-step process with checkpoints" },
      "progressiveDisclosure": { "score": 3, "reasoning": "Well-structured references" }
    }
  },
  "activation": {
    "totalScore": 92,
    "dimensions": {
      "specificity":      { "score": 3, "reasoning": "Lists multiple concrete actions" },
      "completeness":     { "score": 3, "reasoning": "Answers what + when + exclusions" },
      "triggerTermQuality": { "score": 3, "reasoning": "Strong natural keywords" },
      "distinctiveness":  { "score": 3, "reasoning": "Clear niche with explicit boundaries" }
    }
  },
  "total": 91
}
```

### §4.4 Threading / Executor

```java
// QualityExecutorConfig.java
@Configuration
class QualityExecutorConfig {
    @Bean(name = "qualityExecutor")
    public TaskExecutor qualityExecutor() {
        var ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(1);
        ex.setQueueCapacity(500);   // 緩衝高峰 publish；queue 滿時 caller-runs（保守）
        ex.setThreadNamePrefix("quality-judge-");
        ex.initialize();
        return new DelegatingSecurityContextAsyncTaskExecutor(ex);
    }
}
```

理由（per META §5.3）：LLM judge 5-30s/call；獨立 pool 避免擠 `applicationTaskExecutor`（corePool=2 / queue=200）影響 AuditEventListener / SearchProjection 等其他 listener。

### §4.5 SQL migration

V15 schema 同 META §5.1（不重複）。檔名：`backend/src/main/resources/db/migration/V15__create_skill_scores.sql`。

## §5 File Plan

### §5.1 New files

```
backend/src/main/java/io/github/samzhu/skillshub/score/
├── domain/
│   ├── SkillScore.java                                                    [NEW]
│   ├── SkillScoreRepository.java                                          [NEW]
│   └── QualityAxis.java                                                   [NEW]
├── judge/
│   ├── JudgeResponse.java                                                 [NEW]
│   ├── QualityJudge.java                                                  [NEW]
│   ├── RubricPrompts.java                                                 [NEW]
│   └── StubQualityJudge.java                                              [NEW] (test profile only)
├── QualityScoreService.java                                               [NEW]
├── QualityScoreListener.java                                              [NEW]
├── QualityScoreController.java                                            [NEW]
├── ScoreResponse.java                                                     [NEW] (DTO record)
├── NotEvaluatedException.java                                             [NEW]
└── QualityExecutorConfig.java                                             [NEW]

backend/src/main/resources/db/migration/
└── V15__create_skill_scores.sql                                           [NEW]

backend/src/test/java/io/github/samzhu/skillshub/score/
├── QualityScoreListenerTest.java                                          [NEW] (@ApplicationModuleTest + Scenario)
├── QualityJudgeFixtureTest.java                                           [NEW] (10+ fixture regression suite)
├── QualityScoreServiceTest.java                                           [NEW] (unit + slice)
├── SkillScoreRepositoryTest.java                                          [NEW] (extends RepositorySliceTestBase)
└── QualityScoreControllerTest.java                                        [NEW] (extends WebMvcSliceTestBase)

backend/src/test/resources/score-fixtures/
├── high-quality-skill.md                                                  [NEW] (expected total ~95)
├── medium-quality-skill.md                                                [NEW] (~70)
├── low-quality-skill.md                                                   [NEW] (~40)
└── ...至少 10 個 known-score fixtures
```

### §5.2 Modified files

| File | 變更 |
|---|---|
| `backend/build.gradle.kts` | Spring AI BOM `2.0.0-M4` → `2.0.0-M5`（per T01 POC outcome）|
| `backend/src/main/java/.../skill/validation/SkillValidator.java` | 新增 9 條 rule（6 hard + 3 warning）|
| `backend/src/main/java/.../skill/validation/ValidationResult.java` | 加 `warnings` 欄位（4-arg）+ `of(...)` static factory |
| `backend/src/main/resources/application.yaml` | 加 `skillshub.quality.judge.*` properties（model name, prompt-version）|
| `backend/src/main/resources/config/application-dev.yaml` | 加 `skillshub.quality.judge.enabled=true`（dev local 走 real Gemini）|
| `backend/src/main/resources/config/application-test.yaml` | 加 `skillshub.quality.judge.enabled=false`（test 走 `StubQualityJudge`）|
| `backend/src/test/java/.../TestcontainersConfiguration.java` | 加 `@Bean @Primary StubQualityJudge`（per S025a anti-`@MockitoBean` 規範）|
| `docs/grimo/architecture.md` | (1) Spring AI version M4 → M5; (2) Module table 加 `score/`; (3) Module event flow 加 QualityScoreListener; (4) **AI 行 stale doc fix「Gemini (via Vertex AI)」→「Gemini (via Google GenAI direct, `spring-ai-google-genai`)」**（per POC §2.2 Q1 finding）|
| `CLAUDE.md` (root) | (1) Tech stack「Spring AI 2.0.0-M4 + Gemini (via Vertex AI)」→「Spring AI 2.0.0-M5 + Gemini (via Google GenAI direct)」 (2) `architecture.md` 段同步 stale doc fix |
| `docs/grimo/glossary.md` | 加 `Quality Score` / `LLM Judge` / `evaluator_version` 三個 term |

### §5.3 No file pre-creation

S135b（frontend）需要的 component / TypeScript types 不在 S135a 創建 — per planning-spec **Forbidden File-Plan Patterns**「XS or S spec MUST NOT pre-create files for downstream specs that have not yet shipped」。S135b 自己負責 frontend 部分。

---

## §6 Task Plan

> **POC decision**: ✅ Completed 2026-05-05 (planning-tasks Phase 1 — see §2.2)；Plan B / Plan C fallback 不啟用（Google GenAI 路徑已 verify 在 M5 可用）。

### Task index

| Task | Topic | Depends | Est | AC Coverage |
|------|-------|---------|-----|-------------|
| **T01** | Spring AI M5 BOM upgrade + smoke test + doc-sync (architecture.md / CLAUDE.md stale Vertex AI fix) | — | XS | POC follow-through (no AC) |
| **T02** | SkillValidator 9 new rules (6 hard + 3 warning) + ValidationResult 4-arg backward compat | T01 | S | AC-5 / AC-6 / AC-7 |
| **T03** | skill_scores DB layer (V15 migration + SkillScore aggregate + repository) | T01 | S | AC-1 partial / AC-2 (DB-level idempotency) |
| **T04** | QualityJudge + RubricPrompts + 10-fixture reproducibility regression suite | T01 | M | AC-8 |
| **T05** | QualityScoreService + Listener + qualityExecutor pool + Scenario test | T01 / T03 / T04 | M | AC-1 (full path) / AC-2 (event-level) / AC-9 / AC-10 / AC-11 |
| **T06** | QualityScoreController GET endpoint (200 / 404) | T03 | XS | AC-3 / AC-4 |
| **T07** | E2E artifact smoke test (real Gemini + boundary scenarios) | T01-T06 | S | E2E artifact verification (Phase 4 Step 1.5 mandatory) |

### Execution order

```
T01 (BOM upgrade)
  ├─→ T02 (Validation rules)        ┐
  ├─→ T03 (DB layer)                ├─→ T05 (Service + Listener)  ┐
  ├─→ T04 (Judge + fixture corpus)  ┘                              ├─→ T07 (E2E)
  └─→ ...                                                          │
T03 ─→ T06 (Controller GET endpoint)                              ─┘
```

T01 unblock 4 條平行支線（T02 / T03 / T04 / T06 可平行）；T05 是 orchestration 收束點需要 T03+T04 ready；T07 為 final E2E gate。

### AC-to-task coverage matrix

| AC | Covered by Task |
|----|------------------|
| AC-S135a-1 (publish 觸發 3-axis 寫入) | T03 (DB-level partial) + T05 (full path) + T07 (E2E re-verify) |
| AC-S135a-2 (sourceEventId idempotency) | T03 (DB ON CONFLICT) + T05 (event-level early-return) |
| AC-S135a-3 (GET /scores 200) | T06 + T07 (E2E re-verify) |
| AC-S135a-4 (GET /scores 404 QUALITY_NOT_EVALUATED) | T06 |
| AC-S135a-5 (Validation hard rules) | T02 |
| AC-S135a-6 (Validation soft warnings) | T02 |
| AC-S135a-7 (ValidationResult 4-arg compat) | T02 |
| AC-S135a-8 (LLM judge reproducibility) | T04 |
| AC-S135a-9 (qualityExecutor pool) | T05 |
| AC-S135a-10 (LLM 失敗 re-throw + retry) | T05 |
| AC-S135a-11 (Total = 0.2V + 0.4I + 0.4A) | T05 (service-level) + T06 (response-level) |

每 AC 至少 1 個 covering task；T05 + T07 是高 leverage（5 個 AC + E2E re-verify）。

### POC findings reference

POC 細節記於 §2.2（含 Q1/Q2/Q3/Q4/Q5 verdict + 來源 URL + decision）。所有 task 已對齊 POC findings — `GoogleGenAi*` class names + stable alias `gemini-2.5-flash` + `evaluator_version` drift detection。

---

> **Next**: Phase 3 task loop start — `/implementing-task S135a` for T01。每 task RED→GREEN→REFACTOR 完成後 control 回 /planning-tasks 找下個 pending task；全 PASS 後進 Phase 4 consolidate + subagent QA。
