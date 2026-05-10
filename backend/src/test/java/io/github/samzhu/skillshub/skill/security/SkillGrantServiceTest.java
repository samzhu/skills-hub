package io.github.samzhu.skillshub.skill.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import io.github.samzhu.skillshub.shared.api.CannotRevokeOwnOwnerException;
import io.github.samzhu.skillshub.shared.api.GrantNotFoundException;
import io.github.samzhu.skillshub.shared.api.NotSkillOwnerException;
import io.github.samzhu.skillshub.shared.api.OwnerAlreadyExistsException;
import io.github.samzhu.skillshub.shared.security.CurrentUser;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.security.events.SkillGrantedEvent;
import io.github.samzhu.skillshub.skill.security.events.SkillRevokedEvent;

/**
 * S114a T03 — SkillGrantService unit test (pure Mockito, no Spring context).
 *
 * <p>Covers:
 * <ul>
 *   <li>AC-2: grant VIEWER happy path</li>
 *   <li>AC-4: revoke grant happy path</li>
 *   <li>AC-5: grant OWNER when OWNER already exists</li>
 *   <li>non-owner grant attempt</li>
 *   <li>revoke own OWNER grant</li>
 * </ul>
 */
class SkillGrantServiceTest {

    private SkillRepository skillRepo;
    private SkillGrantRepository grantRepo;
    private ApplicationEventPublisher events;
    private CurrentUserProvider users;
    private SkillGrantService service;

    @BeforeEach
    void setUp() {
        skillRepo = mock(SkillRepository.class);
        grantRepo = mock(SkillGrantRepository.class);
        events = mock(ApplicationEventPublisher.class);
        users = mock(CurrentUserProvider.class);
        service = new SkillGrantService(skillRepo, grantRepo, events, users);
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: alice (owner) grants VIEWER to bob → grantId returned + events published")
    void grant_ownerGrantsViewer_returnsGrantId() {
        var skillId = "skill-1";
        var skill = mockSkillWithOwner(skillId, "alice");
        when(users.current()).thenReturn(CurrentUser.synthetic("alice", java.util.List.of(), java.util.List.of(), null));
        when(skillRepo.findById(skillId)).thenReturn(Optional.of(skill));
        when(grantRepo.existsBySkillIdAndRole(skillId, "OWNER")).thenReturn(false);

        var req = new SkillGrantService.GrantRequest("user", "bob", Role.VIEWER);
        var grantId = service.grant(skillId, req);

        assertThat(grantId).isNotBlank();
        verify(grantRepo).save(any(SkillGrant.class));
        verify(events).publishEvent(any(SkillGrantedEvent.class));
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: alice (owner) revokes existing grant → deleteById + event published")
    void revoke_ownerRevokesViewerGrant_deletesAndPublishes() {
        var skillId = "skill-1";
        var skill = mockSkillWithOwner(skillId, "alice");
        when(users.current()).thenReturn(CurrentUser.synthetic("alice", java.util.List.of(), java.util.List.of(), null));
        when(skillRepo.findById(skillId)).thenReturn(Optional.of(skill));

        var grant = SkillGrant.create(skillId, "user", "bob", Role.VIEWER, "alice");
        when(grantRepo.findById(grant.getId())).thenReturn(Optional.of(grant));

        service.revoke(skillId, grant.getId());

        verify(grantRepo).deleteById(grant.getId());
        verify(events).publishEvent(any(SkillRevokedEvent.class));
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: grant OWNER when one already exists → OwnerAlreadyExistsException")
    void grant_ownerAlreadyExists_throws() {
        var skillId = "skill-1";
        var skill = mockSkillWithOwner(skillId, "alice");
        when(users.current()).thenReturn(CurrentUser.synthetic("alice", java.util.List.of(), java.util.List.of(), null));
        when(skillRepo.findById(skillId)).thenReturn(Optional.of(skill));
        when(grantRepo.existsBySkillIdAndRole(skillId, "OWNER")).thenReturn(true);

        var req = new SkillGrantService.GrantRequest("user", "carol", Role.OWNER);
        assertThatThrownBy(() -> service.grant(skillId, req))
                .isInstanceOf(OwnerAlreadyExistsException.class);
        verify(grantRepo, never()).save(any());
    }

    @Test
    @DisplayName("non-owner grant attempt → NotSkillOwnerException")
    void grant_nonOwner_throws() {
        var skillId = "skill-1";
        var skill = mockSkillWithOwner(skillId, "alice");
        when(users.current()).thenReturn(CurrentUser.synthetic("bob", java.util.List.of(), java.util.List.of(), null));
        when(skillRepo.findById(skillId)).thenReturn(Optional.of(skill));

        var req = new SkillGrantService.GrantRequest("user", "carol", Role.VIEWER);
        assertThatThrownBy(() -> service.grant(skillId, req))
                .isInstanceOf(NotSkillOwnerException.class);
        verify(grantRepo, never()).save(any());
    }

    @Test
    @DisplayName("revoke own OWNER grant → CannotRevokeOwnOwnerException")
    void revoke_ownOwnerGrant_throws() {
        var skillId = "skill-1";
        var skill = mockSkillWithOwner(skillId, "alice");
        when(users.current()).thenReturn(CurrentUser.synthetic("alice", java.util.List.of(), java.util.List.of(), null));
        when(skillRepo.findById(skillId)).thenReturn(Optional.of(skill));

        var grant = SkillGrant.create(skillId, "user", "alice", Role.OWNER, "alice");
        when(grantRepo.findById(grant.getId())).thenReturn(Optional.of(grant));

        assertThatThrownBy(() -> service.revoke(skillId, grant.getId()))
                .isInstanceOf(CannotRevokeOwnOwnerException.class);
        verify(grantRepo, never()).deleteById(anyString());
    }

    @Test
    @DisplayName("revoke non-existent grant → GrantNotFoundException")
    void revoke_grantNotFound_throws() {
        var skillId = "skill-1";
        var skill = mockSkillWithOwner(skillId, "alice");
        when(users.current()).thenReturn(CurrentUser.synthetic("alice", java.util.List.of(), java.util.List.of(), null));
        when(skillRepo.findById(skillId)).thenReturn(Optional.of(skill));
        when(grantRepo.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(skillId, "missing"))
                .isInstanceOf(GrantNotFoundException.class);
    }

    /** Build a minimal Skill stub with the given ownerId via reflection. */
    private Skill mockSkillWithOwner(String skillId, String ownerId) {
        var skill = mock(Skill.class);
        when(skill.getId()).thenReturn(skillId);
        when(skill.getOwnerId()).thenReturn(ownerId);
        return skill;
    }
}
