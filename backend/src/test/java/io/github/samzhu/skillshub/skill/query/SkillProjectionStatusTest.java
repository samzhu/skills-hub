package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.shared.events.TestEventTxHelper;
import io.github.samzhu.skillshub.skill.command.CreateSkillCommand;
import io.github.samzhu.skillshub.skill.command.PublishVersionCommand;
import io.github.samzhu.skillshub.skill.command.ReactivateCommand;
import io.github.samzhu.skillshub.skill.command.SkillCommandService;
import io.github.samzhu.skillshub.skill.command.SuspendCommand;
import io.github.samzhu.skillshub.skill.domain.SkillReactivatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillSuspendedEvent;

/**
 * S018 T3 — SkillProjection 對 status transition 的 read-side 反映 + AC-11 sequence 連續性。
 *
 * <p>對應 spec §3 AC-1/2/3/11：
 * <ul>
 *   <li>AC-1: SkillCreated → status='DRAFT'（既有；不退步）</li>
 *   <li>AC-2: 首版 SkillVersionPublished → status='PUBLISHED'（修 BUG）</li>
 *   <li>AC-3: 後續發版不改 status（idempotent）</li>
 *   <li>AC-11: createSkill / uploadSkill 流程走 aggregate.nextSequence() — 連續 1, 2, ...</li>
 *   <li>新 listener: SkillSuspended → SUSPENDED；SkillReactivated → PUBLISHED</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SkillProjectionStatusTest {

    @Autowired
    private SkillCommandService commandService;

    @Autowired
    private SkillReadModelRepository skillRepo;

    @Autowired
    private DomainEventRepository eventStore;

    @Autowired
    private TestEventTxHelper txHelper;

    @Test
    @DisplayName("AC-1: SkillCreated → read model status='DRAFT'（既有；不退步）")
    @Tag("AC-1")
    void created_statusDraft() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("status-draft-" + uniqueSuffix(),
                        "DRAFT fixture", "owner", "Testing"));

        // SkillCreatedEvent listener (SkillProjection.on) 仍是 sync @EventListener，
        // 在 createSkill TX 內就完成；無需 Awaitility
        var readModel = skillRepo.findById(skillId).orElseThrow();
        assertThat(readModel.status()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("AC-2: 首版 publishVersion → read model status 從 DRAFT 轉為 PUBLISHED（修 BUG）")
    @Tag("AC-2")
    void firstVersion_statusPublished() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("status-pub-" + uniqueSuffix(),
                        "PUBLISHED fixture", "owner", "Testing"));

        commandService.publishVersion(new PublishVersionCommand(
                skillId, "1.0.0", "gs://b/p", 0L, Map.of()));

        // SkillVersionPublished 的 SkillProjection.on 仍是 sync @EventListener（FK target row 創建者）
        // 在 publishVersion TX 內就完成 status 更新；無需 Awaitility
        var readModel = skillRepo.findById(skillId).orElseThrow();
        assertThat(readModel.status()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("AC-3: 後續發版 status 維持 PUBLISHED（idempotent）")
    @Tag("AC-3")
    void laterVersions_statusUnchanged() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("status-multi-" + uniqueSuffix(),
                        "Multi-version fixture", "owner", "Testing"));
        commandService.publishVersion(new PublishVersionCommand(
                skillId, "1.0.0", "gs://b/p1", 0L, Map.of()));
        commandService.publishVersion(new PublishVersionCommand(
                skillId, "1.1.0", "gs://b/p2", 0L, Map.of()));
        commandService.publishVersion(new PublishVersionCommand(
                skillId, "1.2.0", "gs://b/p3", 0L, Map.of()));

        var readModel = skillRepo.findById(skillId).orElseThrow();
        assertThat(readModel.status()).isEqualTo("PUBLISHED");
        assertThat(readModel.latestVersion()).isEqualTo("1.2.0");
    }

    @Test
    @DisplayName("AC-11: createSkill 流程 sequence=1（無 hardcoded）")
    @Tag("AC-11")
    void createSkill_sequenceOne() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("seq-create-" + uniqueSuffix(),
                        "...", "owner", "Testing"));

        var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).sequence()).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-11: 後續事件 sequence 連續（create→publishVersion→suspend→reactivate = 1,2,3,4）")
    @Tag("AC-11")
    void chain_sequenceConsecutive() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("seq-chain-" + uniqueSuffix(),
                        "...", "owner", "Testing"));
        commandService.publishVersion(new PublishVersionCommand(
                skillId, "1.0.0", "gs://b/p", 0L, Map.of()));
        commandService.suspend(new SuspendCommand(skillId, "violation", "admin"));
        commandService.reactivate(new ReactivateCommand(skillId, "review approved"));

        var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
        assertThat(events).hasSize(4);
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).sequence())
                    .as("event idx %d sequence", i)
                    .isEqualTo((long) (i + 1));
        }
    }

    @Test
    @DisplayName("New: SkillSuspendedEvent → read model status='SUSPENDED'")
    @Tag("AC-4")
    void suspendedEvent_updatesReadModel() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("susp-listener-" + uniqueSuffix(),
                        "...", "owner", "Testing"));
        commandService.publishVersion(new PublishVersionCommand(
                skillId, "1.0.0", "gs://b/p", 0L, Map.of()));

        txHelper.publishInTx(new SkillSuspendedEvent(
                skillId, "policy violation", "admin"));

        // SkillSuspendedEvent 的 SkillProjection.on 改 @ApplicationModuleListener async；用 Awaitility 等
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var readModel = skillRepo.findById(skillId).orElseThrow();
            assertThat(readModel.status()).isEqualTo("SUSPENDED");
        });
    }

    @Test
    @DisplayName("New: SkillReactivatedEvent → read model status='PUBLISHED'")
    @Tag("AC-7")
    void reactivatedEvent_updatesReadModel() {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("reac-listener-" + uniqueSuffix(),
                        "...", "owner", "Testing"));
        commandService.publishVersion(new PublishVersionCommand(
                skillId, "1.0.0", "gs://b/p", 0L, Map.of()));
        txHelper.publishInTx(new SkillSuspendedEvent(skillId, "v", "admin"));
        // 等 SUSPEND 先寫入再 reactivate（async 順序保證）
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(skillRepo.findById(skillId).orElseThrow().status()).isEqualTo("SUSPENDED"));

        txHelper.publishInTx(new SkillReactivatedEvent(skillId, "manual review approved"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var readModel = skillRepo.findById(skillId).orElseThrow();
            assertThat(readModel.status()).isEqualTo("PUBLISHED");
        });
    }

    private static String uniqueSuffix() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
