package io.github.samzhu.skillshub.skill.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;

/**
 * S114a AC-1/AC-2 — Role + SkillGrant domain model + SkillGrantRepository slice test.
 *
 * <p>Covers:
 * <ul>
 *   <li>Role.OWNER.permissions() and Role.VIEWER.permissions() behavior</li>
 *   <li>SkillGrant.create() factory field assignment</li>
 *   <li>SkillGrantRepository derived query methods reachable against real DB</li>
 * </ul>
 */
class SkillGrantDomainTest extends RepositorySliceTestBase {

    @Autowired
    private SkillGrantRepository grantRepo;

    @Test
    @DisplayName("AC-1: Role.OWNER.permissions() 回 [read, write, delete]")
    @Tag("AC-1")
    void ownerRolePermissions() {
        assertThat(Role.OWNER.permissions()).containsExactlyInAnyOrder("read", "write", "delete");
    }

    @Test
    @DisplayName("AC-2: Role.VIEWER.permissions() 回 [read]")
    @Tag("AC-2")
    void viewerRolePermissions() {
        assertThat(Role.VIEWER.permissions()).containsExactly("read");
    }

    @Test
    @DisplayName("AC-1: Role.EDITOR.permissions() 回 [read, write]")
    @Tag("AC-1")
    void editorRolePermissions() {
        assertThat(Role.EDITOR.permissions()).containsExactly("read", "write");
    }

    @Test
    @DisplayName("AC-1: SkillGrant.create() 生成 UUID id + 正確 fields")
    @Tag("AC-1")
    void skillGrantCreate_setsAllFields() {
        var grant = SkillGrant.create("skill-1", "user", "alice", Role.OWNER, "admin");

        assertThat(grant.getId()).isNotBlank();
        assertThat(grant.getSkillId()).isEqualTo("skill-1");
        assertThat(grant.getPrincipalType()).isEqualTo("user");
        assertThat(grant.getPrincipalId()).isEqualTo("alice");
        assertThat(grant.getRole()).isEqualTo("OWNER");
        assertThat(grant.getGrantedBy()).isEqualTo("admin");
        assertThat(grant.getGrantedAt()).isNotNull();
    }

    @Test
    @DisplayName("AC-1: SkillGrantRepository.findBySkillId() 可呼叫")
    @Tag("AC-1")
    void skillGrantRepository_findBySkillId_callable() {
        var result = grantRepo.findBySkillId("non-existent-id");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("AC-1: SkillGrantRepository.existsBySkillIdAndRole() 可呼叫")
    @Tag("AC-1")
    void skillGrantRepository_existsBySkillIdAndRole_callable() {
        var exists = grantRepo.existsBySkillIdAndRole("non-existent-id", "OWNER");
        assertThat(exists).isFalse();
    }
}
