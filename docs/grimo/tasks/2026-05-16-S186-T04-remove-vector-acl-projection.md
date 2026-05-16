# S186-T04: Remove vector ACL projection

## 對應規格
S186：Skill Embedding 同表化

## 這個 task 要做什麼
這個 task 完成後，grant / visibility 變更只需要更新 `skills.acl_entries` 與 `skills.is_public`，semantic search 下一次查詢會直接讀同一筆 `skills` row。`SkillAclProjectionListener` 不再執行 `UPDATE vector_store ...`，因為 `vector_store` 已由 T01 migration drop 掉。

## 使用者情境（BDD）
Given（前提）DB 有 `skills.id='skill-a'`，`status='PUBLISHED'`，`is_public=false`，`embedding` 非 null
When（動作）`PUT /api/v1/skills/skill-a/visibility` 把 `skills.is_public` 改成 true
Then（結果）下一次 anonymous semantic search 直接依 `skills.is_public=true` 命中 `skill-a`
And（而且）不需要等待任何 listener update `vector_store.is_public`

Given（前提）Bob 被授權 `user:u_bob:read` 讀 private skill
When（動作）grant command commit 後 `skills.acl_entries` 立刻含 `user:u_bob:read`
Then（結果）Bob 的 semantic search 直接命中該 private skill
And（而且）production code 不再有 `UPDATE vector_store SET acl_entries...`

## 研究來源
- `docs/grimo/specs/archive/2026-05-15-S177-is-public-first-search-visibility.md` §7：S177 的 lag 來源是 `vector_store` 作為 search read-scope projection。
- `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java`：目前 `rebuildAcl` 同時 update `skills` 與 `vector_store`。
- S186 §4.3：semantic SQL 直接讀 `skills.is_public OR skills.acl_entries ??| :aclPatterns`。

## 先做 POC
- POC：not required — T02 已證明 semantic SQL 可直接用 `skills` row 的 ACL / visibility。

## 正式程式怎麼做
- Class / file 名稱：
  - `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java`
  - existing grant / visibility tests if they assert vector row updates
- 入口：`SkillAclProjectionListener.rebuildAcl(String skillId)`
- 必要行為：
  - 保留 advisory lock 與 `skill_grants` → `skills.acl_entries` 重建
  - 移除 `SELECT is_public` 只為 vector projection 的讀取，除非 log 仍需要
  - 移除 `UPDATE vector_store SET acl_entries...`
  - log 保留 `skillId`、`skillEntryCount`、`skillsRows`
  - T02 semantic test already proves search reads changed `skills` row; T04 補 grant / visibility command path 的 immediate read scenario

## 單元測試 / 整合測試
- `SkillAclProjectionListenerEmbeddingColocationTest`
  - `@DisplayName("AC-S186-4: grant projection updates skills ACL without touching vector_store")`
- `SemanticSearchVisibilityLagTest`
  - `@DisplayName("AC-S186-4: semantic search sees public visibility change from skills row without vector projection")`
  - `@DisplayName("AC-S186-4: semantic search sees explicit read grant from skills.acl_entries without vector projection")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListenerEmbeddingColocationTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/search/SemanticSearchVisibilityLagTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.skill.security.SkillAclProjectionListenerEmbeddingColocationTest --tests io.github.samzhu.skillshub.search.SemanticSearchVisibilityLagTest`

## 前置條件
- S186-T02 PASS

## 狀態
pending（待做）
