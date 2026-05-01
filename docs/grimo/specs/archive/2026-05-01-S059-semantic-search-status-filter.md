# S059: Semantic Search PUBLISHED-Only Visibility

> Spec: S059 | Size: XS(5) | Status: ✅ Done — target ship `v2.36.0`
> Trigger: 2026-05-01 /loop tick 32 Chrome E2E — HomePage 輸入「test」semantic mode 顯示 10 個結果含 DRAFT skills（`tick29-s056-11699` v2.0.0-rc.1 / `tick26-final-case3` / `tick23-testing-skill`）— 與 S031（list/categories endpoints filter PUBLISHED-only）不一致。Backend `/api/v1/search/semantic` SQL 沒 status filter；vector_store row 為 DRAFT skill 也會被 SearchProjection 寫入。

---

## 1. Goal

`SkillshubPgVectorStore.SIMILARITY_SEARCH_SQL_ACL` 加 JOIN skills table + WHERE status='PUBLISHED' filter，與 S031 list/categories endpoint 視 visibility 一致。DRAFT/SUSPENDED skills 即使有 vector embedding 也不公開呈現於 semantic search。

---

## 2. Approach

### 2.1 SQL diff

```diff
 SELECT id, content, metadata, embedding <=> ? AS distance
-  FROM vector_store
+  FROM vector_store vs
+  JOIN skills s ON s.id = vs.skill_id
- WHERE acl_entries ??| ?::text[]
-   AND embedding <=> ? < ?
+ WHERE s.status = 'PUBLISHED'
+   AND vs.acl_entries ??| ?::text[]
+   AND vs.embedding <=> ? < ?
- ORDER BY distance
- LIMIT ?
+ ORDER BY distance
+ LIMIT ?
```

需確認 `vector_store.skill_id` FK 為 NOT NULL（既有 DB schema：`vector_store_skill_id_fkey FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE`）— 必有 join target。

### 2.2 為何 NOT 改 SearchProjection 過濾不寫 DRAFT

過濾在 query side 比 write side 安全：
- write side 過濾需處理 DRAFT → PUBLISHED 的延遲 indexing（user 期待 publish 後立即可搜）
- query side 過濾即時生效；vector_store 多儲少量 DRAFT row 屬可接受 trade-off
- 對齊 S031 設計（list endpoint 也是 query-side filter）

### 2.3 為何 ON DELETE CASCADE 不影響本 fix

cascade 確保 skill 被 hard-delete 時 vector_store row 跟著刪。但本 fix 場景是 status filter（DRAFT/SUSPENDED skill 仍存在），cascade 與 fix 正交。

### 2.4 Performance

JOIN 一張小表（`skills`，目前 ~50 row，正式預估 ~10k）+ status 已有 idx_skills_status btree index — JOIN cost 可忽略。

---

## 3. SBE Acceptance Criteria

### AC-1: semantic search 不返回 DRAFT skill

```gherkin
Given DB 有 DRAFT skill `tick29-s056-11699`（vector_store 有對應 embedding）
When  GET /api/v1/search/semantic?q=test
Then  results 不含 `tick29-s056-11699`
```

### AC-2: semantic search 不返回 SUSPENDED skill

```gherkin
Given DB 有 SUSPENDED skill（一般 vector_store row 已被 S033 刪除；但若殘留也被 status filter 兜底）
When  semantic search
Then  results 不含 SUSPENDED skill
```

### AC-3: PUBLISHED skill 仍正常返回

```gherkin
When  semantic search "test"
Then  仍返回 PUBLISHED skill 結果（前後筆數可能變少但仍非 0）
```

### AC-4: 既有 backend test 不破

```gherkin
When  ./gradlew test
Then  286 tests / 0 fail
```

---

## 4. Interface

詳 §2.1。

---

## 5. File Plan

### 5.1 Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/search/SkillshubPgVectorStore.java`：`SIMILARITY_SEARCH_SQL_ACL` 加 JOIN skills + status filter

### 5.2 Test
- 既有 test 不破即可；E2E curl 驗 DRAFT 不出現

### 5.3 Docs
- CHANGELOG `v2.36.0`
- spec-roadmap M55

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | SQL JOIN + status filter + curl retest | AC-1~4 | 🔲 |

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.36.0`
>
> Verification: 286 / 0 fail（含 4 個既有 ACL test 對齊 PUBLISHED seed）；E2E 9 results 全 PUBLISHED。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | 286 / 0 fail ✓ AC-4 |
| GET `/search/semantic?q=test` | 9 results；全部 status=PUBLISHED（DB 對照確認）✓ AC-1/2/3 |

### 7.2 Files Changed

#### Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/search/SkillshubPgVectorStore.java`：`SIMILARITY_SEARCH_SQL_ACL` 加 JOIN skills + WHERE status='PUBLISHED'

#### Test (2 files)
- `backend/src/test/java/io/github/samzhu/skillshub/search/SkillshubPgVectorStoreAclSearchTest.java`：seed status `DRAFT` → `PUBLISHED`（對齊 fix；test 重點為 ACL 不為 status）
- `backend/src/test/java/io/github/samzhu/skillshub/search/SemanticSearchIntegrationTest.java`：同上

### 7.3 AC Results Table

| AC | Status |
|----|--------|
| AC-1: 不返回 DRAFT | ✅ |
| AC-2: 不返回 SUSPENDED | ✅ |
| AC-3: PUBLISHED 仍返回 | ✅ |
| AC-4: 既有 test 不破 | ✅ |

### 7.4 Key Findings

**Discovery context**: tick 32 Chrome E2E HomePage 輸入「test」semantic mode 顯 10 結果含明顯 DRAFT skills（`tick29-s056-11699` v2.0.0-rc.1 / `tick26-final-case3` 等）— S031 list endpoint filter PUBLISHED-only 已落地，但 `/search/semantic` SQL 漏 status filter；vector_store 為 DRAFT skill 仍有 embedding row（SearchProjection 在 SkillCreatedEvent 時 INSERT，DRAFT 狀態下也會）。

**Fix scope choice**:
- Query-side filter（JOIN skills + WHERE status='PUBLISHED'）對齊 S031 設計
- 不在 write side（SearchProjection）過濾 — 避免 DRAFT → PUBLISHED 延遲 indexing
- vector_store 容許保留 DRAFT row 為 trade-off（query side 過濾即時生效）

**Performance**：JOIN 一張小表（idx_skills_status btree）+ vector_store table 可忽略 cost。

### 7.5 Pending Verification / Tech Debt

- **Bug X（同 tick 32 發現）**：frontend HomePage semantic mode 卡片顯「草稿」badge 即使結果 status=PUBLISHED — 因 `SemanticSearchResult` JSON 沒 `status` field；SkillCard `skill.status !== 'PUBLISHED'` undefined 比較為 truthy → 顯 badge。屬獨立 frontend bug，留下一輪修
- DB 既有畸形 entries（version "foo"/"" + ACL malformed）需 migration
