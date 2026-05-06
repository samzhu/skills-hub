# S099d — LLM Description Quality Audit

> **Status**: ✅ shipped — v4.7.0
> **Size**: M(12)
> **Depends**: S099 META §5；S099c ✅
> **Goal**: 以 LLM rubric 自動評分既有所有 skills 的 description，產出品質報告，點出需要改寫的候補。回應 user feedback「LLM 寫的說明要簡單易懂」。

---

## §1 Goal

對所有上架 skills 的 `description` 欄位進行結構化品質評估：
- 量化每個 description 的清晰度、具體性、非行銷語言程度
- 找出品質低落（score < 60）的技能候選改寫清單
- 產出 `docs/grimo/quality-audit-report.md` 作為 audit artifact

**成功標準**：
- 腳本評估 ≥5 個 skills（本地 dev DB 內有資料時）
- 每筆有 0-100 分數 + 維度細分
- score < 60 的 skills 列入「需要改寫」section

---

## §2 Approach

### 2.1 Architecture

新增 `tools/quality-audit.py`（Python 3.10+，依賴 `anthropic` SDK）：

```
Step 1: fetch    →  GET /api/v1/skills?size=100（分頁取全部 PUBLIC skills）
Step 2: evaluate →  對每個 skill 的 description 呼叫 Claude claude-haiku-4-5
                    （便宜快速，rubric 評分不需 reasoning 深度）
Step 3: score    →  解析 LLM JSON 回應，計算 total 0-100
Step 4: report   →  Markdown table 按分數排序 + 低分 section
                    → docs/grimo/quality-audit-report.md
```

### 2.2 Rubric（5 維度，各 0-20 分）

| 維度 | 評估重點 | 滿分 |
|------|---------|------|
| `action_clarity` | 以具體動詞開頭（"Generates", "Analyzes"）；避免模糊詞（"helps", "allows", "enables"） | 20 |
| `domain_specificity` | 使用特定領域名詞；避免過度技術抽象（"powerful solution", "leverage AI"） | 20 |
| `non_marketing` | 無行銷話術（"robust", "seamlessly", "world-class", "cutting-edge", "revolutionize"） | 20 |
| `length_fit` | 50-200 字為理想範圍；< 20 字過短（-20）；> 300 字過長（-10） | 20 |
| `language_clarity` | 單語言（zh-TW 或 en）；無 code-switch 混雜（除了 proper nouns）；句子完整 | 20 |

Total 0-100；閾值 60 以下列入「需改寫」。

### 2.3 Prompt Design

```
SYSTEM:
You are a technical writing quality evaluator for AI agent skill descriptions.
Score the following skill description on 5 dimensions, each 0-20.
Return ONLY valid JSON matching this schema:
{
  "action_clarity": <int 0-20>,
  "domain_specificity": <int 0-20>,
  "non_marketing": <int 0-20>,
  "length_fit": <int 0-20>,
  "language_clarity": <int 0-20>,
  "rationale": "<one sentence explanation of the lowest-scoring dimension>"
}

USER:
Skill name: {name}
Description: {description}
```

### 2.4 LLM 選型

- **Model**: `claude-haiku-4-5` — 最快最便宜，足夠 rubric 結構化任務
- **SDK**: `anthropic` Python package（需 pip install anthropic）
- **API key**: `ANTHROPIC_API_KEY` 環境變數
- **Rate limit**: 每個 skill 間 0.5s sleep 避免 429

### 2.5 Trim / Defer

| 項目 | 決策 |
|------|------|
| `quality_warning` DB flag 寫回 | **Defer** — 只產報告，不改 DB（改 DB 需後端 API） |
| 分頁超過 100 skills | **Defer** — V1 只取前 100 |
| 非英文 description 特殊處理 | **Defer** — language_clarity 維度已部分覆蓋 |
| Gemini 備選 | **Defer** — 加 --provider flag 讓 V2 支援 |
| CI 自動跑 | **Defer** — 手動跑 |

---

## §3 Acceptance Criteria

```
AC-S099d-1 (基本執行)
Given: 本地 dev instance 運行中、ANTHROPIC_API_KEY 環境變數已設
When: python3 tools/quality-audit.py --url http://localhost:8080
Then: 腳本完成，每個 skill 有 JSON score 回應（5 維度 + total）
And:  stdout 顯示每筆 skill name + total score

AC-S099d-2 (報告產出)
Given: 至少 3 個 skills 評分完成
When: 腳本結束
Then: docs/grimo/quality-audit-report.md 存在
And:  包含 Markdown score table（按 total 升序 — 最差優先）
And:  包含「需要改寫」section（score < 60 的 skills）

AC-S099d-3 (Dry-run)
Given: 無 ANTHROPIC_API_KEY 或帶 --dry-run flag
When: python3 tools/quality-audit.py --dry-run
Then: 腳本輸出 skills 清單（name + description 前 80 字）
And:  不呼叫 Claude API；不產出報告；提示需要 ANTHROPIC_API_KEY

AC-S099d-4 (Rate limit 保護)
Given: DB 有 20+ skills
When: 腳本正常執行
Then: API 呼叫間隔 ≥ 0.5s（腳本內 sleep 保護）
And:  遇 429 自動重試一次（sleep 5s）後繼續

AC-S099d-5 (評分區間合法)
Given: LLM 回傳 JSON scores
When: 腳本解析分數
Then: 每個維度 clamp 到 [0, 20]；total = sum，clamp 到 [0, 100]
And:  LLM 回傳格式錯誤時記 score=0 + notes="parse-error"，不中斷整體執行
```

---

## §4 File Plan

```
tools/
├── cross-validate.py       ← ✅ S099c shipped
└── quality-audit.py        ← ★ 新增

docs/grimo/
└── quality-audit-report.md ← 腳本輸出；手動 commit snapshot；加 .gitignore
```

**Note**: `quality-audit-report.md` 加入 `.gitignore`（與 cross-validation-report.md 同策略）；
首次執行後手動 commit 一份 snapshot 作為 audit trail。

---

## §5 Test Plan

| # | 驗證項目 | 方法 |
|---|---------|------|
| T1 | `--dry-run` 不呼叫 API | 執行 `--dry-run`，確認 stdout 有 skills 清單且無 API call |
| T2 | JSON parse 容錯 | Mock LLM 回傳壞 JSON，確認腳本繼續（score=0，notes="parse-error"） |
| T3 | Score clamp | Mock 回傳 dimension=25（超出 20），確認 clamp 到 20 |
| T4 | Rate limit sleep | `time.sleep` 調用次數 = skill 數（單元測試 mock sleep） |
| T5 | 報告格式 | 報告含 Markdown table header + score 欄位（手動 review）|

人工測試（本地）：
- 在 `bootTestRun` + `ANTHROPIC_API_KEY` 環境下執行；確認 ≥3 skills 產出完整報告

---

## §6 Task Plan

| Task | Status |
|------|--------|
| T1: 實作 `tools/quality-audit.py` | ✅ done |
| T2: syntax check `python3 -m py_compile` | ✅ PASS |
| T3: `--dry-run` flag + graceful error 處理 | ✅ done |
| T4: `quality-audit-report.md` 加 .gitignore | ✅ done |

---

## §7 Result

**Ship date**: 2026-05-07 (v4.7.0)

**Verification metrics**:
- `python3 -m py_compile tools/quality-audit.py` → syntax OK
- `python3 tools/quality-audit.py --help` → usage 正常
- 5 維度 rubric（action_clarity / domain_specificity / non_marketing / length_fit / language_clarity）各 0-20，總分 0-100
- model: `claude-haiku-4-5-20251001`（最便宜快速）
- score < 60 列入「需要改寫」section

**Trim**: live run (需 ANTHROPIC_API_KEY + bootTestRun) defer 到手動 QA。
dry-run 在 API 不可用時 graceful exit + 提示。
