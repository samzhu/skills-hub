# S135 META — Skill Quality Score System (Tessl 8-dim aligned)

> **Status**: ✅ shipped — S135a (v3.14.0) + S135b (v4.1.0) both shipped 2026-05-06
> **Type**: META + research-driven feature spec
> **Estimate**: L total ≈ 22-26 pts split into S135a (M 13-15) + S135b (S 9-11)
> **Triggered by**: 2026-05-05 user directive — 完成 Tessl Registry 8-dim Quality 研究後，要求合併取代既有 S101 META 的粗粒度設計
> **Research**: [`docs/grimo/research/2026-05-05-tessl-skill-platform-study.md`](../research/2026-05-05-tessl-skill-platform-study.md)
> **Supersedes**: [`2026-05-02-S101-quality-impact-security-scores.md`](./2026-05-02-S101-quality-impact-security-scores.md) — Quality 部分（Impact 移至 S136 Backlog；Security 部分由既有 risk_level mapping + S099e3 已涵蓋）

---

## §1 Goal

加入 **per-skill Quality 軸 score**（對齊 Tessl Registry 8-dim 評分），讓 consumer 在瀏覽 / 安裝前一眼判斷 SKILL.md 是否寫得好、agent 是否會在對的時機載入。

對標 Tessl Skill Optimizer 的 explainable trust signal pattern，但用 Skills Hub 自家 stack（Spring AI 2.0.0-M5 + Gemini + 既有 SkillValidator）實作，不依賴外部 service。

每個 skill 顯：

```
✅ Quality 92% — Validation (rule-based) + Implementation (LLM judge body) + Activation (LLM judge description)
🛡️ Security Passed — 既有 risk_level mapping (NONE/LOW/MEDIUM/HIGH per S096c)
─ Impact 軸延後（S136 Backlog 待討論 task-based eval；hero 不顯示佔位） ─
```

## §2 Research / Context

完整 Tessl 平台研究（含 UI/UX 拆解、Quality 8-dim 細節、Eval scenarios 結構、Security 視覺策略）保存於 [`research/2026-05-05-tessl-skill-platform-study.md`](../research/2026-05-05-tessl-skill-platform-study.md)。本 META spec 只摘錄與 S135 設計直接相關的 takeaways，避免重複。

**關鍵 takeaways for S135**：

1. Quality = 三類 weighted average（Tessl 官方）：
   - **Validation** — rule-based 結構檢查（line count, frontmatter, body sections etc）
   - **Implementation** — LLM judge SKILL.md body：Conciseness / Actionability / Workflow clarity / Progressive disclosure（每項 0-3）
   - **Activation** — LLM judge description：Specificity / Completeness / Trigger Term Quality / Distinctiveness（每項 0-3）
2. 每維度 LLM judge 必輸出 **reasoning 段落** + score — UI 上 hover/expand 顯示 → evidence chain
3. **每個分數都可被追溯到證據** — 沒有空指標
4. UI 上 **Activation** 對 user 顯示為「Discovery」（更直白），但 backend 命名仍用 Activation（對齊官方 docs）

## §3 Sub-spec Breakdown

| SpecID | 標題 | 估計 | 依賴 | 主要交付 |
|--------|------|------|------|----------|
| **S135a** | Backend Quality Score | M(13-15) | Spring AI 2.0.0-M5 + 既有 Gemini stack + SkillValidator | LLM judge backend、`skill_scores` 表、`GET /api/v1/skills/{id}/scores` endpoint、publish 觸發 |
| **S135b** | Frontend Quality Display | S(9-11) | S135a API ready | Hero 兩條進度條 component（Quality + Security）、Quality tab dimension 表格 + reasoning、SkillCard inline score badge |

**Ship 順序**：S135a → S135b（後端 API contract 先定，前端 consume；不可顛倒，否則 frontend 設計憑空猜 schema）。

## §4 Out of Scope

| 項目 | 去向 |
|------|------|
| Impact 軸（with/without context eval）| S136 Backlog 待討論 — task-based eval；hero 不顯示佔位 |
| Weekly recalc cron | 後續 spec — 每次 publish 即時 trigger 已足夠 v1 |
| Admin manual recalc UI | 後續 spec |
| Score audit log | 後續 spec — 既有 `domain_events` 已有 trail |
| SKILL.md 渲染樣式 / sidebar 重整 / install 下拉 / minimalism polish | S137 UI Polish Pass（待 S135b ship 後排程）|
| Security 獨立 tab | 既有 risk_level + S099e3 SBOM scanner 已涵蓋；不重做 |

## §5 Cross-Sub Shared Design

> Phase 2 research 完成 2026-05-05 — 5 個並行 sub-agents 回報整合於下。每段標 confidence（Validated / Hypothesis / POC-required）對齊 planning-spec sufficiency gate。

### §5.1 Score schema（Validated — adopt Direction A wide-JSONB）

每次 evaluation × 每個 axis = 1 row（共 3 rows per evaluation：VALIDATION / IMPLEMENTATION / ACTIVATION）；dimension breakdown 存 JSONB；不 overwrite 歷史。

```sql
-- V15__create_skill_scores.sql （next migration 編號）
CREATE TABLE skill_scores (
    id                  VARCHAR(36)  PRIMARY KEY,            -- deterministic UUID(skill_version_id|axis|source_event_id)
    skill_id            VARCHAR(36)  NOT NULL,                -- soft-FK（不 CASCADE，保留歷史）
    skill_version_id    VARCHAR(36)  NOT NULL,                -- soft-FK
    skill_version       VARCHAR(20)  NOT NULL,                -- denormalized semver（UI 不 join）
    axis                VARCHAR(20)  NOT NULL,                -- VALIDATION / IMPLEMENTATION / ACTIVATION
    total_score         NUMERIC(5,2) NOT NULL,                -- 0-100
    dimensions          JSONB        NOT NULL DEFAULT '{}',   -- {dim: {score, reasoning}}
    evaluated_at        TIMESTAMPTZ  NOT NULL,
    evaluator_version   VARCHAR(50),                          -- judge model+prompt version（drift 偵測）
    source_event_id     VARCHAR(36)  NOT NULL,                -- idempotency hint from SkillVersionPublishedEvent
    CONSTRAINT chk_axis  CHECK (axis IN ('VALIDATION','IMPLEMENTATION','ACTIVATION')),
    CONSTRAINT chk_total CHECK (total_score BETWEEN 0 AND 100)
);
CREATE INDEX idx_skill_scores_skill_axis_eval ON skill_scores (skill_id, axis, evaluated_at DESC);
CREATE INDEX idx_skill_scores_version_id      ON skill_scores (skill_version_id);
```

`dimensions` JSONB 範例：
```json
{
  "specificity":     {"score": 3, "reasoning": "Description lists concrete trigger conditions..."},
  "completeness":    {"score": 2, "reasoning": "Missing 'when NOT to use' exclusion..."},
  "triggerTermQuality": {"score": 3, "reasoning": "..."},
  "distinctiveness": {"score": 3, "reasoning": "..."}
}
```

VALIDATION axis 同格式：`{"line_count": {"score": 100, "reasoning": "412 / 500 lines"}, "body_present": {"score": 100, "reasoning": "..."}}` — 通過/失敗用 0 vs 100。

### §5.2 API contract（Validated）

```
GET /api/v1/skills/{id}/scores
GET /api/v1/skills/{id}/scores?version_id={skill_version_id}    -- 指定版本歷史快照

200 OK
{
  "skillId": "...",
  "skillVersionId": "...",
  "skillVersion": "1.2.0",
  "evaluatedAt": "2026-05-05T...",
  "evaluatorVersion": "gemini-2.5-flash@2026-05-05-prompt-v1",
  "validation":     { "totalScore": 100, "dimensions": {...} },
  "implementation": { "totalScore": 85,  "dimensions": {...} },
  "activation":     { "totalScore": 92,  "dimensions": {...} },
  "total":          92    -- weighted average per §5.6
}
```

未評過：404 with `{ "error": "QUALITY_NOT_EVALUATED", "message": "Score will be available shortly after publish." }`。

### §5.3 Trigger / Listener（Validated — 對齊既有 ScanOrchestrator pattern）

```java
// 新 module: io.github.samzhu.skillshub.score.QualityScoreListener
@Component
class QualityScoreListener {
    @ApplicationModuleListener
    void on(SkillVersionPublishedEvent event) {
        // sourceEventId 做 idempotency — 對齊 ScanOrchestrator
        if (scoreRepo.existsBySourceEventId(event.sourceEventId())) {
            log.debug("[quality] skip duplicate sourceEventId={}", event.sourceEventId());
            return;
        }
        try {
            var result = judgeService.evaluate(event);    // LLM judge 三類
            scoreRepo.saveAll(result.toRows(event));      // 3 rows × 1 publish
        } catch (Exception e) {
            // re-throw → Modulith outbox retry; 不 swallow（per §5.6 失敗策略）
            throw new RuntimeException("QualityScore judge failed, will retry", e);
        }
    }
}
```

**獨立 executor pool**（性能隔離）：
```java
@Bean(name = "qualityExecutor")
TaskExecutor qualityExecutor() {
    var ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(1); ex.setMaxPoolSize(1); ex.setQueueCapacity(500);
    ex.setThreadNamePrefix("quality-judge-");
    return new DelegatingSecurityContextAsyncTaskExecutor(ex);
}
```
理由：LLM judge 5-30s/call 比 AuditListener 慢 100-1000x；共用 `applicationTaskExecutor`（corePool=2 / queue=200）會擠爆其他 listener。在 listener method 上用 `@Async("qualityExecutor")` 指定。

### §5.4 Validation 規則對齊（Validated — 對齊 Tessl + agentskills.io）

#### 既有 SkillValidator 9 條 rule（不動）

`empty_content` / `frontmatter_present` / `yaml_parseable` / `name_required` / `description_required` / `name_regex` / `description_length` / `compatibility_length` / `allowed_tools_syntax`

#### S135a 新增 rules（hard error）

| Rule | 邏輯 |
|---|---|
| `skill_md_line_count` | content.lines().count() ≤ 500 |
| `name_regex_strict` | name 不可開頭/結尾 hyphen、不可連續 `--`（既有 `^[a-z0-9-]{1,64}$` 改為 `^[a-z0-9]([a-z0-9-]*[a-z0-9])?$`）|
| `description_non_blank` | description.toString().isBlank() = false |
| `compatibility_non_blank` | 同上（若 provided） |
| `metadata_value_string` | metadata 內所有 value 必為 String |
| `body_present` | frontmatter 後有 non-blank body |

#### S135a 新增 rules（warning — 軟性檢查）

| Rule | 邏輯 |
|---|---|
| `body_examples` | body 含 `## Example` heading 或 code fence |
| `body_steps` | body 含 numbered list 或 `## Steps` heading |
| `body_output_format` | body 含 output/format keyword 或 code block |

#### ValidationResult migration（backward-compat）

```java
public record ValidationResult(
    boolean valid,
    Map<String, Object> metadata,
    List<String> errors,
    List<String> warnings    // 新增 4th 欄位；既有 caller 不需改 — 用 4-arg constructor 預設 List.of()
) {
    public static ValidationResult of(boolean v, Map<String,Object> m, List<String> e) {
        return new ValidationResult(v, m, e, List.of());   // 既有 3-arg 用法 reuse via factory
    }
}
```

`SkillCommandService.validate(...)` 既有 throw IllegalArgumentException pattern 不變（per `SkillCommandService.java:93-99`）。

#### 不採 Tessl 項

- name 對應目錄名稱檢查 — Skills Hub 用 UUID-based skill ID，不保留 zip 目錄結構

### §5.5 LLM Judge — Spring AI M5 + Gemini（Validated + Hypothesis）

#### Prompt template structure（Validated — Spring AI LLM-as-Judge guide）

```java
// score/judge/JudgeResponse.java
@JsonClassDescription("Quality evaluation result for a SKILL.md artifact.")
public record JudgeResponse(
    List<DimensionScore> scores,
    @JsonPropertyDescription("One-sentence overall verdict for UI display")
    String verdict
) {
    @JsonClassDescription("Score and reasoning for one evaluation dimension.")
    public record DimensionScore(
        @JsonPropertyDescription("Dimension name: Specificity | Completeness | TriggerTermQuality | Distinctiveness")
        String dimension,
        @JsonPropertyDescription("Score 0-3: 0=missing, 1=weak, 2=adequate, 3=excellent")
        int score,
        @JsonPropertyDescription("1-2 sentences explaining why this score was given")
        String reasoning
    ) {}
}

// score/judge/QualityJudge.java
@Component
class QualityJudge {
    // [Updated 2026-05-05 POC]：專案實際用 spring-ai-google-genai（非 Vertex AI）— class 名 GoogleGenAi*
    private final ChatClient client;
    QualityJudge(ChatClient.Builder builder, GoogleGenAiChatModel gemini) {
        var opts = GoogleGenAiChatOptions.builder()
            .model("gemini-2.5-flash")           // stable alias；無 -001 snapshot 形式
            .temperature(0.0f)
            .topK(1).topP(1.0f)
            .candidateCount(1)
            .build();
        this.client = ChatClient.builder(gemini)
            .defaultOptions(opts)
            .defaultSystem(IMPLEMENTATION_RUBRIC_SYSTEM_PROMPT)   // 含 4-dim rubric + 0-3 anchor 描述
            .build();
    }
    JudgeResponse judgeImplementation(String skillBody) {
        return client.prompt()
            .user(u -> u.text("<skill_body>{body}</skill_body>").param("body", skillBody))
            .call().entity(JudgeResponse.class);
    }
    // similar judgeActivation(String description) — 不同 system prompt + record dim names
}
```

#### Settings（Validated — 推薦數值）

| Param | 值 | 理由 |
|---|---|---|
| temperature | 0.0 | 最大 determinism |
| topK | 1 | greedy decoding |
| topP | 1.0 | irrelevant when topK=1 |
| candidateCount | 1 | judge 單一判定 |
| thinkingBudget | 128-512（low）| rubric scoring 不需 deep thinking |
| responseSchema | enabled via `@JsonClassDescription` | structured output 強制 |

#### Model pinning（Hypothesis — 需 implementation 階段 verify）

- Sub-agent #2 推薦 stable alias `gemini-2.5-flash`（pricing/benchmark 數據基於此）
- Sub-agent #1 推薦 suffix-pinned `gemini-X.Y-flash-001`（避免 Google auto-swap → score drift）
- **POC needed**: verify 2.5 是否有 `-001` 形式 snapshot；若無則 fallback `gemini-2.5-flash` + `evaluator_version` column 存 fetched-at timestamp 偵測 drift
- Spec §6 task plan 要 include POC

#### Google GenAI Chat module M5 存活（POC ✅ Validated 2026-05-05）

[Updated 2026-05-05 POC]：原假設專案用 Vertex AI 路徑為錯 — 實際 build.gradle.kts 線 56-58 用 `spring-ai-google-genai`（Google AI Studio 直連，非 Vertex AI platform）。POC 經 Spring AI M5 docs 驗證：
- `spring-ai-google-genai` artifact 在 2.0.0-M5 存在（per <https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html>）
- `gemini-2.5-flash` 是 supported model identifier
- `GoogleGenAiChatOptions.builder().temperature/topK/topP/candidateCount` 與 Vertex AI 路徑同 builder 介面
- ChatClient.entity() pattern provider-agnostic — 與 §5.5 LLM Judge code 同形式

S135a §2.2 已記錄 POC findings 細節；S135a T01 不再是「驗存活」而是 BOM upgrade + 整合 smoke test。

#### S091 reuse（Validated — methodology only）

S091 archived spec 是 security LlmJudge calibration（不是 Quality）；prompt structure 原則（明示 dimension 判斷標準）+ fixture-based smoke method（5 known-label samples）可 reuse；model 設定值 S091 沒寫死 → S135a 全新定義。

### §5.6 Total Score Aggregation（Hypothesis — 待 §7 grill 確認權重）

```
Total = w_v × Validation + w_i × Implementation + w_a × Activation
```

**Tessl 官方權重未公開**。Phase 2 候選方案：

| 方案 | w_v : w_i : w_a | 適用情境 |
|---|---|---|
| **A: 1:2:2 (20/40/40)** | Validation 是 hard gate（fail 直接擋 publish）；displayed score 應該重 LLM judge | **推薦** — 對齊 Tessl 「LLM judge 是核心評估」精神 |
| B: 1:1:1 (33/33/33) | 三類等權 | 簡潔但低估 LLM judge 細節 |
| C: 2:1:1 (50/25/25) | 重 Validation rule-based 可靠性 | 過度懲罰 description 細節 |

待 §7 grill 確認。

### §5.7 AC-META-2 Tolerance（Hypothesis — 由 fixture regression suite verify）

| 層 | tolerance | verify 方法 |
|---|---|---|
| per dim (0-3 integer) | 目標 ±0；上限 ±1 | 10+ known-score fixture × 5 runs（per skill_test_corpus） |
| per category (0-100) | ±5 pt | 同上聚合 |
| per total (0-100) | ±5 pt | 同上聚合 |

實作於 S135a §6 task plan T05 fixture corpus + CI gate（exceed → fail build）。

### §5.8 Sufficiency Gate — Confidence Classification

| Decision | Confidence | Action |
|---|---|---|
| Spring AI M5 ChatClient pattern | Validated | Use directly |
| `.prompt().user().call().entity()` chain | Validated | Use |
| Gemini 2.5 Flash + thinking 適合 judge task | Validated | Adopt |
| temperature=0 / topK=1 / candidateCount=1 settings | Validated | Adopt |
| skill_scores schema (Direction A) | Validated | Adopt |
| sourceEventId idempotency key | Validated | Adopt |
| 獨立 qualityExecutor pool | Validated | Adopt |
| QualityScoreListener structure | Validated | Adopt |
| Existing SkillValidator 9 rules + 9 new | Validated | Adopt |
| ValidationResult 4-arg backward compat | Validated | Adopt |
| Scenario test pattern (S025a-aligned) | Validated | Adopt |
| ~~Vertex AI Gemini Chat module M5 存活~~ → Google GenAI Chat module M5 存活 | **Validated 2026-05-05 POC** | adopted; 專案實際用 spring-ai-google-genai 路徑 |
| Gemini 2.5 model snapshot suffix 存在 | **Resolved 2026-05-05 POC** | 2.5 系列無 -001 snapshot；用 stable alias `gemini-2.5-flash` + `evaluator_version` column 偵測 drift |
| **三類權重 (Validation:Implementation:Activation)** | **Hypothesis** | §7 grill 由 user 決定 |
| **Tolerance 數值** | **Hypothesis** | T05 fixture corpus verify |

## §6 Acceptance Criteria — META level

| AC | Case | Expected |
|----|------|----------|
| AC-META-1 | 每 published skill 有 Quality score 顯示 | SkillCard inline + SkillDetailPage Quality tab |
| AC-META-2 | LLM judge 可重複 / determinable | 同 description + same model_version → 相同 score（tolerance 由 Phase 2 calibration 定）|
| AC-META-3 | Score 計算不阻塞 user-facing query | async @ApplicationModuleListener；read API 永遠 serve latest cached score |
| AC-META-4 | 對 consumer transparent | 每 dim hover/expand 顯 reasoning + 「How is this calculated?」link → docs page |
| AC-META-5 | Validation 與 agentskills.io 標準對齊 | 既有 SkillValidator 擴充項目 + 新增項目皆 ground 在 https://agentskills.io/specification |

## §7 Open Decisions （pending Phase 2 + Phase 3 grill）

1. 三類權重（Validation : Implementation : Activation）— Tessl 官方未公開；常見假設 1:2:2 或 1:1:1，需 Phase 3 grill
2. LLM judge model（Gemini 2.5 Flash vs Pro）— Phase 2 calibration 後決定
3. Score 0-100 scale 還是 letter grade（A/B/C）— UI feedback driven，留 S135b 設計
4. Re-score policy — 同 skill 多次 publish same version 是否 re-score（idempotency key 設計）
5. AC-META-2 tolerance 數值 — Phase 2 calibration 後 finalize

---

> **Next**: S135a Phase 2 research dispatch（5 並行 sub-agents）→ findings synthesize → §5.1-5.5 finalize → S135a §1-5 spec write → handoff to /planning-tasks。
