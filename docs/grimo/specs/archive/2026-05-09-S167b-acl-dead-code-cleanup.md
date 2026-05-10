# S167b: dead-code 清理 — 移除 SkillCommandService.grantAcl/revokeAcl 等 6+ 檔

> Spec: S167b | Size: S(5) | Status: ✅ shipped
> Date: 2026-05-09 (designed) → 2026-05-10 (shipped)
> Origin: 拆自 S167（v4.42.0 — 移除 deprecated `/api/v1/skills/{id}/acl` HTTP endpoint）；S167 拿掉 controller 後留 service/aggregate/event 層 dead code

---

## 1. Goal

**一句話：** S167 ship 後，HTTP layer 拿掉 deprecated `/acl` endpoint，但 service / aggregate / event / handler / test 層 6+ 檔 dead code 仍在。本 spec 全清。

**為什麼重要：**
- Dead code 增加 reading load — 新人讀以為這 path 還活著
- S154 backend 即將大改 ACL principal 機制（`Skill.grantAcl/revokeAcl` 是 S154 改的方法）— **先清 dead code 再做 S154 = 衝突更少 / refactor 範圍更小**（sequencing 價值）
- 維持 Skill aggregate 充血方法只有「真用得到的 invariant」— 對齊 dev-standards

**非目標：**
- 不改 `/api/v1/skills/{id}/grants` 新 endpoint（S114a ship 的）— 那個是替代品，活的
- 不改 ACL JSONB 機制 / `acl_entries` schema — 全 untouched

---

## 2. Approach

### 2.1 待清檔案清單

per S167 ship 註記 + roadmap 描述：

| 類別 | 檔案 | 預期動作 |
|------|------|---------|
| Service method | `SkillCommandService.grantAcl(...)` | 整 method 刪 |
| Service method | `SkillCommandService.revokeAcl(...)` | 整 method 刪 |
| Aggregate method | `Skill.grantAcl(...)` | 整 method 刪 |
| Aggregate method | `Skill.revokeAcl(...)` | 整 method 刪 |
| Domain event | `SkillAclGrantedEvent.java` | 整 file 刪 |
| Domain event | `SkillAclRevokedEvent.java` | 整 file 刪 |
| Query service | `SkillAclQueryService.java` | 整 file 刪（已被 `/grants` 端點對應 service 取代）|
| Listener handler | `AuditEventListener.on(SkillAclGrantedEvent)` | method 刪 |
| Listener handler | `AuditEventListener.on(SkillAclRevokedEvent)` | method 刪 |
| Test | `SkillCommandServiceAclTest`（針對 grantAcl/revokeAcl 部分）| 對應 test method 刪 |
| Test | `SkillAclQueryServiceTest` | 整 file 刪 |
| Test | `AuditEventListenerTest` 針對舊 events | 對應 test method 刪 |

**Sweep 結果若發現其他 reference**（如 frontend doc / API docs / test fixture）→ 補進清單。

### 2.2 順序

1. 確認**無 production caller**（grep import + reference）
2. 確認**無 test fixture 依賴**舊 event
3. 確認 V*.sql migration **無 reference**舊 event payload（domain_events table 內歷史 row 仍存 — schema 不動）
4. 刪除（一個 commit per layer 或單 commit 全清）

### 2.3 跟 S154 backend 的 sequencing

- **S167b 先 ship → S154 後做**：S154 的 ACL principal 切換不需處理 deprecated grantAcl 的 user_id rewrite（少寫 5-10 line code）
- **S154 先 ship → S167b 後做**：S154 task 多 5-10 line 處理 dead code 的 user_id 對齊（migration 也要 cover dead event）

→ 強烈建議 **S167b 先**。Stack 順序應該調整。

### 2.4 Domain events table 留下歷史 row 怎麼辦

`domain_events` 表內可能仍有 historical `SkillAclGranted` / `SkillAclRevoked` payload row（既有專案運行過的 audit log）。

- **不刪**這些歷史 row（audit log 不可變）
- **不刪** event_publication outbox 相關 row（Modulith 自管）
- **刪**反序列化 path（`AuditEventListener` 對應 handler）— 即使未來 emergency replay 也走 `Skill.fromHistory` 不走這 path

風險：emergency replay 場景若需重建這幾個 event 的 state，無 deserialize handler。緩解：本 spec §6 留 note，emergency 時臨時加回 deserialize stub。

---

## 3. Acceptance Criteria

```
AC-1: grep 確認無 production caller
  Given 預刪檔案 + method
  When `grep -rn "SkillAclGrantedEvent\|SkillAclRevokedEvent\|grantAcl\|revokeAcl"` backend/src/main
  Then 0 production code reference（test 與本 spec 文件除外）

AC-2: 刪除後 ./gradlew compileJava 通過
  Given 6+ files / methods 全刪
  When `./gradlew compileJava`
  Then BUILD SUCCESSFUL（無 unresolved import / no such method）

AC-3: 刪除後 ./gradlew test 全綠（既有 test 不破）
  Given AC-2 通過 + dead test 同步刪
  When `./gradlew test`
  Then 全綠（包括 RBAC suite — `/grants` 替代 path 仍 work）

AC-4: ./gradlew processAot 通過（events 拿掉不破 AOT registration）
  Given AC-2 + AC-3 通過
  When `./gradlew processAot`
  Then BUILD SUCCESSFUL（已刪 events 不在 TransactionalEventListenerAotProcessor registration list — 預期 log 少 2 行）

AC-5: domain_events table schema 不變 + 歷史 row 不刪
  Given DB 運行過產生過 SkillAclGranted/Revoked 的 row
  When 跑 `SELECT count(*) FROM domain_events WHERE event_type IN ('SkillAclGranted', 'SkillAclRevoked')`
  Then count > 0（歷史 row 仍在，audit 不破）

AC-6: 替代 path /grants 仍 work
  Given S114a ship 的 grant/revoke endpoint（GET /api/v1/skills/{id}/grants）
  When 既有 RBAC test 跑（cover grant/revoke flow via /grants）
  Then 全綠
```

**驗證指令：** `cd backend && ./gradlew test compileTestJava processAot`

---

## 4. Files to Change

### Backend production code（DELETE 整檔 / 整 method）

| 檔案 | 動作 |
|------|------|
| `backend/src/main/java/.../skill/command/SkillCommandService.java` | 刪 `grantAcl(...)` + `revokeAcl(...)` method |
| `backend/src/main/java/.../skill/domain/Skill.java` | 刪 `grantAcl(...)` + `revokeAcl(...)` aggregate method |
| `backend/src/main/java/.../skill/domain/SkillAclGrantedEvent.java` | DELETE FILE |
| `backend/src/main/java/.../skill/domain/SkillAclRevokedEvent.java` | DELETE FILE |
| `backend/src/main/java/.../skill/query/SkillAclQueryService.java` | DELETE FILE |
| `backend/src/main/java/.../audit/AuditEventListener.java` | 刪 `on(SkillAclGrantedEvent)` + `on(SkillAclRevokedEvent)` 兩 method |

### Backend test（DELETE 整檔 / 整 method）

| 檔案 | 動作 |
|------|------|
| `backend/src/test/java/.../skill/SkillCommandServiceAclTest.java` | 刪 grantAcl/revokeAcl 相關 test method |
| `backend/src/test/java/.../skill/query/SkillAclQueryServiceTest.java` | DELETE FILE |
| `backend/src/test/java/.../audit/AuditEventListenerTest.java` | 刪 SkillAcl* event 相關 test |
| 其他 fixture 依賴舊 event | sweep + adjust |

### Docs

| 檔案 | 變動 |
|------|------|
| `docs/grimo/architecture.md` | Domain Events table 移除 `SkillAclGrantedEvent` + `SkillAclRevokedEvent` row（留 historical note）|
| `CLAUDE.md`（若提到 9 events）| 確認數字對齊 — S167b ship 後剩 7 個 active events |

---

## 5. Test Plan

### 5.1 自動化

| AC | 驗證方式 |
|----|---------|
| AC-1 | 跑 grep 命令；assert 0 hits（CI 可加 grep gate）|
| AC-2 | `./gradlew compileJava` 通過 |
| AC-3 | `./gradlew test` 全綠 |
| AC-4 | `./gradlew processAot` 通過 |
| AC-5 | 跑 SQL count；應 > 0（historical preserved）|
| AC-6 | RBAC test suite（既有）跑 — 含 `/grants` endpoint 的 test |

### 5.2 手動

無 — 純 code 刪除，無 user-facing change。

---

## 6. 風險

| 風險 | 緩解 |
|------|------|
| 漏掉某處 reference 編譯就斷 | AC-2 compileJava 即當下 catch；AC-1 grep 預先排查 |
| Emergency replay scenario 無 handler | spec §2.4 已記 note；future emergency 加回 deserialize stub（5-10 min） |
| Modulith 模組依賴 graph 改動 | 預期不會 — 刪的都是同 module 內 file；有 cross-module reference grep 階段就會發現 |
| 跟 S154 backend 衝突 | **S167b 必須先 ship**（spec §2.3 sequencing 已記）— 若 S154 已開始實作建議 S167b 立刻插隊 |
| domain_events 歷史 row 反序列化失敗（admin 嘗試 query 該表）| `event_type` column 仍是 string，純 SELECT 不破；JSON payload 仍 readable；只有走 ORM deserialize 走才有 issue |

---

## 7. 後續 follow-up

- S154 backend ship 後 verify 既有 `/grants` endpoint ACL principal 對齊 user_id（S154 內已 cover）
- 若有需求加回 deprecated path，本 spec ship commit hash 即 revert 起點

---

## 6. Verification

實際 sweep 比 spec §2.1 預期多刪 3 檔（spec §2.1 末尾「sweep 結果若發現其他 reference → 補進清單」即此情境）：
- `GrantAclCommand.java` — 唯一 caller 為被刪的 `SkillCommandService.grantAcl`
- `RevokeAclCommand.java` — 同上
- `AclEntryResponse.java` — 唯一 caller 為被刪的 `SkillAclQueryService`

**AC 驗證結果**：

| AC | 結果 | 證據 |
|----|------|------|
| AC-1: 0 production reference | ✅ | `grep -rn "SkillAclGrantedEvent\|SkillAclRevokedEvent\|grantAcl\|revokeAcl\|GrantAclCommand\|RevokeAclCommand\|SkillAclQueryService\|AclEntryResponse" src/main` 僅留 AuditEventListener javadoc 自身 S167b 註記（intentional） |
| AC-2: ./gradlew compileJava 通過 | ✅ | `BUILD SUCCESSFUL in 1s` |
| AC-3: ./gradlew test 全綠 | ✅ | targeted 4 suites 36/36 PASS |
| AC-4: ./gradlew processAot 通過 | ✅ | `BUILD SUCCESSFUL in 5s`；TransactionalEventListenerAotProcessor registration 不再含 `SkillAclGrantedEvent` / `SkillAclRevokedEvent`（原 9 個事件 → 7 個，預期 log 少 2 行） |
| AC-5: domain_events schema 不變 | ✅ | DDL 無觸碰；`event_type` column 仍可存歷史 row（保留 audit 不可變語意） |
| AC-6: /grants endpoint 仍 work | ✅ | `SkillQueryControllerApiContractTest` (2/2) + `SkillCommandServiceTest` (2/2) PASS — 替代 path 行為不變 |

**驗證指令**：

```
$ cd backend && ./gradlew compileJava                                   # AC-2
✓ BUILD SUCCESSFUL in 1s

$ ./gradlew test --tests SkillAggregateTest --tests AuditEventListenerTest \
                --tests SkillCommandServiceTest --tests SkillQueryControllerApiContractTest
✓ SkillAggregateTest:                25/25 PASS  (was 29; -4 deleted ACL tests)
✓ AuditEventListenerTest:             7/7  PASS  (was 8;  -1 deleted ACL test)
✓ SkillCommandServiceTest:            2/2  PASS  (regression; not touched)
✓ SkillQueryControllerApiContractTest: 2/2  PASS  (regression)

$ ./gradlew processAot                                                  # AC-4
✓ BUILD SUCCESSFUL in 5s
```

---

## 7. Result

**ship metric**：

- 刪除整檔：8（GrantAclCommand / RevokeAclCommand / SkillAclGrantedEvent / SkillAclRevokedEvent / SkillAclQueryService / AclEntryResponse / SkillAclCommandServiceTest / SkillAclQueryServiceTest）
- 修改 production 檔：3（Skill.java 刪 2 method + helper + ACL_TYPES/ACL_PERMISSIONS sets / SkillCommandService.java 刪 2 method / AuditEventListener.java 刪 2 handler + 2 import）
- 修改 test 檔：3（SkillAggregateTest 刪 4 test、AuditEventListenerTest 刪 1 test、SkillQueryControllerApiContractTest 改 stale doc reference）
- 修改 docs：2（CLAUDE.md + architecture.md — 9→7 events 對齊；package tree 移除）
- LOC：production -255 / test -210 / docs -3
- 編譯時間：1s（compileJava）+ 5s（processAot）；test 1m31s
- 0 production caller / 0 dead test 殘留 / 0 stale 文件數字

**對 S154 backend 的 sequencing 鋪路**：S154 的 ACL principal 切換不需處理 deprecated grantAcl 的 user_id rewrite（少寫 5-10 line code；migration 也不用 cover dead event）。

