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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import io.github.samzhu.skillshub.shared.api.CannotRevokeOwnOwnerException;
import io.github.samzhu.skillshub.shared.api.GrantNotFoundException;
import io.github.samzhu.skillshub.shared.api.NotSkillOwnerException;
import io.github.samzhu.skillshub.shared.api.OwnerAlreadyExistsException;
import io.github.samzhu.skillshub.shared.security.CurrentUser;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.User;
import io.github.samzhu.skillshub.shared.security.UserRepository;
import io.github.samzhu.skillshub.shared.security.UserResolver;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.security.events.SkillGrantedEvent;
import io.github.samzhu.skillshub.skill.security.events.SkillRevokedEvent;
import tools.jackson.databind.ObjectMapper;

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
    private UserResolver userResolver;
    private UserRepository userRepo;
    private NamedParameterJdbcTemplate jdbc;
    private SkillGrantIdGenerator grantIdGenerator;
    private SkillGrantService service;

    @BeforeEach
    void setUp() {
        skillRepo = mock(SkillRepository.class);
        grantRepo = mock(SkillGrantRepository.class);
        events = mock(ApplicationEventPublisher.class);
        users = mock(CurrentUserProvider.class);
        userResolver = mock(UserResolver.class);
        userRepo = mock(UserRepository.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);
        grantIdGenerator = mock(SkillGrantIdGenerator.class);
        // S154b T04 — ctor 擴 UserResolver + UserRepository 供 grant resolve + listGrants enrich。
        // 既有 AC-2/AC-4/AC-5 test 預設「principalId 已是 user_id」場景：stub resolver
        // 回 same value 維持 backward compatibility。
        when(userResolver.resolveByEmailHandleOrId(anyString())).thenAnswer(
                inv -> Optional.of((String) inv.getArgument(0)));
        when(grantIdGenerator.nextId()).thenReturn("public001abc");
        service = new SkillGrantService(skillRepo, grantRepo, events, users, userResolver, userRepo,
                jdbc, new ObjectMapper(), grantIdGenerator);
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

    @Test
    @Tag("AC-9")
    @DisplayName("AC-9 (S154b T04): grant 接收 email → UserResolver 解析成 user_id 寫入 ACL")
    void grant_acceptsEmail_resolvesToUserId() {
        var skillId = "skill-1";
        var skill = mockSkillWithOwner(skillId, "u_alice0");
        when(users.current()).thenReturn(CurrentUser.synthetic("u_alice0", java.util.List.of(), java.util.List.of(), null));
        when(skillRepo.findById(skillId)).thenReturn(Optional.of(skill));
        when(grantRepo.existsBySkillIdAndRole(skillId, "OWNER")).thenReturn(false);
        // bob 用 email 申請；resolver 解析到 u_bob000
        when(userResolver.resolveByEmailHandleOrId("bob@example.com")).thenReturn(Optional.of("u_bob000"));

        var req = new SkillGrantService.GrantRequest("user", "bob@example.com", Role.VIEWER);
        service.grant(skillId, req);

        // SkillGrant.principalId 應為 resolved user_id，而非 raw email
        var captor = org.mockito.ArgumentCaptor.forClass(SkillGrant.class);
        verify(grantRepo).save(captor.capture());
        assertThat(captor.getValue().getPrincipalId()).isEqualTo("u_bob000");
        assertThat(captor.getValue().getPrincipalType()).isEqualTo("user");
    }

    @Test
    @Tag("AC-9")
    @DisplayName("AC-9 (S154b T04): grant 接收 handle → UserResolver 解析成 user_id 寫入 ACL")
    void grant_acceptsHandle_resolvesToUserId() {
        var skillId = "skill-1";
        var skill = mockSkillWithOwner(skillId, "u_alice0");
        when(users.current()).thenReturn(CurrentUser.synthetic("u_alice0", java.util.List.of(), java.util.List.of(), null));
        when(skillRepo.findById(skillId)).thenReturn(Optional.of(skill));
        when(grantRepo.existsBySkillIdAndRole(skillId, "OWNER")).thenReturn(false);
        when(userResolver.resolveByEmailHandleOrId("bob")).thenReturn(Optional.of("u_bob000"));

        var req = new SkillGrantService.GrantRequest("user", "bob", Role.VIEWER);
        service.grant(skillId, req);

        var captor = org.mockito.ArgumentCaptor.forClass(SkillGrant.class);
        verify(grantRepo).save(captor.capture());
        assertThat(captor.getValue().getPrincipalId()).isEqualTo("u_bob000");
    }

    @Test
    @Tag("AC-S184-9")
    @DisplayName("AC-S184-9: public principal is rejected by grants API")
    void grant_publicPrincipalRejected() {
        var skillId = "skill-1";
        var skill = mockSkillWithOwner(skillId, "u_alice0");
        when(users.current()).thenReturn(CurrentUser.synthetic("u_alice0", java.util.List.of(), java.util.List.of(), null));
        when(skillRepo.findById(skillId)).thenReturn(Optional.of(skill));

        var req = new SkillGrantService.GrantRequest("public", "*", Role.VIEWER);

        assertThatThrownBy(() -> service.grant(skillId, req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PUT /api/v1/skills/skill-1/visibility");

        verify(grantRepo, never()).save(any());
        // resolver 不該被呼叫於 public principal
        verify(userResolver, never()).resolveByEmailHandleOrId(anyString());
    }

    @Test
    @Tag("AC-6")
    @DisplayName("AC-6 (S154b T04): listGrants user principal enrich displayName + handle")
    void listGrants_enrichesUserPrincipal() {
        var skillId = "skill-1";
        var skill = mockSkillWithOwner(skillId, "u_alice0");
        var userGrant = SkillGrant.create(skillId, "user", "u_alice0", Role.OWNER, "u_alice0");
        var publicGrant = SkillGrant.create(skillId, "public", "*", Role.VIEWER, "u_alice0");
        when(users.current()).thenReturn(CurrentUser.synthetic("u_alice0", java.util.List.of(), java.util.List.of(), null));
        when(skillRepo.findById(skillId)).thenReturn(Optional.of(skill));
        when(grantRepo.findBySkillId(skillId)).thenReturn(java.util.List.of(userGrant, publicGrant));

        var alice = mock(User.class);
        when(alice.getId()).thenReturn("u_alice0");
        when(alice.getName()).thenReturn("Alice Chen");
        when(alice.getEmail()).thenReturn("alice@example.com");
        when(alice.getHandle()).thenReturn("alice");
        when(userRepo.findById("u_alice0")).thenReturn(Optional.of(alice));

        var result = service.listGrants(skillId);

        assertThat(result).hasSize(2);
        var userRow = result.stream().filter(g -> "user".equals(g.getPrincipalType())).findFirst().orElseThrow();
        assertThat(userRow.getDisplayName()).isEqualTo("Alice Chen");
        assertThat(userRow.getHandle()).isEqualTo("alice");
        // public row 不 enrich — displayName / handle 保持 null
        var publicRow = result.stream().filter(g -> "public".equals(g.getPrincipalType())).findFirst().orElseThrow();
        assertThat(publicRow.getDisplayName()).isNull();
        assertThat(publicRow.getHandle()).isNull();
    }

    @Test
    @Tag("S169")
    @DisplayName("S169 AC-10: non-owner listGrants → NotSkillOwnerException")
    void listGrants_nonOwnerThrows() {
        var skillId = "skill-1";
        var skill = mockSkillWithOwner(skillId, "u_alice0");
        when(users.current()).thenReturn(CurrentUser.synthetic("u_bob000", java.util.List.of(), java.util.List.of(), null));
        when(skillRepo.findById(skillId)).thenReturn(Optional.of(skill));

        assertThatThrownBy(() -> service.listGrants(skillId))
                .isInstanceOf(NotSkillOwnerException.class);
    }

    /** Build a minimal Skill stub with the given ownerId via reflection. */
    private Skill mockSkillWithOwner(String skillId, String ownerId) {
        var skill = mock(Skill.class);
        when(skill.getId()).thenReturn(skillId);
        when(skill.getOwnerId()).thenReturn(ownerId);
        return skill;
    }
}
