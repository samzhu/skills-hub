# S075 — `FlagReadModel.isNew()` `@JsonIgnore`

> **Status**: in-flight
> **Bug ledger**: AI (loop e2e tick 64 Round 21)
> **Estimate**: XS / 3 pts

## §1 Problem

`GET /api/v1/skills/{skillId}/flags` 回傳 entries 包含 Spring Data JDBC `Persistable.isNew()` 的 serialization artifact `"new": true`：

```json
{
  "id": "...",
  "skillId": "...",
  "type": "spam",
  "description": "...",
  "reportedBy": "anonymous",
  "createdAt": "2026-05-01T...",
  "status": "OPEN",
  "new": true   // ← 不該出現的 internal flag
}
```

`Persistable.isNew()` 是 Spring Data JDBC 用來判斷 INSERT vs UPDATE 的 framework hook，不是 domain 概念；不該洩漏到 API contract。

完全平行於 Bug AA / S063 — 當時對 `Skill` aggregate 的 `isNew()` 加了 `@JsonIgnore`，但 `FlagReadModel` 是獨立 aggregate（read-side projection 用 `Persistable<String>`），那次 fix 沒覆蓋。

## §2 Detection

Round 21.1 GET `/skills/{id}/flags` 第一筆回應 keys：
```
['id', 'skillId', 'type', 'description', 'reportedBy', 'createdAt', 'status', 'new']
```
最後一個 `new` 即 bug。

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | GET /flags 回應 JSON keys | 不含 `new`；只含 7 個 domain 欄位（id, skillId, type, description, reportedBy, createdAt, status） |
| AC-2 | Spring Data JDBC INSERT/UPDATE 行為 | 不變 — `isNew()` 仍回 `true`，只是 Jackson 不 serialize |

## §4 Fix

加 `@JsonIgnore` 到 `FlagReadModel.isNew()`（method-level；Jackson 會優先 method-level annotation 過 record property 推斷）。

```java
@JsonIgnore
@Override
public boolean isNew() {
    return true;
}
```

## §5 Test plan

`FlagReadModelJsonTest` 加 `@JsonTest` slice：serialize FlagReadModel → 解析 JSON keys → assert 不含 `new`。

## §6 Verification

- `./gradlew test --tests "*Flag*"` 全 PASS
- Smoke：curl GET `/skills/{id}/flags` → keys 不含 `new`

## §7 Result

待 ship 後填。

**Result（填於 ship 後）**：
- 299 backend tests / 0 fail（298 → 299，新增 `getFlagsExcludesIsNewArtifact`）
- 重啟 backend → 真實 curl `GET /skills/{pdf}/flags` → 6 entries → 第一筆 keys = ['id','skillId','type','description','reportedBy','createdAt','status']（無 `new`）✓
- AC-1 PASS：JSON 不含 `new`
- AC-2 PASS：Spring Data JDBC INSERT/UPDATE 行為不變
- ship v2.53.0 (M71)
