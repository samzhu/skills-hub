package io.github.samzhu.skillshub.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.shared.security.CurrentUser;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.User;
import io.github.samzhu.skillshub.shared.security.UserRepository;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S125a — SkillSubscriptionService slice test。對齊 SkillSearchTest @MockitoBean
 * CurrentUserProvider 既驗 pattern；無需設置 SecurityContext 即可驗 service-layer
 * 業務邏輯（subscribe / unsubscribe / isSubscribed / findSubscribersOf /
 * findSubscriptionsOfCurrentUser）+ DB UNIQUE invariant + idempotency。
 */
@Import(SkillSubscriptionService.class)
class SkillSubscriptionServiceTest extends RepositorySliceTestBase {

    @Autowired
    private SkillSubscriptionService service;

    @Autowired
    private SkillSubscriptionRepository repo;

    @Autowired
    private SkillRepository skillRepo;

    @Autowired
    private UserRepository userRepo;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    private String skillId;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        skillRepo.deleteAll();
        userRepo.deleteAll();
        // seed PUBLIC skill 給 subscribe 路徑用
        skillId = UUID.randomUUID().toString();
        var now = Instant.now();
        skillRepo.save(Skill.fromRow(
                skillId, "demo-skill", "demo for subscription test",
                "alice", "devops", "1.0.0", null, "PUBLISHED", 0L, now, now,
                List.of("user:alice:read", "user:alice:write", "user:alice:delete", "public:*:read"),
                null));
    }

    private String seedSkill(String name, String authorId, String authorName, String latestVersion, String riskLevel) {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        userRepo.save(User.createNew(authorId, "google", authorId + "-sub", authorId + "@example.com",
                authorName, authorId.replace("u_", ""), null, now));
        skillRepo.save(Skill.fromRow(
                id, name, "summary fixture", authorId, "devops", latestVersion, null,
                "PUBLISHED", 0L, now, now, List.of("public:*:read"), null));
        if (riskLevel != null) {
            skillRepo.updateRiskLevel(id, riskLevel, now);
        }
        return id;
    }

    private void asUser(String userId) {
        when(currentUserProvider.userId()).thenReturn(userId);
        when(currentUserProvider.current())
                .thenReturn(CurrentUser.synthetic(userId, List.of("viewer"), List.of(), null));
    }

    @Test
    @DisplayName("AC-S125a-1: subscribe 寫入一筆 row + isSubscribed=true")
    void subscribePersists() {
        asUser("bob");
        service.subscribe(skillId);

        assertThat(service.isSubscribed(skillId)).isTrue();
        assertThat(repo.existsBySkillIdAndSubscriberId(skillId, "bob")).isTrue();
    }

    @Test
    @DisplayName("AC-S125a-2: subscribe 對不存在 skill → NoSuchElementException")
    void subscribeUnknownSkill() {
        asUser("bob");

        assertThatThrownBy(() -> service.subscribe("nonexistent-id"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Skill not found");
    }

    @Test
    @DisplayName("AC-S125a-3: 重複 subscribe 為 idempotent — 不拋例外，row 仍只有 1 筆")
    void subscribeIdempotent() {
        asUser("bob");
        service.subscribe(skillId);
        service.subscribe(skillId);
        service.subscribe(skillId);

        assertThat(repo.findBySkillId(skillId)).hasSize(1);
    }

    @Test
    @DisplayName("AC-S125a-4: unsubscribe 移除 row + isSubscribed=false")
    void unsubscribeRemoves() {
        asUser("bob");
        service.subscribe(skillId);
        assertThat(service.isSubscribed(skillId)).isTrue();

        service.unsubscribe(skillId);

        assertThat(service.isSubscribed(skillId)).isFalse();
        assertThat(repo.findBySkillId(skillId)).isEmpty();
    }

    @Test
    @DisplayName("AC-S125a-5: unsubscribe 對未訂閱 skill → 安靜 noop（不拋例外）")
    void unsubscribeNoop() {
        asUser("bob");
        // 沒 subscribe 過直接 unsubscribe
        service.unsubscribe(skillId);
        assertThat(repo.findBySkillId(skillId)).isEmpty();
    }

    @Test
    @DisplayName("AC-S125a-6: findSubscribersOf 回傳指定 skill 的所有 subscriber id")
    void findSubscribersOf() {
        asUser("bob");   service.subscribe(skillId);
        asUser("carol"); service.subscribe(skillId);
        asUser("dave");  service.subscribe(skillId);

        var subscribers = service.findSubscribersOf(skillId);
        assertThat(subscribers).containsExactlyInAnyOrder("bob", "carol", "dave");
    }

    @Test
    @DisplayName("AC-S125a-7: findSubscriptionsOfCurrentUser 回傳當前 user 訂閱的 skillId list")
    void findSubscriptionsOfCurrentUser() {
        // seed 第二個 skill
        var skill2 = UUID.randomUUID().toString();
        var now = Instant.now();
        skillRepo.save(Skill.fromRow(
                skill2, "another-skill", "second skill", "alice", "devops", "1.0.0",
                null, "PUBLISHED", 0L, now, now, List.of("public:*:read"), null));

        asUser("bob");
        service.subscribe(skillId);
        service.subscribe(skill2);

        var mySubs = service.findSubscriptionsOfCurrentUser();
        assertThat(mySubs).containsExactlyInAnyOrder(skillId, skill2);
    }

    @Test
    @DisplayName("AC-S145-2: details list 只回當前 user 訂閱摘要，含 card 欄位")
    void findSubscriptionDetailsOfCurrentUser_filtersAndReturnsSummary() {
        var deepResearch = seedSkill("deep-research", "u_author1", "Sam Zhu", "1.2.0", "LOW");
        var dockerHelper = seedSkill("docker-helper", "u_author2", "Docker Author", "2.0.0", "MEDIUM");

        asUser("alice");
        service.subscribe(deepResearch);
        asUser("bob");
        service.subscribe(dockerHelper);

        asUser("alice");
        var details = service.findSubscriptionDetailsOfCurrentUser();

        assertThat(details).singleElement().satisfies(summary -> {
            assertThat(summary.skillId()).isEqualTo(deepResearch);
            assertThat(summary.skillName()).isEqualTo("deep-research");
            assertThat(summary.author()).isEqualTo("u_author1");
            assertThat(summary.authorDisplayName()).isEqualTo("Sam Zhu");
            assertThat(summary.latestVersion()).isEqualTo("1.2.0");
            assertThat(summary.riskLevel()).isEqualTo("LOW");
            assertThat(summary.status()).isEqualTo("PUBLISHED");
            assertThat(summary.subscribedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("AC-S145-5: existing id-list contract remains string skillId list")
    void findSubscriptionsOfCurrentUser_stillReturnsOnlySkillIds() {
        asUser("alice");
        service.subscribe(skillId);

        assertThat(service.findSubscriptionsOfCurrentUser()).containsExactly(skillId);
    }
}
