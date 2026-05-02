# S101 META — Skill Quality / Impact / Security Score System

> **Status**: in-design — **awaiting human confirmation** (per user directive「先開 spec 給人類確認」)
> **Type**: META + competitive research-driven feature spec
> **Estimate**: M-L total ≈ 25-35 pts across sub-specs
> **Triggered by**: 2026-05-02 user directive — 「研究 tessl.io skill-optimizer 怎麼量測 Quality / Impact / Security 給人類參考」

## §1 Goal

加入 **per-skill 三軸 score**（Quality / Impact / Security），給 consumer 在瀏覽 / 安裝前一眼判斷品質 — 對標 Tessl Skill Optimizer 的 explainable trust signal pattern，但用 Skills Hub 自家 stack（PostgreSQL aggregates / pgvector / 既有 risk scanner / Gemini）實作，不依賴外部 service。

每個 skill 顯：
- ✅ Quality 91% — 「Does it follow best practices?」
- 📈 Impact 92% — 「Average score across 25 scenarios」
- 🛡️ Security Passed — 「No known issues」(Snyk 等級)

## §2 Competitive Research：Tessl Skill Optimizer

[研究來源](https://tessl.io/registry/tessl/skill-optimizer)

| Axis | What it measures | Method | Output |
|------|-------------------|--------|--------|
| **Quality** | SKILL.md 文件本身的 best-practice 遵守 | LLM judge 對 4 dimensions（completeness / actionability / conciseness / robustness）打分 | % score |
| **Impact** | Skill 部署在 agent workflow 後的實際 value-add | `tessl eval run`：跑 25 scenarios 比對 with/without skill 的 agent 表現 | % score + multiplier (e.g. 1.10x) |
| **Security** | 已知 dependency vulnerabilities | 第三方 Snyk 掃描 | Pass / Fail binary + 「No known issues」 |

**設計 takeaways**：
- Quality 用 LLM judge — Skills Hub 可重用既有 Gemini API
- Impact 用 black-box eval — Skills Hub 沒 sandbox runtime，得用 proxy metrics 替代
- Security 第三方 + 簡單 binary — Skills Hub 既有 4-level risk 可降維為 tri-state（Pass / Warn / Fail）

## §3 Skills Hub Adapted Design

### §3a Quality Score (S101a)

**Range**: 0-100 整數百分比；4 dimensions × 25 each。

| Dimension | Question | LLM Rubric | Weight |
|-----------|----------|------------|--------|
| Completeness | 有 name / description / license / version 等基本欄位嗎？ | regex check 加 LLM「描述是否覆蓋主要 use case」 | 25% |
| Actionability | description 含具體 verb + trigger 條件嗎？ | LLM judge：「這段 description 能讓 agent 知道何時呼叫此 skill 嗎？」 | 25% |
| Conciseness | 80 ≤ desc length ≤ 1024 chars？無冗詞？ | LLM judge：「能否縮短？是否有 marketing fluff？」 | 25% |
| Robustness | scripts/references/assets 結構完整？ instructions 有錯誤處理 hints？ | LLM judge bundle 結構 + SKILL.md 內文 | 25% |

**Run schedule**: 每次 publish + 每 7 天 batch re-evaluate（description 不變不重跑）。

**Storage**: 新 `skill_scores` projection table — `(skill_id, dim, score, evaluated_at, model_version)`。

### §3b Impact Score (S101b)

Skills Hub **沒有 agent runtime sandbox** — 不能跑 `tessl eval run` 那種 with/without 對照。改用 **proxy metrics**：

| Sub-metric | Source | Weight | Rationale |
|------------|--------|--------|-----------|
| Adoption percentile | download_count percentile within category | 40% | 高下載 = 多 user 選擇 = 實際 value |
| Rating mean | review aggregate (S098e2 dependency) | 30% | direct user feedback |
| Flag count inverse | flag count → score 反比 | 15% | 多回報 = 低 trust |
| Trend slope | 30d download trend 月增率 | 15% | growth signal |

**Range**: 0-100 整數百分比。

**Caveat**: explicit 標 「proxy metric — 非 in-sandbox eval」對 consumer 透明。等未來 ship sandbox runtime 才能像 Tessl 跑真 eval。

**Open question**: 是否要等 Reviews aggregate (S098e2) 先 ship 才能算 Rating 部分？或 ship 不含 rating 的「Adoption-Trend-Flag」3 sub-metric 版本，rating slot 暫 reserved。

### §3c Security Status (S101c)

Skills Hub 既有 4-level RiskLevel (NONE/LOW/MEDIUM/HIGH per S096c) — 已比 Tessl 二元 Pass/Fail 更細。降維對齊 Tessl 顯示：

| risk_level | Display | Color |
|------------|---------|-------|
| NONE / LOW | ✅ **Passed** — 無 known risk patterns | green |
| MEDIUM | ⚠️ **Warn** — 含 scripts 但未偵測危險 | amber |
| HIGH | ❌ **Failed** — 危險指令 / 敏感路徑 / 進入審核 | red |

**Add**: 串接 OWASP LLM05 dependency scanner (S099e3) — 加 Snyk-equivalent badge：
- ✅「No known dependency vulnerabilities」
- ⚠️「N issues found（severity-low）」
- ❌「Critical vulnerabilities — see report」

## §4 Backend Implementation Plan

### New module: `score/`

- `ScoreCalculationService` — 每 publish + cron 跑 quality LLM judge
- `score/QualityJudge` — Gemini API 呼叫 + 4-dim rubric prompt template
- `score/ImpactCalculator` — SQL aggregate 計 percentile / trend slope
- `score/projection/SkillScores` — read model

### New table: `skill_scores`

```sql
CREATE TABLE skill_scores (
  skill_id UUID NOT NULL,
  axis TEXT NOT NULL,        -- 'quality' / 'impact' / 'security'
  dimension TEXT,            -- nullable; 'completeness' / 'actionability' / etc for quality breakdown
  score INT NOT NULL,        -- 0-100
  evaluated_at TIMESTAMPTZ NOT NULL,
  model_version TEXT,        -- e.g. 'gemini-2.0-flash@2026-05-01'
  details JSONB,             -- judge reasoning + metric breakdown
  PRIMARY KEY (skill_id, axis, dimension, evaluated_at)
);
```

### Endpoints

- `GET /api/v1/skills/{id}/scores` — 回 latest 3-axis scores + dimension breakdown
- `POST /api/v1/skills/{id}/scores/recalculate` — admin manual trigger（rate-limited）

## §5 Frontend Implementation Plan

### SkillCard 加 score row

```
[icon] skill-name      🛡️✅ 92%  📈 87%
       by team-a       quality   impact
```

3 figures inline；hover tooltip 顯詳細 dimension breakdown。

### SkillDetailPage 加 Scores tab

新 tab 在既有 6-tab structure（Overview/Risk/Versions/Reviews/Flags/Files）之後加第 7 tab「Scores」：
- 3 axis radial chart 或 horizontal bars
- Quality 4 dimensions breakdown
- Impact 4 sub-metrics breakdown + 「proxy caveat」disclaimer
- Security: risk_level mapping + dependency vuln list（若 S099e3 ship）
- 「Last evaluated」timestamp + 「Recalculate」CTA (admin only)

## §6 Sub-spec Breakdown

| Spec | Title | Estimate | Dependencies |
|------|-------|----------|--------------|
| **S101a** | Quality Score backend + LLM judge | M(10) | Gemini API (existing) |
| **S101b** | Impact Score proxy metrics + endpoint | M(8) | optionally S098e2 Reviews aggregate |
| **S101c** | Security Status simplified display + Snyk integration | S(6) | optionally S099e3 SBOM dep scanner |
| **S101d** | Frontend SkillCard + SkillDetailPage Scores tab | S(8) | S101a/b/c backend ready |
| **S101e** | Quality Score weekly re-evaluation cron | XS(3) | S101a |
| **S101f** | Score audit log + admin recalculate UI | XS(3) | S101a-d |

≈ 38 pts total。

## §7 Open Questions for Human Confirmation

**先請 user 確認以下設計選擇**：

1. **Quality dimensions 4 vs 6**: Tessl 用 4 (completeness/actionability/conciseness/robustness)。Skills Hub 是否要對齊？或加「i18n quality」(中英文 description 一致性) / 「frontmatter compliance」(欄位齊全度) 等補充 dimension？

2. **Impact proxy weights**: 上表預設 40/30/15/15。是否合理？或加重 trend slope（growth signal）權重？

3. **Reviews dependency for Impact**: 等 S098e2 Reviews aggregate ship 後才有 rating data。是否：
   - a. 先 ship 3-sub-metric 版（無 rating），待 Reviews ship 後 backfill？
   - b. 等 S098e2 ship 後再啟動 S101b？

4. **LLM judge model selection**: Gemini text-embedding-004 已用於 semantic search。Quality judge 用 Gemini Pro 還是 Claude Haiku？前者 free quota 高、後者 reasoning 較強。

5. **Recalculate cadence**: 每 7 天 re-judge 是否過頻？或改成「description 變動才 re-judge」+ 「人工 trigger」？前者更省 LLM API。

6. **Score display scope**: SkillCard 顯 3 figures 是否擁擠？或只在 SkillDetailPage 顯，SkillCard 只顯 single composite score（3 axes 平均）？

7. **OWASP LLM05 SBOM dependency**: S099e3 SBOM scanner 是否需要先 ship 才啟動 S101c Security？或 S101c 先用既有 risk_level mapping，SBOM 後續加？

## §8 Acceptance Criteria — META level

| AC | Case | Expected |
|----|------|----------|
| AC-META-1 | 每 published skill 有 3-axis score 顯示 | SkillCard inline 3 figures + SkillDetailPage Scores tab 詳細 breakdown |
| AC-META-2 | Quality LLM judge 可重複 / determinable | 同 description + same model_version → 相同 score (within ±5 tolerance) |
| AC-META-3 | Score recalculation 不阻塞 user-facing query | async batch；read API 永遠 serve latest cached score |
| AC-META-4 | 對 consumer transparent | 每 score hover 顯 「How is this calculated?」link → docs page |

## §9 Result

**Awaiting human confirmation** — User 先請看 §7 7 個 open questions 確認設計選擇，再啟動 sub-spec implementation。

**Plan summary**:
- 6 sub-specs (S101a-f) ≈ 38 pts
- 對標 Tessl Skill Optimizer trust signal pattern；自家 stack 實作
- 3 axes 對 consumer 公開 trust signal；hover/tab 提供 explainability
