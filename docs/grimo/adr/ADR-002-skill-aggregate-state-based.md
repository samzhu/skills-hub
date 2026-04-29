# ADR-002: Core Domain 從純 Event Sourcing 轉向 Spring Data JDBC 充血聚合 + Modulith Outbox

> Status: **Accepted** (2026-04-29)
> Supersedes: PRD Decision Log D20 / D21 / D22 / D23 · architecture.md §Architecture Pattern: Event Sourcing + CQRS (Core Domain) L22-345 · S018 §2.4 Challenge #10
> Triggered by: S023 規劃階段研究 — Spring Data JDBC + Spring Modulith Event Publication Registry 整合可行性深入研究
> Research: `docs/deepwiki/spring-data-jdbc-modulith/`（6 份檔案、2K+ 行 source-level 引用）
> Implementation: S023（outbox 基礎建設）→ S024（Skill aggregate 重寫）

---

## 1. Context

Skills Hub MVP（v1.0.0）至 Phase 2（v1.4.0）採用「Core domain (skill, security): Event Sourcing + CQRS」架構（PRD D20-D23、architecture.md L22-162）：

| 原決策 | 內容 |
|---|---|
| **D20** | 後端架構：Event Sourcing + CQRS（核心領域）|
| **D21** | ES 實作：Spring Modulith Events + 自建 Event Store |
| **D22** | Event Store：PostgreSQL `domain_events` 表（JSONB payload + per-aggregate `(aggregate_id, sequence)` UNIQUE）|
| **D23** | ES MVP 範圍：僅儲存事件 + 更新 projection；replay/snapshot/upcasting 進 backlog（ES-B1~B4）|

對應實作（截至 v1.4.0）：

- `skill/domain/Skill.java` 為純 POJO，不持久化；構造子 replay events 重建 state
- 業務 method 驗證不變量後 **回傳 event**（不 mutate state）
- `SkillCommandService.saveAndPublish()` 手動串接 `eventStore.save()` + `events.publishEvent()`
- 全模組 11 個 `@EventListener`（同步、in-TX）消費 domain events 維護 read models
- `domain_events` 表為 source of truth；`skills` / `skill_versions` 等為衍生 read model

### 1.1 觸發本 ADR 的演化壓力

S023 在 backlog（「Spring Modulith outbox migration」）原本範圍是「全模組 `@EventListener` → `@ApplicationModuleListener`、加 `spring-modulith-starter-jdbc`、啟用 `event_publication` outbox 表」— 純基礎設施升級，不動 aggregate 設計。

但在 S023 規劃前期深入研究 Spring Data JDBC + Spring Modulith 整合（產出 `docs/deepwiki/spring-data-jdbc-modulith/` 6 份 source-level 文件）時發現：

1. **ES 純度的代價愈來愈不對等**：
   - aggregate method 不能 mutate state，每次 command 都要 `loadAggregate()` 重 replay events（O(N) per command）
   - service 層每個 method 約 15 行（手動組 payload、寫 store、publish），業務僅 1-2 行
   - read model 與 aggregate 概念分離但實質重複（`Skill` aggregate 欄位 ≈ `SkillReadModel` 欄位）— 兩處 sync drift

2. **ES backlog 4 項實際 use case 評估後皆模糊**：
   - **ES-B1（Event Replay）** — 重建 read model；但目前無「需要從零重建」的場景，且 read model schema 變更可用 Flyway migration 直接 ALTER
   - **ES-B2（Aggregate Snapshot）** — 加速 aggregate 載入；只在 events 數百筆以上才有感，Skills Hub 每個 skill < 100 events
   - **ES-B3（Event Upcasting）** — schema 演化；若 aggregate 改回 state-based，不再需要
   - **ES-B4（Saga / Process Manager）** — 跨 aggregate 流程；Skills Hub 目前無此需求（B7/B8 組織管理進來才用得到）

3. **Spring Data JDBC + Spring Modulith Event Publication Registry 提供乾淨組合**（per deepwiki 研究）：
   - `repo.save()` → `@DomainEvents` 自動 publish → outbox 寫入同 TX → AFTER_COMMIT async listener
   - 可靠性保證（at-least-once）由框架接管，不再手動處理 ES + manual publish 的 side-effect
   - 充血聚合 method 直接 mutate state + `registerEvent()`，符合 DDD 主流寫法（Vaughn Vernon、Eric Evans）

4. **S018 §2.4 Challenge #10 拒絕 `AbstractAggregateRoot` 的前提變了**：
   - S018 否決 Path B 的理由：「Skill 不是 Spring Data entity，無 repository → 無 SPI 點 invoke pipeline；架構錯位」
   - 本 ADR 改變前提 — Skill **變成** Spring Data entity（`@Table` + `extends AbstractAggregateRoot`），原否決理由不再成立
   - S018 §2.4 Challenge #10 結論「Spring Data `AbstractAggregateRoot` pattern 適合 traditional persisted-aggregate 架構，**不適合 ES + CQRS where aggregate 不被持久化、只持久化 events**」**仍然正確**；本 ADR 的選擇是改變後者而非反駁前者

---

## 2. Decision

**全面從 Event Sourcing 核心領域改為 Spring Data JDBC 充血聚合（rich domain model）+ Spring Modulith Event Publication Registry 事件投遞，保留 `domain_events` 表作 audit log。**

### 2.1 變動明細

| 元素 | 變動前（v1.4.0） | 變動後（S024 ship 後） |
|---|---|---|
| `Skill` 類別 | POJO，不持久化，constructor replay events | `@Table("skills")` + `extends AbstractAggregateRoot<Skill>`，**直接持久化** |
| `Skill` 業務方法 | 驗證不變量 → return event（不改 state） | 驗證不變量 → mutate state + `registerEvent(...)` |
| `Skill` 載入 | `eventStore.findByAggregateIdOrderBySequenceAsc(id)` → `new Skill(id, events)` | `skillRepository.findById(id).orElseThrow()` |
| `Skill` 儲存 | service 內手動 `eventStore.save(domainEvent)` + `events.publishEvent(applicationEvent)` | `skillRepository.save(skill)` — 框架觸發 `@DomainEvents` → outbox INSERT 同 TX |
| Source of truth | `domain_events` 表 | `skills` 表（state） + `domain_events` 表（audit log，**降級**） |
| Event 投遞機制 | 手動 `ApplicationEventPublisher.publishEvent()` | Spring Data proxy 觸發 `@DomainEvents` → Modulith `event_publication` outbox |
| Listener 標註 | `@EventListener`（同步、in-TX）+ `@Order` 控順序 | `@ApplicationModuleListener`（async、AFTER_COMMIT、REQUIRES_NEW、outbox 追蹤）|
| Listener 失敗語義 | propagate 回 publisher → save rollback | event 留 outbox `status=FAILED` 待 retry（`IncompleteEventPublications` API）|
| `SkillReadModel` | 獨立 read model record + `SkillReadModelRepository` | **刪除**（合併進 `Skill` aggregate 本身即 read model） |
| `SkillVersion` | aggregate 內部概念（無獨立 entity） | **獨立 aggregate**（`@Table("skill_versions")`，避開 `@MappedCollection` delete-and-reinsert 雷） |
| `AclEntry` | aggregate 內部概念 + `acl_entries jsonb` 欄位（S016 ship） | **維持 `acl_entries jsonb` 欄位**（避免高頻寫雷；S016 設計保留） |
| `SkillProjection` | 7 個 `@EventListener` 分別維護 `SkillReadModel` 各欄位 | **大幅縮減**（read model 已是 aggregate 自身）— 僅留 cross-aggregate 投影（download_count）|

### 2.2 不變動明細

| 元素 | 維持原樣的理由 |
|---|---|
| `SkillStatus` enum | S018 已實作 state machine 充血 enum；保留 |
| `acl_entries jsonb` + GIN 索引 | S016 已驗證高頻寫場景效能；不能拆 `@MappedCollection` |
| `vector_store` 表 + `SkillshubPgVectorStore` | S014/S017 已驗證；search projection 仍以 listener 維護 embedding |
| `domain_events` 表結構 | 不改 schema，由 audit listener 寫入；保留作為合規 audit trail |
| Spring Modulith 模組邊界（skill / search / security / analytics / storage / shared） | ArchUnit 驗證沿用 |
| Read API（`SkillQueryController`、`SkillQueryService`） | 改打 `SkillRepository.findById()` 取代 `SkillReadModelRepository`；對外 API contract 不變 |

### 2.3 不採用的替代方案

| 方案 | 否決理由 |
|---|---|
| 維持純 ES，僅做 Modulith outbox 升級（路徑 B） | 不解決 §1.1 列出的 ES 純度代價；service 層仍冗長、aggregate 不能 mutate state、read model 與 aggregate 仍重複 |
| ES 保留 + Aggregate Snapshot（路徑 C；提前實作 ES-B2） | 實質複雜度高於 A：需自寫 `SkillRepository` 同時維護 events + snapshot；snapshot 與 events 一致性自管；無框架支援 |
| `SkillVersion` 用 `@MappedCollection` 內嵌進 Skill | per deepwiki `aggregate-design.md §2`：每次 publishVersion → DELETE 全部既有 versions + INSERT 全部，O(N) 寫放大 |
| `AclEntry` 拆獨立 `@Table` aggregate | 同上原因；ACL grant/revoke 高頻 → JSONB 行內 UPDATE 比 DELETE-and-reinsert 安全 |
| 直接注入 `ApplicationEventPublisher` 到 `Skill` entity | Spring Data JDBC entity 不是 Spring bean，無法 @Autowired；且 entity 變 Spring 耦合違反 POJO 原則。`AbstractAggregateRoot` + `registerEvent()` 是框架官方等價解 |

---

## 3. Drivers — 為什麼現在做

### 3.1 Spring 生態正式提供完整方案（2025 起）

- Spring Boot **4.0.6**（Java 25 baseline）+ Spring Data JDBC **4.0.5** — `AbstractAggregateRoot` + `EventPublishingRepositoryProxyPostProcessor` 機制穩定（spring-data-commons source verified per deepwiki）
- Spring Modulith **2.0.6** — V2 `event_publication` schema（含 `status` / `completion_attempts` / `last_resubmission_date` 欄位）+ `IncompleteEventPublications` API + Staleness Monitor 已 GA
- ShedLock **7.7.0** — 明確支援 Spring Boot 4.x（official RELEASES.md），`JdbcTemplateLockProvider` + `usingDbTime()` PostgreSQL 整合穩定

### 3.2 Skills Hub Phase 2 完成後是合理的轉換點

Phase 2（M14/M15/M16）三個 milestone 連續 ship `v1.2.0/v1.3.0/v1.4.0`，Core Domain 已在 ES 模式下試作完整：
- `Skill.java` aggregate replay 邏輯成熟（含 ACL + state machine + version tracking）
- 11 個 listener 模式已穩定（projection / search / analytics / scan）
- 已知痛點明確（service 層冗長、`@Order` 跨模組順序、手動 publish boilerplate）

下一個 Phase（B7/B8 組織管理 / 企業級多戰情室）擴展前，先把核心領域改為更可維護的形狀，避免 ES 模式的痛點放大到更多 aggregate。

### 3.3 deepwiki 研究確認可行性與雷區位置

`docs/deepwiki/spring-data-jdbc-modulith/` 的 6 份檔案 source-level 確認：
- `aggregate-design.md` — `AbstractAggregateRoot.java` source 驗證；`@MappedCollection` delete-and-reinsert 機制證據（`WritingContext.update()` lines 78-83）
- `event-publication-registry.md` — `event_publication` schema、`PersistentApplicationEventMulticaster` 攔截路徑、retry / staleness monitor 機制
- `data-flow.md` — 5 個端到端流程（含失敗 retry 路徑）
- `design-decisions.md` — 10 條生產陷阱清單（每條配 GitHub issue 引用）

可信度：**Validated**（all source-cited，可重現）。

---

## 4. Consequences

### 4.1 正面影響

| 面向 | 改善 |
|---|---|
| **DDD 表達力** | aggregate method 直接 mutate state + register event，符合教科書充血 model；service 層每個 command 縮為 3 行 |
| **可靠性** | 事件投遞從手動 `publishEvent()` 升級為 transactional outbox（at-least-once）+ 失敗 retry + 觀測 |
| **觀測性** | `event_publication` 表可 query incomplete count；`/actuator/modulith` 端點看模組依賴 |
| **效能** | aggregate load 從 O(events.size) replay 降為 O(1) row read；長期 events 累積不影響 latency |
| **程式碼縮減** | `SkillReadModel` + `SkillReadModelRepository` 整組刪除（~250 行）；`SkillCommandService` 命令方法各約 -10 行（`saveAndPublish` boilerplate 全消） |
| **Listener 可靠性** | `@EventListener` 失敗 = silently drop；`@ApplicationModuleListener` 失敗 = `event_publication.status=FAILED` 可觀測 + retry |

### 4.2 負面影響 & 接受的取捨

| 取捨 | 影響 | 緩解 |
|---|---|---|
| 失去 ES「事件即真相」時間旅行 | 無法重建任意時點 aggregate state | 接受 — Skills Hub 無此 use case；audit trail 仍由 `domain_events` audit log 提供 |
| `domain_events` 退化為 audit log | 不再 source of truth；S024 起新事件由 `AuditEventListener` 寫入 | 既有 events 不動（歷史 audit 可查）；新事件 schema 不變（只是寫入 path 改變） |
| ES backlog ES-B1~B4 廢除 | Event Replay / Snapshot / Upcasting / Saga 不再規劃 | spec-roadmap 標記 obsolete；若未來真有需求（如跨 aggregate saga）再評估獨立技術選型 |
| Listener async + AFTER_COMMIT 引入 eventual consistency window | API 回 201 後 read model 可能短暫不可見 | 接受最終一致；S023 文件化此語義；security-sensitive listener（suspend / revoke）特別評估（S023 §設計） |
| HikariCP pool 壓力增加 | `@ApplicationModuleListener` REQUIRES_NEW 各持有 1 個連線；async 並發 + 主執行緒競爭 pool | S023 強制設定 GCP `db-f1-micro` `maximum-pool-size: 3` 仍適用；async listener 並發數受限於 Spring Modulith executor 配置（預設 single-thread executor，需評估） |
| `@Order` 跨模組順序失效 | `SkillCreated` → `SearchProjection` 對 `vector_store.skill_id` FK 順序、`SkillVersionPublished` → `ScanOrchestrator` 對 `skill_versions` row 順序皆斷裂 | **S023 設計階段必須處理**（可選方案：ScanOrchestrator 加 retry-on-FK-violation；或同 listener 內 chain 呼叫；或保留 sync `@TransactionalEventListener(BEFORE_COMMIT)` 對 FK 依賴 listener） |
| Listener 重複觸發風險（retry） | retry 機制可能對同 event 投遞 ≥ 2 次 | 全部 listener 必須加冪等保護（`ON CONFLICT DO UPDATE` / `INSERT ... WHERE NOT EXISTS` / domain_event.id 去重） |

### 4.3 棄用 / 淘汰

| 項目 | 棄用時機 | 處理方式 |
|---|---|---|
| `SkillReadModel.java` + `SkillReadModelRepository.java` | S024 ship | 整組刪除 |
| `SkillCommandService.saveAndPublish()` | S024 ship | 內部方法刪除；replaced by `repo.save(skill)` |
| `SkillCommandService.loadAggregate()`（event replay） | S024 ship | replaced by `repo.findById(id).orElseThrow()` |
| Spec roadmap Backlog ES-B1 / ES-B2 / ES-B3 / ES-B4 | 本 ADR Accepted | 標 `obsolete - superseded by ADR-002` |
| architecture.md L22-162「Architecture Pattern: Event Sourcing + CQRS (Core Domain)」段落 | S024 ship | 改寫為「Spring Data JDBC 充血聚合 + Modulith Outbox」 |
| architecture.md L329-345「Event Store」段落 | S024 ship | 改為「Audit Log」段落 |
| architecture.md L165-174 ES backlog 表格 | 本 ADR Accepted | 刪除 |
| CLAUDE.md「Core domain (skill, security): Event Sourcing + CQRS」 | S024 ship | 改為「Core domain: Spring Data JDBC rich aggregate + Modulith outbox」 |

---

## 5. Implementation Plan

```
ADR-002 (本檔，Accepted) — 2026-04-29
   │
   ▼
S023 — Spring Modulith Outbox Foundation     (M, ~12 pts)
   │ ├─ spring-modulith-starter-jdbc dep
   │ ├─ V4 Flyway: event_publication + archive
   │ ├─ V5 Flyway: shedlock 表
   │ ├─ ShedLock 7.7.0 整合
   │ ├─ 全 11 個 @EventListener → @ApplicationModuleListener
   │ ├─ FK 順序問題解法（per S023 §2 設計）
   │ ├─ Idempotency 保護（per S023 §4 listener 改寫）
   │ ├─ Scheduled retry bean (IncompleteEventPublications.resubmitOlderThan)
   │ ├─ event_publication_failed_count Micrometer gauge
   │ └─ ship as v1.5.0
   │
   ▼ (S023 verified ship 後)
S024 — Skill State-Based Aggregate           (M-L, ~13-14 pts)
   │ ├─ Skill = @Table + extends AbstractAggregateRoot
   │ ├─ SkillVersion 拆獨立 @Table aggregate
   │ ├─ AclEntry 維持 jsonb 欄位（S016 設計保留）
   │ ├─ AuditEventListener — domain_events audit-only 寫入
   │ ├─ SkillReadModel + Repository 刪除
   │ ├─ SkillCommandService 重寫（每方法 3 行）
   │ ├─ architecture.md / CLAUDE.md 同步更新
   │ ├─ ES backlog 標 obsolete
   │ └─ ship as v2.0.0（major bump — 架構轉向）
```

### 5.1 為什麼 S024 是 v2.0.0

`v1.x` 系列為 ES + CQRS 架構；`v2.0.0` 起為 Spring Data JDBC 充血聚合架構。雖然對外 REST API contract 不變，但內部架構模式根本性改變，符合 SemVer 對 major bump 的精神（即使無 API breaking change，重大架構轉向值得用 major 標記，便於日後 audit / git bisect）。

### 5.2 Rollback 風險評估

| Spec | Rollback 成本 | 細節 |
|---|---|---|
| S023 | 低 | revert 一個 PR；event_publication 表保留無傷（不再寫入即可）；listener 改回 `@EventListener` |
| S024 | 中 | revert 一個 PR；skills 表狀態欄位需與 `domain_events` 重新對齊（Flyway down migration 不寫，採手動腳本）；既有資料不會丟失（events audit log 仍完整） |

S024 ship 前必須手動 dual-write 一段時間驗證？— **不需要**。原因：S024 寫 `skills` row 與寫 `domain_events`（audit）發生在同 TX；rollback 只是把寫入路徑切回去，既有 row 仍可從 events replay 重建（`Skill.fromHistory()` 邏輯在 S024 PR 內保留為 deprecated 私有方法供 emergency 用）。

---

## 6. Validation

### 6.1 Validated Claims

| Claim | Evidence |
|---|---|
| `AbstractAggregateRoot` + `@DomainEvents` 在 `repo.save()` 後自動 publish events | `EventPublishingRepositoryProxyPostProcessor.invoke()` lines 104-118 — `invocation.proceed()` 後呼叫 `publishEventsFrom`。引用：`docs/deepwiki/spring-data-jdbc-modulith/aggregate-design.md §3` |
| `event_publication` INSERT 與業務 entity SQL 同 transaction | `JdbcEventPublicationRepository.create()` `@Transactional`（預設 propagation REQUIRED）— 加入呼叫者 TX。引用：`event-publication-registry.md §3` |
| `@MappedCollection` 子集合在 update 時 delete-and-reinsert | `WritingContext.update()` lines 78-83 — `deleteReferenced()` 後 `insertReferenced()`。引用：`aggregate-design.md §2` |
| ShedLock 7.7.0 支援 Spring Boot 4.x | RELEASES.md 明確標示「7.x.x: Spring 7.0, 6.2; Spring Boot 4.x, 3.5, 3.4」。引用：[ShedLock RELEASES.md](https://github.com/lukas-krecan/ShedLock/blob/master/RELEASES.md) |
| `spring.modulith.events.jdbc.schema-initialization.enabled` 預設 false | `JdbcEventPublicationAutoConfiguration.java` lines 74-80 — `@ConditionalOnProperty(... havingValue = "true")`。引用：`event-publication-registry.md §9` |

### 6.2 Hypothesis Items（POC required in S023 / S024）

| Item | POC required | Spec |
|---|---|---|
| Spring Modulith intra-module listener 的 `@Order` 是否仍生效（用以解 SkillCreated → SearchProjection FK 順序） | Yes | S023 |
| `@ApplicationModuleListener` async executor 的預設 thread pool 配置在 Cloud Run multi-instance 下是否需自訂 | Yes | S023 |
| Skill 充血方法呼叫多個 `registerEvent` 時，`AbstractAggregateRoot.domainEvents()` 是否按 register 順序 publish | Yes | S024 |

POC 結果寫入 spec §6 Task Plan 的 POC Findings 段落。

---

## 7. References

### 7.1 內部文件

- `docs/deepwiki/spring-data-jdbc-modulith/` — 6 份 source-level 研究檔案（README / architecture / aggregate-design / event-publication-registry / data-flow / design-decisions）
- `docs/grimo/specs/archive/2026-04-27-S018-skill-aggregate-rich-domain.md` §2.4 Challenge #10 — 本 ADR 推翻的前置決策
- `docs/grimo/PRD.md` Decision Log D20-D23 — 本 ADR supersede 的原 ES + CQRS 決策
- `docs/grimo/architecture.md` L22-345 — 將由 S024 改寫的 Core Domain 段落
- `docs/grimo/adr/ADR-001-postgresql-migration.md` — 上一個架構級 ADR；本 ADR 不影響 ADR-001 結論

### 7.2 外部 source

- [Spring Data Commons `AbstractAggregateRoot.java`](https://github.com/spring-projects/spring-data-commons/blob/main/src/main/java/org/springframework/data/domain/AbstractAggregateRoot.java)
- [Spring Data Commons `EventPublishingRepositoryProxyPostProcessor.java`](https://github.com/spring-projects/spring-data-commons/blob/main/src/main/java/org/springframework/data/repository/core/support/EventPublishingRepositoryProxyPostProcessor.java)
- [Spring Modulith `event_publication` V2 schema (PostgreSQL)](https://github.com/spring-projects/spring-modulith/blob/main/spring-modulith-events/spring-modulith-events-jdbc/src/main/resources/org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql)
- [Spring Modulith `@ApplicationModuleListener`](https://docs.spring.io/spring-modulith/reference/events.html)
- [Spring Modulith Appendix — Schemas](https://docs.spring.io/spring-modulith/reference/appendix.html)
- [ShedLock RELEASES.md](https://github.com/lukas-krecan/ShedLock/blob/master/RELEASES.md)
- [Spring Data Relational `WritingContext.java`](https://github.com/spring-projects/spring-data-relational/blob/main/spring-data-relational/src/main/java/org/springframework/data/relational/core/conversion/WritingContext.java)
- [Spring Modulith Issue #519 — Postgres index 8191 bytes limit](https://github.com/spring-projects/spring-modulith/issues/519)
- [Spring Modulith Issue #926 — Duplicate events on startup](https://github.com/spring-projects/spring-modulith/issues/926)
- [Spring Modulith Issue #1146 — Performance problem with event publication lookup](https://github.com/spring-projects/spring-modulith/issues/1146)
