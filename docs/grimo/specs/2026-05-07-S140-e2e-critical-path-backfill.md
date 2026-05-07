# S140: E2E Critical Path Backfill

> Spec: S140 | Size: S(11) | Status: ⏳ Plan — POC PASS（poc/S140/StubEmbeddingPoc.java）；9 task files in `docs/grimo/tasks/`；ready for /implementing-task loop
> Date: 2026-05-07 (design via /planning-spec; tasks via /planning-tasks; POC validated 2026-05-07)

---

## 1. Goal

PRD Critical Path P1–P6 + Quality Score (S135b) 已全部 ship，但 user-visible browser flow **目前只有後端 integration test + 手動 golden-path 操作**。`qa-strategy.md` Layer 3 已從「手動」改為「Playwright via `/playwright-expert` VERIFY」，V07 也已 enroll — 但 e2e/ workspace 內**只有 placeholder smoke**，沒有任何對應 PRD critical path 的 happy-path spec。

本 spec backfill 那段空白：用 `/playwright-expert` DESIGN 模式從 PRD §Critical Path 拆 6 支 happy-path Playwright spec，搭配 backend `TestDataController`（`@Profile({"local","dev","e2e"})` only）提供 fixture seeding。Ship 後 V07 真正成為 critical-path regression gate。

**為何現在：**

- `e2e/` workspace 已 BOOTSTRAP（v4.17.x ready）；`playwright-expert` skill 含 fixtures-patterns + ac-translation-guide 已 ship
- 後續每個 UI spec 若都要重新驗證 critical path 是否 regression，成本高且重複
- 一個 backfill spec 把 P1–P6 lock 住，後續 spec 只加增量 happy path（與既有 6 支共用 fixture helper）
- 跟 S139 login UI（in-flight）解耦：S139 ship 後再用增量 spec 加「lazy auth gate」happy path，不阻擋本 backfill

**簡單講：**

為 PRD critical path 寫 6 支 happy path Playwright spec，加上 backend `TestDataController` 兩個 endpoint（`POST /internal/test/reset` + `POST /internal/test/seed/skill`）在非 production profile 提供 deterministic fixture seeding（per `playwright-expert/references/fixtures-patterns.md` Pattern 1）。

**非目標：**

- 不 backfill bug-fix / polish spec（S028–S080 等 100+ 個 polish spec 都已被 unit / integration test 覆蓋）— 只 cover PRD §Critical Path
- 不做 cross-browser / mobile responsive E2E（chromium-only per skill default）
- 不做 visual regression baseline（`toHaveScreenshot()` 留給專屬 spec）
- 不做 a11y E2E（留給專屬 spec）
- 不 backfill S139 login UI lazy auth gate（in-flight；S139 ship 後做增量 spec）
- 不重新設計 fixture seeding pattern — 採 `playwright-expert` 已決定的 Pattern 1（backend test API endpoint）

---

## 2. Approach

### 2.1 整體 pattern

`playwright-expert` DESIGN mode 從 **PRD §Critical Path SBE acceptance criteria** 拆 happy path（NOT 從個別 archived spec 的 §3，因 critical path 是跨多 spec 的 user journey）。Fixture seeding 採 Pattern 1（backend test API），per `playwright-expert/references/fixtures-patterns.md`。

### 2.2 6 支 happy path（對應 PRD P1–P6 + Quality Score）

| # | Path | spec test file | fixture profile |
|---|---|---|---|
| 1 | Browse + keyword search（P1） | `tests/critical-path-browse-search.spec.ts` | `paged`（10 skills，混合 risk / category） |
| 2 | Skill detail page（P1） | `tests/critical-path-skill-detail.spec.ts` | `single`（含 file list + version history） |
| 3 | Upload + publish flow（P2 + P3） | `tests/critical-path-publish.spec.ts` | `empty`（fresh state） |
| 4 | Download zip（P4） | `tests/critical-path-download.spec.ts` | `single`（已 publish + low-risk） |
| 5 | Semantic search（P5） | `tests/critical-path-semantic-search.spec.ts` | `paged`（10 skills，含 docker / testing 分類） |
| 6 | Analytics dashboard（P6） | `tests/critical-path-analytics.spec.ts` | `single` + 預先 seed 5 個 download events |

每支 spec 的 `test()` 數量 = 該 path 在 PRD §SBE 列出的 Scenario 數（上面列的下界 1，可能拆 2-3 個）。

### 2.3 Backend `TestDataController`（new infrastructure）

`@Profile({"local","dev","e2e"})` 限定，production profile **絕對不暴露**：

- `POST /internal/test/reset` — wipe `skill_aggregate / vector_store / download_events / domain_events / event_publication` 5 張表；保持 schema 不動
- `POST /internal/test/seed/skill` — body: `{ author, name, version, tags, riskLevel, category, ... }`；**透過 `SkillCommandService.create()`**（不直接 INSERT），維持 `@DomainEvents` outbox + audit listener invariant

**關鍵原則：seed 走 domain layer，不繞過聚合**。直接 INSERT 會破壞 `domain_events` audit log + Modulith outbox（per ADR-002），讓 read-side projection 失同步。Pattern 2 (direct DB) 因此明確 reject。

### 2.4 V07 變身為 critical-path regression gate

V07（`cd e2e && npx playwright test --grep @happy-path`）目前只跑 placeholder smoke（`@bootstrap` tag，已被 grep 排除）。本 spec ship 後，V07 變成 6 支 critical-path test 的 regression gate — 這是 V07 真正的設計目的兌現。

### 2.5 後續增量規劃

- S139 ✅ shipped（v4.18.0）→ 後續增量 spec 加 `tests/critical-path-login-gate.spec.ts`（lazy auth gate happy path）
- 未來 P7 Collections / P8 Request Board / P9 Notifications ship → 各自增量 happy path
- ACL（S114a Owner+Viewer）visibility 行為：另起 spec 補（涉及多 user role fixture，scope 較大）

### 2.6 三個技術選擇（grill 確認 2026-05-07）

| 議題 | 選擇 | 為什麼 |
|------|------|--------|
| **語意搜尋的 embedding** | **C：e2e profile 用 deterministic stub `EmbeddingModel` bean** | 每次回固定 vector → vector_store 完整走流程；避開外部 Gemini API（cost + flaky）；happy-path 驗的是「semantic 路由 + 結果渲染」不是 LLM 語意品質 |
| **Analytics download events seeding** | **B：新增 `POST /internal/test/seed/download-event { skillId, count, daysAgo }` 直寫 `download_events`** | `download_events` 是 read-side projection table（非 aggregate），直 INSERT 不破 outbox / audit invariant（pattern 2 受限可接受 case，spec §4 明示） |
| **`/internal/test/reset` 範圍** | **B：清「所有 application data tables」allowlist**（`TRUNCATE ... CASCADE`，Flyway history 保留） | 完整 isolation；新表上線時 controller 程式碼裡明示加入 allowlist，防漏清是 future spec 該管 |

### 2.7 Research Citations

| Source | 一句總結 |
|--------|----------|
| `playwright-expert/references/fixtures-patterns.md` Pattern 1 | Skills Hub 預設走 backend test API（`@Profile({"local","dev","e2e"})` controller），其他 3 patterns（direct DB / per-test API / DB snapshot）受 ADR-002 outbox 限制不適用 |
| `playwright-expert/references/ac-translation-guide.md` | Tag 格式 `@<spec-id> @ac-N @happy-path \| @edge`；每個 AC 對 1 個 `test()` block + 3 個 `test.step()`（Given / When / Then） |
| `SkillCommandService.uploadSkill()` source | 走完整 aggregate path（INSERT skills row + publish 2 events + INSERT skill_versions + GCS upload）；seed endpoint 應 reuse 此入口而非自寫 INSERT，維持 outbox + audit invariant |
| `SkillCommandService.createSkill()` source | 不上版本只建 aggregate，給 seed 中「不需要 zip 的最小 skill」場景用；可選 |
| Spring AI `EmbeddingModel` interface（spring-ai-2.0.0-M5）| `embed(String) -> float[]` 單方法 SPI，e2e profile 註冊一個 deterministic stub bean 即可繞過 Gemini |
| Spring `@Profile("e2e")` activation | `application-e2e.yaml` + Cloud Build 不啟用 e2e profile（per S132 §8 baked profile 機制）→ production binary 完全不含 TestDataController bean definition |

---

## 3. Acceptance Criteria

> Verification command: `cd e2e && npx playwright test --grep "@S140.*@happy-path"`
> Pass: 全部 6 個 `test()` block green；`e2e/results/evidence.json` 內對應 6 個 entry 全 `ok: true`。
>
> V07 gate（`--grep @happy-path` 全 happy-path）也 SHOULD pass — 本 spec ship 後 V07 才真正 enrolled。

每個 AC 對 1 個 Playwright `test()` block，3 個 `test.step()` 對 Given / When / Then。Tag 格式：`@S140 @ac-N @happy-path @profile-<profile>`。

### AC-1: 關鍵字搜尋顯示符合條件的技能列表（P1）
```
Given 平台 seed 10 個 skills（profile=paged，含 3 個 name/description 含 "docker"，分散風險等級）
When  使用者在 HomePage 搜尋框輸入 "docker" 並提交
Then  結果列表顯示 3 張 SkillCard（每張含 name / description / author / version / 風險 badge / 下載次數）
And   結果不含其他 7 個 non-matching skill
```
- Profile：`@profile-paged`
- Spec file：`tests/S140-critical-path-browse-search.spec.ts`

### AC-2: Skill 詳情頁顯示完整 SKILL.md + 版本 + Quality Score（P1 + S135b）
```
Given 平台 seed 1 個 skill（profile=single，含 SKILL.md 描述 + v1.0.0 版本 + 預先 seed 8-dim quality score）
When  使用者點擊該 skill 卡片進入詳情頁
Then  顯示 rendered markdown SKILL.md 內容
And   顯示版本歷史（含 v1.0.0）
And   顯示風險評估等級
And   顯示下載統計
And   顯示 Quality Score hero bar（per S135b — 8-dim 平均分數 + 各 dim 進度條）
```
- Profile：`@profile-single`
- Spec file：`tests/S140-critical-path-skill-detail.spec.ts`

### AC-3: 上傳合法 SKILL.md zip 自動標低風險並上架（P2 + P3）
```
Given 平台空 state（profile=empty）
And   使用者已登入（per S139；test 透過 storage state 或 mock /api/v1/me 200）
When  使用者在 /publish 上傳一個僅含 SKILL.md 的 zip（無 scripts/）並提交
Then  redirect 到 /publish/review 顯示「上傳成功」
And   skill 進入「已上架」狀態
And   風險等級 = 「低風險」
And   建立版本 v1.0.0
```
- Profile：`@profile-empty`
- Spec file：`tests/S140-critical-path-publish.spec.ts`
- Note：含 scripts/ 高風險路徑屬 P3 edge case，本 spec 只 cover happy path（純 markdown 低風險）；含 scripts 高風險路徑由 backend integration test 涵蓋

### AC-4: 從詳情頁下載最新版本 zip（P4）
```
Given 平台 seed 1 個 skill（profile=single，已 publish v1.0.0）
When  使用者進入該 skill 詳情頁並點「下載」按鈕
Then  瀏覽器收到 Content-Disposition: attachment 的 zip 檔（filename 含 skill name + version）
And   zip 內容可解壓且 root 含 SKILL.md
And   詳情頁的下載計數 +1（reload 後可見）
```
- Profile：`@profile-single`
- Spec file：`tests/S140-critical-path-download.spec.ts`

### AC-5: 自然語言查詢觸發語意搜尋路徑並回傳穩定排序結果（P5）
```
Given 平台 seed 5 個 skills（profile=paged，含 docker / k8s / testing / lint / docs 五類，e2e profile 用 deterministic stub embedder）
When  使用者在 /search 輸入「我想把應用部署到容器環境」
Then  /search 結果列表顯示非空 skill 列表（5 個全列，因 stub embedder 對所有 cosine > -0.1 → all 進結果集）
And   每筆結果附 match reason field（backend 即使 stub embedder 也產 reason 欄位）
And   跨 page reload 兩次結果列表的順序完全相同（deterministic verification）
```
- Profile：`@profile-paged`
- Spec file：`tests/S140-critical-path-semantic-search.spec.ts`
- Note 1：**Deterministic 不等於 semantic** — `Random(input.hashCode())` 768-dim stub 經 POC 驗證（poc/S140/StubEmbeddingPoc.java，2026-05-07）：H1 determinism PASS、H2 separation cosine 範圍 0.09（足夠不 tie）、H3 跨 run 順序穩定 PASS。**但語意上 "docker" 排序最低（cosine -0.058）、"junit" 最高（+0.034）**，與「容器/部署」query 的人類直覺相反。AC-5 因此驗的是「semantic 路由觸發 + 穩定排序」，而不是「semantic 質量」。
- Note 2：真 Gemini 排序質量由 backend integration test（若有）+ 真 LAB 部署人工驗證涵蓋；happy-path E2E 不負擔此職責。

### AC-6: Analytics dashboard 顯示總覽 + 趨勢（P6）
```
Given 平台 seed 1 個 skill + 預先 seed 5 個 download events（散在過去 7 天）
When  使用者進入 /analytics
Then  顯示總 skill 數 = 1
And   顯示總下載次數 = 5
And   顯示近 7 天下載趨勢 sparkline / chart
And   顯示熱門排行 Top 10 含該 skill
```
- Profile：`@profile-single` + 5 download events
- Spec file：`tests/S140-critical-path-analytics.spec.ts`

### AC Coverage Summary

| AC | PRD Path | Spec File | Profile |
|----|----------|-----------|---------|
| AC-1 | P1 (browse + search) | `S140-critical-path-browse-search.spec.ts` | paged |
| AC-2 | P1 (detail) + S135b Quality | `S140-critical-path-skill-detail.spec.ts` | single |
| AC-3 | P2 + P3 | `S140-critical-path-publish.spec.ts` | empty |
| AC-4 | P4 | `S140-critical-path-download.spec.ts` | single |
| AC-5 | P5 | `S140-critical-path-semantic-search.spec.ts` | paged |
| AC-6 | P6 | `S140-critical-path-analytics.spec.ts` | single + events |

### Skipped ACs（covered elsewhere）

| PRD Scenario | Coverage |
|--------------|----------|
| P2「上傳含 scripts 的 skill」（高風險）| Backend integration test：`S005`/`S099e1-e4` scanner test |
| P2「上傳不合規的 skill」（缺 SKILL.md）| Backend integration test：`SkillValidationException` test + `S098b3-2` finding payload test |
| P3「含外部依賴」掃描 | Backend integration test：`S099e3` DependencyVulnScanner test |
| P5「無相關結果」回 fallback | 已被現有 Vitest test 涵蓋（`SearchResultsPage.test.tsx` empty-state） |
| P6「技能作者查看自己的數據」 | 涉及 ACL + login，留給 S139 lazy-auth-gate 增量 spec 涵蓋 |

---

## 4. Interfaces / API Design

### 4.1 `TestDataController`（new；non-production profile only）

```java
package io.github.samzhu.skillshub.testsupport;

@RestController
@RequestMapping("/internal/test")
@Profile({"local", "dev", "e2e"})
public class TestDataController {

    private final SkillCommandService skillCommandService;
    private final NamedParameterJdbcTemplate jdbc;
    private final PackageService packageService;
    // constructor injection ...

    /**
     * 清空所有 application data tables（保留 Flyway schema_history）。
     * Allowlist 寫死在程式碼，新表上線時要明示加入這個列表。
     */
    @PostMapping("/reset")
    ResponseEntity<Map<String, Object>> reset() {
        var tables = List.of(
            "skill_aggregate", "skill_versions", "skill_acl",
            "skill_subscription", "skill_collection", "skill_collection_skills",
            "skill_quality_score", "vector_store",
            "download_events", "domain_events", "event_publication",
            "notification", "request_board_entry", "flag_record"
        );
        tables.forEach(t -> jdbc.update("TRUNCATE TABLE " + t + " CASCADE", Map.of()));
        return ResponseEntity.ok(Map.of("tablesCleared", tables));
    }

    /**
     * 走 SkillCommandService.uploadSkill() 完整 path（aggregate + outbox + audit）。
     * 若 skillMdContent 為 null，自動合成最小 SKILL.md（用 name + description）。
     */
    @PostMapping("/seed/skill")
    ResponseEntity<Map<String, String>> seedSkill(@RequestBody SeedSkillRequest req) throws IOException {
        var skillMd = req.skillMdContent() != null
            ? req.skillMdContent()
            : synthesizeMinimalSkillMd(req.name(), req.description(), req.author());
        var zipBytes = packageService.normalizeToZip(skillMd.getBytes(UTF_8));
        var id = skillCommandService.uploadSkill(
            zipBytes, req.version(), req.author(), req.category(),
            req.visibility() != null ? req.visibility() : Visibility.PUBLIC);
        return ResponseEntity.ok(Map.of("id", id));
    }

    /**
     * 直 INSERT download_events（read-side projection table，非 aggregate；不破 outbox / audit invariant）。
     * daysAgo 散布：count=5, daysAgo=7 → 7/2=3.5 天前 1 筆、3.0 天前 1 筆... 平均散布。
     */
    @PostMapping("/seed/download-event")
    ResponseEntity<Map<String, Integer>> seedDownloadEvent(@RequestBody SeedDownloadEventRequest req) {
        // INSERT INTO download_events (skill_id, downloaded_at, ...) VALUES ...
        // 散布邏輯：i=0..count-1，downloaded_at = now() - INTERVAL ((daysAgo * i) / (count-1))
        return ResponseEntity.ok(Map.of("count", req.count()));
    }
}
```

### 4.2 DTOs

```java
package io.github.samzhu.skillshub.testsupport;

public record SeedSkillRequest(
    String name,                   // required
    String description,            // required
    String author,                 // required
    String category,               // required (e.g. "DevOps", "Testing")
    String version,                // default "1.0.0" if null
    Visibility visibility,         // default PUBLIC if null
    String skillMdContent          // optional override；null → 自動合成
) {}

public record SeedDownloadEventRequest(
    String skillId,                // required
    int count,                     // 多少筆 download events
    int daysAgo                    // 散布在過去 N 天（0 = 全部 now）
) {}
```

### 4.3 `DeterministicRandomEmbeddingModel`（new；e2e profile only；@Primary）

**Phase 0 修正：** 既有 `SearchConfig.NoOpEmbeddingModel`（768 維零向量）跟 `googleGenAiEmbeddingModel`（`@ConditionalOnProperty(skillshub.genai.api-key)`）已 cover production 兩種模式。e2e 需要的是「deterministic 非零」第三模式，避免 zero vector cosine tie。對齊既有 768 維（不是原 spec 寫的 384）。

新增 `@Profile("e2e")` config 內的 `@Primary` bean，winning over NoOp / Google bean：

```java
package io.github.samzhu.skillshub.testsupport;

@Configuration
@Profile("e2e")
class E2EEmbeddingConfig {

    /**
     * Deterministic random 768-dim EmbeddingModel for e2e tests.
     *
     * <p>Per POC at poc/S140/StubEmbeddingPoc.java (2026-05-07)：
     * <ul>
     *   <li>{@code Random(input.hashCode())} 產生 unit-normed 768-dim vector</li>
     *   <li>同 input → 同 vector（determinism）</li>
     *   <li>不同 input cosine 範圍約 0.09（足夠分離不 tie）</li>
     *   <li>跨 run 排序穩定（happy-path 驗證可預期）</li>
     * </ul>
     *
     * <p><b>非語意</b>：cosine ranking 不對應人類直覺（query "容器部署" 對 docker
     * 排序最低）。E2E 只驗 deterministic 排序 + UI 渲染，不驗語意質量。
     *
     * <p>對齊既有 {@link SearchConfig} 維度 = 768；@Primary 確保 e2e profile
     * 啟用時優先於 NoOpEmbeddingModel / GoogleGenAiTextEmbeddingModel 被注入。
     */
    @Bean
    @Primary
    EmbeddingModel e2eStubEmbeddingModel() {
        return new DeterministicRandomEmbeddingModel(768);
    }

    static final class DeterministicRandomEmbeddingModel implements EmbeddingModel {
        private final int dim;
        DeterministicRandomEmbeddingModel(int dim) { this.dim = dim; }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            var embeddings = IntStream.range(0, request.getInstructions().size())
                .mapToObj(i -> new Embedding(embedString(request.getInstructions().get(i)), i))
                .toList();
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(@Nullable Document document) {
            return embedString(document == null ? "" : document.getText());
        }

        private float[] embedString(String input) {
            var rng = new Random(input.hashCode());
            var vec = new float[dim];
            for (int i = 0; i < dim; i++) vec[i] = rng.nextFloat() * 2 - 1;
            return normalize(vec);
        }

        private static float[] normalize(float[] v) {
            double norm = 0;
            for (float f : v) norm += f * f;
            norm = Math.sqrt(norm);
            if (norm < 1e-10) return v;
            var out = new float[v.length];
            for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
            return out;
        }
    }
}
```

### 4.4 `application-e2e.yaml`（new profile）

```yaml
spring:
  datasource:
    # 沿用 local profile 的 PostgreSQL（compose.yaml）；無新 datasource 需求
  ai:
    google:
      genai:
        api-key: stub-not-used   # StubEmbeddingModel 接管，這欄位 Spring AI Google GenAI autoconfig 仍要 non-empty
        # base-url/model 不重要，bean 不會被 instantiate（StubEmbeddingModel 優先註冊）
logging:
  level:
    io.github.samzhu.skillshub: INFO
    org.springframework.web: WARN
skillshub:
  storage:
    # GCS 替換為 in-memory 或 local fs（避免 e2e 需 GCP credentials）
    backend: local-fs
    local-fs-path: ${java.io.tmpdir}/skillshub-e2e
```

### 4.5 Playwright `e2e/tests/_fixtures.ts`（new；shared helper）

從 `playwright-expert/assets/fixtures-helper-template.ts` 衍生：

```typescript
import { test as base, expect, type APIRequestContext } from '@playwright/test';

const TEST_API_BASE = 'http://localhost:8080/internal/test';

export type SkillSeed = {
  name: string;
  description: string;
  author: string;
  category: string;
  version?: string;
  visibility?: 'PUBLIC' | 'PRIVATE';
  skillMdContent?: string;
};

export type DownloadEventSeed = {
  skillId: string;
  count: number;
  daysAgo: number;
};

export async function resetAll(req: APIRequestContext) { /* POST /reset */ }
export async function seedSkill(req: APIRequestContext, data: SkillSeed): Promise<string> { /* POST /seed/skill */ }
export async function seedDownloadEvents(req: APIRequestContext, data: DownloadEventSeed) { /* POST /seed/download-event */ }

// Pre-defined fixture profiles (per fixtures-patterns.md taxonomy)
export const profiles = {
  empty: async (req: APIRequestContext) => { await resetAll(req); },
  single: async (req: APIRequestContext) => { /* reset + seed 1 skill, return id */ },
  paged: async (req: APIRequestContext) => { /* reset + seed 10 skills 混合 risk/category */ },
};

export const test = base.extend<{ resetState: void }>({
  resetState: [async ({ request }, use) => { await resetAll(request); await use(); }, { auto: true }],
});
export { expect };
```

### 4.6 Profile gating 摘要

| Profile | TestDataController bean? | E2EEmbeddingConfig bean? | EmbeddingModel 實際解析到 |
|---------|--------------------------|--------------------------|--------------------------|
| `default` / `prod` | ❌ 無 | ❌ 無 | `googleGenAiEmbeddingModel`（真 Gemini，per `SearchConfig`）|
| `local` | ✅ 有（`/internal/test/*` 暴露）| ❌ 無 | `googleGenAiEmbeddingModel` 或 `noOpEmbeddingModel`（依 api-key）|
| `dev` | ✅ 有 | ❌ 無 | 同 local |
| `e2e` | ✅ 有 | ✅ 有（`@Primary`）| `e2eStubEmbeddingModel`（取代其他兩種）|
| `lab` | ❌ 無 | ❌ 無 | `googleGenAiEmbeddingModel`（LAB 跑真 Google）|

關鍵：`cloudbuild.yaml` 不啟用 `e2e` profile（per S132 §8.1 baked profile 機制）→ production native binary 完全不含 TestDataController / E2EEmbeddingConfig bean definition。security-by-build-time。

---

## 5. File Plan

### Backend（新增）

| File | Action | Description |
|------|--------|-------------|
| `backend/.../testsupport/TestDataController.java` | new | REST controller `@Profile({"local","dev","e2e"})`；3 個 endpoint：`/reset`、`/seed/skill`、`/seed/download-event` |
| `backend/.../testsupport/SeedSkillRequest.java` | new | DTO record；對齊 `SkillCommandService.uploadSkill()` 入參 |
| `backend/.../testsupport/SeedDownloadEventRequest.java` | new | DTO record |
| `backend/.../testsupport/E2EEmbeddingConfig.java` | new | `@Component @Profile("e2e")`；deterministic stub `EmbeddingModel` 實作 |
| `backend/src/main/resources/application-e2e.yaml` | new | profile 設定（log level + storage local-fs + ai.google.genai stub key） |

### Backend tests

| File | Action | Description |
|------|--------|-------------|
| `backend/src/test/java/.../testsupport/TestDataControllerTest.java` | new | WebMvc slice test：`/reset` truncate 驗證、`/seed/skill` 走 SkillCommandService、`/seed/download-event` INSERT 驗證 |
| `backend/src/test/java/.../testsupport/TestDataControllerProfileTest.java` | new | 驗證 `default` / `prod` profile 下 bean 不存在（防 controller 漏出 production） |

### e2e/（新增）

| File | Action | Description |
|------|--------|-------------|
| `e2e/tests/_fixtures.ts` | new | 共用 helper（resetAll / seedSkill / seedDownloadEvents + `profiles` 物件 + extend `test`） |
| `e2e/tests/S140-critical-path-browse-search.spec.ts` | new | AC-1（P1 browse + keyword search）|
| `e2e/tests/S140-critical-path-skill-detail.spec.ts` | new | AC-2（P1 detail + S135b Quality Score）|
| `e2e/tests/S140-critical-path-publish.spec.ts` | new | AC-3（P2 + P3 upload + 風險評估）|
| `e2e/tests/S140-critical-path-download.spec.ts` | new | AC-4（P4 download zip）|
| `e2e/tests/S140-critical-path-semantic-search.spec.ts` | new | AC-5（P5 語意搜尋；deterministic stub embedder）|
| `e2e/tests/S140-critical-path-analytics.spec.ts` | new | AC-6（P6 analytics dashboard）|

### Doc sync（modify）

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/specs/spec-roadmap.md` | modify | S140 status `📐 in-design` → `⏳ in-progress`（task plan 完成後改 ⏳；ship 後改 ✅）|
| `docs/grimo/architecture.md` | modify | 加 `testsupport` module（`@Profile`-gated，非 production 暴露）+ `application-e2e.yaml` 條目 |
| `docs/grimo/development-standards.md` | modify | 在 §E2E fixture seeding 條目補充：S140 確立 `/internal/test/{reset,seed/skill,seed/download-event}` 三個 endpoint 為標準 seed API |

### 不需要新增

- `application-e2e.yaml` 不需要新 datasource — 沿用 local profile 的 PostgreSQL（compose.yaml 已 spin up）
- 不需要新 build.gradle.kts 條目 — `testsupport` 跟其他 module 同 source set
- 不需要 Playwright config 變動 — `playwright.config.ts` 已 ready；增加 `webServer` env：`SPRING_PROFILES_ACTIVE=local,e2e` 啟用 stub embedder

### Verification command（per QA strategy V07）

```bash
# Spec-targeted
cd e2e && npx playwright test --grep "@S140.*@happy-path"

# V07 critical-path gate（本 spec ship 後 6 個 test 都進這個 grep）
cd e2e && npx playwright test --grep @happy-path
```

---

## Estimation Re-check

六維 score（per `/planning-spec` references/estimation-scale.md）：

| 維度 | Score | 理由 |
|------|-------|------|
| Tech risk | 2 | 自寫 stub `EmbeddingModel` + `TestDataController`；無新框架；S132 §8 baked profile 機制已驗 |
| Uncertainty | 2 | StubEmbedding 對 vector_store 行為的影響需 POC（hypothesis：固定 vector 經 cosine 仍能排序）|
| Dependencies | 1 | 無外部 framework 依賴；`playwright-expert` skill 已 ship |
| Scope | 3 | 14 個新檔（5 backend + 2 backend test + 7 e2e）+ 3 doc sync |
| Testing | 2 | E2E + backend slice + profile guard test |
| Reversibility | 1 | 純 test infra，不影響 production 行為 |
| **Total** | **11** | **S(11)** — 比 stub 預估 M(12-14) 略小，因為 §2.6 三個技術選擇收斂後 scope 變清晰 |

**POC 標記：** `StubEmbeddingModel` 對 vector_store cosine ranking 的穩定性 = **Hypothesis**。`/planning-tasks` 階段的第一個 task 應跑 minimal POC 驗證固定 vector + 多 skill embedding 後 cosine 排序確實穩定（不同 input 應產生足夠分離的 vector）。若 POC fail → 改用 keyword fallback only（degrade to grill Q1 option B）。

---

## 6. Task Plan

**POC: completed** — see POC Findings below; spec §3 AC-5 + §4.3 已 reflect findings。

### POC Findings（2026-05-07）

POC location：`poc/S140/StubEmbeddingPoc.java`（純 java，無 Spring 依賴）。

| Hypothesis | Verdict | Evidence |
|------------|---------|----------|
| H1 Determinism | ✅ PASS | `embed("docker-compose-helper")` 跑兩次 `Arrays.equals` true |
| H2 Sufficient separation | ✅ PASS | 5 個 skills 對 query 「我想把應用部署到容器環境」 cosine 範圍 -0.058 ~ +0.034（差 0.092；不 tie，可排序）|
| H3 Stable ranking across runs | ✅ PASS | 兩次 embed query 後 5 skills 排序完全相同 |
| H4 hashCode collision check | ✅ PASS | 5 skill names 無 collision |

**關鍵 caveat**：cosine ranking **deterministic 但非 semantic** — query 對 `docker-compose-helper` cosine -0.058（最低）、對 `junit-test-generator` +0.034（最高），與「容器/部署」query 的人類直覺相反。

**設計影響**：
1. 維度從 spec 原 §4.3 的 384 修正為 **768**（對齊既有 `SearchConfig.NoOpEmbeddingModel`）
2. AC-5 從「docker / k8s skills 排前」relax 為「list 非空 + 跨 reload 順序穩定」
3. 結構從獨立 `StubEmbeddingModel` 改為 `E2EEmbeddingConfig` `@Configuration @Profile("e2e")` 內的 `@Primary` bean，避開既有 `@ConditionalOnMissingBean` race
4. POC 直接落 spec §4.3，不需另開 POC task

### Tasks

| # | Task | AC | Deps | Status |
|---|------|----|------|--------|
| T01 | Backend infra — `TestDataController`（reset / seed-skill / seed-download-event）+ `SeedSkillRequest` / `SeedDownloadEventRequest` DTO + `E2EEmbeddingConfig` + `application-e2e.yaml` + 2 個 backend slice test（含 profile guard） | AC-1 ~ AC-6 enabling | — | pending |
| T02 | Playwright fixtures helper — `e2e/tests/_fixtures.ts`（從 template 衍生：resetAll / seedSkill / seedDownloadEvents / `profiles = { empty, single, paged }` / extend `test`） | AC-1 ~ AC-6 enabling | T01 | pending |
| T03 | E2E spec — `tests/S140-critical-path-browse-search.spec.ts`（playwright-expert DESIGN）| AC-1 | T02 | pending |
| T04 | E2E spec — `tests/S140-critical-path-skill-detail.spec.ts`（含 S135b Quality Score hero bar 驗證）| AC-2 | T02 | pending |
| T05 | E2E spec — `tests/S140-critical-path-publish.spec.ts`（含 OAuth storage state preload）| AC-3 | T02 | pending |
| T06 | E2E spec — `tests/S140-critical-path-download.spec.ts` | AC-4 | T02 | pending |
| T07 | E2E spec — `tests/S140-critical-path-semantic-search.spec.ts`（驗 deterministic 排序，非 semantic 質量）| AC-5 | T02 | pending |
| T08 | E2E spec — `tests/S140-critical-path-analytics.spec.ts`（含 5 download events seed）| AC-6 | T02 | pending |
| T09 | V07 evidence + doc sync — 跑 `cd e2e && npx playwright test --grep "@S140.*@happy-path"` 全綠，輸出 `e2e/results/evidence.json`，sync `architecture.md` / `development-standards.md`，spec §7 consolidate | gate | T03-T08 | pending |

**Execution order**：T01（backend infra）→ T02（playwright fixtures）→ T03..T08 並行（playwright-expert DESIGN 可一次處理多 spec）→ T09（evidence + sync）。

### AC Coverage

| AC | Covered by |
|----|------------|
| AC-1 P1 browse + search | T01 (seed/reset infra) + T03 (spec) |
| AC-2 P1 detail + S135b | T01 + T04 |
| AC-3 P2 + P3 publish | T01 + T05 |
| AC-4 P4 download | T01 + T06 |
| AC-5 P5 semantic search | T01 (E2EEmbeddingConfig + seed) + T07 |
| AC-6 P6 analytics | T01 (seed/download-event) + T08 |
| Gate | T09 (V07 happy-path full green) |

### POC cleanup note

`poc/S140/` 目錄在 Phase 4 consolidation 時刪除（per `/planning-tasks` skill ：「POC directory is temporary — cleaned up in Phase 4 after results are consolidated」）。POC findings 已落 §6 上方表格成永久記錄。

---

## 7. Implementation Results

> 待 ship 後填。
