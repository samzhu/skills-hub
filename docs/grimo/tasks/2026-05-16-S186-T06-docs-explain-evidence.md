# S186-T06: Docs + EXPLAIN evidence

## 對應規格
S186：Skill Embedding 同表化

## 這個 task 要做什麼
這個 task 完成後，S186 spec §7 會記錄 semantic SQL 的 `EXPLAIN (ANALYZE, BUFFERS)` 實際輸出摘要，並同步 `architecture.md` / `development-standards.md`，讓文件不再說 runtime semantic search 依賴 `vector_store`。這個 task 不改功能，只把已實作事實與驗證證據寫清楚。

## 使用者情境（BDD）
Given（前提）測試 DB 至少有 public、private、draft 三種 skill，各有 `skills.embedding`
When（動作）對 S186 semantic SQL 執行 `EXPLAIN (ANALYZE, BUFFERS)`
Then（結果）S186 §7 記錄 `Execution Time`、`Buffers shared hit/read`、是否使用 `idx_skills_embedding_hnsw`
And（而且）若 planner 沒使用 HNSW index，§7 記錄原因與後續調校建議

Given（前提）S186 T01-T05 已完成
When（動作）閱讀 `docs/grimo/architecture.md` 與 `docs/grimo/development-standards.md`
Then（結果）文件描述 semantic search embedding 儲存在 `skills.embedding`，`Skill` aggregate 不 mapping embedding
And（而且）文件不再把 `vector_store.acl_entries` 描述成 runtime permission projection

## 研究來源
- `docs/grimo/specs/2026-05-16-S186-skill-vector-colocation-research.md` AC-S186-7 / §2.1 pgvector + PostgreSQL HOT/TOAST citations。
- `docs/grimo/architecture.md`：目前仍描述 `SkillshubPgVectorStore` 與 `vector_store`。
- `docs/grimo/development-standards.md`：Permission / Sharing Contract 目前仍寫 `vector_store.acl_entries`。

## 先做 POC
- POC：not required — 這個 task 是 implementation evidence + docs sync；T02/T03 已提供 runnable SQL path。

## 正式程式怎麼做
- Class / file 名稱：
  - `docs/grimo/specs/2026-05-16-S186-skill-vector-colocation-research.md`
  - `docs/grimo/architecture.md`
  - `docs/grimo/development-standards.md`
  - `docs/grimo/specs/spec-roadmap.md`
- 入口：documentation + SQL evidence。
- 必要行為：
  - 在 S186 §7 加 Implementation Results table
  - 貼 `EXPLAIN (ANALYZE, BUFFERS)` 的精簡摘要，不貼巨大 raw log
  - 說明 planner 是否使用 `idx_skills_embedding_hnsw`
  - architecture 的 search/data flow 改成 `skills.embedding` 同表；若保留 `vector_store` 歷史段，標明已由 S186 移除
  - development standards 的 Permission / Sharing Contract 改成 list/search 讀 `skills.is_public OR skills.acl_entries`；不再提 runtime `vector_store.acl_entries`
  - roadmap S186 狀態維持 `⏳ Dev` 或準備 Phase 4，不能標 shipped

## 單元測試 / 整合測試
- Evidence-only task；無 production code 變動。驗證靠下列 command 與文件內容：
  - `rg -n "SkillshubPgVectorStore|vector_store.acl_entries|vector_store.is_public" docs/grimo/architecture.md docs/grimo/development-standards.md`
  - `cd backend && ./gradlew test --tests '*S186*'`

## 會改哪些檔案
- `docs/grimo/specs/2026-05-16-S186-skill-vector-colocation-research.md`
- `docs/grimo/architecture.md`
- `docs/grimo/development-standards.md`
- `docs/grimo/specs/spec-roadmap.md`

## 驗證方式
執行：`cd backend && ./gradlew test --tests '*S186*'`

執行：`rg -n "SkillshubPgVectorStore|vector_store.acl_entries|vector_store.is_public" docs/grimo/architecture.md docs/grimo/development-standards.md`

通過條件：S186 tests 通過；docs grep 沒有 runtime dependency 描述，若有歷史提及必須明確標示「S186 前」或 archived context。

## 前置條件
- S186-T05 PASS

## 狀態
pending（待做）
