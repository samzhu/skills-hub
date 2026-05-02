package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S025b T04 demote — 從 {@code @SpringBootTest(RANDOM_PORT) + TestRestTemplate} 改
 * {@link RepositorySliceTestBase}（{@code @DataJdbcTest} slice）。原 HTTP-bound assertion
 * 已被 {@link io.github.samzhu.skillshub.S016EndToEndSmokeTest} E2E 涵蓋；本 test
 * 收斂為 {@link SkillQueryService#search} + {@link SkillQueryService#getCategoryCounts}
 * 純 SQL 邏輯驗證。Seed 直接走 {@link SkillRepository#save}（無需 HTTP POST）。
 */
@Import(SkillQueryService.class)
class SkillSearchTest extends RepositorySliceTestBase {

    @Autowired
    private SkillQueryService queryService;

    @Autowired
    private SkillRepository skillRepo;

    @BeforeEach
    void setUp() {
        skillRepo.deleteAll();
        var now = Instant.now();
        // S031: search/categories 過濾 status=PUBLISHED；fixture 改 PUBLISHED 對齊公開查詢預期
        skillRepo.save(Skill.fromRow(
                UUID.randomUUID().toString(), "docker-helper", "Docker compose helper",
                "sam", "DevOps", "1.0.0", null, "PUBLISHED", 0L, now, now, List.of(), null));
        skillRepo.save(Skill.fromRow(
                UUID.randomUUID().toString(), "k8s-deploy", "Kubernetes deployment skill",
                "jane", "DevOps", "1.0.0", null, "PUBLISHED", 0L, now, now, List.of(), null));
        skillRepo.save(Skill.fromRow(
                UUID.randomUUID().toString(), "test-runner", "Run unit tests automatically",
                "bob", "Testing", "1.0.0", null, "PUBLISHED", 0L, now, now, List.of(), null));
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
        skillRepo.save(Skill.fromRow(
                UUID.randomUUID().toString(), "sam-draft-skill", "draft for sam",
                "sam", "DevOps", null, null, "DRAFT", 0L, now, now, List.of(), null));
        skillRepo.save(Skill.fromRow(
                UUID.randomUUID().toString(), "sam-suspended-skill", "suspended for sam",
                "sam", "DevOps", "1.0.0", null, "SUSPENDED", 0L, now, now, List.of(), null));

        var page = queryService.search(null, null, "sam", PageRequest.of(0, 20));
        // 1 PUBLISHED + 1 DRAFT + 1 SUSPENDED = 3 (S094a 跳過 PUBLISHED filter for author view)
        assertThat(page.getContent()).hasSize(3);
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
