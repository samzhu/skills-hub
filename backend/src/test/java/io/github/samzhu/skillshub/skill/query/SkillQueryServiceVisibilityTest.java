package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.shared.security.PrincipalContextService;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S177-T03 — keyword search uses is_public for public visibility and ACL only
 * for explicit private grants.
 */
@Import(SkillQueryService.class)
class SkillQueryServiceVisibilityTest extends RepositorySliceTestBase {

    @Autowired
    private SkillQueryService queryService;

    @Autowired
    private SkillRepository skillRepo;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @MockitoBean
    private PrincipalContextService principalContextService;

    @MockitoBean
    private ViewerPermissionService viewerPermissionService;

    @BeforeEach
    void cleanState() {
        skillRepo.deleteAll();
    }

    @Test
    @DisplayName("AC-S177-2: anonymous keyword browse returns only public skills")
    @Tag("AC-S177-2")
    void anonymousKeywordBrowseReturnsOnlyPublicSkills() {
        seed("s177-public-visible", true, List.of("user:u_alice0:read"));
        seed("s177-private-hidden", false, List.of("user:u_alice0:read"));
        seed("s177-private-shared", false, List.of("user:u_bob00:read"));
        when(principalContextService.currentPrincipalKeys()).thenReturn(Set.of());

        var page = queryService.search("s177", null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(Skill::getName)
                .containsExactly("s177-public-visible");
    }

    @Test
    @DisplayName("AC-S177-3: authenticated keyword browse returns public and granted private skills")
    @Tag("AC-S177-3")
    void authenticatedKeywordBrowseReturnsPublicAndGrantedPrivateSkills() {
        seed("s177-public-visible", true, List.of("user:u_alice0:read"));
        seed("s177-private-hidden", false, List.of("user:u_alice0:read"));
        seed("s177-private-shared", false, List.of("user:u_bob00:read"));
        when(principalContextService.currentPrincipalKeys()).thenReturn(Set.of("user:u_bob00"));

        var page = queryService.search("s177", null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(Skill::getName)
                .containsExactlyInAnyOrder("s177-public-visible", "s177-private-shared")
                .doesNotContain("s177-private-hidden");
    }

    @Test
    @DisplayName("AC-S185-1: list row visibility comes from skills.is_public")
    @Tag("AC-S185-1")
    void listRowVisibilityComesFromSkillsIsPublic() {
        var id = seed("s185-public-visible", true, List.of(), "LOW");
        seedVersion(id, "1.0.0", Instant.parse("2026-05-15T21:06:42Z"),
                """
                {"license":"MIT","compatibility":["codex"]}
                """);
        when(principalContextService.currentPrincipalKeys()).thenReturn(Set.of());

        var page = queryService.search("s185-public", null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        var result = page.getContent().getFirst();
        assertThat(result.getId()).isEqualTo(id);
        assertThat(result.isPublic()).isTrue();
    }

    @Test
    @DisplayName("AC-S185-2: list row S142b fields match detail source fields")
    @Tag("AC-S185-2")
    void listRowS142bFieldsMatchDetailSourceFields() {
        var publishedAt = Instant.parse("2026-05-15T21:06:42Z");
        var id = seed("s185-detail-parity", true, List.of(), "LOW");
        seedVersion(id, "1.0.0", publishedAt,
                """
                {"license":"MIT","compatibility":["codex"]}
                """);
        seedOpenFlag(id);
        when(principalContextService.currentPrincipalKeys()).thenReturn(Set.of());

        var page = queryService.search("s185-detail", null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        var result = page.getContent().getFirst();
        assertThat(result.isVerified()).isTrue();
        assertThat(result.getLatestVersionPublishedAt()).isEqualTo(publishedAt);
        assertThat(result.getLicense()).isEqualTo("MIT");
        assertThat(result.getCompatibility()).containsExactly("codex");
        assertThat(result.getVersionCount()).isEqualTo(1L);
        assertThat(result.getOpenFlagCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-S185-3: category counts use same visibility filter as skill list")
    @Tag("AC-S185-3")
    void categoryCountsUseSameVisibilityFilterAsSkillList() {
        seed("testing-public", true, List.of(), null, "testing");
        seed("video-private", false, List.of(), null, "video");
        when(principalContextService.currentPrincipalKeys()).thenReturn(Set.of());

        var categories = queryService.getCategoryCounts();

        assertThat(categories)
                .extracting(CategoryCount::name)
                .contains("testing")
                .doesNotContain("video");
        assertThat(categories)
                .filteredOn(category -> category.name().equals("testing"))
                .singleElement()
                .extracting(CategoryCount::count)
                .isEqualTo(1L);
    }

    private String seed(String name, boolean isPublic, List<String> aclEntries) {
        return seed(name, isPublic, aclEntries, null);
    }

    private String seed(String name, boolean isPublic, List<String> aclEntries, String riskLevel) {
        return seed(name, isPublic, aclEntries, riskLevel, "testing");
    }

    private String seed(String name, boolean isPublic, List<String> aclEntries, String riskLevel, String category) {
        var now = Instant.now();
        var id = UUID.randomUUID().toString();
        skillRepo.save(Skill.fromRow(
                id, name, "S177 keyword fixture", "u_alice0", category,
                "1.0.0", riskLevel, "PUBLISHED", 0L, now, now, aclEntries, null));
        jdbc.update("UPDATE skills SET is_public = :isPublic, risk_level = :riskLevel WHERE id = :id",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("isPublic", isPublic)
                        .addValue("riskLevel", riskLevel));
        return id;
    }

    private void seedVersion(String skillId, String version, Instant publishedAt, String frontmatterJson) {
        jdbc.update("""
                INSERT INTO skill_versions
                    (id, skill_id, version, storage_path, file_size, frontmatter, published_at)
                VALUES
                    (:id, :skillId, :version, :storagePath, 100, CAST(:frontmatter AS jsonb),
                     CAST(:publishedAt AS timestamptz))
                """,
                Map.of(
                        "id", UUID.randomUUID().toString(),
                        "skillId", skillId,
                        "version", version,
                        "storagePath", "skills/" + skillId + "/" + version + "/skill.zip",
                        "frontmatter", frontmatterJson,
                        "publishedAt", publishedAt.toString()));
    }

    private void seedOpenFlag(String skillId) {
        jdbc.update("""
                INSERT INTO flags (id, skill_id, type, description, reported_by, created_at, status)
                VALUES (:id, :skillId, 'QUALITY', 'needs review', 'qa', CAST(:createdAt AS timestamptz), 'OPEN')
                """,
                Map.of(
                        "id", UUID.randomUUID().toString(),
                        "skillId", skillId,
                        "createdAt", Instant.parse("2026-05-15T21:07:42Z").toString()));
    }
}
