package io.github.samzhu.skillshub.skill.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.shared.security.CurrentUser;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.UserRepository;
import io.github.samzhu.skillshub.shared.security.UserResolver;
import io.github.samzhu.skillshub.skill.command.CreateSkillCommand;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.Visibility;

/**
 * S177-T02 — grant/revoke service updates public visibility and explicit ACL in
 * the same transaction as grant rows.
 */
@Import({SkillGrantService.class, SkillGrantIdGenerator.class})
class SkillGrantServiceVisibilityTest extends RepositorySliceTestBase {

    @Autowired
    private SkillGrantService service;

    @Autowired
    private SkillRepository skillRepo;

    @Autowired
    private SkillGrantRepository grantRepo;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @MockitoBean
    private CurrentUserProvider users;

    @MockitoBean
    private UserResolver userResolver;

    @MockitoBean
    private UserRepository userRepo;

    @BeforeEach
    void cleanState() {
        jdbc.getJdbcTemplate().update("DELETE FROM skill_grants");
        jdbc.getJdbcTemplate().update("DELETE FROM skills");
        when(users.current()).thenReturn(CurrentUser.synthetic("u_alice0", java.util.List.of(), java.util.List.of(), null));
        when(userResolver.resolveByEmailHandleOrId("u_bob00")).thenReturn(Optional.of("u_bob00"));
    }

    @Test
    @DisplayName("AC-S184-5: visibility PUBLIC command updates is_public and public grant without public ACL")
    @Tag("AC-S177-7")
    @Tag("AC-S184-5")
    void visibilityPublicCommandUpdatesIsPublicWithoutPublicAcl() {
        var skillId = seedSkill(Visibility.PRIVATE);
        grantRepo.save(SkillGrant.create(skillId, "user", "u_alice0", Role.OWNER, "u_alice0"));

        var result = service.setVisibility(skillId, Visibility.PUBLIC);

        assertThat(result.visibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(isPublic(skillId)).isTrue();
        assertThat(grantCount(skillId, "public", "*")).isEqualTo(1);
        assertThat(aclEntries(skillId))
                .contains("user:u_alice0:read")
                .doesNotContain("public:*:read");
    }

    @Test
    @DisplayName("AC-S184-5: visibility PRIVATE command is idempotent and does not require public grant")
    @Tag("AC-S184-5")
    void visibilityPrivateCommandIsIdempotentWhenAlreadyPrivate() {
        var skillId = seedSkill(Visibility.PRIVATE);
        grantRepo.save(SkillGrant.create(skillId, "user", "u_alice0", Role.OWNER, "u_alice0"));

        var first = service.setVisibility(skillId, Visibility.PRIVATE);
        var second = service.setVisibility(skillId, Visibility.PRIVATE);

        assertThat(first.visibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(second.visibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(isPublic(skillId)).isFalse();
        assertThat(grantCount(skillId, "public", "*")).isZero();
    }

    @Test
    @DisplayName("AC-S184-6: visibility PUBLIC no-op uses is_public and does not duplicate public grant")
    @Tag("AC-S184-6")
    void visibilityPublicNoopUsesIsPublicWithoutDuplicatingGrant() {
        var skillId = seedSkill(Visibility.PUBLIC);
        grantRepo.save(SkillGrant.create(skillId, "user", "u_alice0", Role.OWNER, "u_alice0"));

        var result = service.setVisibility(skillId, Visibility.PUBLIC);

        assertThat(result.visibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(isPublic(skillId)).isTrue();
        assertThat(grantCount(skillId, "public", "*")).isZero();
    }

    @Test
    @DisplayName("AC-S184-9: external grant API rejects public principal")
    @Tag("AC-S184-9")
    void grantApiRejectsPublicPrincipal() {
        var skillId = seedSkill(Visibility.PRIVATE);
        grantRepo.save(SkillGrant.create(skillId, "user", "u_alice0", Role.OWNER, "u_alice0"));

        assertThatThrownBy(() ->
                service.grant(skillId, new SkillGrantService.GrantRequest("public", "*", Role.VIEWER)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/visibility");

        assertThat(grantCount(skillId, "public", "*")).isZero();
    }

    @Test
    @DisplayName("AC-S184-10: non-owner cannot change visibility")
    @Tag("AC-S184-10")
    void nonOwnerCannotChangeVisibility() {
        var skillId = seedSkill(Visibility.PUBLIC);
        when(users.current()).thenReturn(CurrentUser.synthetic("u_bob00", java.util.List.of(), java.util.List.of(), null));

        assertThatThrownBy(() -> service.setVisibility(skillId, Visibility.PRIVATE))
                .isInstanceOf(io.github.samzhu.skillshub.shared.api.NotSkillOwnerException.class);

        assertThat(isPublic(skillId)).isTrue();
    }

    @Test
    @DisplayName("AC-S177-7: public grant revoke updates is_public false and keeps explicit ACL")
    @Tag("AC-S177-7")
    void publicGrantRevokeUpdatesIsPublicFalseAndKeepsExplicitAcl() {
        var skillId = seedSkill(Visibility.PUBLIC);
        grantRepo.save(SkillGrant.create(skillId, "user", "u_alice0", Role.OWNER, "u_alice0"));
        grantRepo.save(SkillGrant.create(skillId, "user", "u_bob00", Role.VIEWER, "u_alice0"));
        var publicGrant = SkillGrant.create(skillId, "public", "*", Role.VIEWER, "u_alice0");
        grantRepo.save(publicGrant);
        setAclEntries(skillId, """
                ["user:u_alice0:read","user:u_alice0:write","user:u_alice0:delete","user:u_bob00:read"]
                """);

        service.revoke(skillId, publicGrant.getId());

        assertThat(isPublic(skillId)).isFalse();
        assertThat(grantCount(skillId, "public", "*")).isZero();
        assertThat(aclEntries(skillId))
                .contains("user:u_alice0:read")
                .contains("user:u_bob00:read")
                .doesNotContain("public:*:read");
    }

    private String seedSkill(Visibility visibility) {
        var skill = Skill.create(new CreateSkillCommand(
                "s177-grant-" + UUID.randomUUID().toString().substring(0, 8),
                "S177 grant fixture",
                "u_alice0",
                "testing",
                visibility));
        return skillRepo.save(skill).getId();
    }

    private boolean isPublic(String skillId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_public FROM skills WHERE id = :id",
                Map.of("id", skillId),
                Boolean.class));
    }

    private int grantCount(String skillId, String principalType, String principalId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                FROM skill_grants
                WHERE skill_id = :skillId
                  AND principal_type = :principalType
                  AND principal_id = :principalId
                """, new MapSqlParameterSource()
                .addValue("skillId", skillId)
                .addValue("principalType", principalType)
                .addValue("principalId", principalId),
                Integer.class);
        return count == null ? 0 : count;
    }

    private String aclEntries(String skillId) {
        return jdbc.queryForObject(
                "SELECT acl_entries::text FROM skills WHERE id = :id",
                Map.of("id", skillId),
                String.class);
    }

    private void setAclEntries(String skillId, String json) {
        jdbc.update(
                "UPDATE skills SET acl_entries = :acl::jsonb WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("id", skillId)
                        .addValue("acl", json));
    }
}
