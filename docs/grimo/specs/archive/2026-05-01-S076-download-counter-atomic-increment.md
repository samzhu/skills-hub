# S076 — Download Counter Atomic Increment

> **Status**: in-flight
> **Bug ledger**: AJ (loop e2e tick 65 Round 22)
> **Estimate**: S / 5 pts

## §1 Problem

並行下載同一 skill 時 OptimisticLockingFailureException → 409 STATE_CONFLICT 級聯：

| N parallel | success | rate |
|------------|---------|------|
| 1 | 1 | 100% |
| 2 | 1 | **50%** |
| 3 | 1 | 33% |
| 5 | 1 | 20% |
| 10 | 1 | 10% |
| 30 | 4 | 13% |

每個「下載 window」最多 1 個成功；其他全打到 `@Version` aggregate-level 樂觀鎖衝突，client 收 409。**N=2 就半數失敗**——任意正常使用情境（兩個 user 同時點下載按鈕）就會觸發。

GlobalExceptionHandler `handleConcurrentModification` 註解明寫：「不在此處 auto-retry — 屬 future spec scope」。Future = 現在。

## §2 Root Cause

`SkillQueryService.downloadAndRecord` 走 aggregate 充血路徑：
```java
skill.recordDownload();        // mutate downloadCount + register SkillDownloadedEvent
skillRepo.save(skill);          // SQL UPDATE WHERE id=? AND version=N → @DomainEvents 自動 publish
```

並行 tx 第二個跑時 `version=N` clause fail，throw OptimisticLockingFailureException。對 **counter** 用樂觀鎖是 over-engineering — counter 不是 state machine，不需要「先看到別人的 update 才合併」的語意。

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | N=10 parallel downloads same skill | **10/10 HTTP 200**（無 409） |
| AC-2 | counter accuracy | `download_count` delta == HTTP 200 count |
| AC-3 | event publishing | `download_events` table delta == HTTP 200 count |
| AC-4 | aggregate `recordDownload()` 行為 | 不變（單元測試仍 PASS；其他 caller 仍可用） |
| AC-5 | SkillSuspended 守門 | 不變（fail-fast 在 storage 之前；suspended → 403） |

## §4 Fix

**Atomic SQL increment via `@Modifying @Query`** + 顯式 `ApplicationEventPublisher`（不走 `@DomainEvents` save 路徑）。Modulith Event Publication Registry 仍透過 `@TransactionalEventListener` 接收，outbox at-least-once 不變。

`SkillRepository`（per S024 T5 既有 cross-aggregate projection pattern — `updateRiskLevel`）：
```java
@Modifying
@Query("UPDATE skills SET download_count = download_count + 1, updated_at = :ts WHERE id = :id")
int incrementDownloadCount(@Param("id") String id, @Param("ts") Instant ts);
```

`SkillQueryService.downloadAndRecord`：
```java
// Before:
// skill.recordDownload();
// skillRepo.save(skill);

// After:
skillRepo.incrementDownloadCount(skillId, Instant.now());
eventPublisher.publishEvent(SkillDownloadedEvent.of(skillId, version.getVersion()));
```

`recordDownload()` aggregate method 保留（`SkillAggregateTest` 仍測它示範 invariant）；只是 service 不再走它。

## §5 Test plan

新增 `SkillDownloadConcurrencyTest`（@SpringBootTest + Testcontainers）：
- 並行 10 個下載 → 全 HTTP 200，count delta=10
- 並行 30 個下載 → 全 HTTP 200，count delta=30，無 409

既有 `SkillAggregateTest.recordDownloadIncrementsCountAndRegistersEvent` 不變（aggregate method 行為保留）。

## §6 Verification

- `./gradlew test` 全 PASS
- Smoke: 重跑 R22 → N=10 success rate=100% (was 10%)
- `event_publication` outbox drain 後 `download_events` count 對齊
- 觀察 `domain_events` audit log — `SkillDownloadedEvent` 是否仍由 AuditEventListener 接收（confirm event publishing path 完整）

## §7 Result

待 ship 後填。

**Result（填於 ship 後）**：
- 299 backend tests / 0 fail（無 regression — aggregate `recordDownload()` 仍由 SkillAggregateTest 覆蓋）
- 重啟 backend → smoke test：N=1/2/3/5/10/30 全 100% 成功率（pre: 100%/50%/33%/20%/10%/13%）
- counter accuracy: download_count delta == HTTP 200 count ✓
- 事件路徑：download_events 表（AnalyticsProjection 寫入）delta == HTTP 200 count → 證明 Modulith Event Publication Registry 透過 `@TransactionalEventListener` 攔截 ApplicationEventPublisher events 與 `@DomainEvents` 同效
- outbox 0 pending 收尾
- ship v2.54.0 (M72)
