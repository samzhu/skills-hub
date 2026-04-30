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
        skillRepo.save(Skill.fromRow(
                UUID.randomUUID().toString(), "docker-helper", "Docker compose helper",
                "sam", "DevOps", null, null, "DRAFT", 0L, now, now, List.of(), null));
        skillRepo.save(Skill.fromRow(
                UUID.randomUUID().toString(), "k8s-deploy", "Kubernetes deployment skill",
                "jane", "DevOps", null, null, "DRAFT", 0L, now, now, List.of(), null));
        skillRepo.save(Skill.fromRow(
                UUID.randomUUID().toString(), "test-runner", "Run unit tests automatically",
                "bob", "Testing", null, null, "DRAFT", 0L, now, now, List.of(), null));
    }

    @Test
    @DisplayName("AC-1: 用關鍵字搜尋技能 — keyword=docker returns matching skills")
    void keywordSearch() {
        var page = queryService.search("docker", null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getName()).isEqualTo("docker-helper");
    }

    @Test
    @DisplayName("AC-2: 按分類篩選技能 — category=DevOps returns 2 skills")
    void categoryFilter() {
        var page = queryService.search(null, "DevOps", PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).allMatch(s -> "DevOps".equals(s.getCategory()));
    }

    @Test
    @DisplayName("AC-3: 關鍵字 + 分類組合篩選 — keyword=deploy & category=DevOps")
    void keywordAndCategoryCombo() {
        var page = queryService.search("deploy", "DevOps", PageRequest.of(0, 20));

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
}
