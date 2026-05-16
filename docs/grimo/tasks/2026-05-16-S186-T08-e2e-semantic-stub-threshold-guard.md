# S186-T08: E2E semantic stub threshold guard

## 對應規格
S186：Skill Embedding 同表化

## 這個 task 要做什麼
這個 task 完成後，Playwright V07 的 S140 browse-search case 會重新通過：`/browse` 搜尋 `docker` 時只顯示 3 個 docker-related skills，不再多出 `csv-to-parquet`。修復範圍只限 e2e profile 的 deterministic embedding stub / 對應測試；production Gemini embedding 與 production semantic ranking 不變。

## 使用者情境（BDD）
Given（前提）E2E profile seed 10 個技能，其中 3 個 skill name 含 `docker`，`csv-to-parquet` 不含 `docker`
When（動作）使用者開 `/browse` 並在搜尋框輸入 `docker`
Then（結果）畫面顯示 `找到 3 個相關技能` 或 `共 3 個技能`
And（而且）結果包含 `docker-compose-helper`、`docker-image-builder`、`docker-cleaner`
And（而且）結果不包含 `csv-to-parquet`

## 研究來源
- `docs/grimo/specs/2026-05-16-S186-skill-vector-colocation-research.md` §7.6：`./scripts/verify-all.sh` rerun 後只剩 V07 fail；artifact 顯示 `docker` query 回 4 筆，多出 `csv-to-parquet`。
- `backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/E2EEmbeddingConfig.java`：e2e stub 使用 deterministic random noise + word-overlap boost；目前 non-overlap pair 仍可能高於 threshold。
- `backend/src/main/resources/application-e2e.yaml`：e2e semantic similarity threshold 是 `0.1`，目標是篩出 keyword-match 並拒絕雜訊。
- `e2e/tests/S140-critical-path-browse-search.spec.ts`：AC-1 目前明確期待 3 個 docker-related skills。

## 先做 POC
POC: not required — 這是 e2e profile deterministic test double 的 threshold drift；用 unit test 直接固定 `docker` query 對 docker/non-docker fixture 的 cosine 邊界即可。

## 正式程式怎麼做
- Class / file 名稱：
  - `backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/E2EEmbeddingConfig.java`
  - `backend/src/test/java/io/github/samzhu/skillshub/skill/testsupport/E2EEmbeddingConfigTest.java`（新增）
  - `e2e/tests/S140-critical-path-browse-search.spec.ts`（只在註解或 assertion 需要對齊時修改）
- 必要行為：
  - `docker` query 對 3 個 docker fixture 的 cosine similarity 必須 `>= 0.1`。
  - `docker` query 對 `csv-to-parquet Converts CSV datasets to Parquet` 必須 `< 0.1`。
  - stub 仍要 deterministic：同 input 跨呼叫回同 vector。
  - production profile 不啟用 `E2EEmbeddingConfig`；不得修改 production `SemanticSearchService` threshold 或 SQL。
- 建議修法：
  - 降低或移除 non-overlap random noise，保留 word-overlap boost 作為主要訊號。
  - 若需要 deterministic ranking，使用穩定但低於 threshold 的 secondary noise，並用 unit test 固定上界。

## 單元測試 / 整合測試
- 新增 `E2EEmbeddingConfigTest`
  - `@DisplayName("AC-S186-6: e2e embedding stub keeps docker non-overlap below threshold")`
  - `@DisplayName("AC-S186-6: e2e embedding stub is deterministic for the same input")`
- 保留 / 覆跑 Playwright：
  - `e2e/tests/S140-critical-path-browse-search.spec.ts`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/E2EEmbeddingConfig.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/testsupport/E2EEmbeddingConfigTest.java`
- `e2e/tests/S140-critical-path-browse-search.spec.ts`（視需要）
- `docs/grimo/specs/2026-05-16-S186-skill-vector-colocation-research.md`
- `docs/grimo/tasks/2026-05-16-S186-T08-e2e-semantic-stub-threshold-guard.md`

## 驗證方式
執行：

```bash
cd backend && ./gradlew test --tests io.github.samzhu.skillshub.skill.testsupport.E2EEmbeddingConfigTest
cd e2e && npx playwright test tests/S140-critical-path-browse-search.spec.ts --grep @profile-paged
```

通過條件：backend stub boundary tests 通過；Playwright browse-search case 通過，畫面只看到 3 個 docker-related skills。

## 前置條件
- S186-T07 PASS

## 狀態
PASS（2026-05-17）

## 實作結果

- RED：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.skill.testsupport.E2EEmbeddingConfigTest` 一開始 `2 tests completed, 1 failed`，失敗點是 `docker` query 對 `csv-to-parquet` 的 cosine 沒低於 `0.1`。
- GREEN：`E2EEmbeddingConfig` 的 e2e-only stub 改成 token-only sparse vector；每個 token 用 SHA-256 派生 16 個 signed slots，避免 random noise 或單一 slot collision 讓 non-docker fixture 穿過 `0.1` threshold。
- 補強：`E2EEmbeddingConfigTest` 固定完整 paged fixture 邊界：3 個 docker skill 必須 `>= 0.1`，其餘 7 個 non-docker skill 必須 `< 0.1`；同 input vector 必須一致。
- E2E：`e2e/tests/S140-critical-path-browse-search.spec.ts` 新增 `csv-to-parquet` 不可見 assertion，並更新註解對齊 S186-T08 後的 stub 行為。

## 驗證結果

```bash
cd backend && ./gradlew test --tests io.github.samzhu.skillshub.skill.testsupport.E2EEmbeddingConfigTest
```

結果：PASS，`BUILD SUCCESSFUL in 1m 52s`。

```bash
cd e2e && npx playwright test tests/S140-critical-path-browse-search.spec.ts --grep @profile-paged
```

結果：PASS，`1 passed (21.2s)`；`/browse` 搜尋 `docker` 只看到 3 個 docker-related skills。
