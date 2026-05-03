package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.shared.security.AclPrincipalExpander;
import io.github.samzhu.skillshub.shared.security.CurrentUser;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S025b T04 demote — 從 {@code @SpringBootTest(RANDOM_PORT) + TestRestTemplate} 改
 * {@link RepositorySliceTestBase}（{@code @DataJdbcTest} slice）。原 HTTP-bound assertion
 * 已被 {@link io.github.samzhu.skillshub.S016EndToEndSmokeTest} E2E 涵蓋；本 test
 * 收斂為 {@link SkillQueryService#search} + {@link SkillQueryService#getCategoryCounts}
 * 純 SQL 邏輯驗證。Seed 直接走 {@link SkillRepository#save}（無需 HTTP POST）。
 *
 * <p>S121: SkillQueryService 加 {@link CurrentUserProvider} + {@link AclPrincipalExpander}
 * 兩個 dep 後，slice test 透過 {@code @MockitoBean} 提供 stub — 預設 stub 回傳含
 * {@code *:read} pattern（對應 PUBLIC skill 預設可見），讓既有 6 個 AC 不變動 (fixtures
 * acl_entries 同步改 {@code List.of("*:read")} 表達 PUBLIC 語意)；專屬 ACL 行為驗證走
 * S121 新 AC（owner-only / granted-user / non-grantee 三場景）。
 */
@Import(SkillQueryService.class)
class SkillSearchTest extends RepositorySliceTestBase {

    @Autowired
    private SkillQueryService queryService;

    @Autowired
    private SkillRepository skillRepo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private AclPrincipalExpander aclExpander;

    @BeforeEach
    void setUp() {
        skillRepo.deleteAll();
        // 預設 stub：admin user + AclPrincipalExpander.expand 回傳含 *:read 的 patterns。
        // 既有 6 ACs fixture 走 PUBLIC 語意（acl_entries 含 *:read），通過 ?| 比對。
        when(currentUserProvider.current())
                .thenReturn(new CurrentUser("test-admin", List.of("admin"), List.of()));
        when(aclExpander.expand(any(CurrentUser.class), eq("read")))
                .thenReturn(List.of("user:test-admin:read", "role:admin:read", "*:read"));
        var now = Instant.now();
        // S031: search/categories 過濾 status=PUBLISHED；fixture 改 PUBLISHED 對齊公開查詢預期
        // S121: acl_entries 加 *:read（PUBLIC 語意）— ACL filter 啟用後須含此 pseudo-principal 才對 anonymous/admin 可見
        skillRepo.save(Skill.fromRow(
                UUID.randomUUID().toString(), "docker-helper", "Docker compose helper",
                "sam", "DevOps", "1.0.0", null, "PUBLISHED", 0L, now, now, List.of("*:read"), null));
        skillRepo.save(Skill.fromRow(
                UUID.randomUUID().toString(), "k8s-deploy", "Kubernetes deployment skill",
                "jane", "DevOps", "1.0.0", null, "PUBLISHED", 0L, now, now, List.of("*:read"), null));
        skillRepo.save(Skill.fromRow(
                UUID.randomUUID().toString(), "test-runner", "Run unit tests automatically",
                "bob", "Testing", "1.0.0", null, "PUBLISHED", 0L, now, now, List.of("*:read"), null));
    }

    @Test
    @DisplayName("AC-1: 用關鍵字搜尋技能 — keyword=docker returns matching skills")
    void keywordSearch() {
        var page = queryService.search("docker", null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getName()).isEqualTo("docker-helper");
    }

    @Test
    @DisplayName("AC-2: 按分類篩選技能 — category=DevOps returns 2 skills")
    void categoryFilter() {
        var page = queryService.search(null, "DevOps", null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).allMatch(s -> "DevOps".equals(s.getCategory()));
    }

    @Test
    @DisplayName("AC-3: 關鍵字 + 分類組合篩選 — keyword=deploy & category=DevOps")
    void keywordAndCategoryCombo() {
        var page = queryService.search("deploy", "DevOps", null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getName()).isEqualTo("k8s-deploy");
    }

    @Test
    @DisplayName("AC-4: 分類列表 — getCategoryCounts returns counts grouped by category")
    void categoriesList() {
        var categories = queryService.getCategoryCounts();

        assertThat(categories).isNotEmpty();
        assertThat(categories).anyMatch(c -> "DevOps".equals(c.name()) && c.count() == 2);
        assertThat(categories).anyMatch(c -> "Testing".equals(c.name()) && c.count() == 1);
    }

    @Test
    @DisplayName("AC-S043: keyword 搜尋 category 名（DevOps）→ 命中該分類所有 skill")
    void keywordSearchMatchesCategory() {
        // fixture 含 2 個 DevOps + 1 個 Testing；keyword="DevOps" 應匹配 2 個（含 category match）
        var page = queryService.search("DevOps", null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).allMatch(s -> "DevOps".equals(s.getCategory()));
    }

    @Test
    @DisplayName("AC-S044: keyword 含 leading/trailing whitespace 仍命中（trim 預處理）")
    void keywordTrimsWhitespace() {
        var plain = queryService.search("docker", null, null, PageRequest.of(0, 20));
        var trailing = queryService.search("docker  ", null, null, PageRequest.of(0, 20));
        var leading = queryService.search("  docker", null, null, PageRequest.of(0, 20));
        var surround = queryService.search("  docker  ", null, null, PageRequest.of(0, 20));

        assertThat(plain.getContent()).hasSize(1);
        assertThat(trailing.getContent()).hasSize(1);
        assertThat(leading.getContent()).hasSize(1);
        assertThat(surround.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("AC-S094a-1: ?author=sam exact filter (case-insensitive)")
    void authorFilterExactMatch() {
        var page = queryService.search(null, null, "sam", PageRequest.of(0, 20));
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getName()).isEqualTo("docker-helper");
    }

    @Test
    @DisplayName("AC-S094a-2: ?author=SAM uppercase 仍命中（LOWER 比對）")
    void authorFilterCaseInsensitive() {
        var page = queryService.search(null, null, "SAM", PageRequest.of(0, 20));
        assertThat(page.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("AC-S094a-3: ?author= 帶值時跳過 PUBLISHED filter（含 DRAFT/SUSPENDED）")
    void authorFilterShowsAllStatuses() {
        var now = Instant.now();
        // S121: DRAFT/SUSPENDED skill 仍須含 *:read 才對非 owner / non-admin user 可見；
        // 此 AC 驗 author-mode 不過濾 status，acl_entries 維持 PUBLIC 語意確保 list 命中
        skillRepo.save(Skill.fromRow(
                UUID.randomUUID().toString(), "sam-draft-skill", "draft for sam",
                "sam", "DevOps", null, null, "DRAFT", 0L, now, now, List.of("*:read"), null));
        skillRepo.save(Skill.fromRow(
                UUID.randomUUID().toString(), "sam-suspended-skill", "suspended for sam",
                "sam", "DevOps", "1.0.0", null, "SUSPENDED", 0L, now, now, List.of("*:read"), null));

        var page = queryService.search(null, null, "sam", PageRequest.of(0, 20));
        // 1 PUBLISHED + 1 DRAFT + 1 SUSPENDED = 3 (S094a 跳過 PUBLISHED filter for author view)
        assertThat(page.getContent()).hasSize(3);
    }

    @Test
    @DisplayName("AC-S121-1: 非 owner non-admin user 看不到 PRIVATE skill (acl_entries 不含 *:read 也無 grant)")
    void privateSkillHiddenFromNonGrantee() {
        var now = Instant.now();
        skillRepo.save(Skill.fromRow(
                UUID.randomUUID().toString(), "private-secret-skill", "owned by alice (private)",
                "alice", "DevOps", "1.0.0", null, "PUBLISHED", 0L, now, now,
                List.of("user:alice:read", "user:alice:write", "user:alice:delete"), null));
        // user=bob 不是 alice / 非 admin / 對 private-secret-skill 沒 grant：expand 出
        // [user:bob:read, *:read] — 無一命中該 skill 的 acl_entries
        when(currentUserProvider.current())
                .thenReturn(new CurrentUser("bob", List.of("viewer"), List.of()));
        when(aclExpander.expand(any(CurrentUser.class), eq("read")))
                .thenReturn(List.of("user:bob:read", "role:viewer:read", "*:read"));

        var page = queryService.search(null, null, null, PageRequest.of(0, 20));
        assertThat(page.getContent()).extracting(Skill::getName)
                .doesNotContain("private-secret-skill")
                .containsExactlyInAnyOrder("docker-helper", "k8s-deploy", "test-runner");
    }

    @Test
    @DisplayName("AC-S121-2: 被 grant 的 user 看得到該 PRIVATE skill")
    void privateSkillVisibleAfterGrant() {
        var now = Instant.now();
        skillRepo.save(Skill.fromRow(
                UUID.randomUUID().toString(), "private-shared-with-bob", "owned by alice, granted to bob",
                "alice", "DevOps", "1.0.0", null, "PUBLISHED", 0L, now, now,
                List.of("user:alice:read", "user:alice:write", "user:bob:read"), null));
        // user=bob expand 後含 user:bob:read — 命中該 skill acl_entries
        when(currentUserProvider.current())
                .thenReturn(new CurrentUser("bob", List.of("viewer"), List.of()));
        when(aclExpander.expand(any(CurrentUser.class), eq("read")))
                .thenReturn(List.of("user:bob:read", "role:viewer:read", "*:read"));

        var page = queryService.search(null, null, null, PageRequest.of(0, 20));
        assertThat(page.getContent()).extracting(Skill::getName)
                .contains("private-shared-with-bob");
    }

    @Test
    @DisplayName("AC-S119-1: list endpoint 回 averageRating + reviewCount（before fix=0/0；after fix=projection 真值）")
    void listEndpointReturnsRatingProjection() {
        var now = Instant.now();
        var skillId = UUID.randomUUID().toString();
        skillRepo.save(Skill.fromRow(
                skillId, "rated-skill-x", "rated 4.5 from 2 reviews",
                "alice", "DevOps", "1.0.0", null, "PUBLISHED", 0L, now, now,
                List.of("*:read"), null));
        // average_rating / review_count 為 @ReadOnlyProperty — 走 raw SQL UPDATE 模擬
        // SkillRatingService.refresh projection 寫入路徑（per S098e2 既驗）
        jdbcTemplate.update(
                "UPDATE skills SET average_rating = ?, review_count = ? WHERE id = ?",
                4.5, 2L, skillId);

        var page = queryService.search("rated-skill-x", null, null, PageRequest.of(0, 20));
        assertThat(page.getContent()).hasSize(1);
        var s = page.getContent().getFirst();
        assertThat(s.getAverageRating()).isEqualTo(4.5);
        assertThat(s.getReviewCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("AC-S094a-4: ?author=ghost 不存在 → 空 page")
    void authorFilterNoMatch() {
        var page = queryService.search(null, null, "ghost", PageRequest.of(0, 20));
        assertThat(page.getContent()).isEmpty();
    }

    @Test
    @DisplayName("AC-S094a-5: ?author= + ?keyword= 組合 filter (AND)")
    void authorAndKeywordCombined() {
        var page = queryService.search("docker", null, "sam", PageRequest.of(0, 20));
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getName()).isEqualTo("docker-helper");
    }
}
