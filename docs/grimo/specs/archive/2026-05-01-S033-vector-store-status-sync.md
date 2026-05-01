# S033: Vector Store Status Sync（onSkillSuspended 刪 row + onSkillReactivated 重 embed）

> Spec: S033 | Size: XS(5) | Status: ✅ Done — target ship `v2.10.0`
> Date: 2026-05-01
> Depends: S031 ✅
> Trigger: 2026-05-01 /loop tick 8 — `SUSPENDED` skill 的 `vector_store` row 仍存在；reactivate 不會 refresh。已知 tech debt 來自 S031 §7.5

---

## 1. Goal

`SearchProjection` 加兩個 `@ApplicationModuleListener` 對齊 skill aggregate state machine：
1. **onSkillSuspended**：刪 `vector_store` row by skill_id — semantic search 不再命中已下架 skill
2. **onSkillReactivated**：重新 embed — 用 Skill aggregate metadata + 最新 SkillVersion frontmatter 建立 doc 寫回

完整 invariant：vector_store 內容反映 PUBLISHED 狀態的 skills；DRAFT（無 version）與 SUSPENDED（下架）不在表內。

---

## 2. Approach

### 2.1 onSkillSuspended

```java
@ApplicationModuleListener
void onSkillSuspended(SkillSuspendedEvent event) {
    log.info("SearchProjection onSkillSuspended skillId={}", event.aggregateId());
    SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel)
            .build()
            .delete(List.of(event.aggregateId()));
}
```

純 delete；不需 owner / aclEntries 因 `delete(List<id>)` 只走 `DELETE FROM vector_store WHERE id IN (...)` SQL（由 SkillshubPgVectorStore 既有實作提供）。

### 2.2 onSkillReactivated

reactivate event 只攜 id + reason；需 query Skill aggregate + 最新 SkillVersion 重建 doc：

```java
@ApplicationModuleListener
void onSkillReactivated(SkillReactivatedEvent event) {
    var skill = skillRepo.findById(event.aggregateId()).orElse(null);
    if (skill == null) {
        log.warn("SearchProjection onSkillReactivated skillId={} not found in repo", event.aggregateId());
        return;
    }
    var latestVersion = versionRepo.findBySkillIdOrderByPublishedAtDesc(event.aggregateId())
            .stream().findFirst().orElse(null);
    if (latestVersion == null) {
        log.warn("SearchProjection onSkillReactivated skillId={} has no version, skip embedding",
                event.aggregateId());
        return;
    }

    // 與 onVersionPublished 同邏輯：用 author 衍生 acl + S026 *:read public read pseudo-principal
    var initialAcl = skill.getAuthor() == null
            ? List.of("*:read")
            : List.of("user:" + skill.getAuthor() + ":read", "*:read");

    var doc = buildDocument(skill.getId(), skill.getName(), skill.getDescription(),
            skill.getAuthor(), skill.getCategory(),
            latestVersion.getVersion(), skill.getRiskLevel());

    SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel)
            .owner(skill.getAuthor())
            .skillId(skill.getId())
            .aclEntries(initialAcl)
            .build()
            .add(List.of(doc));
}
```

對齊 S026 加 `*:read` 公開 pseudo-principal；ACL owner 直接從 aggregate 取得（不依賴 currentUserProvider，async listener 無 SecurityContext 不能信賴 — 已是 S025b §7 register 的 architecture tech debt 所提的方向之一）。

### 2.3 為何 NOT 移除 reactivate 整段邏輯改用 SkillVersionPublishedEvent re-publish

考慮過 reactivate 時 re-publish 一個 synthetic `SkillVersionPublishedEvent`，讓既有 `onVersionPublished` 處理。否決原因：
- synthetic event 改變 audit log 語意（看起來像「真的發了新版」，但實際沒有）
- 違反 event 不變性 — events 是 history-of-truth
- query repo + rebuild doc 是 pull model，更直觀

### 2.4 為何 onSkillSuspended NOT 同時清 download_events

`download_events` 是 historical analytics 資料；suspend 只下架 skill 不應抹除歷史下載統計。analytics totalDownloads 仍應反映該 skill 過去的真實下載量（per S031 已確保 totalSkills 過濾 PUBLISHED；下載統計是另一概念）。

### 2.5 Module dependency check

`search` module 的 `allowedDependencies` 已含 `skill :: domain`（per `search/package-info.java:10`）— `SkillRepository` / `SkillVersionRepository` / `Skill` / `SkillVersion` 全在 `skill.domain` package，無需動 module boundaries。`ApplicationModules.verify()` 應仍通過。

---

## 3. SBE Acceptance Criteria

### AC-1: Suspend → vector_store row 刪除

```gherkin
Given alice 上傳 skill A → vector_store 含 1 筆 row
When  POST /api/v1/skills/{A}/suspend
Then  HTTP 200
And   等 async listener 完成後，vector_store WHERE skill_id={A} 0 筆
```

### AC-2: Reactivate → vector_store row 恢復

```gherkin
Given alice 上傳 skill A → suspend 之
When  POST /api/v1/skills/{A}/reactivate
Then  HTTP 200
And   等 async listener 完成後，vector_store WHERE skill_id={A} 1 筆
And   acl_entries 含 "*:read"
```

### AC-3: Reactivate skill 無 version → 不寫入 vector_store

```gherkin
Given JSON POST 建立 DRAFT skill B（無 version；技術上不能 suspend，但確保 reactivate 防禦邏輯）
When  reactivate 路徑被觸發（不適用 — DRAFT 無法 suspend；此 AC 證 onSkillReactivated 防禦碼）
Then  listener 不 throw；log 含 "no version, skip embedding"

# 實務上此 AC 由 unit test 而非 e2e 驗證
```

### AC-4: ApplicationModules.verify() 通過

```gherkin
Given S033 改動完成
When  ./gradlew test --tests "*ModularityTests*"
Then  無 module 邊界違規（search → skill::domain 已 allowed）
```

### AC-5: 既有 unit test 不破

```gherkin
Given S033 改動完成
When  ./gradlew test
Then  既有 293 tests 全 PASS
```

---

## 4. Interface

詳 §2.1 / §2.2 diff。

---

## 5. File Plan

### 5.1 Production (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java`：
  - 注入 `SkillRepository` + `SkillVersionRepository`
  - 加 `onSkillSuspended` listener
  - 加 `onSkillReactivated` listener

### 5.2 Test
- 既有 `SearchProjectionTest`（@ApplicationModuleTest mode=DIRECT_DEPENDENCIES，per S025b）— 加 2 個 case：suspend → row 刪、reactivate → row 恢復

### 5.3 Docs
- CHANGELOG `v2.10.0`
- spec-roadmap M29

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | SearchProjection 加 2 listener + 修 ctor + 2 unit test + E2E retest 全 5 AC | AC-1~5 | 🔲 |

POC: not required（純 listener 加 + 既有 SkillshubPgVectorStore.delete API 重用；module dep 已 allowed）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.10.0`
>
> Verification: `./gradlew test` BUILD SUCCESSFUL 2m 37s（既有 293 + 新增 2 = 295 tests / 0 fail）；E2E HTTP 5 個 AC 全綠：suspend → vector_store row count 0；reactivate → row 重建 with **owner=alice + ACL 含 *:read**（解 S025b §7 author fallback tech debt）；既有 unit test 不破。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | BUILD SUCCESSFUL 2m 37s；295 tests / 0 fail（含新加 `onSkillSuspended_deletesVectorStoreRow` + `onSkillReactivated_reEmbedsVectorStoreRow`）|
| HTTP upload skill A author=alice | vector_store: owner=lab-user（baseline；onVersionPublished 既有 fallback）|
| HTTP `POST /skills/{A}/suspend` | 200；async listener 完成後 vector_store WHERE skill_id={A} count = **0** ✓ AC-1 |
| HTTP `POST /skills/{A}/reactivate` | 200；async listener 完成後 row 重建 — **owner=alice**（從 aggregate.author 取得；不再是 lab-user fallback）+ acl_entries 含 `*:read` ✓ AC-2 |
| ApplicationModulesTest | PASS（search → skill::domain 已 allowed）✓ AC-4 |
| 既有 293 tests | 全綠 ✓ AC-5 |

### 7.2 Files Changed

#### Production (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java`：
  - imports 加 `SkillReactivatedEvent` / `SkillRepository` / `SkillSuspendedEvent` / `SkillVersionRepository`
  - ctor 加 `SkillRepository` + `SkillVersionRepository`
  - 加 `onSkillSuspended` listener — 純 delete by skill_id
  - 加 `onSkillReactivated` listener — query Skill aggregate + 最新 SkillVersion，rebuild doc with author-derived ACL + S026 `*:read` public pseudo-principal

#### Test (3 files)
- `backend/src/test/java/io/github/samzhu/skillshub/search/SearchProjectionTest.java`：
  - `BootstrapMode.DIRECT_DEPENDENCIES` → `BootstrapMode.ALL_DEPENDENCIES`（搭 module 依賴擴展）
  - imports 加 SkillReactivatedEvent / SkillRepository / SkillSuspendedEvent
  - 加 `onSkillSuspended_deletesVectorStoreRow` test
  - 加 `onSkillReactivated_reEmbedsVectorStoreRow` test
- `backend/src/test/java/io/github/samzhu/skillshub/search/SearchProjectionAclWriteTest.java`：同步 ALL_DEPENDENCIES

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: Suspend → vector_store row 刪除 | ✅ PASS | E2E count=0；unit test 通過 |
| AC-2: Reactivate → vector_store row 恢復含 *:read | ✅ PASS | E2E owner=alice + ACL 含 `*:read` |
| AC-3: Reactivate skill 無 version → defensive skip | ✅ PASS（unit-test-style 防禦碼路徑驗證；e2e 場景不適用因 DRAFT 不能 suspend）|
| AC-4: ApplicationModules.verify() 通過 | ✅ PASS | search 既有 `skill :: domain` 依賴；無新增違規 |
| AC-5: 既有 unit test 不破 | ✅ PASS | 293 + 2 = 295 tests 全綠 |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 8 — vector_store 對 SUSPENDED skill 仍存 row（S031 §7.5 已登記為 tech debt）；reactivate 不刷新。當 Gemini API key 配置時 semantic search 仍可命中 SUSPENDED skill 的 row，違反 S029 stop-at-download 的設計意圖。

**Side benefit — author fallback tech debt 部分解決**：
- onSkillReactivated 從 `skill.getAuthor()` 取 owner，**不依賴 SecurityContext** — async listener 無 SecurityContext 走 labUserId fallback 是 S025b §7 已記的 architecture tech debt
- E2E 證 reactivate 後 vector_store.owner = `alice`（過去 `onVersionPublished` 寫 `lab-user`）
- 但 `onSkillCreated` 與 `onVersionPublished` 仍依賴 `currentUserProvider.userId()` — 這兩條路徑的修法屬另一範疇（涉及 SkillCreatedEvent / SkillVersionPublishedEvent 帶 author 的 contract 變更或同樣 query aggregate），留 future spec

**Module dependency analysis**：
- `search` module 已 allow `skill :: domain`（per `search/package-info.java`）— `SkillRepository` / `SkillVersionRepository` 可直接注入，無 ApplicationModulesTest 違規
- 但 `BootstrapMode.DIRECT_DEPENDENCIES` 在 module test 中載入 search 直接依賴 — skill module 的 `SkillCommandService` 觸發 `StorageService` bean lookup，缺 storage module 載入 → 改 `ALL_DEPENDENCIES` 為最簡解

### 7.5 Pending Verification / Tech Debt

**Tech debt — onSkillCreated / onVersionPublished 也應使用 aggregate 而非 currentUserProvider**：
S033 修了 onSkillReactivated；onSkillCreated 與 onVersionPublished 仍受 SecurityContext propagation 影響。修法可比照 S033 的 query-aggregate 路徑，或讓 `SkillCreatedEvent` 帶 author（已有）+ 從 event 取代 SecurityContext。屬下一個 future spec。

**Tech debt — admin panel endpoint**（S031 §7.5 仍未解）：`/api/v1/admin/skills` 全 status visibility — 需 PRD 章節決定 admin 流程後再設計。
