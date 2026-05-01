# S078 — `Skill.riskLevel` `@ReadOnlyProperty`（preemptive defense-in-depth）

> **Status**: in-flight
> **Bug ledger**: AL（theoretical — pattern-matched with S077 / Bug AK；無法在 dev 重現但架構上相同）
> **Estimate**: XS / 2 pts

## §1 Problem

S077（Bug AK）的 lost-update pattern 並非只發生在 `download_count`；任何「aggregate 欄位 + 有獨立 atomic SQL UPDATE 路徑」的組合都同樣易感。Audit 發現 `Skill.riskLevel` 即此模式：

| Path | Writer |
|------|--------|
| Aggregate save | `Skill.create()` 設 null；後續 grantAcl/revokeAcl/suspend/reactivate 等 aggregate 改其他欄位但 save() 仍 full-row UPDATE 帶上 riskLevel 的 in-memory 值 |
| Atomic SQL | `ScanOrchestrator` 處理完 risk scan 後呼叫 `SkillRepository.updateRiskLevel(id, level, ts)` — `@Modifying @Query`，繞過 aggregate |

理論 race window：
1. T0：grantAcl handler `findById` → 載入 skill (`riskLevel=null` 或前次值)
2. T1：ScanOrchestrator 完成 scan，atomic SQL 寫 `risk_level = HIGH`（不增加 aggregate `version`）
3. T2：grantAcl `save()` → full-row UPDATE 把 riskLevel 寫回 in-memory 舊值（null 或前次）→ **scan 結果遺失**

`updateRiskLevel` SQL **不增加 `version`** → aggregate 的 `@Version` optimistic lock 偵測不到此衝突 → save() 成功且默默覆蓋。

## §2 Reproduce 難度

實測 5 次 trial（upload risky SKILL.md + 並發 20 grantAcl spam）皆無法觸發：scan async listener 在 thread pool 排隊執行，dev 環境 timing 多落在 grantAcl 群組已完成之後。但這是 **environment-dependent**：production 高負載 / 慢網路 / 跨 host 都可能擴大 race window。

## §3 Decision: Preemptive Fix

依 S077 同樣的 architectural fix：

| 理由 | 評估 |
|------|------|
| 與 S077 完全同模式（`@ReadOnlyProperty`）| ✓ |
| 一行修改 | ✓ |
| 零行為改動（read 不變、INSERT 由 DB schema 接管 NULL）| ✓ |
| 留下未修的同模式漏洞 = 後人地雷 | ✗ |

採取 **defense-in-depth** 立場 — 不等到 production incident 才修。

## §4 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | aggregate save 不再寫 risk_level 欄位 | Spring Data JDBC INSERT/UPDATE 排除此 column（與 download_count 同行為） |
| AC-2 | findById SELECT 含 risk_level | read 不變（API JSON 仍 expose `riskLevel`） |
| AC-3 | `ScanOrchestrator.updateRiskLevel` 寫入後不被 aggregate save 覆蓋 | 任何後續 grantAcl/suspend/reactivate save() 不影響 risk_level |
| AC-4 | INSERT createSkill flow | DB schema `risk_level VARCHAR(10) NULL` 接管，預設 NULL |

## §5 Fix

`Skill.riskLevel` field 加 `@org.springframework.data.annotation.ReadOnlyProperty`（同 S077 pattern）。

## §6 Verification

- `./gradlew test` 全 PASS
- Smoke：upload risky skill → wait scan → grantAcl → risk_level 仍為 HIGH
- 同步 audit：本次 audit 確認其他 aggregate 欄位無同模式（`status` / `latestVersion` / `aclEntries` 都只走 aggregate save，無獨立 atomic path）

## §7 Result

待 ship 後填。

**Result（填於 ship 後）**：
- 299 backend tests / 0 fail（無 regression）
- Smoke：upload risky skill → scan @ 1.0s 寫 HIGH → grantAcl HTTP 201 → risk_level=HIGH 仍存活 ✓
- INSERT createSkill flow：upload 後 risk_level=NULL（DB DEFAULT 接管），scan 完成寫 HIGH ✓
- Audit 完成（CHANGELOG 列）：Skill aggregate 所有欄位 lost-update 漏洞清零；SkillVersion.riskAssessment 走 aggregate 路徑無風險
- ship v2.56.0 (M74)
