# S099c — Cross-Marketplace Risk Validation Script & Report

> **Status**: 📋 planned → 📐 in-design
> **Size**: S(7)
> **Depends**: S099 META §4 sources verified
> **Goal**: 建立公開 skills marketplace 的交叉驗證報告，以量化 Skills Hub 風險評估與競品之一致性，建立 enterprise trust narrative。

---

## §1 Goal

對齊 S099 META AC-META-3：從 3 個公開 skills marketplace 下載 ≥43 個 skills，批次上傳至本地 dev instance，收集 risk_level 結果，產出 `docs/grimo/cross-validation-report.md`。

**成功標準**：
- 報告含 ≥10 個 skills，跨 ≥2 來源
- 每筆記錄有 skill 名稱 / source / 我們的 risk_level / 人工備註
- 完整流程可用一個指令重跑（reproducible）

---

## §2 Approach

### 2.1 Architecture

新增 `tools/cross-validate.py`（Python 3.10+，stdlib only，不引入新依賴）：

```
Step 1: git clone  →  anthropics/skills, huggingface/skills, agentregistry-dev/skills
Step 2: extract    →  遍歷 skills/*/SKILL.md，略過缺 SKILL.md 的目錄
Step 3: zip-wrap   →  每個 SKILL.md 打包成 minimal zip（只含 SKILL.md）
Step 4: upload     →  POST /api/v1/skills/upload（multipart）
Step 5: poll       →  GET /api/v1/skills/{id} 等待 risk_level 非 null（max 60s/skill）
Step 6: report     →  生成 Markdown table → docs/grimo/cross-validation-report.md
```

### 2.2 Input — Sources（已驗證 2026-05-05）

| Source | Repo | 預計 skill 數 |
|--------|------|-------------|
| Anthropic 官方 | `github.com/anthropics/skills` | 17 |
| HuggingFace 官方 | `github.com/huggingface/skills` | 13 |
| AgentRegistry | `github.com/agentregistry-dev/skills` | ~13 |

**格式說明**：agentskills.io 規範的 `version` 在 `metadata.version`；公開 repo 實際只含 `name` / `description` / `license`。腳本以 SKILL.md 原始內容為主，不強驗 frontmatter 格式。

### 2.3 Upload 行為

- 上傳前先 GET `/api/v1/skills?name={name}` 確認是否已存在，避免重複 POST
- 若 skill 名稱衝突（409），跳過並記錄 `skipped`
- 每次上傳後記錄 `skillId`，下一步才能 poll

### 2.4 Poll 策略

- 每 2 秒 poll 一次，timeout 60 秒 / skill
- risk_level 出現（非 null）即停止 poll
- Timeout 時記錄 `risk_level = TIMEOUT`

### 2.5 Report 格式

```markdown
# Cross-Marketplace Risk Validation Report
Generated: {datetime}
Instance: {base_url}
Skills uploaded: {total} | Timeout: {n} | Skipped: {n}

| # | Skill Name | Source | Risk Level | Notes |
|---|-----------|--------|-----------|-------|
| 1 | web-search | anthropics/skills | LOW | clean no-scripts |
| 2 | ... | | | |
```

### 2.6 Trim / Defer

- **Defer**: 自動人工標注（`expected_risk` 欄位）— 需 domain knowledge，改為備註欄位由人工後填
- **Defer**: `agentregistry-dev/skills` 若 clone 失敗（repo 不存在/改名），降級至 2 sources
- **Defer**: 一致性統計（agreement rate）— 先 ship 原始表格
- **In scope**: 腳本產出 reproducible report，不需 CI/CD 整合（手動跑）

---

## §3 Acceptance Criteria

```
AC-S099c-1 (Script 可執行)
Given: 本地 dev instance 在 http://localhost:8080 執行中
When: 執行 python3 tools/cross-validate.py --url http://localhost:8080
Then: 腳本完成，stdout 輸出進度 log（每個 skill 上傳/poll 結果）
And:  docs/grimo/cross-validation-report.md 被建立/覆寫
And:  報告 skill 數 ≥10（跨 ≥2 來源）

AC-S099c-2 (Upload 成功率)
Given: anthropics/skills repo 可 clone（最穩定 source）
When: 腳本逐一上傳 anthropics/skills 的全部 SKILL.md
Then: upload HTTP status 200/202 比例 ≥80%（允許少量格式不相容的 skill 失敗）
And:  每筆成功上傳都有 skillId 記錄

AC-S099c-3 (Poll 完整)
Given: Skills Hub risk assessment pipeline 正常運作
When: 腳本 poll GET /api/v1/skills/{id}
Then: ≥80% 的 skills 在 60s 內取得 non-null risk_level
And:  報告表格 Risk Level 欄位無空值（TIMEOUT 替代）

AC-S099c-4 (冪等重跑)
Given: cross-validate.py 已執行過一次
When: 不清空 DB，重新執行腳本
Then: 已存在的 skill 被 skipped，不產生重複資料
And:  報告正常產出（含 skipped 記錄）
```

---

## §4 File Plan

```
tools/
└── cross-validate.py          ← 新增；stdlib only（requests 不可用時改 urllib）

docs/grimo/
└── cross-validation-report.md ← 腳本輸出；由腳本自動覆寫；加入 .gitignore 或 commit 一份 snapshot
```

**注意**：`cross-validation-report.md` 第一次執行後手動 commit 一份 snapshot 作為 audit trail；後續重跑覆寫但不強制 commit（可選）。

---

## §5 Test Plan

| # | 驗證項目 | 方法 |
|---|---------|------|
| T1 | 腳本 dry-run（不呼叫 API） | `--dry-run` flag：只 clone + extract，印出 SKILL.md 清單，無 network call |
| T2 | zip 打包正確 | dry-run 後用 `zipfile.ZipFile` 手動驗證每個 zip 含 `SKILL.md` |
| T3 | Upload 成功回傳 skillId | 對本地 dev 執行，驗證 stdout 含 `uploaded: skill-{id}` |
| T4 | Poll timeout 處理 | 故意傳不合法 zip，確認 30s 後記錄 TIMEOUT 不卡住 |
| T5 | 報告格式 | 檢查 `cross-validation-report.md` 包含 Markdown table header 且行數 ≥12（10 data rows + 2 header）|

人工測試（本地手動）：
- 在 `bootRun` 下執行腳本，確認 ≥10 skills 完整進入報告

---

## §6 Task Plan

（實作時填）

---

## §7 Result

（Ship 後填）
