# S099 META — Trust Maturity & Implementation Audit

> **Status**: in-design (planning artefact)
> **Type**: META audit + 6-area gap roadmap
> **Estimate**: ~80-100 pts total across sub-specs
> **Triggered by**: 2026-05-02 user 連續 mid-tick directives：
>   1. 「OpenAPI 3.1 + 各頁面檢查未實作 + skill 沒有直接文本輸入」
>   2. 「重新抓 skill 上傳測試我們風險跟別人接近？LLM 寫的說明要簡單易懂」
>   3. 「OWASP LLM Top 10 看可以用什麼 + 跟競品比較」

## §1 Goal

把目前 Skills Hub「polish 完整但 trust signal 薄弱」的狀態升級為「可向 enterprise 證明風險評估有公信力 + 安全成熟度對齊業界標準」。三條軸線：

1. **Implementation completeness audit** — 哪些頁面還有 stub
2. **Trust validation** — 我們的 risk classification 與競品 / 公開 marketplace 做交叉驗證
3. **Security maturity** — 對齊 OWASP LLM Top 10 (LLM01-LLM10) 補齊 control 缺口

本 META 為 planning artefact — 不直接 ship code，只盤 gaps + 開 sub-specs roadmap rows。

## §2 Implementation Audit (per page)

| # | Surface | State | Gap |
|---|---------|-------|-----|
| 1 | PublishPage (`/publish`) | ✅ zip upload + .md upload | ❌ **沒有「直接貼上 SKILL.md 文本」mode** — 使用者必須打包 zip 或上傳 .md 檔；對 quick prototype 不友善 |
| 2 | SkillDetailPage Reviews tab | 🟡 stub (S098e) | aggregate + ratings + UI 未實作 |
| 3 | SkillDetailPage Flags tab | 🟡 stub (S098e) | flag 回報流程 + reviewer queue 整合 |
| 4 | CollectionsPage | 🟡 read-only stub (S096f1) | aggregate + install + create endpoints |
| 5 | RequestBoardPage | 🟡 read-only stub (S096g1) | aggregate + voting + claim |
| 6 | NotificationsPage | 🟡 read-only stub (S096h1) | projection from domain_events + WebSocket / 4 mutation endpoints |
| 7 | VersionDiffPage | 🟡 frontend metadata only (S098c) | backend `/diff` endpoint w/ risk/sha per-version + file content line-level diff |
| 8 | PublishFailedPage State A | 🟡 single err-row (S098b3) | structured findings payload from backend (rule + line + hint) |
| 9 | PublishValidatePage upload-strip | 🟡 派生 filename/version (S098a3) | backend `/skills/{id}/bundle-info` 真實 filename/fileSize/fileCount |
| 10 | Admin Review Queue | ⏸ post-MVP | 完全未實作 |
| 11 | `/docs/rest-api` | ✅ corrected v3.2.5 | 對齊 actual controllers ✓ |

**Summary count**: 9 個 stubs / 1 ⏸ post-MVP / 1 ✅ 剛 fix (rest-api docs)。

## §3 Gap A — Skill 文本直接輸入（PublishPage no-zip mode）

### Current

PublishPage `FileDropZone` 接受 `.zip` 或 `.md`；backend `POST /api/v1/skills/upload` 走 multipart。沒有「使用者貼 SKILL.md 內容到 textarea → submit」的 path。

### Proposed

加 dual mode 切換 (Tabs)：
- **Tab 1：上傳 zip** （既有 FileDropZone）
- **Tab 2：貼上文本** — `<textarea>` for SKILL.md content + 4 個 metadata input (name / description / version / category) + submit
  - 後端：text mode 走 `POST /api/v1/skills` JSON body (CreateSkillCommand 已存在，原本 testing/seed 用)；frontend 自動派生 minimum SKILL.md from textarea content + form metadata
  - OR 後端加 `POST /api/v1/skills/text` 包成虛擬 zip 走既有 pipeline （行為一致）
- 兩 mode 共用 PublishValidatePage 後續流程

### Sub-spec: **S099b** (S=8) — PublishPage text mode

## §4 Gap B — Cross-Marketplace Risk Validation

### Idea

抓公開 skill marketplace（候選：Cisco AskMe / awesome-claude-code-skills GitHub repo / agentskills.io 上市 skills / 其他 LLM agent 框架的 skill 庫）的 skills，逐一 upload 到 Skills Hub，比對：

- Skills Hub 我們判定的 risk_level (NONE/LOW/MEDIUM/HIGH)
- 來源平台的 risk label / community rating（若有）
- 預期 outcome：高 agreement rate（≥80%）證明我們 classifier 有公信力

### Scope

| Step | Description |
|------|-------------|
| Source selection | 挑 1-2 個 high-quality public marketplace（先 awesome-claude-code-skills 因 GitHub 公開易抓） |
| Scraper | Python script clone repo + 解析 `skills/*/SKILL.md` |
| Bulk upload | Loop 呼叫 `POST /skills/upload` 上傳 |
| Comparison report | Markdown table：來源 / 我們 risk / 來源 risk（若有）/ agreement (Y/N) / 不同點 root cause |
| Audit log | 結果存 `docs/grimo/cross-validation-report.md` 作為 trust signal |

### Sub-spec: **S099c** (M=10) — Cross-marketplace risk validation script + report

## §5 Gap C — LLM Description Quality Audit

### Idea

User feedback：「LLM 寫的說明要簡單易懂」— 暗示我們的 description 內容（不論作者寫或 AI 生成）品質參差。需 automated quality audit：

- 跑既有所有 skills 的 `description` 通過 LLM rubric 評分（Claude / Gemini）
- 指標：concrete-verb count / domain-noun count / marketing-blurb-ratio / 字數分佈 / 中英文混雜 ratio
- 報告分數 + 列出最差的 N 個 skills（候選改寫）

### Sub-spec: **S099d** (M=12) — LLM-assisted description quality audit

## §6 Gap D — OWASP LLM Top 10 Alignment

對齊 [OWASP LLM Top 10 v1.1 (2023)](https://owasp.org/www-project-top-10-for-large-language-model-applications/)：

| LLM ID | Threat | Skills Hub Current Coverage | Gap |
|--------|--------|------------------------------|-----|
| LLM01 | Prompt Injection | 🟡 partial — risk scanner 偵測 `curl \| bash` 等明顯 RCE patterns；但不過濾 SKILL.md instructions 對 agent 的隱藏 prompt 注入 | 加 prompt-injection-pattern detector（mitigation：scan instructions for jailbreak patterns） |
| LLM02 | Insecure Output Handling | ❌ 無 — Skills Hub 是中介市集，agent 端責任 | 文件警示 consumer (LLM03 docs page) |
| LLM03 | Training Data Poisoning | ❌ 無 — Skills Hub 不訓練 model | OOS — explicit 標 not applicable |
| LLM04 | Model DoS | ❌ 無 — 但 skill scripts 可能 DoS agent | 加 resource-hint scanner（infinite loops / large memory） |
| LLM05 | Supply Chain Vulnerabilities | 🟡 partial — risk scanner 偵測 `curl` 來源未驗證；未來可加 SBOM | 加 SBOM 產生 + dependency scanning（npm audit / pip-audit equivalent） |
| LLM06 | Sensitive Info Disclosure | ✅ medium — 偵測 `~/.ssh` `~/.aws` 等敏感路徑存取 → HIGH | 擴 detector pattern（API key in scripts / hardcoded creds） |
| LLM07 | Insecure Plugin Design | 🟡 partial — `allowed-tools` 宣告觸發 risk scan；但無「plugin 對 plugin 互動」分析 | post-MVP — agentic composability scanner |
| LLM08 | Excessive Agency | ✅ partial — `allowed-tools` 越多分越高；HIGH 必審 | 文件化 agency-budget concept（給 consumer） |
| LLM09 | Overreliance | ❌ 無 — agent 端責任 | docs page 警示「scan ≠ certified safe」（NONE tooltip 已部分達）|
| LLM10 | Model Theft | ❌ 無 — Skills Hub 不託管 model weights | OOS — explicit 標 not applicable |

### Sub-spec: **S099e** (L=15) — OWASP LLM Top 10 alignment + risk scanner upgrades

實作分割：
- **S099e1** (M=8) — Prompt-injection pattern detector (LLM01)
- **S099e2** (S=5) — Resource DoS scanner (LLM04)
- **S099e3** (M=8) — SBOM + dependency scanning (LLM05)
- **S099e4** (S=4) — Hardcoded creds detector enhancement (LLM06)
- **S099e5** (S=3) — Docs page: 「Risk Scanner 涵蓋與限制」說明 LLM01-10 對應

## §7 Other Gaps from Audit

### Gap E — OpenAPI 3.1 explicit declaration

現況：SpringDoc 3.0.2 預設產出 OpenAPI 3.1。`/v3/api-docs` 已可用。應 verify `openapi: 3.1.0` 在 raw JSON 中 — 若仍是 3.0.x 則 application.yaml 加：

```yaml
springdoc:
  api-docs:
    version: openapi_3_1
```

### Sub-spec: **S099a** (XS=2) — Verify + 強制 OpenAPI 3.1 + 加到 docs

## §8 Sub-spec Backlog Plan

### P0 — User-direct directives

| Spec | Title | Estimate | Source |
|------|-------|----------|--------|
| **S099a** | OpenAPI 3.1 verification + config | XS(2) | user directive 1 |
| **S099b** | PublishPage text mode (no-zip) | S(8) | user directive 1 |
| **S099c** | Cross-marketplace risk validation report | M(10) | user directive 2 |
| **S099d** | LLM description quality audit | M(12) | user directive 2 |
| **S099e** META | OWASP LLM Top 10 alignment | L(15+) split into e1-e5 | user directive 3 |

### P1 — Existing audit gaps (already in roadmap)

詳見 `spec-roadmap.md`：S098e2 / S098e3 / S096f2/g2/h2 / S098c2/c3 / S098b3-2 / S098a3-2 / S094e

## §9 Sequencing recommendation

優先：
1. **S099a** (XS) — fast verify OpenAPI 3.1 status；若已是 3.1 只加 docs page note
2. **S099b** (S) — PublishPage text mode：高 user value (lower friction publishing)；frontend-heavy with既有 backend endpoint
3. **S099e1** (M) — prompt-injection pattern detector：trust signal 升級 highest impact
4. **S099c** (M) — cross-marketplace validation：建立 trust narrative
5. **S099d** (M) — description quality audit：細水長流 polish
6. **S099e2-e5** — 其餘 OWASP coverage 補完
7. **P1 既有 backend specs** (Reviews/Collections/Requests/Notifications full)

≈ 80-100 pts 總工 to full trust + completeness baseline.

## §10 Acceptance Criteria — META level

| AC | Case | Expected |
|----|------|----------|
| AC-META-1 | 各 P0 sub-spec ship | Roadmap row → ✅ + spec doc archived |
| AC-META-2 | OWASP LLM Top 10 mapping table | docs page 對 consumer 公開「我們做什麼 / 不做什麼」 |
| AC-META-3 | Cross-marketplace report 發佈 | `docs/grimo/cross-validation-report.md` 含 ≥10 skills 比對 |
| AC-META-4 | Description quality baseline | 既有所有 skills 跑過 rubric；底分 Top 10 加 `quality_warning` flag |

## §11 Result

待 sub-specs ship 累積後填。

**Plan summary**:
- 5 new P0 sub-specs (S099a/b/c/d/e META) + 5 e-split (e1-e5) + 既有 P1 backlog
- ~80-100 pts total
- 對齊 OWASP LLM Top 10 v1.1 (2025 版差異待 sub-spec 時 verify)
- Trust narrative：cross-marketplace validation + LLM rubric + OWASP coverage 三軸建立 enterprise 信任感
