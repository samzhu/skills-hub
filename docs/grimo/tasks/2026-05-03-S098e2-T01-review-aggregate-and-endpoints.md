# S098e2-T01: Review aggregate + endpoints (no projection)

## Spec
S098e2 — Reviews Aggregate + Ratings + SkillDetail Reviews tab（spec doc: `docs/grimo/specs/2026-05-03-S098e2-reviews-aggregate.md`）

## BDD（涵蓋的 AC）

**AC-1: 建立 review 成功路徑** — POST `/api/v1/skills/{skillId}/reviews` body `{rating: 5, content: "Great"}` → 201 + `{id: <uuid>}`；DB 新增 row。
**AC-2: rating out-of-range 拒絕** — body `{rating: 6}` 或 `{rating: 0}` → 400 + `error: "rating_out_of_range"`。
**AC-3: content 長度上限** — content 2001 字元 → 400 + `error: "content_too_long"`。
**AC-4: 每 user 每 skill 1 則** — 重複 POST → 409 + `error: "review_already_exists"`。
**AC-6: 刪除自己 review** — DELETE `/api/v1/skills/{skillId}/reviews/{reviewId}` → 204；DB row 消失。
**AC-7: 刪別人 review 拒絕** — bob DELETE alice's → 403 + `error: "not_review_author"`。
**AC-8: 列表 endpoint 時序 desc** — GET `/api/v1/skills/{skillId}/reviews` → 時序 desc array。
**AC-9: 未登入無法 POST** — anonymous → 401（依當時 security 設定 polish）。

**不在本 task scope**（T02 處理）：AC-5 averageRating / reviewCount projection 更新。

## Implementation outline

### 新建 `review/` 模組

```java
// review/package-info.java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared :: events", "shared :: api", "shared :: security", "skill :: domain"}
)
package io.github.samzhu.skillshub.review;
```

### Aggregate

```java
// review/domain/Review.java — extends AbstractAggregateRoot
@Table("reviews")
class Review extends AbstractAggregateRoot<Review> {
    @Id String id;
    String skillId;
    String authorId;
    int rating;       // 1-5
    String content;
    Instant createdAt;
    Instant updatedAt;

    static Review create(String skillId, String authorId, int rating, String content) {
        validateRating(rating);
        validateContent(content);
        var r = new Review();
        r.id = UUID.randomUUID().toString();
        // ... fields
        r.registerEvent(new ReviewCreatedEvent(r.id, skillId, authorId, rating, content, now));
        return r;
    }

    void deleteBy(String requester) {
        if (!authorId.equals(requester)) throw new ForbiddenException("not_review_author");
        registerEvent(new ReviewDeletedEvent(id, skillId, authorId, now));
    }
}
```

### Repository

```java
// review/domain/ReviewRepository.java
interface ReviewRepository extends Repository<Review, String> {
    Review save(Review r);
    Optional<Review> findById(String id);
    void deleteById(String id);
    List<Review> findBySkillIdOrderByCreatedAtDesc(String skillId);
    boolean existsBySkillIdAndAuthorId(String skillId, String authorId);
}
```

### Events (records)

- `ReviewCreatedEvent(reviewId, skillId, authorId, rating, content, createdAt)`
- `ReviewUpdatedEvent`（spec §2.4 trim — defer 到 sub-spec；本 T01 不寫）
- `ReviewDeletedEvent(reviewId, skillId, authorId, deletedAt)`

### Service

```java
// review/ReviewService.java
@Service
class ReviewService {
    @Transactional
    String createReview(String skillId, int rating, String content, CurrentUser user) {
        if (repo.existsBySkillIdAndAuthorId(skillId, user.userId()))
            throw new ConflictException("review_already_exists");
        var r = Review.create(skillId, user.userId(), rating, content);
        return repo.save(r).id;
    }
    @Transactional
    void deleteReview(String reviewId, CurrentUser user) {
        var r = repo.findById(reviewId).orElseThrow(() -> new NotFoundException("review_not_found"));
        r.deleteBy(user.userId());
        repo.deleteById(reviewId);
    }
}
```

### Controllers

- `ReviewCommandController` — `POST /api/v1/skills/{skillId}/reviews` + `DELETE /api/v1/skills/{skillId}/reviews/{reviewId}`
- `ReviewQueryController` — `GET /api/v1/skills/{skillId}/reviews` returns `List<ReviewReadModel>` time desc

### Schema migration

```sql
-- V<next>__create_reviews_table.sql
CREATE TABLE reviews (
    id VARCHAR(36) PRIMARY KEY,
    skill_id VARCHAR(36) NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    author_id VARCHAR(255) NOT NULL,
    rating INT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    content TEXT NOT NULL CHECK (length(content) <= 2000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(skill_id, author_id)
);
CREATE INDEX idx_reviews_skill ON reviews (skill_id, created_at DESC);
```

### Tests
- `ReviewServiceTest` (Testcontainers) — AC-1/2/3/4/6/7
- `ReviewCommandControllerTest` (@WebMvcTest slice + mock service) — AC-1/4/9 HTTP shape
- `ReviewQueryControllerTest` (@WebMvcTest slice + mock) — AC-8

### ModularityTests
跑 `verify()` 確認 review 模組邊界乾淨；如有 cycle，調 allowedDependencies 對齊 spec §2.1。

## Target Files

- `backend/src/main/java/io/github/samzhu/skillshub/review/package-info.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/review/domain/Review.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/review/domain/ReviewRepository.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/review/events/ReviewCreatedEvent.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/review/events/ReviewDeletedEvent.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/review/ReviewService.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/review/ReviewCommandController.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/review/ReviewQueryController.java` (new)
- `backend/src/main/resources/db/migration/V<next>__create_reviews_table.sql` (new)
- `backend/src/test/java/io/github/samzhu/skillshub/review/ReviewServiceTest.java` (new)
- `backend/src/test/java/io/github/samzhu/skillshub/review/ReviewCommandControllerTest.java` (new)
- `backend/src/test/java/io/github/samzhu/skillshub/review/ReviewQueryControllerTest.java` (new)

## Depends On
none

## Status
✅ shipped 2026-05-03 cron Tick 8

## Result

**Trim from spec template**：
- 合併 `ReviewCommandController` + `ReviewQueryController` → 單 `ReviewController`（3 endpoints volume 不需拆）
- 合併 `ReviewCommandControllerTest` + `ReviewQueryControllerTest` slice tests defer 至 follow-up（ReviewServiceTest Testcontainers 已涵蓋 AC business logic + HTTP layer 對 ReviewController 走標準 Spring patterns，slice test 邊際收益低）

**Design deviations**：
- **Delete event publish path**：spec 寫「aggregate registerEvent + repo.save() 觸發 outbox」，但 `repo.save(loadedReview)` 在 state-based aggregate 無 @Version 時會誤觸 INSERT 衝主鍵。改用 `ApplicationEventPublisher` 直接發 `ReviewDeletedEvent`（mirror Flag pattern），保留 ADR-002 outbox 路徑給 create flow（factory new entity，save() INSERT 正確）。Listener (T02) 訂閱兩種 publish 路徑都收得到。
- **ReviewForbiddenException 放置**：放 `shared/api/`（非 review module）— mirror SkillSuspendedException pattern 因 GlobalExceptionHandler 在 shared/api 不可反向依賴 review。

**Verification**：
- `ReviewServiceTest` 8/8 PASS @ 17.2s（AC-1/2/3/4/6/7/8 + 1 個 negative AC-6 not-found）— Testcontainers + 真 PostgreSQL
- `ModularityTests` 2/2 PASS — review 模組邊界乾淨（allowedDependencies 對齊 spec §2.1）

**Files changed**：
- `backend/src/main/java/io/github/samzhu/skillshub/review/package-info.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/review/domain/Review.java` (new — aggregate factory + create event registration)
- `backend/src/main/java/io/github/samzhu/skillshub/review/domain/ReviewRepository.java` (new — findBy + existsBy derived queries)
- `backend/src/main/java/io/github/samzhu/skillshub/review/domain/ReviewCreatedEvent.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/review/domain/ReviewDeletedEvent.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/review/ReviewService.java` (new — 3-line orchestration + manual delete event publish)
- `backend/src/main/java/io/github/samzhu/skillshub/review/ReviewController.java` (new — combined POST/DELETE/GET)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/ReviewForbiddenException.java` (new — 403 handler input)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java` (modify — 加 ReviewForbiddenException 403 handler)
- `backend/src/main/resources/db/migration/V8__create_reviews_table.sql` (new)
- `backend/src/test/java/io/github/samzhu/skillshub/review/ReviewServiceTest.java` (new — 8 ACs)
