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

    private String seed(String name, boolean isPublic, List<String> aclEntries) {
        var now = Instant.now();
        var id = UUID.randomUUID().toString();
        skillRepo.save(Skill.fromRow(
                id, name, "S177 keyword fixture", "u_alice0", "testing",
                "1.0.0", null, "PUBLISHED", 0L, now, now, aclEntries, null));
        jdbc.update("UPDATE skills SET is_public = :isPublic WHERE id = :id",
                Map.of("id", id, "isPublic", isPublic));
        return id;
    }
}
