# S069: AuditEventListener Null-Defense for ACL Events

> Spec: S069 | Size: XS(5) | Status: ✅ Done — target ship `v2.47.0`
> Trigger: 2026-05-01 /loop tick 46 — `event_publication` 查 outbox 發現 2 個 pending 卡住的 `SkillAclGrantedEvent`（tick 28 pre-S055 ACL validation 時期，type=null 已寫進 outbox）。AuditEventListener `Map.of("type", null, ...)` 拋 NPE → republish task 重投仍失敗 → 永久卡死。同 Bug V (S058) `Map.of` null pattern。

---

## 1. Goal

`AuditEventListener.on(SkillAclGrantedEvent)` 與 `on(SkillAclRevokedEvent)` 加 null-coalesce defense — 即使 historical bad event 有 null 欄位也能正常 recordAudit + drain outbox。

---

## 2. Approach

```java
var type = event.type() == null ? "" : event.type();
var principal = event.principal() == null ? "" : event.principal();
// 同樣對 permission / grantedBy / revokedBy
```

S055 後 aggregate validation 阻擋新 null 事件；defense 是 historical drainage。

---

## 7. Implementation Results — ✅ Done

### Verification
- `./gradlew test` 286 / 0 fail
- Restart backend → IncompleteEventRepublishTask 重投 → outbox `pending = 0`（從 2 → 0）✓

### Files Changed (1)
- `backend/src/main/java/io/github/samzhu/skillshub/audit/AuditEventListener.java`：on(SkillAclGrantedEvent) + on(SkillAclRevokedEvent) 加 null-coalesce

### Stuck Event 來源
2 個 SkillAclGrantedEvent at 2026-05-01 06:50:54（tick 28 pre-S055，testing ACL endpoint with `{principal:"user:bob", permission:"read"}` 缺 type → aggregate 接受 → outbox 寫入 type=null → listener Map.of NPE → permanent stuck）

S055 (M51) 修了 aggregate（grantAcl 預驗 type ∈ {user,role,group}）— prevent new bad events。
S069 修了 listener（drain pre-existing bad events）— full closure。

### Pattern Consolidation
`Map.of` doesn't accept null — 已知 NPE 陷阱：
- S058 修 FlagService 改 HashMap conditional add
- S069 修 AuditEventListener 改 null-coalesce ?? ""

兩個 fix 都 valid pattern。Future code 看哪個語意更符合：
- 「null = 空字串」適合 — 用 `?? ""`
- 「null = 不該出現」適合 — 用 conditional add（不 inserting null entry）
