# S018: Skill Aggregate 充血演化 + Suspend/Reactivate Events

> Spec: S018 | Size: S(11) | Status: ⏳ Design
> Date: 2026-04-27
> Depends: S014（PostgreSQL `domain_events` 表 — code-level: aggregate replay 需 JDBC 路徑）、S016（`PermissionEvaluator` 介面 — code-level: 新端點掛 `@PreAuthorize("hasPermission(...)")`）
> Blocks: 無
> Parallel design ok：S014 / S016 仍在 design 期，本 spec 設計可平行進行；ship 必須等兩者皆 shipped。

> **本 spec 自包含**：所有載重設計決策的研究結論已內聯於 §2.4 / §2.5。

---

## 1. Goal

把 Skill aggregate 從目前的「**部分重建**」（只追蹤版本號）升級為**完整充血模型**：aggregate 透過 `apply(DomainEvent)` 重建完整狀態（含 `status`、`name` 等），對外暴露 `suspend()` / `reactivate()` 業務動作並產生對應 domain events，同時順手修兩個既有缺陷（SkillProjection 不轉 status 的 BUG + `SkillCommandService.createSkill/uploadSkill` 的 hardcoded sequence）。

**簡單講**：以前 Skill 只知道「我發過哪些版本」；以後 Skill 知道「我現在是 DRAFT / PUBLISHED / SUSPENDED」、「我能不能被 publish 新版本」、「我能不能被 suspend」。所有狀態轉換規則由 aggregate 自己 enforce，service 只是 orchestrator。新增「下架 / 恢復」兩個管理者動作，授權交給 S016 的 `PermissionEvaluator`。

```
┌── v1.0.0（current）──────────────────────────────────────────────┐
│   Skill aggregate 部分重建：                                       │
│   - 只追蹤 publishedVersions + latestSequence                      │
│   - 不追蹤 status / name / owner                                   │
│   - 唯一不變量：版本號不重複                                       │
│                                                                    │
│   SkillProjection BUG：                                            │
│   - SkillVersionPublishedEvent 不會把 status 從 DRAFT → PUBLISHED  │
│   - 結果：所有 skills 永遠停在 status="DRAFT"                       │
│                                                                    │
│   SkillCommandService 一致性問題：                                 │
│   - createSkill / uploadSkill 用 hardcoded sequence (1L, 2L)       │
│   - addVersion / publishVersion 用 aggregate.nextSequence()        │
│   - 兩條路徑不一致                                                 │
└────────────────────────────────────────────────────────────────────┘
                                  ↓ S018（本 spec）
┌── 終態（S018 完成）──────────────────────────────────────────────┐
│   Skill aggregate 完整重建：                                       │
│   - apply(DomainEvent) → 多型分派到 typed apply(...)              │
│   - 追蹤 status (DRAFT / PUBLISHED / SUSPENDED)                    │
│   - 不變量：版本號不重複 + 狀態轉換合法性                          │
│                                                                    │
│   新業務動作：                                                     │
│   - skill.suspend(SuspendCommand) → SkillSuspendedEvent           │
│   - skill.reactivate(ReactivateCommand) → SkillReactivatedEvent   │
│   - 端點 @PreAuthorize("hasPermission(#id, 'suspend')") (S016)    │
│                                                                    │
│   修正既有問題：                                                   │
│   - SkillProjection.on(SkillVersionPublishedEvent) 同時轉 status  │
│   - SkillProjection.on(SkillCreatedEvent) 維持 status="DRAFT"     │
│   - 全部 sequence 走 aggregate.nextSequence()                      │
└────────────────────────────────────────────────────────────────────┘
```

### 與 Critical Path 的對應

PRD §Backlog **B1（權限控制 — Admin / Publisher / Consumer 三層角色）** 隱含「Admin 可下架不合規 skill」這個能力。S018 提供 **下架 / 恢復** 兩個業務動作的 domain layer，B1 後續可在這層上掛角色判斷。對應 PRD §Security Model 的「累積一定 flag 數量自動觸發複查」也需要 status 機制。

### 事件驅動架構（既有，本 spec 擴充）

```
SkillCommandService.suspend(cmd)
   │
   ├─▶ skill = loadAggregate(skillId)            ← 完整 replay 重建狀態
   │      apply(SkillCreatedEvent) → status=DRAFT
   │      apply(SkillVersionPublishedEvent) → versions.add(...)
   │      ...
   │
   ├─▶ event = skill.suspend(cmd)                ← aggregate 驗不變量 + 產生 event
   │      檢查：status 必須是 PUBLISHED（IllegalStateException 否）
   │
   └─▶ saveAndPublish(skillId, "SkillSuspended", ..., skill.nextSequence(), event)
          eventStore.save(domainEvent) ← 持久化 generic DomainEvent
          ApplicationEventPublisher.publish(SkillSuspendedEvent)
                  └─▶ SkillProjection.on(SkillSuspendedEvent) ← 更新 read model status
```

**S014 已驗證的事件流結構不變**；S018 只新增兩個 event types + 兩個 listener handlers + 修一個 BUG。

---

## 2. Approach

### 2.1 關鍵設計決策（共 9 項）

| # | 決策 | 選擇 | 理由 | 否決的替代 |
|---|------|------|------|-----------|
| 1 | Aggregate 重建模式 | **完整重建（apply pattern）+ 多型分派** | 業界 ES 標準寫法；development-standards §27 已宣告目標「狀態轉換合法性」；refactor 友善 | 部分重建（現況）— rich domain 必須完整 state；單一 method + switch（user 偏好多型分派）— 失去 IDE 友善 |
| 2 | apply dispatch 機制 | **outer 字串 switch + inner typed apply method（多型）** | `DomainEvent.payload` 是 `Map<String,Object>`，無法直接 sealed pattern match；outer 字串 switch 處理「generic → typed」轉換，inner typed method 才是業務語義 | Sealed type + pattern matching（需引入 mapper 層；cross-JAR replay 有 MatchException 風險）；純 reflection（黑魔法、難 debug） |
| 3 | State machine 實作 | **手寫 enum SkillStatus 加 transition methods** | 3 個狀態手寫最直觀；非法 transition 拋 `IllegalStateException`；零依賴 | Spring StateMachine（過度設計，引入 `spring-statemachine-core` 跟 Spring Boot 4 相容性需驗證） |
| 4 | 新事件粒度 | **細粒度（SkillSuspendedEvent + SkillReactivatedEvent）** | 與既有 SkillCreatedEvent / SkillVersionPublishedEvent / SkillDownloadedEvent 命名風格一致；listener 可獨立 subscribe | 通用 SkillStatusChanged（payload 含 oldStatus/newStatus）— 失去語義、listener 多寫 if 判斷 |
| 5 | 動作 command 物件化 | **每個動作一個 record command**（`SuspendCommand`、`ReactivateCommand`） | 與 `CreateSkillCommand` / `PublishVersionCommand` 既有風格一致；後續加 audit field（`suspendedBy`、`reason`）只動 record 不破壞 caller | 直接傳 primitives（`String reason, String userId`）— 加欄位破壞 caller |
| 6 | 端點授權 | **`@PreAuthorize("hasPermission(#id, 'suspend')")` + S016 PermissionEvaluator** | 走 S016 ABAC 路徑統一；ADR-001 §5 已定義 S016 提供 `PermissionEvaluator` Strategy/Registry | `hasRole('admin')` — 簡單但與 S016 ABAC 形成兩條路徑；無授權 — 違反 PRD §Security Model |
| 7 | SkillProjection BUG 修法 | **在既有 `on(SkillVersionPublishedEvent)` 內加 `if (status == DRAFT) → PUBLISHED`；並新增 `on(SkillSuspendedEvent)` / `on(SkillReactivatedEvent)`** | 與既有 read-modify-write 模式一致；event-driven 自然轉換 | 加 status 欄位 default 在 Projection 寫死 — 不正確；需 aggregate 主動驅動 |
| 8 | hardcoded sequence 修法 | **`createSkill` / `uploadSkill` 改用 `Skill.create()` factory 之後的 `aggregate.nextSequence()`**（即 1L，但走標準路徑） | 一致性；rich domain 化後所有路徑走相同 API | 保留 hardcoded — 持續技術債、rich domain 化後產生 inconsistency |
| 9 | Transactional boundary | **新方法 `suspend` / `reactivate` 加 `@Transactional`；既有方法保持原樣**（避免 side effect） | 新方法的 event store + projection 一致性必要；既有方法行為已驗證 v1.0.0 通過，不在本 spec 範圍 | 全部統一加 `@Transactional` — 改變既有行為，需更廣的回歸測試 |

### 2.2 與既有架構的契合

| 維度 | 現況（v1.0.0 + S014） | S018 變動 |
|------|---------------------|-----------|
| **儲存層** | PostgreSQL + Spring Data JDBC（S014 後） | **不變** |
| **Event Store** | `domain_events` 表（JDBC, S014 後） | **不變** |
| **Read Models** | 4 個 `@Table` records（S014 後） | `skills.status` 欄位語義收斂（從「永遠 DRAFT」變成「會轉換」） |
| **Aggregate Root** | `Skill` 部分重建（追蹤版本號 + sequence） | **完整重建**：加 `status` state、加 `apply(...)` methods、加 `suspend()` / `reactivate()` business methods |
| **Domain Events** | SkillCreated / SkillVersionPublished / SkillDownloaded / SkillFlagged | **加** SkillSuspended / SkillReactivated |
| **Commands** | CreateSkillCommand / PublishVersionCommand | **加** SuspendCommand / ReactivateCommand |
| **Event listeners 解耦** | `SkillProjection`（DRAFT/Published/Downloaded）+ `SearchProjection` + `ScanOrchestrator` | `SkillProjection` 加 status 轉換 + 加 SkillSuspended/Reactivated listeners |
| **Spring Modulith 邊界** | shared / skill / security / search / analytics / storage | **不變** — `ApplicationModules.verify()` 仍綠 |
| **CQRS + ES 模式** | Aggregate Root → events → projection listener | **不變**（這是核心模式，本 spec 強化它） |
| **API Endpoints** | POST `/api/v1/skills` / POST `/api/v1/skills/upload` / PUT `/api/v1/skills/{id}/versions` | **加** POST `/api/v1/skills/{id}/suspend` / POST `/api/v1/skills/{id}/reactivate` |
| **Frontend** | React 19 SPA | **不動** — 本 spec 純後端；UI 需求由後續 spec 覆蓋 |
| **JWT / OAuth (S011/S012)** | OAuth2 RS + LAB | **不變** |
| **`@EnableMethodSecurity`** | 已啟用（SecurityConfig.java:56） | **不變** |
| **`@EventListener` vs `@ApplicationModuleListener`** | 既有 `SkillProjection` 用 `@EventListener` | **保持 `@EventListener`** — 統一切換到 `@ApplicationModuleListener` 是另一個 chore spec 範圍 |

### 2.3 Schema 設計（對 read model 的影響）

V1 schema 中 `skills.status` 已是 `VARCHAR(20) NOT NULL`，無 CHECK constraint。S018 不改 schema，只改 read 路徑與寫入語義。

#### `skills` 表的 status 語義變化（範例資料）

**Before S018（永遠停在 DRAFT）：**

| id | name | status | latestVersion |
|----|------|--------|---------------|
| `abc-1` | docker-helper | `DRAFT` | `1.0.0` ❌ 不正確 |
| `abc-2` | k8s-deploy | `DRAFT` | `2.1.0` ❌ 不正確 |

**After S018：**

| id | name | status | latestVersion |
|----|------|--------|---------------|
| `abc-1` | docker-helper | `PUBLISHED` | `1.0.0` ✅ |
| `abc-2` | k8s-deploy | `SUSPENDED` | `2.1.0` ✅（被 admin 下架） |
| `abc-3` | new-skill | `DRAFT` | `null` ✅（剛建立未發版） |

#### Domain Event 範例

新增的 event payload（SkillSuspended）：

```json
{
  "id": "evt-9f7e",
  "aggregateId": "abc-2",
  "aggregateType": "Skill",
  "eventType": "SkillSuspended",
  "payload": {
    "reason": "Multiple security flags from community",
    "suspendedBy": "admin-user-1"
  },
  "sequence": 5,
  "occurredAt": "2026-05-01T10:00:00Z",
  "metadata": {}
}
```

SkillReactivated：

```json
{
  "id": "evt-a1b2",
  "aggregateId": "abc-2",
  "aggregateType": "Skill",
  "eventType": "SkillReactivated",
  "payload": {
    "reason": "Issues resolved by author"
  },
  "sequence": 6,
  "occurredAt": "2026-05-02T15:30:00Z",
  "metadata": {}
}
```

### 2.4 Challenges Considered

> 本節內聯所有對載重決策有貢獻的研究結論，不依賴外部目錄留存。

1. **Java ES 業界 apply pattern 對比（Axon / Spring Modulith / 手寫）**
   - **Axon Framework**：`@EventSourcingHandler` 強制每個 event type 一個 method，由框架做 type resolution dispatch。重 — 需引入 `axon-spring-boot-starter`。
   - **Spring Modulith**：不提供 ES aggregate base class（只管 module 間 event publication）；aggregate 內部 ES 自行實作。
   - **手寫（業界標準）**：抽象 base class + 字串 switch / pattern matching 進入 typed method。Skills Hub 走此路。
   - 引用：[Axon Aggregate Docs 4.12](https://docs.axoniq.io/axon-framework-reference/4.12/axon-framework-commands/modeling/aggregate/)、[Spring Modulith Events Reference](https://docs.spring.io/spring-modulith/reference/events.html)、[JVM Advent 2024 — Introduction to Event Sourcing](https://www.javaadvent.com/2024/12/introduction-to-event-sourcing.html)
   - Confidence: **Validated**（讀過原始碼）

2. **`DomainEvent.payload` 是 `Map<String, Object>`，無法直接 sealed pattern match**
   - Skills Hub 既有 `DomainEvent` (record) 是 generic，cross-process replay 必須走字串型別名稱
   - 即使 in-process publish `SkillCreatedEvent` (typed record)，event store replay 拿到的仍是 `DomainEvent`
   - 結論：apply 必須兩層 — outer 從 `DomainEvent` 字串 dispatch，inner typed method 才能運用多型
   - Confidence: **Validated**（讀過 `Skill.java:27-40` + `DomainEventRepository.java`）

3. **`SkillProjection` 既有 BUG — status 不轉換**
   - `backend/.../skill/query/SkillProjection.java:78` 的 `on(SkillVersionPublishedEvent)` 只更新 `latestVersion`，沒改 `status`
   - V1 schema `status VARCHAR(20) NOT NULL` 在 `SkillProjection.on(SkillCreatedEvent):57` 寫死 `"DRAFT"`，之後沒有任何路徑改它
   - 修法：在 `on(SkillVersionPublishedEvent)` 加判斷 `if (status == "DRAFT") → "PUBLISHED"`（每個 skill 第一次發版時觸發）
   - Confidence: **Validated**（讀過 listener 完整邏輯）

4. **`SkillCommandService` hardcoded sequence**
   - `createSkill`（行 46）和 `uploadSkill`（行 60）寫死 `1L` 與 `2L`
   - `addVersion`（行 110）和 `publishVersion`（行 150）走 `skill.nextSequence()`
   - 修法：`createSkill` / `uploadSkill` 也走 `Skill.create()` factory 後的標準 sequence 計算路徑
   - 對齊風險：低（`create()` 場景 aggregate 為新，nextSequence 必為 1，行為等價）
   - Confidence: **Validated**

5. **State machine 實作 — 手寫 enum vs Spring StateMachine**
   - Spring StateMachine 設計給長執行、跨請求狀態機（含 persist、guard、action hooks）
   - 對 3 狀態 aggregate 是過度設計；引入 `spring-statemachine-core` 與 Spring Boot 4 相容性需驗證
   - DDD 慣例：aggregate 內 state machine 應封裝在 domain 層，不引入 Spring 框架滲入
   - 引用：[State Machine in DDD Context](https://patricsteiner.github.io/state-machine-in-a-ddd-context/)
   - Confidence: **Validated**

6. **`@PreAuthorize` 已啟用 — `@EnableMethodSecurity` on SecurityConfig.java:56**
   - 既有 `AdminController.java:41` 已示範 `@PreAuthorize("hasRole('admin')")`
   - S016 計畫提供 `PermissionEvaluator` Strategy/Registry（ADR-001 §5）
   - S018 等 S016 ship 後可用 `@PreAuthorize("hasPermission(#id, 'suspend')")`
   - Confidence: **Validated**（讀過 SecurityConfig + AdminController）

7. **`@EventListener` vs `@ApplicationModuleListener` drift**
   - development-standards §29 規定 `@ApplicationModuleListener`，但現況 `SkillProjection` 用 `@EventListener`
   - `@EventListener` 同步執行 + 失敗傳播，`@ApplicationModuleListener` async + transactional + 失敗追蹤
   - 本 spec **保持 `@EventListener`**（minimal change），統一切換是另一個 chore spec 範圍
   - 風險：新 listener 用 `@EventListener` 與 standards 不一致；spec §6 註明此 known gap
   - Confidence: **Validated**

8. **Reactivation 語義 — 重置版本還是只翻轉 status**
   - 業界共識（event sourcing 教材一致）：`SkillReactivated` 只翻轉 status，不影響版本歷史
   - 已 published 的版本不會因 reactivate 重新算 sequence；event store 是純 append
   - Confidence: **Validated**（[event-driven.io — sealed state](https://event-driven.io/en/this_is_not_your_uncle_java/)）

9. **`@Transactional` 邊界 — 既有 `SkillCommandService` 沒有 @Transactional**
   - 既有 `saveAndPublish` 先 `eventStore.save(domainEvent)` 再 `events.publishEvent(applicationEvent)`
   - 中間若 publish 失敗 / listener 拋例外，event 已落 DB，但 read model 沒更新（最終一致性問題）
   - 本 spec **僅在新方法 suspend / reactivate 加 @Transactional**，既有方法保留現狀
   - 後續若要全面加 @Transactional，需獨立 spec + 完整回歸測試
   - Confidence: **Validated**

### 2.5 Research Citations

| 來源 | 對本 spec 的支撐點 |
|------|-------------------|
| [Axon Framework Aggregate Docs 4.12](https://docs.axoniq.io/axon-framework-reference/4.12/axon-framework-commands/modeling/aggregate/) | `@EventSourcingHandler` 多型分派模式（決策 #1）；提供「業界標準 apply 寫法」對比 |
| [Spring Modulith Events Reference](https://docs.spring.io/spring-modulith/reference/events.html) | Modulith 不提供 aggregate base class（決策 #1 否決自動 framework）；`@ApplicationModuleListener` 語義 |
| [JVM Advent 2024 — Introduction to Event Sourcing](https://www.javaadvent.com/2024/12/introduction-to-event-sourcing.html) | 抽象 base class + replay pattern 範本（決策 #1） |
| [event-driven.io — This is not your uncle's Java (sealed state)](https://event-driven.io/en/this_is_not_your_uncle_java/) | Reactivation 只翻轉 status 不重置版本（決策 #4 + Challenge #8） |
| [State Machine in DDD Context](https://patricsteiner.github.io/state-machine-in-a-ddd-context/) | DDD aggregate 內 state machine 應手寫不引入框架（決策 #3） |
| [Baeldung — CQRS and Event Sourcing in Java](https://www.baeldung.com/cqrs-event-sourcing-java) | Spring 生態手寫 ES 慣例參考 |
| [JEP 441 — Pattern Matching for switch (Java 21 finalized)](https://openjdk.org/jeps/441) | Java 25 pattern matching 在本 spec 不採用之原因（Challenge #2） |

**既有 codebase 錨點**（git 永久留存）：
- `backend/.../skill/domain/Skill.java` — aggregate 待重構主體
- `backend/.../skill/domain/SkillStatus.java` — enum 加 transition methods（已存在 3 個 value）
- `backend/.../skill/command/SkillCommandService.java` — 加 suspend / reactivate methods、修 hardcoded sequence
- `backend/.../skill/query/SkillProjection.java` — 修 BUG + 加 2 個 listener handlers
- `backend/.../shared/security/SecurityConfig.java:56` — `@EnableMethodSecurity` 已啟用
- `backend/.../shared/security/AdminController.java:41` — `@PreAuthorize` 既有用法範例

### 2.6 Confidence Classification

| 設計決策 | Confidence | 證據 / POC 計畫 |
|---------|-----------|-----------------|
| Aggregate apply pattern（outer string switch + inner typed dispatch） | **Validated** | Axon / 手寫範例 + 既有 `Skill.java` 已實證部分模式 |
| `SkillStatus` enum 內方法 guard | **Validated** | DDD 標準慣例 + Java 25 enum 標準語法 |
| 細粒度新事件（SkillSuspendedEvent / SkillReactivatedEvent） | **Validated** | development-standards §25-26 已規定命名風格 |
| `@PreAuthorize("hasPermission(...)")` 整合 S016 PermissionEvaluator | **Hypothesis** | S016 尚未 ship；`PermissionEvaluator` 介面簽名以 ADR-001 §5 為準（標準 Spring Security 介面）；S018 task plan 內僅做 `hasRole('admin')` POC，等 S016 ship 再切換 |
| SkillProjection 既有 BUG 修法（DRAFT → PUBLISHED on first version） | **Validated** | 讀過完整 listener 邏輯 |
| `@Transactional` 加在新方法 | **Validated** | Spring 標準用法 |
| `@EventListener` 保持（不切到 `@ApplicationModuleListener`） | **Validated** | 既有 code 已驗證 |

**POC: not required** — 所有設計決策皆基於既有 code 模式或標準 Spring/Java 用法。**唯一 Hypothesis 是 S016 PermissionEvaluator 整合**，但等 S016 ship 後 task plan 自然驗證；S018 task 階段先用 `hasRole('admin')` 過 controller security test，S016 ship 後 PR 切換。

### 2.7 Validation Pass — pre-handoff drift check

從現況 read 確認：
- ✅ 既有 `Skill.java` 是 mutable class（非 record）— 可直接加欄位
- ✅ 既有 `SkillStatus` enum 已存在 3 個 value（DRAFT / PUBLISHED / SUSPENDED）— 加 transition methods
- ✅ 既有 `DomainEvent` 是 record，含 `eventType` String + `payload` Map — apply dispatch 可走字串
- ✅ 既有 `SkillCommandService.saveAndPublish` 簽名足夠彈性（`Object applicationEvent`）— 可傳新事件 record
- ✅ 既有 `SkillProjection` 的 listener 模式（@EventListener + repo.findById → save）— 可複製套用
- ✅ `@EnableMethodSecurity` 已啟用 — `@PreAuthorize` 直接生效
- ⚠️ S014 / S016 尚未 ship — spec design 進行，spec 狀態維持 ⏳ Design 直到兩者都 shipped；本 spec ship 順序為 S014 → S015/S016 → **S018 / S017**（S018 與 S017 互不依賴可平行）
- ⚠️ `@EventListener` vs `@ApplicationModuleListener` drift 已知，本 spec 不解決，未來 chore spec 處理

無 design drift；可進 §3。

---

## 3. SBE Acceptance Criteria

> 驗證指令：`cd backend && ./gradlew test`（既有 QA strategy 標準入口）
> 測試類別：3 個新 unit test 檔（Skill aggregate、SkillStatus enum）+ 約 5 個 integration test（command + projection）+ 對既有 controller test 加 2 個新端點 case

```gherkin
Scenario: AC-1 — 新建 skill 後 status 為 DRAFT
  Given 沒有 abc-1 這個 skill
  When 呼叫 createSkill(name="docker-helper", description="...", author="...", category="DevOps")
  Then 產生 SkillCreatedEvent，sequence=1
  And  read model skills.status = "DRAFT"
  And  aggregate 重建後 skill.status() == DRAFT

Scenario: AC-2 — 第一個版本發布後 status 從 DRAFT 轉為 PUBLISHED（修 BUG）
  Given abc-1 是 DRAFT 狀態的 skill（無任何版本）
  When 呼叫 publishVersion(abc-1, "1.0.0")
  Then 產生 SkillVersionPublishedEvent，sequence=2
  And  SkillProjection 把 read model skills.status 從 "DRAFT" 改為 "PUBLISHED"
  And  read model skills.latestVersion = "1.0.0"

Scenario: AC-3 — 後續發版不重複改 status
  Given abc-1 是 PUBLISHED 狀態（已發 v1.0.0）
  When 呼叫 publishVersion(abc-1, "1.1.0")
  Then 產生 SkillVersionPublishedEvent
  And  read model skills.status 仍是 "PUBLISHED"
  And  read model skills.latestVersion = "1.1.0"

Scenario: AC-4 — Suspend 一個 PUBLISHED skill
  Given abc-1 是 PUBLISHED 狀態
  And  使用者擁有 'suspend' 權限（透過 S016 PermissionEvaluator）
  When 呼叫 POST /api/v1/skills/abc-1/suspend，body { "reason": "..." }
  Then 產生 SkillSuspendedEvent，sequence=N+1
  And  aggregate.status() == SUSPENDED
  And  read model skills.status = "SUSPENDED"
  And  HTTP 200

Scenario: AC-5 — DRAFT skill 不能 suspend（aggregate 拋例外）
  Given abc-3 是 DRAFT 狀態（剛建立未發版）
  When 呼叫 skill.suspend(cmd)
  Then 拋 IllegalStateException("Cannot suspend from DRAFT")
  And  read model 不變

Scenario: AC-6 — 已 SUSPENDED skill 不能再 suspend
  Given abc-2 是 SUSPENDED 狀態
  When 呼叫 skill.suspend(cmd)
  Then 拋 IllegalStateException("Cannot suspend from SUSPENDED")

Scenario: AC-7 — Reactivate 一個 SUSPENDED skill
  Given abc-2 是 SUSPENDED 狀態
  And  使用者擁有 'reactivate' 權限
  When 呼叫 POST /api/v1/skills/abc-2/reactivate
  Then 產生 SkillReactivatedEvent
  And  aggregate.status() == PUBLISHED
  And  read model skills.status = "PUBLISHED"

Scenario: AC-8 — PUBLISHED skill 不能 reactivate
  Given abc-1 是 PUBLISHED 狀態
  When 呼叫 skill.reactivate(cmd)
  Then 拋 IllegalStateException("Cannot reactivate from PUBLISHED")

Scenario: AC-9 — SUSPENDED skill 不能 publishVersion
  Given abc-2 是 SUSPENDED 狀態
  When 呼叫 skill.publishVersion("2.0.0", ...)
  Then 拋 IllegalStateException("Cannot publish version on suspended skill")

Scenario: AC-10 — Aggregate 完整重建（apply 多型分派）
  Given event store 有 abc-1 的 events: [SkillCreated, SkillVersionPublished, SkillSuspended]
  When 呼叫 loadAggregate("abc-1")
  Then aggregate.status() == SUSPENDED
  And  aggregate.publishedVersions().contains("1.0.0")
  And  aggregate.nextSequence() == 4

Scenario: AC-11 — Hardcoded sequence 移除
  Given 沒有 abc-1
  When 呼叫 createSkill(...)
  Then SkillCommandService 內部走 Skill.create() factory 後查 nextSequence()
  And  domain_events 表中該 SkillCreatedEvent 的 sequence = 1
  And  uploadSkill 路徑：SkillCreated sequence=1, SkillVersionPublished sequence=2（皆來自 aggregate.nextSequence()，非 hardcoded）

Scenario: AC-12 — 未授權使用者無法 suspend（依賴 S016 PermissionEvaluator）
  Given 使用者沒有 'suspend' 權限
  When 呼叫 POST /api/v1/skills/abc-1/suspend
  Then HTTP 403 Forbidden
  And  event store 不變
```

每個 AC 必須對應至少一個測試（@DisplayName("AC-N: ...")）。AC-12 在 S016 ship 前先用 `hasRole('admin')` 占位（spec §6 task plan 將標 [needs S016]）。

---

## 4. Interface / API Design

### 4.1 `SkillStatus` enum 升級（加 transition methods）

```java
package io.github.samzhu.skillshub.skill.domain;

/**
 * Skill 狀態機（DRAFT → PUBLISHED ↔ SUSPENDED）。
 *
 * <p>非法 transition 拋 IllegalStateException。所有 transition 方法為 pure（無 side effect），
 * 由 aggregate 在驗證業務規則後呼叫。
 */
public enum SkillStatus {
    DRAFT {
        @Override public SkillStatus publish() { return PUBLISHED; }
    },
    PUBLISHED {
        @Override public SkillStatus suspend() { return SUSPENDED; }
        @Override public SkillStatus publish() { return PUBLISHED; } // 後續發版不轉狀態
    },
    SUSPENDED {
        @Override public SkillStatus reactivate() { return PUBLISHED; }
    };

    public SkillStatus publish() {
        throw new IllegalStateException("Cannot publish version from " + this);
    }
    public SkillStatus suspend() {
        throw new IllegalStateException("Cannot suspend from " + this);
    }
    public SkillStatus reactivate() {
        throw new IllegalStateException("Cannot reactivate from " + this);
    }
}
```

### 4.2 `Skill` aggregate 重構（多型分派 + state machine）

```java
package io.github.samzhu.skillshub.skill.domain;

public class Skill {
    private final String aggregateId;

    // 完整 state（從 events replay 重建）
    private String name;
    private SkillStatus status;
    private final Set<String> publishedVersions = new HashSet<>();
    private long latestSequence = 0;

    /** 從 event store 重建 — 完整 replay */
    public Skill(String aggregateId, List<DomainEvent> events) {
        this.aggregateId = aggregateId;
        for (var event : events) {
            apply(event);
            this.latestSequence = event.sequence();
        }
    }

    // ─── Factory ─────────────────────────────────────────
    public static SkillCreatedEvent create(CreateSkillCommand cmd) {
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (cmd.description() == null || cmd.description().isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
        return new SkillCreatedEvent(
            UUID.randomUUID().toString(),
            cmd.name(), cmd.description(), cmd.author(), cmd.category());
    }

    // ─── Business actions ────────────────────────────────
    public SkillVersionPublishedEvent publishVersion(PublishVersionCommand cmd) {
        // state machine：SUSPENDED 不可 publish
        this.status.publish();  // 預檢查（不 mutate state，state 由 apply 更新）
        if (publishedVersions.contains(cmd.version())) {
            throw new VersionExistsException("Version exists: " + cmd.version());
        }
        return new SkillVersionPublishedEvent(
            aggregateId, cmd.version(), cmd.storagePath(), cmd.fileSize(), cmd.frontmatter());
    }

    public SkillSuspendedEvent suspend(SuspendCommand cmd) {
        this.status.suspend();  // 預檢查
        return new SkillSuspendedEvent(aggregateId, cmd.reason(), cmd.suspendedBy());
    }

    public SkillReactivatedEvent reactivate(ReactivateCommand cmd) {
        this.status.reactivate();  // 預檢查
        return new SkillReactivatedEvent(aggregateId, cmd.reason());
    }

    // ─── apply: outer dispatch + inner typed methods ─────
    private void apply(DomainEvent event) {
        switch (event.eventType()) {
            case "SkillCreated"          -> apply(toCreatedEvent(event));
            case "SkillVersionPublished" -> apply(toVersionPublishedEvent(event));
            case "SkillSuspended"        -> apply(toSuspendedEvent(event));
            case "SkillReactivated"      -> apply(toReactivatedEvent(event));
            case "SkillDownloaded"       -> { /* aggregate 不需追蹤 download */ }
            default                       -> { /* 向前兼容：未知 event 忽略 */ }
        }
    }

    private void apply(SkillCreatedEvent e) {
        this.name = e.name();
        this.status = SkillStatus.DRAFT;
    }

    private void apply(SkillVersionPublishedEvent e) {
        this.publishedVersions.add(e.version());
        this.status = this.status.publish(); // DRAFT → PUBLISHED on first version
    }

    private void apply(SkillSuspendedEvent e) {
        this.status = this.status.suspend();
    }

    private void apply(SkillReactivatedEvent e) {
        this.status = this.status.reactivate();
    }

    // ─── Generic → Typed mappers（payload Map → record） ─
    private SkillCreatedEvent toCreatedEvent(DomainEvent e) {
        var p = e.payload();
        return new SkillCreatedEvent(
            e.aggregateId(),
            (String) p.get("name"),
            (String) p.get("description"),
            (String) p.get("author"),
            (String) p.get("category"));
    }
    // toVersionPublishedEvent / toSuspendedEvent / toReactivatedEvent 同模式

    // ─── Read-only accessors ────────────────────────────
    public String aggregateId() { return aggregateId; }
    public SkillStatus status() { return status; }
    public long nextSequence() { return latestSequence + 1; }
}
```

### 4.3 新 Domain Events（records）

```java
public record SkillSuspendedEvent(
    String aggregateId,
    String reason,
    String suspendedBy
) {}

public record SkillReactivatedEvent(
    String aggregateId,
    String reason
) {}
```

### 4.4 新 Commands（records）

```java
public record SuspendCommand(
    String skillId,
    String reason,
    String suspendedBy   // 從 SecurityContext 取
) {}

public record ReactivateCommand(
    String skillId,
    String reason
) {}
```

### 4.5 `SkillCommandService` 新方法 + 修 hardcoded

```java
@Service
public class SkillCommandService {

    @Transactional
    public void suspend(SuspendCommand cmd) {
        var skill = loadAggregate(cmd.skillId());
        var event = skill.suspend(cmd);
        saveAndPublish(cmd.skillId(), "SkillSuspended",
            Map.of("reason", cmd.reason(), "suspendedBy", cmd.suspendedBy()),
            skill.nextSequence(), event);
    }

    @Transactional
    public void reactivate(ReactivateCommand cmd) {
        var skill = loadAggregate(cmd.skillId());
        var event = skill.reactivate(cmd);
        saveAndPublish(cmd.skillId(), "SkillReactivated",
            Map.of("reason", cmd.reason()),
            skill.nextSequence(), event);
    }

    // 修 hardcoded sequence（createSkill / uploadSkill）：
    public String createSkill(CreateSkillCommand cmd) {
        var event = Skill.create(cmd);
        // 新 aggregate 的第一個 event 永遠 sequence=1，但走標準路徑而不寫死：
        var aggregate = new Skill(event.aggregateId(), List.of()); // empty events
        saveAndPublish(event.aggregateId(), "SkillCreated",
            Map.of("name", event.name(), /* ... */),
            aggregate.nextSequence(), event);  // = 1L 但走 API
        return event.aggregateId();
    }
    // uploadSkill 同理，第二個 event 走 aggregate.nextSequence() 取 2L
}
```

### 4.6 新 Endpoints

```java
@RestController
@RequestMapping("/api/v1/skills")
public class SkillCommandController {

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasPermission(#id, 'Skill', 'suspend')")  // S016 PermissionEvaluator
    public void suspend(@PathVariable String id, @RequestBody SuspendRequest req) {
        var userId = currentUserProvider.userId();
        commandService.suspend(new SuspendCommand(id, req.reason(), userId));
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasPermission(#id, 'Skill', 'reactivate')")
    public void reactivate(@PathVariable String id, @RequestBody ReactivateRequest req) {
        commandService.reactivate(new ReactivateCommand(id, req.reason()));
    }
}

public record SuspendRequest(String reason) {}
public record ReactivateRequest(String reason) {}
```

S016 尚未 ship 期間：先用 `@PreAuthorize("hasRole('admin')")` 占位，spec §6 task plan 標 [needs S016 swap]。

### 4.7 `SkillProjection` 修 BUG + 加新 listeners

```java
@Component
class SkillProjection {

    @EventListener  // 維持既有 annotation（drift 由獨立 spec 解）
    public void on(SkillVersionPublishedEvent event) {
        var existing = repo.findById(event.aggregateId()).orElseThrow();
        // 修 BUG：第一次發版時 DRAFT → PUBLISHED
        var newStatus = "DRAFT".equals(existing.status()) ? "PUBLISHED" : existing.status();
        var updated = new SkillReadModel(
            existing.id(), existing.name(), existing.description(), existing.author(),
            existing.category(), event.version(), existing.riskLevel(),
            newStatus, existing.downloadCount(),
            existing.createdAt(), Instant.now());
        repo.save(updated);
        // ... versionRepo.save 既有邏輯保留
    }

    @EventListener
    public void on(SkillSuspendedEvent event) {
        repo.findById(event.aggregateId()).ifPresent(existing -> {
            var updated = new SkillReadModel(
                existing.id(), existing.name(), /* ... */,
                "SUSPENDED", existing.downloadCount(),
                existing.createdAt(), Instant.now());
            repo.save(updated);
        });
    }

    @EventListener
    public void on(SkillReactivatedEvent event) {
        repo.findById(event.aggregateId()).ifPresent(existing -> {
            var updated = new SkillReadModel(
                existing.id(), existing.name(), /* ... */,
                "PUBLISHED", existing.downloadCount(),
                existing.createdAt(), Instant.now());
            repo.save(updated);
        });
    }
}
```

---

## 5. File Plan

| File | Action | Description |
|------|--------|-------------|
| `backend/.../skill/domain/SkillStatus.java` | modify | 加 `publish()` / `suspend()` / `reactivate()` transition methods + 預設拋 `IllegalStateException` |
| `backend/.../skill/domain/Skill.java` | modify（重寫） | 完整 state（status / name）+ apply 多型分派 + state machine 預檢查 + suspend/reactivate business methods |
| `backend/.../skill/domain/SkillSuspendedEvent.java` | new | record(aggregateId, reason, suspendedBy) |
| `backend/.../skill/domain/SkillReactivatedEvent.java` | new | record(aggregateId, reason) |
| `backend/.../skill/command/SuspendCommand.java` | new | record(skillId, reason, suspendedBy) |
| `backend/.../skill/command/ReactivateCommand.java` | new | record(skillId, reason) |
| `backend/.../skill/command/SkillCommandService.java` | modify | 加 `suspend()` / `reactivate()`（@Transactional）；改 `createSkill` / `uploadSkill` 走 `aggregate.nextSequence()` |
| `backend/.../skill/command/SkillCommandController.java` | modify | 加 `POST /{id}/suspend` / `POST /{id}/reactivate` 端點 + `@PreAuthorize`；新增 SuspendRequest / ReactivateRequest records |
| `backend/.../skill/query/SkillProjection.java` | modify | 修 `on(SkillVersionPublishedEvent)` 加 status 轉換邏輯；新增 `on(SkillSuspendedEvent)` / `on(SkillReactivatedEvent)` listeners |
| `backend/src/test/.../skill/domain/SkillTest.java` | new | 純 unit test：aggregate replay、state machine、不變量驗證（AC-1, 5, 6, 8, 9, 10） |
| `backend/src/test/.../skill/domain/SkillStatusTest.java` | new | 純 unit test：enum transition methods（AC-5, 6, 8, 9） |
| `backend/src/test/.../skill/command/SkillSuspendReactivateTest.java` | new | Integration test：command service 完整流程（AC-2, 3, 4, 7, 11） |
| `backend/src/test/.../skill/command/SkillCommandControllerSecurityTest.java` | new | Controller security test：@PreAuthorize 攔截（AC-12，需與 S016 整合） |
| `backend/src/test/.../skill/query/SkillProjectionStatusTest.java` | new | Integration test：projection BUG 修復驗證（AC-2 重點） |

**Files: 9 production（4 new + 5 modify）+ 5 test = 14 files。** 落在 scope=2 範圍上限。

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
