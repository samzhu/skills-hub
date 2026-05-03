# S098e2-T02: Skill rating projection — listener + service + skill aggregate field

## Spec
S098e2 — Reviews Aggregate + Ratings + SkillDetail Reviews tab（spec doc: `docs/grimo/specs/2026-05-03-S098e2-reviews-aggregate.md`）

## BDD（涵蓋的 AC）

**AC-5: Skill projection 更新 averageRating / reviewCount**
- Given：skill `S` 有 3 則 review (rating 5/4/3)
- When：另一 user bob 新增 1 則 rating=2
- Then：async（≤ 2s）後 `GET /api/v1/skills/S` 回的 `averageRating=3.50`、`reviewCount=4`

亦需驗 DELETE 後 projection 刷新（少 1 則）。

## Implementation outline

### Schema migration

```sql
-- V<next+1>__add_skill_rating_projection_columns.sql
ALTER TABLE skills
    ADD COLUMN average_rating NUMERIC(3,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN review_count BIGINT NOT NULL DEFAULT 0;
```

### Skill aggregate read-only field

```java
// skill/domain/Skill.java — 加 read-only field
private double averageRating;
private long reviewCount;

public double getAverageRating() { return averageRating; }
public long getReviewCount() { return reviewCount; }
// 無 mutation method — projection 走 raw SQL UPDATE，不經 aggregate
```

### SkillRatingService

```java
// skill/SkillRatingService.java — projection 內部用
@Service
public class SkillRatingService {
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional
    public void refresh(String skillId) {
        // SQL: UPDATE skills SET average_rating, review_count = (SELECT AVG, COUNT FROM reviews WHERE skill_id=?) WHERE id=?
        // 對齊 S076 download_count projection pattern
    }
}
```

### Listener placement decision

Spec §2.1 寫「listener 放置位置 implementer 自決，視 Modulith verifier 結果」。

**Default approach**：放 `review/SkillRatingProjectionListener.java`（review 模組訂閱自家 events，呼叫 `skill :: domain` exposed `SkillRatingService`）。Per S112-T01 啟示：Modulith 邊界要 grep `package-info.java` 先確認。

```java
// review/SkillRatingProjectionListener.java
@Component
class SkillRatingProjectionListener {
    @ApplicationModuleListener
    void on(ReviewCreatedEvent e) {
        skillRatingService.refresh(e.skillId());
    }
    @ApplicationModuleListener
    void on(ReviewDeletedEvent e) {
        skillRatingService.refresh(e.skillId());
    }
}
```

**注意**：`skill :: domain` allowedDependency 已在 review 模組宣告（spec §2.1）；但 SkillRatingService 是否在 `skill :: domain` 還是 `skill :: query`？跑 `ModularityTests` 確認；如需 cross-module SPI，可加 `@NamedInterface` 暴露。

**Fallback**：若 verifier 檢出 violation，移 listener 到 `skill/SkillRatingProjectionListener.java` 反向訂閱 review events，需要 `skill` allowedDependencies 加 `review :: events`。

### Tests

- `SkillRatingProjectionListenerTest` (Testcontainers + Modulith Scenario API) — AC-5
  - Seed skill + 3 reviews → publish ReviewCreatedEvent for 4th review → wait `andWaitForStateChange` until `skills.average_rating == 3.50` + `review_count == 4`
  - Mirror S025a `RiskAssessmentIntegrationTest` Scenario pattern
- `SkillRatingServiceTest` (Testcontainers) — pure SQL projection unit test
- `ModularityTests` — verifier 仍綠

## Target Files

- `backend/src/main/resources/db/migration/V<next+1>__add_skill_rating_projection_columns.sql` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` (modify — 加 read-only field)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/SkillRatingService.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/review/SkillRatingProjectionListener.java` (new — fallback to skill/ if verifier 拒)
- `backend/src/test/java/io/github/samzhu/skillshub/review/SkillRatingProjectionListenerTest.java` (new)
- `backend/src/test/java/io/github/samzhu/skillshub/skill/SkillRatingServiceTest.java` (new)

## Depends On
T01（Review aggregate + events 已在；listener 訂閱才有 source）

## Status
pending
