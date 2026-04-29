package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.skill.query.SkillReadModel;
import io.github.samzhu.skillshub.skill.query.SkillReadModelRepository;

/**
 * S018 T4 — POST {@code /api/v1/skills/{id}/suspend} 與 {@code /reactivate} 端點安全行為驗證。
 *
 * <p>對應 spec §3 AC-12：admin（acl_entries 含 role:admin:suspend）→ 200；alice（無 verb）→ 403；
 * E2E 驗 projection 端 status 更新。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SkillSuspendControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DomainEventRepository eventStore;

    @Autowired
    private SkillReadModelRepository skillRepo;

    @Test
    @DisplayName("AC-12: alice (無 suspend verb) POST /skills/{id}/suspend → 403 Forbidden")
    @Tag("AC-12")
    void aliceSuspend_returns403() throws Exception {
        var skillId = seedPublishedSkill(List.of("user:alice:read", "user:alice:write"));

        mockMvc.perform(post("/api/v1/skills/" + skillId + "/suspend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"my own\"}")
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isForbidden());

        // event store 不變（aggregate 端未被觸發）
        var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
        assertThat(events).extracting(DomainEvent::eventType).doesNotContain("SkillSuspended");
    }

    @Test
    @DisplayName("AC-12: admin (acl_entries 含 role:admin:suspend) POST /skills/{id}/suspend → 200 OK + SkillSuspended 寫入")
    @Tag("AC-12")
    void adminSuspend_returns200AndPersistsEvent() throws Exception {
        var skillId = seedPublishedSkill(List.of("role:admin:suspend"));

        mockMvc.perform(post("/api/v1/skills/" + skillId + "/suspend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"policy violation\"}")
                .with(jwt()
                        .jwt(j -> j.subject("admin-user")
                                .claim("roles", List.of("admin"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_admin"))))
                .andExpect(status().isOk());

        var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
        assertThat(events).extracting(DomainEvent::eventType).contains("SkillSuspended");

        // S023-T07 follow-up: SkillProjection.on(SkillSuspendedEvent) 改 @ApplicationModuleListener async；
        // MockMvc 回 200 後 listener 仍可能 in-flight，用 Awaitility 等
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(30))
                .untilAsserted(() -> {
                    var readModel = skillRepo.findById(skillId).orElseThrow();
                    assertThat(readModel.status()).isEqualTo("SUSPENDED");
                });
    }

    @Test
    @DisplayName("AC-12: alice (無 reactivate verb) POST /skills/{id}/reactivate → 403")
    @Tag("AC-12")
    void aliceReactivate_returns403() throws Exception {
        var skillId = seedSuspendedSkill(List.of("user:alice:read"));

        mockMvc.perform(post("/api/v1/skills/" + skillId + "/reactivate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"i want it back\"}")
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-12: admin (acl_entries 含 role:admin:reactivate) POST /skills/{id}/reactivate → 200 OK + SkillReactivated 寫入")
    @Tag("AC-12")
    void adminReactivate_returns200AndPersistsEvent() throws Exception {
        var skillId = seedSuspendedSkill(List.of("role:admin:reactivate"));

        mockMvc.perform(post("/api/v1/skills/" + skillId + "/reactivate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"manual review approved\"}")
                .with(jwt()
                        .jwt(j -> j.subject("admin-user")
                                .claim("roles", List.of("admin"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_admin"))))
                .andExpect(status().isOk());

        var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
        assertThat(events).extracting(DomainEvent::eventType).contains("SkillReactivated");

        // S023-T07 follow-up: SkillProjection.on(SkillReactivatedEvent) 改 @ApplicationModuleListener async；
        // MockMvc 回 200 後 listener 仍可能 in-flight，用 Awaitility 等
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(30))
                .untilAsserted(() -> {
                    var readModel = skillRepo.findById(skillId).orElseThrow();
                    assertThat(readModel.status()).isEqualTo("PUBLISHED");
                });
    }

    /**
     * Seed PUBLISHED skill：(a) skills row + acl_entries + status='PUBLISHED'；
     * (b) domain_events 寫 SkillCreated + SkillVersionPublished — sequences 1, 2，
     *     讓 SkillCommandService.loadAggregate 能 replay 出 status=PUBLISHED.
     */
    private String seedPublishedSkill(List<String> aclEntries) {
        var skillId = UUID.randomUUID().toString();
        var now = Instant.now();
        var name = "susp-ctrl-" + skillId.substring(0, 8);

        skillRepo.save(new SkillReadModel(
                skillId, name, "fixture", "owner", "Testing",
                "1.0.0", "LOW", "PUBLISHED", 0L, now, now, aclEntries));

        eventStore.save(new DomainEvent(
                UUID.randomUUID().toString(), skillId, "Skill", "SkillCreated",
                Map.of("name", name, "description", "fixture", "author", "owner", "category", "Testing"),
                1L, now, Map.of()));
        eventStore.save(new DomainEvent(
                UUID.randomUUID().toString(), skillId, "Skill", "SkillVersionPublished",
                Map.of("version", "1.0.0", "storagePath", "p", "fileSize", 0L),
                2L, now, Map.of()));
        return skillId;
    }

    /** Seed SUSPENDED skill — 在 PUBLISHED fixture 之上加 SkillSuspended event sequence=3。 */
    private String seedSuspendedSkill(List<String> aclEntries) {
        var skillId = seedPublishedSkill(aclEntries);
        var now = Instant.now();

        // 直接 UPDATE read model 為 SUSPENDED + 寫 SkillSuspended event（替代 service.suspend()
        // 走過 ACL gate 的依賴；本 fixture 為 setup 直設 state）
        skillRepo.updateStatus(skillId, "SUSPENDED", now);
        eventStore.save(new DomainEvent(
                UUID.randomUUID().toString(), skillId, "Skill", "SkillSuspended",
                Map.of("reason", "fixture", "suspendedBy", "fixture-admin"),
                3L, now, Map.of()));
        return skillId;
    }
}
