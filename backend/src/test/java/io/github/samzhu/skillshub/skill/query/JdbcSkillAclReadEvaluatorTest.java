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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.shared.security.PrincipalContextService;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S177-T03 — detail read permission checks public visibility through
 * skills.is_public, not a public ACL entry.
 */
@Import(JdbcSkillAclReadEvaluator.class)
class JdbcSkillAclReadEvaluatorTest extends RepositorySliceTestBase {

    @Autowired
    private JdbcSkillAclReadEvaluator evaluator;

    @Autowired
    private SkillRepository skillRepo;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @MockitoBean
    private PrincipalContextService principalContextService;

    @BeforeEach
    void cleanState() {
        skillRepo.deleteAll();
    }

    @Test
    @DisplayName("AC-S177-6: read permission allows public skill without public ACL entry")
    @Tag("AC-S177-6")
    void readAllowsPublicSkillWithoutPublicAclEntry() {
        var skillId = seed(true, List.of("user:u_alice0:read"));
        when(principalContextService.currentPrincipalKeys()).thenReturn(Set.of());

        assertThat(evaluator.canRead(skillId)).isTrue();
    }

    @Test
    @DisplayName("AC-S177-6: read permission denies ungranted private skill")
    @Tag("AC-S177-6")
    void readDeniesUngrantedPrivateSkill() {
        var skillId = seed(false, List.of("user:u_alice0:read"));
        when(principalContextService.currentPrincipalKeys()).thenReturn(Set.of("user:u_bob00"));

        assertThat(evaluator.canRead(skillId)).isFalse();
    }

    private String seed(boolean isPublic, List<String> aclEntries) {
        var now = Instant.now();
        var id = UUID.randomUUID().toString();
        skillRepo.save(Skill.fromRow(
                id, "s177-detail-" + id.substring(0, 8), "S177 detail fixture", "u_alice0", "testing",
                "1.0.0", null, "PUBLISHED", 0L, now, now, aclEntries, null));
        jdbc.update("UPDATE skills SET is_public = :isPublic WHERE id = :id",
                Map.of("id", id, "isPublic", isPublic));
        return id;
    }
}
