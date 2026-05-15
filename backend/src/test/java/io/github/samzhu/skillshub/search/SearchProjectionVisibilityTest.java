package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.skill.command.CreateSkillCommand;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.Visibility;

/**
 * S177-T04 — projection writes public visibility into vector_store.is_public
 * and keeps vector ACL explicit.
 */
@Import(SearchProjection.class)
class SearchProjectionVisibilityTest extends RepositorySliceTestBase {

    @Autowired
    private SearchProjection projection;

    @Autowired
    private SkillRepository skillRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("AC-S177-5: search projection writes vector_store is_public and explicit ACL")
    @Tag("AC-S177-5")
    void searchProjectionWritesVectorIsPublicAndExplicitAcl() {
        var skill = skillRepo.save(Skill.create(new CreateSkillCommand(
                "s177-projection-public", "S177 projection fixture", "u_alice0", "testing", Visibility.PUBLIC)));

        projection.onSkillCreated(new SkillCreatedEvent(
                skill.getId(), skill.getName(), skill.getDescription(), skill.getAuthor(), skill.getCategory()));

        Boolean isPublic = jdbc.queryForObject(
                "SELECT is_public FROM vector_store WHERE id = ?::uuid",
                Boolean.class, skill.getId());
        String aclJson = jdbc.queryForObject(
                "SELECT acl_entries::text FROM vector_store WHERE id = ?::uuid",
                String.class, skill.getId());

        assertThat(isPublic).isTrue();
        assertThat(aclJson)
                .contains("user:u_alice0:read")
                .doesNotContain("user:u_alice0:write")
                .doesNotContain("user:u_alice0:delete")
                .doesNotContain("public:*:read");
    }
}
