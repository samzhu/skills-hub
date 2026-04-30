package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillStatus;

/**
 * S018 T4 — POST {@code /api/v1/skills/{id}/suspend} 與 {@code /reactivate} 端點安全行為驗證。
 *
 * <p>對應 spec §3 AC-12：admin（acl_entries 含 role:admin:suspend）→ 200；alice（無 verb）→ 403；
 * E2E 驗 aggregate state 更新（S024 ship 後 read 直接讀 skills 表）。
 *
 * <p>S024 T05B：seed 改直接 save Skill aggregate（取代 dual-write read-model + event store seed）；
 * audit event 斷言加 Awaitility wrap（AuditEventListener async 寫入 domain_events）。
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
    private SkillRepository skillRepo;

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

        // aggregate 狀態不變（command service 未被觸發）
        assertThat(skillRepo.findById(skillId).orElseThrow().getStatus()).isEqualTo(SkillStatus.PUBLISHED);
    }

    @Test
    @DisplayName("AC-12: admin (acl_entries 含 role:admin:suspend) POST /skills/{id}/suspend → 200 OK + state SUSPENDED")
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

        // SUSPENDED state 同 TX 寫 skills 表 — synchronous，無需 await
        assertThat(skillRepo.findById(skillId).orElseThrow().getStatus()).isEqualTo(SkillStatus.SUSPENDED);

        // S024 T05B: AuditEventListener async 寫 domain_events row — 用 Awaitility 等 SkillSuspended
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
                    assertThat(events).extracting(DomainEvent::eventType).contains("SkillSuspended");
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
    @DisplayName("AC-12: admin (acl_entries 含 role:admin:reactivate) POST /skills/{id}/reactivate → 200 OK + state PUBLISHED")
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

        // synchronous skills 表 state
        assertThat(skillRepo.findById(skillId).orElseThrow().getStatus()).isEqualTo(SkillStatus.PUBLISHED);

        // async audit row
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
                    assertThat(events).extracting(DomainEvent::eventType).contains("SkillReactivated");
                });
    }

    /**
     * Seed PUBLISHED Skill aggregate — 直接 save 含目標 acl_entries + status=PUBLISHED 的 row。
     * S024 T05B：取代 dual-write seed pattern（read-model + event store）為單純 aggregate save。
     */
    private String seedPublishedSkill(List<String> aclEntries) {
        var skillId = UUID.randomUUID().toString();
        var now = Instant.now();
        var name = "susp-ctrl-" + skillId.substring(0, 8);
        skillRepo.save(Skill.fromRow(
                skillId, name, "fixture", "owner", "Testing",
                "1.0.0", "LOW", "PUBLISHED", 0L, now, now, aclEntries, null));
        return skillId;
    }

    /** Seed SUSPENDED Skill aggregate — 直接 save status=SUSPENDED。 */
    private String seedSuspendedSkill(List<String> aclEntries) {
        var skillId = UUID.randomUUID().toString();
        var now = Instant.now();
        var name = "susp-ctrl-" + skillId.substring(0, 8);
        skillRepo.save(Skill.fromRow(
                skillId, name, "fixture", "owner", "Testing",
                "1.0.0", "LOW", "SUSPENDED", 0L, now, now, aclEntries, null));
        return skillId;
    }
}
