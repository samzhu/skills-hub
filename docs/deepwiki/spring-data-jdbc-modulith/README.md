# Spring Data JDBC + Spring Modulith 深度分析

> **定位：** Spring 官方 DDD aggregate persistence + transactional outbox + 模組化單體框架
> **GitHub：** [spring-projects/spring-data-relational](https://github.com/spring-projects/spring-data-relational) · [spring-projects/spring-modulith](https://github.com/spring-projects/spring-modulith)
> **授權：** Apache 2.0 · **語言：** Java 17+
> **版本對齊：** Spring Data Relational `4.0.5`（含 spring-data-jdbc）· Spring Modulith `2.0.6`
> **驗證日期：** 2026-04-29 · 對應 Skills Hub `backend/build.gradle.kts:23-26`（Spring Boot 4.0.6 BOM）

---

## 一句話總結

**Spring Data JDBC** 提供「immutable aggregate + 顯式 SQL」為核心的 DDD persistence — 沒有 JPA 的 lazy loading、沒有 dirty tracking、沒有 session/cache，每次 `repo.save()` 直接生成 SQL；代價是 `@MappedCollection` 用 delete-and-reinsert 維護子集合，對高頻寫聚合不友善。**Spring Modulith** 在其上加裝 transactional outbox（Event Publication Registry），把 in-process `publishEvent()` 從「best-effort」升級為「at-least-once 投遞 + 失敗可 retry」，並用 ArchUnit 強制模組邊界。兩者組合是 Spring 官方對「模組化單體 + 事件驅動」的標準配方。

---

## 文件索引

| 文件 | 內容 | 主要素材 |
|---|---|---|
| [architecture.md](./architecture.md) | 兩專案目錄結構、分層、核心抽象、模組依賴 | 官方 reference docs + GitHub repo tree |
| [aggregate-design.md](./aggregate-design.md) | Spring Data JDBC 聚合設計 — annotations、save 生命週期、`@MappedCollection` 雷區、`AbstractAggregateRoot` 內部、isNew 偵測、AOT/Dialect | spring-data-relational `4.0.5` 與 spring-data-commons source |
| [event-publication-registry.md](./event-publication-registry.md) | Spring Modulith outbox — schema、listener wrapping、retry、externalization、observability | spring-modulith `2.0.6` source |
| [data-flow.md](./data-flow.md) | 端到端流程圖（5 個情境）：建立、發版、ACL 高頻寫、listener 失敗 retry、應用重啟重投 | 跨兩專案 |
| [design-decisions.md](./design-decisions.md) | 設計決策表、10 條生產陷阱、Skills Hub S023 遷移借鑑分析（含 V4 Flyway DDL） | 綜合 + GitHub issues |

---

## 技術棧一覽

| 層面 | Spring Data JDBC | Spring Modulith |
|---|---|---|
| 語言版本 | Java 17+（4.0.x） | Java 17+（2.0.x） |
| 主要抽象 | `JdbcAggregateTemplate`, `AbstractJdbcConfiguration`, `AbstractAggregateRoot` | `PersistentApplicationEventMulticaster`, `EventPublicationRegistry`, `@ApplicationModuleListener` |
| 持久化 | `JdbcTemplate` / `NamedParameterJdbcTemplate` | JDBC outbox 表（`event_publication`） |
| 事件機制 | `@DomainEvents` method on aggregate | Transactional outbox + AFTER_COMMIT async listener |
| 模組劃分 | N/A（不關心模組） | 套件即模組（ArchUnit `ApplicationModules.of(...)`） |
| 樂觀鎖 | `@Version` on aggregate root | N/A |
| Schema 管理 | 推薦 Liquibase code-first；亦相容 Flyway 手寫 | `event_publication` 表須由應用 Flyway/Liquibase 提供（預設不自動建） |
| AOT 相容性 | Dialect 解析需 DataSource → 與 GraalVM/AOT 不友善（須 fallback） | `@ApplicationModuleListener` 走 AOP advisor，AOT 兼容 |

---

## 與 Skills Hub 的關聯

當前 `backend/build.gradle.kts` 已引入：
- `spring-boot-starter-data-jdbc` (`4.0.6` → spring-data-jdbc `4.0.5`)
- `spring-modulith-starter-core` (`2.0.6`)
- 但 **尚未** 引入 `spring-modulith-starter-jdbc`（即未啟用 Event Publication Registry）

**S023（Backlog）** 是這份 deepwiki 的目標 spec — 「ES → Spring Data JDBC 充血聚合 + Modulith 事件通訊」遷移：

| 借鑑點 | 對應決策 | 影響的 Skills Hub 程式 |
|---|---|---|
| `AbstractAggregateRoot` + `@DomainEvents` | `Skill` 從「method 回傳 event」改為「method 變更狀態 + `registerEvent()`」 | `skill/domain/Skill.java`（重寫） |
| Event Publication Registry | 取代手動 `events.publishEvent()`，獲得 at-least-once 保證 | `skill/command/SkillCommandService.java`（拿掉手動 publish） |
| `@ApplicationModuleListener` | 取代現行 `@EventListener`；獲得 async + REQUIRES_NEW + outbox 追蹤 | `skill/query/SkillProjection.java`、`search/`、`analytics/` 全模組 listener |
| `@MappedCollection` 不適用於高頻寫 | `SkillVersion` / `AclEntry` 維持獨立 aggregate（不放進 `Skill` 內部集合） | 維持現有 `SkillVersionReadModel` 結構，升格為 aggregate |
| Flyway V4 schema | `event_publication` 表手動建立（disable schema-initialization） | `db/migration/V4__event_publication.sql`（新增） |

完整遷移計畫見 [design-decisions.md §4 Skills Hub S023 借鑑分析](./design-decisions.md)。

---

## 如何閱讀

- 想理解整體架構與分層 → 從 `architecture.md` 開始
- 想了解「為什麼 `@MappedCollection` 不能用於 ACL」→ `aggregate-design.md §2`
- 想知道 outbox 的可靠性保證細節（什麼會掉、什麼不會）→ `event-publication-registry.md §3, §4`
- 想看具體流程（含失敗 retry 路徑）→ `data-flow.md`
- 想直接動手做 S023 遷移 → `design-decisions.md §4`（含 V4 Flyway DDL、套件結構、新依賴清單）
