# S077 — `Skill.downloadCount` `@ReadOnlyProperty`（lost-update fix）

> **Status**: in-flight — regression introduced by S076
> **Bug ledger**: AK (loop e2e tick 66 Round 23.5)
> **Estimate**: XS / 3 pts

## §1 Problem

S076 引入原子 SQL `incrementDownloadCount` 解決並行下載 OptimisticLocking 問題；但同時引入 lost-update：concurrent suspend (or any aggregate save) 與 download 交錯時，aggregate `save()` 用 full-row UPDATE 把所有欄位（含 `download_count`）回寫，**覆蓋掉並發的原子增量**。

實測：10 並行 download + 1 並發 suspend：
- 10 dl 全 HTTP 200（counter increment 都跑了）
- 但 `final download_count = 3`（其餘 7 次增量被 suspend 的 save 覆寫掉）

```
Suspend TX:
  T1: read skill (download_count=0, version=0)
  T2: aggregate.suspend() in memory (status=SUSPENDED, version+1)
  T3: <download TX increments via atomic SQL: SET download_count = 1>
  T4: <download TX 2 increments: SET download_count = 2>
  ...
  T9: <download TX 7 increments: SET download_count = 7>
  T10: save() → UPDATE SET status=SUSPENDED, download_count=0, version=1 WHERE id=? AND version=0
       ↑ download_count=0 是 in-memory 的舊值，覆寫 7
```

## §2 Root Cause

Spring Data JDBC `save()` 不做 dirty-tracking（與 JPA 不同），每次 UPDATE 都是 full-row replace，所有 entity field 一起回寫。S076 之前 download_count 與 version 變動同步走 aggregate save，沒 race；S076 之後 increment 走獨立 SQL，aggregate save 沒參與，但仍把舊的 in-memory 值寫回。

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | 10 並行 download + 1 並發 suspend | counter delta == 10（不被 suspend save 覆蓋） |
| AC-2 | INSERT path (createSkill) | 不依賴 in-memory `downloadCount`；DB DEFAULT 0 接管 |
| AC-3 | findById path | downloadCount 仍正確讀回（READ 行為不變） |
| AC-4 | aggregate `recordDownload()` 單元測試 | 行為不變（in-memory mutation；測試不測 save） |
| AC-5 | analytics download_events table | delta 與 HTTP 200 count 一致（不變，S076 已驗） |

## §4 Fix

`Skill.downloadCount` 加 `@ReadOnlyProperty`：
```java
import org.springframework.data.annotation.ReadOnlyProperty;
...
@ReadOnlyProperty
@Column("download_count")
private long downloadCount;
```

語意：
- findById SELECT 仍含此欄位（read 不變）
- save() 的 INSERT / UPDATE 排除此欄位
- 唯一寫入路徑：`SkillRepository.incrementDownloadCount` 的 atomic SQL UPDATE

INSERT path 不指定 download_count → DB DEFAULT 0 接管（schema 已是 `NOT NULL DEFAULT 0`）。

## §5 Test plan

加 `SkillSuspendDuringDownloadTest`（@SpringBootTest 整合測試）：
1. Upload skill
2. 10 並行 download + 1 suspend
3. assert counter delta == 10

`SkillAggregateTest.recordDownloadIncrementsCountAndRegistersEvent` 不變（純 in-memory）。

## §6 Verification

- `./gradlew test` PASS
- Smoke: 重跑 R23.5 race → 10 dl + 1 sus → counter=10（pre: 3）
- Smoke: 重跑 R22 N=30 → counter=30（pure download，無 suspend；S076 fix 仍生效）

## §7 Result

待 ship 後填。

**Result（填於 ship 後）**：
- 299 backend tests / 0 fail（無 regression — aggregate `recordDownload()` 純 in-memory，不受影響）
- Smoke 1：sanity test (10 parallel download, 無 suspend) → counter=10 ✓（S076 fix 維持）
- Smoke 2：race test (10 parallel download + 1 concurrent suspend) → counter=10 ✓（pre: 3 — 7 個被 save 蓋掉）
- INSERT createSkill flow 正常（DB DEFAULT 0 接管）
- ship v2.55.0 (M73)
