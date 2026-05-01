# S070: Cleanup Pre-S033 SUSPENDED Vector Orphans

> Spec: S070 | Size: XS(5) | Status: ✅ Done — target ship `v2.48.0`
> Trigger: 2026-05-01 /loop tick 47 — data integrity probe 發現 2 個 SUSPENDED skill 仍在 vector_store。S033（M29 v2.10.0）加 `SearchProjection.onSkillSuspended` listener 之前的 SUSPENDED events 從未過 listener — orphan vectors 永久累積。

---

## 1. Goal

Flyway V7 migration：one-shot DELETE pre-S033 SUSPENDED vector_store rows。Idempotent — 已 clean DB 跑 no-op。

```sql
DELETE FROM vector_store
 WHERE skill_id IN (SELECT id FROM skills WHERE status = 'SUSPENDED');
```

---

## 2. User Impact Pre-fix

S059 (M55 v2.36.0) JOIN skills + WHERE status='PUBLISHED' filter — semantic search 不返回 SUSPENDED skill。User-visible impact = 0；fix 為 storage hygiene。

---

## 7. Implementation Results — ✅ Done

### Verification
- `flyway_schema_history` V7 success ✓
- `SELECT FROM vector_store JOIN skills WHERE status='SUSPENDED'` count: 0 ✓
- `./gradlew test` 286 / 0 fail

### Files Changed (1)
- `backend/src/main/resources/db/migration/V7__cleanup_pre_s033_suspended_vectors.sql`

### Pattern Note
S069 (audit listener) + S070 (vector cleanup) 都是「post-fix historical data drainage」pattern：
- Aggregate / projection 修了之後，historical bad state 仍卡在 DB
- 修兩端：（1）listener defense / migration cleanup（2）防止 NEW bad state（aggregate validation）
