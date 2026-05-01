# S034: SearchProjection Owner from Event/Aggregate（完成 author fallback 修正）

> Spec: S034 | Size: XS(5) | Status: ✅ Done — target ship `v2.11.0`
> Date: 2026-05-01
> Depends: S033 ✅
> Trigger: 2026-05-01 /loop tick 9 — alice 上傳 skill 後 vector_store.owner = `lab-user`（不是 `alice`）；async listener 無 SecurityContext 走 `currentUserProvider` labUserId fallback。S033 已修 reactivate path；S034 完成 onSkillCreated + onVersionPublished。

---

## 1. Goal

`SearchProjection.onSkillCreated` 與 `onVersionPublished` 不再依賴 `currentUserProvider.userId()`（async thread 無 SecurityContext 走 `labUserId` fallback）。改用：
1. **onSkillCreated**：直接從 `SkillCreatedEvent.author()` 取（event 已攜帶）
2. **onVersionPublished**：query `skillRepo.findById(event.aggregateId())` 取 `skill.getAuthor()`（mirror S033 onReactivated 模式）

完整解決 S025b §7 architecture tech debt 中所有三個寫入 path（onSkillCreated / onVersionPublished / onReactivated）。

---

## 2. Approach

### 2.1 onSkillCreated diff

```diff
 var initialAcl = event.author() == null
         ? List.of("*:read")
         : List.of("user:" + event.author() + ":read", "*:read");

 SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel)
-        .owner(currentUserProvider.userId())
+        .owner(event.author())
         .skillId(event.aggregateId())
         .aclEntries(initialAcl)
         .build()
         .add(List.of(doc));
```

### 2.2 onVersionPublished diff

```diff
-var owner = currentUserProvider.userId();
+var skill = skillRepo.findById(event.aggregateId()).orElse(null);
+var owner = skill != null ? skill.getAuthor() : null;
 var initialAcl = owner == null
         ? List.of("*:read")
         : List.of("user:" + owner + ":read", "*:read");
```

`skillRepo` 已於 S033 注入；不需新依賴。

### 2.3 Remove currentUserProvider field

無使用後 `currentUserProvider` field + ctor param 可移除（DI 簡化、降低 SearchProjection 對 shared::security 的耦合）。

但需檢查：search module 的 `package-info.java` 仍 declare `shared :: security` allowedDependency 是否有其他用途？— 純粹只 SearchProjection 用，可以一起移除依賴；但移除依賴 risk: 未來 spec 可能需要重加。保守做法：keep allowedDependency 註腳，僅移除 field。

### 2.4 為何 onVersionPublished query aggregate 而非 event.frontmatter()

`SkillVersionPublishedEvent` 的 frontmatter 含 `author` field（per agentskills.io spec），但：
- 系統 invariant：aggregate `author` 才是 source of truth；frontmatter author 可能與 aggregate 不一致（pre-S032 也容許不一致；S032 後 name 一致但 author 沒驗）
- query aggregate 直接拿 `skill.getAuthor()` 與 onSkillCreated 寫入時的值一致；不會被 frontmatter quirk 污染

### 2.5 既有 test 影響

`SearchProjectionTest`:
- AC-3 `onSkillCreated_writesRowWithMetadataOwnerAndSkillId`：expected `owner = "test-owner"` → 改 `"sam"`（event.author）
- AC-4 `onVersionPublished_deletesAndReWritesWithFrontmatter`：expected `owner = "test-owner"` → 改 `"sam"`（aggregate seed setUp 用 author=sam）
- AC-3 `onSkillCreated_multipleSkillsHaveIndependentOwnerState`：原 test 用 SecurityContext 切換驗 per-request isolation；現在 owner 從 event.author 取 → 改驗兩個 SkillCreatedEvent 攜不同 author 各自獨立寫入；移除 SecurityContextHolder 操作

`@WithMockUser(username = "test-owner")` 不再對 owner 產生影響但仍可保留（其他 test 可能用到，或為 future SecurityContext-aware bean）— 移除最乾淨。但要再 grep 確認 test class 內無其他依賴。

---

## 3. SBE Acceptance Criteria

### AC-1: 上傳 skill author=alice → vector_store.owner = alice

```gherkin
Given anonymous（lab-user）發 POST /api/v1/skills/upload，frontmatter author=alice
When  async listener 完成
Then  vector_store.owner = "alice"（不是 "lab-user"）
And   acl_entries 含 "user:alice:read" + "*:read"
```

### AC-2: PUT version → vector_store.owner 維持 aggregate.author

```gherkin
Given alice 上傳 skill A（onSkillCreated 寫 owner=alice）
And   onVersionPublished delete-then-add 走過
When  async listener 完成
Then  vector_store.owner 仍為 "alice"
And   acl_entries 含 "user:alice:read" + "*:read"
```

### AC-3: 既有 unit test 更新後全綠

```gherkin
Given S034 改動完成 + 既有 SearchProjectionTest 更新 expected owner
When  ./gradlew test
Then  全 295 tests PASS（onSkillCreated 對 event.author + onVersionPublished 對 aggregate.author）
```

### AC-4: ApplicationModules.verify() 通過

```gherkin
Given S034 改動完成
When  ./gradlew test --tests "*ModularityTests*"
Then  PASS（搬出 currentUserProvider 不破現有 module dep）
```

---

## 4. Interface

詳 §2.1 / §2.2 diffs。

---

## 5. File Plan

### 5.1 Production (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java`：
  - onSkillCreated: `currentUserProvider.userId()` → `event.author()`
  - onVersionPublished: `currentUserProvider.userId()` → `skillRepo.findById(...).getAuthor()`
  - 評估移除 `CurrentUserProvider` field（已無使用）

### 5.2 Test (1 file)
- `backend/src/test/java/io/github/samzhu/skillshub/search/SearchProjectionTest.java`：
  - 兩個 expected `"test-owner"` → `"sam"`
  - `onSkillCreated_multipleSkillsHaveIndependentOwnerState` 重寫為「不同 author 的 events 各自獨立寫入」
  - 評估移除 `@WithMockUser` 與 SecurityContextHolder imports

### 5.3 Docs
- CHANGELOG `v2.11.0`
- spec-roadmap M30

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | SearchProjection 改兩個 listener + 修 SearchProjectionTest 三處 + E2E retest | AC-1~4 | 🔲 |

POC: not required（純 listener 內部 author 來源 swap；S033 已驗 query-aggregate 路徑可行）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.11.0`
>
> Verification: `./gradlew test` BUILD SUCCESSFUL 2m 14s（295 tests / 0 fail，3 處 SearchProjectionTest 預期值更新）；E2E HTTP AC-1/AC-2 全綠：upload author=alice → vector_store.owner=alice（不再是 lab-user）；PUT version → owner 維持 alice。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | BUILD SUCCESSFUL 2m 14s；295 tests / 0 fail；SearchProjectionTest 三處 expected `"test-owner"` → `"sam"` |
| HTTP `POST /skills/upload` author=alice | vector_store.owner = **`alice`**（baseline `lab-user`）✓ AC-1；acl 含 `user:alice:read` + `*:read` |
| HTTP `PUT /skills/{A}/versions` | owner 仍 = **`alice`**；acl 不變 ✓ AC-2 |
| ApplicationModulesTest | PASS（無新 module dep；移除 currentUserProvider 依賴反而簡化 search → shared::security 耦合）✓ AC-4 |
| 既有 unit test 不破 | 295 tests 全綠 ✓ AC-3 |

### 7.2 Files Changed

#### Production (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java`：
  - import `CurrentUserProvider` 移除
  - field 與 ctor param `currentUserProvider` 移除（無使用）
  - onSkillCreated: `.owner(currentUserProvider.userId())` → `.owner(event.author())`
  - onVersionPublished: query Skill aggregate → `owner = skill.getAuthor()`（mirror onSkillReactivated 模式）

#### Test (1 file)
- `backend/src/test/java/io/github/samzhu/skillshub/search/SearchProjectionTest.java`：
  - 三處 expected `owner = "test-owner"` → `"sam"`（event/aggregate author）
  - `onSkillCreated_multipleSkillsHaveIndependentOwnerState` 重寫：移除 SecurityContextHolder 操作；改驗 sam vs jane 兩個不同 author 的 events 各自獨立寫入
  - 移除 `@WithMockUser`、`SecurityContextHolder`、`UsernamePasswordAuthenticationToken` imports（不再需要）

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: 上傳 skill author=alice → vector_store.owner=alice | ✅ PASS | E2E HTTP DB query 確認 |
| AC-2: PUT version → owner 維持 aggregate.author | ✅ PASS | E2E HTTP DB query 確認 |
| AC-3: 既有 unit test 更新後全綠 | ✅ PASS | 295 tests 全綠 |
| AC-4: ApplicationModules.verify() 通過 | ✅ PASS | 無新 module dep；clean up shared::security 依賴 |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 9 — alice 上傳 skill 後 vector_store.owner = `lab-user`（不是 alice）。S033 已修 `onSkillReactivated`；S034 完成 `onSkillCreated` 與 `onVersionPublished` 同樣 fix。

**Fix design rationale**:
- `onSkillCreated`：`SkillCreatedEvent.author()` 已是 source of truth（caller 上傳 form 帶來），直接用，最簡
- `onVersionPublished`：query aggregate（mirror S033 onSkillReactivated）— 不從 event.frontmatter 取 author 因 frontmatter author 與 aggregate author 可能不一致（pre-S032 也容許不一致；以 aggregate 為準避免 frontmatter quirk 污染）
- 移除 `CurrentUserProvider` field — 簡化 SearchProjection 依賴；無剩餘使用點
- ACL 仍由 author 衍生（`"user:" + author + ":read"` + `"*:read"`），與 Skill aggregate 自身 ACL 一致

**Architecture tech debt 完全解結**：
- S025b §7 列的「`SearchProjection.onVersionPublished` 用 `currentUserProvider.userId()` 在 async thread 走 labUserId fallback」一條 — S033（onReactivated）+ S034（onSkillCreated + onVersionPublished）共同完成
- 後續 SearchProjection 行為對 author 屬 deterministic：永遠跟 event/aggregate 同步，不受 SecurityContext propagation 影響

### 7.5 Pending Verification / Tech Debt

無新增 tech debt。S031 §7.5 admin panel endpoint 議題範圍不變。
