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
pending
