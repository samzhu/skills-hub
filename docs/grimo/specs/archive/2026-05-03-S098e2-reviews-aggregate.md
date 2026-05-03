# S098e2: Reviews Aggregate + Ratings + SkillDetail Reviews tab

> Spec: S098e2 | Size: S(11) re-estimated from M(8) | Status: 🚧 in-progress (4 tasks queued — cron tick handoff)
> Date: 2026-05-03

> **Tasks**: T01 review aggregate + endpoints (no projection) → T02 skill rating projection listener + service + skill aggregate field + migrations → T03 frontend infra (api/reviews.ts + useReviews + RatingStars + Skill type) → T04 SkillDetailPage Reviews tab + tests。Execution order T01→T02→T03→T04（T03 type-only 可與 T01/T02 平行）。


---

## 1. Goal

讓使用者可以對 PUBLISHED skill 打 1-5 星 + 寫文字評論；SkillDetail 的「評論」tab 顯示既有評論列表 + 平均星等 + 撰寫/編輯/刪除自己評論的表單；後端新建 `review` 模組（Spring Data JDBC 充血聚合 + Modulith Outbox），透過事件投影把 `averageRating` / `reviewCount` 寫回 `skills` 表供列表頁與 Impact Score（S101b）消費。

**起源**：2026-05-03 page audit 跑出 SkillDetail Reviews tab 永遠 render `<EmptyState>` 的「畫面有但功能假」狀態（`SkillDetailPage.tsx:213-219`），對應 S098 META 的 P2 sub-spec、PRD §316 B3「社群回報與評分」與 PRD §80/§221 SkillDetail / Analytics 顯「社群評分」期望。

**Visual flow**：

```
User 流程
─────────────────────────────────────────────
1. 看 SkillDetail → 切「評論」tab
2. 0 reviews    → EmptyState invite + 「撰寫第一則評論」CTA
   N reviews    → 平均星等 hero + reviews list (time desc)
3. 點「撰寫評論」 → modal 含 1-5 星 picker + textarea + Submit
4. 已寫過該 skill 的 review → 列表中標自己 + 「編輯/刪除」affordance
                              CTA 變「編輯我的評論」（不能再寫第二則）
─────────────────────────────────────────────
資料流
─────────────────────────────────────────────
Frontend POST /api/v1/skills/{skillId}/reviews
   ↓
ReviewCommandController → ReviewService.createReview(...)
   ↓
review aggregate save() → Modulith outbox (same TX)
                       → AFTER_COMMIT async listener
                          → SkillRatingProjection 更新 skills.average_rating / review_count
                          → AuditEventListener 寫 domain_events log
   ↓
Frontend invalidate ['skill-reviews', skillId] + ['skills', skillId]
   ↓
TanStack Query refetch → 平均星等與 list 即時刷新
```

## 2. Approach

**走 ADR-002 / S024 canonical pattern：Spring Data JDBC 充血聚合 + Modulith Outbox + AFTER_COMMIT 投影更新 `skills` 表的 averageRating / reviewCount。**

| Approach | Chosen | Rationale |
|----------|--------|-----------|
| A: Skill row 加 `average_rating` + `review_count` 欄位 + ReviewProjectionListener async 更新 | **yes** | O(1) read（list / detail / search 全免 JOIN）；對齊 S076 既有 download_count projection；S101b Impact Score 直接消費 |
| B: 獨立 `skill_review_stats` read model 在 `review` 模組 | no | 模組邊界乾淨但每次 Skill detail 多一個 query；list views 要 JOIN 影響 `/browse` / `/search` 效能 |
| C: 完全 on-demand（每次 query 即 AVG aggregation） | no | 列表頁 N 個 skill 跑 N 次 AVG 不可接受 |

### 2.1 Aggregate / Projection 設計概要

**Module**：新建 `review/` 模組，與 `analytics` / `audit` 同層級。

**`review/` 模組 Modulith 宣告**：

```java
@ApplicationModule(
    allowedDependencies = {"shared :: events", "shared :: api", "shared :: security", "skill :: domain"}
)
package io.github.samzhu.skillshub.review;
```

`shared :: security` 是為了 `CurrentUserProvider` 抽 `userId()`；`skill :: domain` 是讓 review 在寫入前可驗 skillId 真實存在（呼叫 `SkillRepository.existsById`）。

**Aggregate**：`Review` (per S024 pattern)

```java
@Table("reviews")
public class Review extends AbstractAggregateRoot<Review> implements Persistable<String> {
    @Id String id;                  // UUID
    String skillId;                 // FK to skills.id
    String authorId;                // 評論者 sub (用 useMe 拿)
    int rating;                     // 1-5
    String content;                 // nullable; max 2000 chars
    Instant createdAt;
    Instant updatedAt;              // 同 createdAt 直到 update

    // Factory: ReviewService 用
    public static Review create(String skillId, String authorId, int rating, String content) { ... }
    
    // 業務 method 充血
    public void edit(String newRating, String newContent) {
        validateRating(newRating);
        this.rating = newRating;
        this.content = trimContent(newContent);
        this.updatedAt = Instant.now();
        registerEvent(new ReviewUpdatedEvent(this.id, this.skillId, this.authorId, this.rating));
    }
}
```

**Projection 更新策略**：

`ReviewCreated/Updated/Deleted` 事件由 `review` 模組 emit；**`SkillRatingProjectionListener` 放置位置由 implementer 視 Modulith verifier 決定**（S112-T01 已驗證 Modulith 邊界限制可能要求 implementer 偏離 spec 原案）：

- **首選方案**：listener 住在 `review` 模組內，呼叫 `skill :: api` 暴露的 `SkillRatingService.refresh(skillId)` method（review 是 producer，own 自己造成的副作用）— 但需要 `skill` 模組對外暴露此 service interface
- **備選方案**：listener 住在 `skill` 模組內訂閱事件，前提是 `skill` 模組 allowedDependencies 加 `review :: events`（symmetric to 既有 `audit` 模組訂閱 skill events 的 pattern）

兩者皆走 `@ApplicationModuleListener`（AFTER_COMMIT async + 自動 outbox）。Implementer 該擇一，於 §7 Result 寫明採用哪個 + 理由。

**SkillRatingService 內部實作**（無論住哪）：

```java
@Transactional
public void refresh(String skillId) {
    var stats = jdbc.queryForObject("""
        SELECT COALESCE(AVG(rating), 0)::numeric(3,2) AS avg,
               COUNT(*) AS cnt
        FROM reviews WHERE skill_id = :skillId
        """, Map.of("skillId", skillId), (rs, n) -> Map.of("avg", rs.getBigDecimal("avg"), "cnt", rs.getLong("cnt")));
    jdbc.update("""
        UPDATE skills SET average_rating = :avg, review_count = :cnt WHERE id = :id
        """, Map.of("id", skillId, "avg", stats.get("avg"), "cnt", stats.get("cnt")));
}
```

對齊 S076 download_count atomic update pattern；不走 Skill aggregate 充血 method（避免每次 review 都 load 整個 Skill aggregate；純 projection 寫 SQL 更直接）。

### 2.2 6 個產品/UX 決策

| # | 決策 | 採用 | 理由 |
|---|---|---|---|
| 1 | Rating shape | **1-5 星** | 行業標準；prototype 寫「星級」；user-recognizable |
| 2 | 每用戶每 skill 限制 | **1 則**，可 update/delete 自己 | 防 spam 沖洗；行業預設（Amazon / App Store） |
| 3 | Auth 模式 | **強制登入身份**（reuse `useMe()` pattern；reviewer = sub） | MVP LAB mode 自動 sub；不開 anonymous；對齊「Feature First」 |
| 4 | Helpful votes / 排序 | **MVP 只時序 desc + average display**；helpful/排序 defer | YAGNI；S101b Impact Score 之後可拉 helpful 當 sub-metric |
| 5 | 投影策略 | **Approach A**（skills 加欄位 + projection listener） | 見上方比較表 |
| 6 | Moderation hook | **MVP 純信任**；不加 hide/flag review 機制 | 需求未證實；future spec（類 S098e3）統一處理 |

### 2.3 Behavior validation

| 決策 | Confidence | 證據 |
|------|------------|------|
| ADR-002 canonical pattern (Spring Data JDBC + Modulith Outbox) | Validated | ADR-002 + S024 ship + S023 outbox 基礎建設 |
| `@ApplicationModuleListener` AFTER_COMMIT async | Validated | 既有 `AuditEventListener` 用同 pattern (S024 引用) |
| `SkillRatingService` SQL projection update | Validated | S076 download-counter pattern + S074 file browser 多筆 SQL |
| `useMe()` sub 為 reviewer identity | Validated | S112 同 pattern（MeFlagsController 注入 CurrentUserProvider） |
| Modulith listener 放置（review 內 vs skill 內） | **Hypothesis** | S112-T01 揭示 Modulith verifier 有時會否決 spec 預設方案；implementer 試 build 後決定 |

唯一 Hypothesis 不阻塞 spec 寫；implementer 在 task 階段試 Modulith verifier 拒哪邊就走另一邊。**不需 POC**（兩個方案都是已驗證 pattern，只是 boundary direction 差異）。

### 2.4 Trim list

S(11) 一個 cron tick 可能 wall hit；可 defer 的 polish：

- Review **編輯** affordance（MVP 只先做 create + delete + list；edit 可下個 sub-spec 補）
- Pagination on reviews list（MVP 全列出，反正 skill 評論數 < 50 不會卡）
- Skill aggregate test 對 `averageRating` getter 的覆蓋（projection 端有 test 即可）

### 2.5 Research Citations

無外部框架研究 — 全部使用既有專案內 pattern。Internal references：

- `docs/grimo/adr/ADR-002-skill-aggregate-state-based.md`（aggregate pattern 取捨歷史）
- `backend/.../skill/domain/Skill.java`（canonical 充血聚合範本）
- `backend/.../security/FlagService.java` (S058/S072) + `MeFlagsController.java` (S112-T01)（同 domain shape 範本）
- `backend/.../audit/AuditEventListener.java`（跨模組 listener pattern 範本）
- 既有 download_count projection（S076）— 同類 SQL UPDATE projection
- `frontend/src/pages/SkillDetailPage.tsx:213-219`（Reviews tab 現況）
- `frontend/src/pages/SkillDetailPage.tsx:294-356` `AddVersionForm`（同檔已有 mutation form 範本可 mirror）

## 3. SBE Acceptance Criteria

驗證指令（per `qa-strategy.md`）：

- Backend：`./gradlew test` + `./gradlew modulithTest`
- Frontend：`cd frontend && npm test`
- Pass：所有 `@DisplayName("AC-N: ...")` / `@Tag("AC-N")` 標記測試綠 + Modulith boundary verifier 綠

---

**AC-1：建立 review — 成功路徑**
- Given：alice 已登入，skill `S` 為 PUBLISHED 且 alice 從未對 `S` 評論
- When：發 `POST /api/v1/skills/S/reviews` body `{"rating": 5, "content": "Great"}`
- Then：回 201 + body `{"id": "<uuid>"}`；DB `reviews` 新增一筆 (skill_id=S, author_id=alice, rating=5, content="Great")；`skills.average_rating` / `review_count` async 更新（poll 1s 內反映）

**AC-2：rating out-of-range 拒絕**
- Given：alice 已登入
- When：發 POST body `{"rating": 6, "content": "..."}` 或 `{"rating": 0, ...}`
- Then：回 400 + `error: "rating_out_of_range"`，DB 無新增

**AC-3：content 長度上限**
- Given：alice 已登入
- When：POST body content 為 2001 字元
- Then：回 400 + `error: "content_too_long"`（cap = 2000，對齊 Flag 描述 cap pattern）

**AC-4：每 user 每 skill 1 則 — 重複 POST 拒絕**
- Given：alice 已對 skill `S` 寫過 1 則 review
- When：alice 再發 POST 給同 skill
- Then：回 409 + `error: "review_already_exists"`，DB 無新增第二筆

**AC-5：Skill projection 更新 averageRating / reviewCount**
- Given：skill `S` 有 3 則 review (rating 5/4/3)
- When：另一 user bob 新增 1 則 rating=2
- Then：async（≤ 2s）後 `GET /api/v1/skills/S` 回的 `averageRating=3.50`、`reviewCount=4`

**AC-6：刪除自己 review**
- Given：alice 對 skill `S` 寫過 review
- When：alice 發 `DELETE /api/v1/skills/S/reviews/{reviewId}`
- Then：回 204；DB 該 row 消失；async 後 Skill `averageRating` / `reviewCount` 重算（少 1 則）

**AC-7：刪別人 review 拒絕**
- Given：alice 寫的 review，bob 嘗試刪
- When：bob 發 DELETE 該 reviewId
- Then：回 403 + `error: "not_review_author"`

**AC-8：列表 endpoint 時序 desc**
- Given：skill `S` 有 3 則 review (createdAt t1 < t2 < t3)
- When：發 `GET /api/v1/skills/S/reviews`
- Then：回 200 + array 順序 [r3, r2, r1]（最新在前），每 element 含 `{id, skillId, authorId, rating, content, createdAt, updatedAt}`

**AC-9：未登入無法 POST**
- Given：無 Authorization header（且非 LAB mode anonymous fallback）
- When：發 POST review
- Then：回 401（依專案 Spring Security MVP permit-all 設定，本 AC 在實作時依當時 security 設定 polish — 可能變成 LAB mode 注入 anonymous user 的 graceful path；implementer 確認）

**AC-10：SkillDetail UI — 0 reviews 顯示 invite EmptyState**
- Given：skill `S` 無評論
- When：user 開啟 `/skills/S` 並切到「評論」tab
- Then：顯示 `EmptyState tone="invite"` headline「成為第一個評論這個技能的人」+ 「撰寫評論」按鈕

**AC-11：SkillDetail UI — N reviews 顯示 hero + list**
- Given：skill `S` 有 3 則 review (avg 4.0)
- When：user 開啟 Reviews tab
- Then：tab 上方顯「⭐ 4.0 · 3 則評論」hero；下方時序 desc list；每 row 含星等 + content + 作者 + 日期 + （若是自己的 review）編輯/刪除按鈕

**AC-12：SkillDetail UI — 提交 review 表單 happy path**
- Given：alice 登入；skill `S` 已存在；alice 從未評論該 skill
- When：alice 點「撰寫評論」→ modal 開啟 → 選 5 星 → 輸入「Great」→ 點 Submit
- Then：modal 關閉；list 內出現 alice 的新 review（樂觀更新 or refetch）；hero 更新平均；CTA 變「編輯我的評論」

## 4. Interface / API Design

### 4.1 Backend — REST endpoints

```
POST   /api/v1/skills/{skillId}/reviews           # 建立
   body { rating: int 1-5, content: string nullable }
   201 { id: string }
   400 rating_out_of_range / content_too_long
   404 skill_not_found
   409 review_already_exists

GET    /api/v1/skills/{skillId}/reviews           # 列表（時序 desc）
   200 [{ id, skillId, authorId, rating, content, createdAt, updatedAt }, ...]

DELETE /api/v1/skills/{skillId}/reviews/{reviewId}  # 刪除（限 author 自己）
   204
   403 not_review_author
   404 review_not_found
```

### 4.2 Backend — Schema migration

```sql
-- V<next>__create_reviews_table.sql
CREATE TABLE reviews (
    id           VARCHAR(36) PRIMARY KEY,
    skill_id     VARCHAR(36) NOT NULL,
    author_id    VARCHAR(255) NOT NULL,
    rating       SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    content      TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (skill_id, author_id)
);
CREATE INDEX idx_reviews_skill ON reviews (skill_id, created_at DESC);

-- V<next+1>__add_skill_rating_projection_columns.sql
ALTER TABLE skills
    ADD COLUMN average_rating NUMERIC(3,2) NOT NULL DEFAULT 0,
    ADD COLUMN review_count   INTEGER      NOT NULL DEFAULT 0;
```

`UNIQUE (skill_id, author_id)` 強制 AC-4「每 user 每 skill 1 則」於 DB 層；service 層也 pre-check 但 DB 是最後關卡（race-safe）。

### 4.3 Backend — Domain events

```java
public record ReviewCreatedEvent(String reviewId, String skillId, String authorId, int rating) {}
public record ReviewUpdatedEvent(String reviewId, String skillId, String authorId, int rating) {}
public record ReviewDeletedEvent(String reviewId, String skillId) {}
```

放置：`review/events/` 或 `review/domain/events/`，由 implementer 對齊既有專案結構。

### 4.4 Backend — Skill aggregate 加欄位

`skill/domain/Skill.java` 加 2 個 read-only 欄位（同 S077 `downloadCount` readonly pattern）：

```java
@Column("average_rating") BigDecimal averageRating;  // default 0
@Column("review_count")    int reviewCount;          // default 0
```

Aggregate 不開 mutation method（projection 走 raw SQL UPDATE，不經 aggregate）。

### 4.5 Frontend — API + hooks

**新檔 `frontend/src/api/reviews.ts`**：

```typescript
import { apiFetch } from './client'

export interface Review {
  id: string
  skillId: string
  authorId: string
  rating: number       // 1-5
  content: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateReviewRequest {
  rating: number
  content: string | null
}

export function fetchReviews(skillId: string): Promise<Review[]> {
  return apiFetch<Review[]>(`/skills/${skillId}/reviews`)
}

export function createReview(skillId: string, body: CreateReviewRequest): Promise<{ id: string }> {
  return apiFetch<{ id: string }>(`/skills/${skillId}/reviews`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function deleteReview(skillId: string, reviewId: string): Promise<void> {
  return apiFetch<void>(`/skills/${skillId}/reviews/${reviewId}`, { method: 'DELETE' })
}
```

**新檔 `frontend/src/hooks/useReviews.ts`**：

```typescript
import { useQuery } from '@tanstack/react-query'
import { fetchReviews, type Review } from '../api/reviews'

export function useReviews(skillId: string | undefined) {
  return useQuery<Review[]>({
    queryKey: ['skill-reviews', skillId],
    queryFn: () => fetchReviews(skillId!),
    enabled: !!skillId,
    staleTime: 60 * 1000,
    refetchOnWindowFocus: false,
  })
}
```

### 4.6 Frontend — SkillDetailPage Reviews tab

替換 `SkillDetailPage.tsx:213-219` 為 `<ReviewsPanel skillId={id} />`，同檔內加新內部元件 `ReviewsPanel` + `ReviewRow` + `ReviewForm`（modal）+ `RatingHero`。pattern mirror 既有 `AddVersionForm`（同檔 line 294+，含 useMutation + invalidate query 模式）。

`Skill` type 也要加 `averageRating: number` + `reviewCount: number`（`frontend/src/types/skill.ts`）。

### 4.7 Frontend — 星等元件

新檔 `frontend/src/components/RatingStars.tsx`（小型 5 顆 star icon row，支援 readonly + interactive 雙模式；用 lucide-react `Star` icon）。

## 5. File Plan

### Backend

| File | Action | Description |
|------|--------|-------------|
| `backend/src/main/.../review/package-info.java` | new | `@ApplicationModule(allowedDependencies={shared::events, shared::api, shared::security, skill::domain})` |
| `backend/src/main/.../review/domain/Review.java` | new | Aggregate `extends AbstractAggregateRoot`，含 create/edit/delete 充血方法 |
| `backend/src/main/.../review/domain/ReviewRepository.java` | new | Spring Data JDBC repo + `findBySkillIdOrderByCreatedAtDesc` derived query + `existsBySkillIdAndAuthorId` |
| `backend/src/main/.../review/events/ReviewCreatedEvent.java` | new | record |
| `backend/src/main/.../review/events/ReviewUpdatedEvent.java` | new | record |
| `backend/src/main/.../review/events/ReviewDeletedEvent.java` | new | record |
| `backend/src/main/.../review/ReviewService.java` | new | createReview / deleteReview business orchestration |
| `backend/src/main/.../review/ReviewCommandController.java` | new | POST / DELETE endpoints |
| `backend/src/main/.../review/ReviewQueryController.java` | new | GET endpoint |
| `backend/src/main/.../review/SkillRatingProjectionListener.java` | new | `@ApplicationModuleListener` async 訂閱 3 events，呼叫 SkillRatingService（**listener 放置位置由 implementer 視 Modulith verifier 結果決定**；備選：放到 `skill` 模組內反向訂閱 review events，需要 `skill` allowedDependencies 加 `review :: events`） |
| `backend/src/main/.../skill/SkillRatingService.java` | new | `refresh(skillId)`：raw SQL AVG + COUNT → UPDATE skills（`@Transactional`，per S076 pattern） |
| `backend/src/main/.../skill/domain/Skill.java` | modify | 加 `averageRating` + `reviewCount` 欄位（read-only，無 mutation method） |
| `backend/src/main/resources/db/migration/V<next>__create_reviews_table.sql` | new | 見 §4.2 |
| `backend/src/main/resources/db/migration/V<next+1>__add_skill_rating_projection_columns.sql` | new | 見 §4.2 |
| `backend/src/test/.../review/ReviewServiceTest.java` | new | AC-1/2/3/4/6/7（unit + Testcontainers） |
| `backend/src/test/.../review/ReviewCommandControllerTest.java` | new | AC-1/4/9 web slice |
| `backend/src/test/.../review/ReviewQueryControllerTest.java` | new | AC-8 web slice |
| `backend/src/test/.../review/SkillRatingProjectionListenerTest.java` | new | AC-5（async listener integration） |

### Frontend

| File | Action | Description |
|------|--------|-------------|
| `frontend/src/api/reviews.ts` | new | Review type + fetchReviews + createReview + deleteReview |
| `frontend/src/hooks/useReviews.ts` | new | TanStack Query hook |
| `frontend/src/components/RatingStars.tsx` | new | 5-star icon row, readonly + interactive |
| `frontend/src/types/skill.ts` | modify | Skill type 加 `averageRating: number` + `reviewCount: number` |
| `frontend/src/pages/SkillDetailPage.tsx` | modify | Reviews tab 由 EmptyState 換成 ReviewsPanel；同檔加 ReviewsPanel/ReviewRow/ReviewForm/RatingHero internal components |
| `frontend/src/pages/SkillDetailPage.test.tsx` | modify | 加 AC-10 / AC-11 / AC-12 tests |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/specs/spec-roadmap.md` | modify | M92e2 row：📋 planned → 📐 in-design + 估點修正 M(8) → S(11) + 設計摘要 |
| `docs/grimo/glossary.md` | modify | 加「Review / Rating」中英對照 + averageRating / reviewCount field 定義 |
| `docs/grimo/architecture.md` | modify | 加 `review/` 模組進 module map；標 ADR-002 pattern adoption |

---

## 7. Result

**Status**: ✅ Shipped 2026-05-03 cron Tick 7-11（30m loop，5 ticks 含 spec planning）— v3.5.0 minor。

**Task ledger**：
- T01 (Tick 8) — review/ 模組 + Review aggregate (state-based 充血) + ReviewService (3-line orchestration) + 合併 ReviewController + V8 migration + ReviewServiceTest 8/8 PASS @ 17.2s。Deviation：delete flow 改 ApplicationEventPublisher 直接發 event（state-based aggregate 無 @Version 不適合 save loaded entity）；ReviewForbiddenException 放 shared/api/（mirror SkillSuspendedException）。
- T02 (Tick 9) — V9 migration ALTER skills 加 average_rating + review_count；Skill aggregate `@ReadOnlyProperty` field（mirror downloadCount S077 pattern）；SkillRatingService raw SQL UPDATE in skill::query NamedInterface；SkillRatingProjectionListener `@ApplicationModuleListener` × 2 訂閱 ReviewCreated/Deleted。Listener 放 review/，review allowedDependencies 加 skill::query（與 ScanOrchestrator 既有 cross-module SPI 同 pattern）。Bootstrap deviation：DIRECT_DEPENDENCIES 拉到 SkillAclController 但 StorageService missing → 改 @SpringBootTest。SkillRatingProjectionListenerTest Scenario API 2/2 PASS @ 16.9s。
- T03 (Tick 10) — frontend infra 5 個新檔：api/reviews.ts + useReviews + RatingStars (readonly + interactive ARIA radiogroup) + Skill type field。RatingStars.test.tsx 5/5 PASS。
- T04 (Tick 11) — ReviewsPanel extract 獨立 component (per S112-T03 啟示)；含 RatingHero / ReviewRow / ReviewForm internal；SkillDetailPage Reviews tab integration。Trim：edit affordance defer（spec §2.4），已寫過後 CTA 隱藏避免 DUP 衝 AC-4。ReviewsPanel.test.tsx 4/4 PASS。

**Verification metrics**：
- Backend: ReviewServiceTest 8 (AC-1/2/3/4/6/7/8 + not-found) + SkillRatingProjectionListenerTest 2 (AC-5 created + deleted) + ModularityTests 2 — 全 PASS
- Frontend cross-spec: ReviewsPanel 4 + RatingStars 5 + FlagsList 2 + MySkillsPage 5 + SkillDetailPage 3 — 19/19 PASS @ 1.73s
- Typecheck 0 error；ModularityTests boundary 仍乾淨
- LOC delta: backend +650 (含 ~280 LOC test)，frontend +500 (含 ~210 LOC test)

**12 ACs 涵蓋**：
- AC-1～8 backend by ReviewServiceTest + SkillRatingProjectionListenerTest
- AC-9 (auth) deferred — Spring Security MVP permit-all 暫無強制 401，per spec §3 留待 security 階段重審
- AC-10～12 frontend by ReviewsPanel.test.tsx
- AC-12 edit happy-path 部分達成（create → modal close → list refresh by query invalidate）；edit affordance defer per §2.4

**Lessons**：
- **State-based aggregate + delete event 不適合 save() proxy publishing**：load 後再 save() 觸發 outbox 會誤觸 INSERT 衝主鍵（因 isNew() 不易判斷）；改用 ApplicationEventPublisher 直接發是乾淨 fallback。
- **Cross-module SPI via NamedInterface**：`skill::query` 暴露 SkillRatingService 給 review module 跨模組 call，與 ScanOrchestrator security → skill::query 同 pattern；ModularityTests 守 boundary 不變形。
- **Modulith bootstrap mode 限制**：DIRECT_DEPENDENCIES 拉直接依賴模組會 transitively 拉到 sibling controller bean（如 SkillAclController），但這些 bean 的依賴（如 StorageService）不在 DIRECT_DEPENDENCIES 裡。Workaround：Listener integration test 改用 @SpringBootTest full bootstrap。
- **Component extract 比 Tab interaction test 可靠**：RadixTabs 在 JSDOM 沒 user-event dep 時 fireEvent.click 不觸發 panel 渲染；S112-T03 + S098e2-T04 兩次驗證 — 直接 unit test 獨立 component 是 testing-driven cleaner pattern。

---
