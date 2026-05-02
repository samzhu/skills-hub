# S095 — Risk tier 4-level (split LOW → NONE + LOW)

> **Status**: ⛔ **superseded by S096c** (`v2.75.0`, 2026-05-02 — UI v2 META 將 NONE tier 與 RiskBadge dark-theme redesign 合一個 sub-spec ship 一次到位 per Q3 grill 2026-05-02)
> **Type**: backend enum + classify logic + FE badge + DB migration
> **Estimate**: S / 9 pts (~9 pts 實際 ship 進 S096c trim total M→9)
> **Depends on**: none (orthogonal to S094 META; can ship independently after META)
> **Source of truth**: agent aafc4ed560e7e5916 competitor research (Cisco Skill Scanner / CVSS / Snyk / npm / OWASP MCP)

## §1 Goal

當前 `RiskLevel` 3-tier (LOW / MEDIUM / HIGH) 把兩類**語意不同**的結果擠進 LOW：
- (a) **0 findings** — pure documentation skill, no scripts, no allowed-tools (e.g., `date-formatter` walkthrough 範例 / 9 個既有 anthropic canonical skills)
- (b) **Low-severity findings** — scripts present 但 patterns benign

User 看到 LOW badge 會以為「有東西但小事」，但 (a) 其實是「乾淨」 — 應該分清楚。

加 `NONE` tier（對齊 Cisco Skill Scanner + CVSS `None` 0.0 band），避免歧義 + 給 zero-finding skill 一個明確正面訊號。

```
[現]    LOW (0-finding 與 minor-finding 混)  →  MEDIUM  →  HIGH
[新]    NONE (0-finding) → LOW (minor-finding) → MEDIUM → HIGH
```

不引入 marketing-style 命名（`SAFE` / `ZERO` / `CLEAN`） — 跟 Cisco 一致用 `NONE`，並在 tooltip 註明「NONE ≠ 100% safe，只代表 scanner 沒抓到 known patterns」避免 user 過度信任。

## §2 Approach

### §2.1 Classification predicate

User-confirmed (Q1=a 嚴格 Cisco-aligned)：

```
NONE  ⇔ findings.length == 0
        AND no scripts/ folder in zip bundle
        AND no allowed-tools in SKILL.md frontmatter
LOW   ⇔ findings.length == 0 但有 scripts/ OR allowed-tools
        OR findings 全為 Severity.LOW
MEDIUM ⇔ max(severity) == MEDIUM
HIGH  ⇔ max(severity) == HIGH
```

**為什麼三條件 AND**：
- 0 findings 但聲稱 `allowed-tools: Bash` → scanner 沒抓到威脅但 capability declared，仍應 LOW
- 0 findings 但 zip 帶 `scripts/` → 等同上理，scripts 存在 = 需要 slight scrutiny
- 三者皆無 → 純 documentation skill，真乾淨

對齊 Cisco Skill Scanner README 「Max Severity: NONE, Total Findings: 0」 + capability presence check.

### §2.2 Migration strategy (Q2=a SQL bulk re-classify)

新 upload：直接走新 classifyTier，正常 4-tier。

既有 87 LOW skills：一次性 SQL re-classify，避免昂貴 LLM call：

```sql
-- step 1: 找出符合 NONE 條件的 LOW skills
UPDATE skills s
SET risk_level = 'NONE'
FROM skill_versions sv
JOIN risk_assessments ra ON ra.skill_version_id = sv.id
WHERE s.id = sv.skill_id
  AND s.risk_level = 'LOW'
  AND s.latest_version = sv.version
  AND ra.findings_count = 0
  AND NOT EXISTS (...)  -- 透過 metadata 表查 zip 沒 scripts/ + frontmatter 沒 allowed-tools
;
```

實際 metadata 來源：
- `scripts/` 偵測：query `bundle_files` 表 (S074) 看 path LIKE `%scripts/%`
- `allowed-tools` 偵測：query `risk_assessments.metadata_json` 或 frontmatter 解析後的欄位

若 metadata 表沒這些欄位 → 退一步只用「findings_count = 0」（Q1 option b 的弱條件），記為 partial migration 在 §7 Result。

Migration script 為 Flyway V_xxx__migrate_low_to_none.sql；idempotent（只更新 LOW → NONE 一次，再跑 0 row affected）。

### §2.3 RiskBadge 色彩 (Q3=a 4-tier semantic)

DESIGN.md `--color-success-soft` / `--color-info-soft` / `--color-warning-soft` / `--color-danger-soft` 全用上：

| RiskLevel | bg | fg | label (繁中) | label (en) |
|-----------|-----|-----|-------------|-----------|
| NONE | `#E1F5EE` | `#085041` | 無風險 | None |
| LOW | `#E0EAF8` | `#1A4480` | 低風險 | Low |
| MEDIUM | `#FAEEDA` | `#633806` | 中風險 | Medium |
| HIGH | `#FCEBEB` | `#791F1F` | 高風險 | High |

LOW 從現在的 `bg-green-100` 改 `#E0EAF8` blue-soft；視覺上 NONE/LOW 都偏「正面」但分得清楚（綠 vs 藍）。

Tooltip on NONE:
> 「掃描器未發現 known risk patterns。不代表 100% 安全 — 只代表 sysam 沒抓到已知威脅指紋。」

### §2.4 Confidence classification

| Decision | Confidence | Source |
|----------|------------|--------|
| 加 NONE 不破現有 LOW/MEDIUM/HIGH 行為 | **Validated** | Java enum 加值是 backward compatible（DB 欄位 character varying(10) 容納 'NONE' 4 chars） |
| Frontend `RiskBadge` 加 NONE 不破既有 SkillCard render | **Validated** | RiskBadge 接 `level: string \| null`，新值會走 `riskStyles[level] ?? ''` fallback path（既有 graceful） |
| SQL migration 對既有 LOW 正確 re-classify | **Hypothesis** | 需 verify metadata 表（bundle_files / risk_assessments.metadata_json）有夠資料判定 — POC: query 既有 LOW skills 看 metadata 是否含 scripts 與 allowed-tools 訊息 |
| Cisco 6-tier 縮減為 4-tier 不漏 critical UX 訊號 | **Validated** | Cisco INFO/CRITICAL 兩 tier 對 Skills Hub 不適用（INFO 只是 metadata；CRITICAL 與 HIGH 在 MVP 同樣 block-pending-review） |

**§4 Migration script POC required**: 跑 SQL `SELECT count(*) FROM skills s JOIN risk_assessments ra ... WHERE risk_level = 'LOW' AND findings_count = 0` 看實際符合條件的 row 數，估 migration impact。

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | Backend `RiskLevel` enum 含 4 值 (NONE/LOW/MEDIUM/HIGH) | Java + DB schema 接受新 NONE 值 |
| AC-2 | Upload pure docs skill (no scripts, no allowed-tools, frontmatter 只 name+description) | risk_level = NONE，auto-publish |
| AC-3 | Upload skill with `allowed-tools: Read` (no scripts) | risk_level = LOW（有 capability declaration） |
| AC-4 | Upload skill with `scripts/clean.sh` 內容 echo only (no danger patterns) | risk_level = LOW（scripts 存在） |
| AC-5 | Upload skill with `rm -rf` 在 scripts | risk_level = HIGH（既有行為不變，regression check） |
| AC-6 | Migration script 跑後 — 87 LOW skills 部分變 NONE | DB query verify NONE count > 0；HIGH/MEDIUM 不被動 |
| AC-7 | Migration idempotent — 重跑 0 row affected | SQL `UPDATE` 第二次 returns 0 |
| AC-8 | FE RiskBadge 顯 4 tier with new colors | NONE green-soft / LOW blue-soft / MEDIUM amber / HIGH red |
| AC-9 | FE i18n: 「無風險」/「低風險」/「中風險」/「高風險」 4 個對映 | 各 RiskLevel 顯對應繁中 label |
| AC-10 | DocsLayout YourFirstSkillPage RiskRow 4 tiers (was 3) | 加 NONE 條目作 first row |
| AC-11 | Backend tests + Frontend tests 0 fail | regression check |
| AC-12 | OpenAPI schema 反映 RiskLevel enum 4 值 | `/v3/api-docs` 顯 enum array 含 NONE |

## §4 Implementation file plan

```
backend/src/main/java/io/github/samzhu/skillshub/security/
├── RiskLevel.java                      ← 加 NONE 值 + Javadoc
└── scan/
    ├── ScanOrchestrator.java           ← classifyTier 加 NONE 條件邏輯
    └── (test files updated)

backend/src/main/resources/db/migration/
└── V<NN>__migrate_low_to_none.sql      ← SQL bulk re-classify

backend/src/test/java/io/github/samzhu/skillshub/security/scan/
└── ScanOrchestratorTest.java           ← 加 NONE classifyTier test cases

frontend/src/components/
└── RiskBadge.tsx                       ← 4 tier 色彩 + tooltip on NONE

frontend/src/components/RiskBadge.test.tsx (NEW)
└── 4 tier render test

frontend/src/pages/docs/
└── YourFirstSkillPage.tsx              ← RiskRow 加 NONE row + 改 LOW 色

docs/grimo/architecture.md (optional, only if RiskLevel 屬 architectural decision)
└── 補 NONE tier rationale
```

## §5 Test plan

- `cd backend && ./gradlew test` — 預期 299 → ~302 PASS（+3 NONE classifyTier test：pure-docs skill, allowed-tools-no-scripts skill, scripts-clean skill）
- Migration test: pre-migration count(LOW) → post-migration count(LOW) + count(NONE) == count(LOW pre)
- `cd frontend && npm test` — 預期 28 → ~29 PASS（+1 RiskBadge 4-tier render test）
- E2E smoke: upload `r37-pure-docs.zip` (only SKILL.md with name+desc) → GET skill → riskLevel = NONE，UI green-soft badge

Per `qa-strategy.md` 標準 pipeline；不引入 per-spec 工具。

## §6 Acceptance verification

Run: `cd backend && ./gradlew test && cd ../frontend && npm test && npm run build`
Pass: All AC-tagged tests green; both build artifacts generated.

## §7 Result — ⛔ Superseded (not shipped as own spec)

**Superseded by S096c** `v2.75.0` (2026-05-02)。S096c 的範圍包含本 spec 全部技術內容 + UI v2 dark-theme RiskBadge redesign，merge 一次 ship 取代分兩 spec：

### Decision rationale (per Q3 grill 2026-05-02)

| Question | Resolution |
|----------|------------|
| 是否兩 spec 各自 ship？ | NO — RiskBadge 4-tier visual + RiskLevel.NONE backend enum 是 hard-coupled（badge needs new tier value to render；enum 無 frontend 顯示等於 dead code），分開 ship 中間態不可用 |
| 為何併進 S096c (Routing Schema)？ | S096c 已 touch RiskBadge.tsx (route schema 改動觸發 dark-theme migration)；同檔同 PR 一次到位 vs 連兩個 PR 改同檔 |
| Trim impact？ | S095 estimate 9 pts + S096c estimate M(12) 合併後實際 ship ~9 pts（trim total M→9：deferred Flyway SQL migration for 既有 LOW skills；runtime classify only — 對齊 S095 §3 的 「migration optional」原則） |

### What actually shipped in S096c

- ✅ Backend `RiskLevel.NONE` enum value 加入
- ✅ `classifyRiskLevel(...)` 三條件分流（pure-docs / allowed-tools-no-scripts / scripts-clean）→ NONE
- ✅ Frontend `RiskBadge` 4-tier dark-theme variants（NONE green-soft / LOW / MEDIUM / HIGH）
- ✅ Canonical route `/skills/:author/:name` + `:id` alias（S096c 自身 scope）

### Deferred from S095 §3 (carried forward)

- **Flyway SQL migration for 既有 LOW skills**：runtime-only path 已足夠（new uploads classified correctly；既有 LOW skills 仍顯 LOW 直到 re-upload / re-classify event 觸發）。Polish backlog 若需 batch backfill 再開新 spec。

### Lesson recorded

當兩 spec 是 hard-coupled 同檔 + 同 PR 範圍時（backend enum 加 + frontend badge tier 加），**分 spec 反而製造 ship 不可用中間態**。Selection priority 「META spec before sub-specs」與「smallest size first」之間有第三條隱含 rule：**hard-coupled split 應 merge**，已寫入 S096c §8 lesson。

### Audit trail

- Roadmap row line 198：`⛔ superseded — absorbed into S096c`
- Spec doc 從 specs/ 移至 archive/（本 closeout commit 動作）
- Pts 不重複計算：S095 9 pts 已涵蓋於 S096c 9 pts trim total，cumulative roadmap pts 不加 S095
