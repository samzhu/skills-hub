package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersion;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;

/**
 * S024 T3 — SkillVersionRepository derived query + DB UNIQUE constraint 整合測試。
 *
 * <p>覆蓋：
 * <ul>
 *   <li>AC-5 — existsBySkillIdAndVersion / findBySkillIdOrderByPublishedAtDesc /
 *       findBySkillIdAndVersion derived queries 行為</li>
 *   <li>AC-5 — hasRiskAssessmentFromEvent {@code @Query} JSONB 查詢</li>
 *   <li>AC-7 partial — DB UNIQUE (skill_id, version) constraint 兜底（bypass service 預檢場景）</li>
 *   <li>AC-5 — attachRiskAssessment + skillVersionRepo.save UPDATE risk_assessment column</li>
 * </ul>
 *
 * <p>S025b T02 — extends {@link RepositorySliceTestBase}；{@link S024CrossAggregateSaveHelper}
 * 為 {@code @Component}（test source root），slice 不掃 {@code @Component}/{@code @Service}
 * stereotype，需顯式 {@code @Import}。
 */
@Import(S024CrossAggregateSaveHelper.class)
class SkillVersionRepositoryTest extends RepositorySliceTestBase {

    @Autowired SkillRepository skillRepo;
    @Autowired SkillVersionRepository skillVersionRepo;
    @Autowired S024CrossAggregateSaveHelper helper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        jdbc.update("DELETE FROM event_publication");
        jdbc.update("DELETE FROM skill_versions");
        jdbc.update("DELETE FROM skills");
        jdbc.update("DELETE FROM domain_events");
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: existsBySkillIdAndVersion 命中與不命中")
    void existsBySkillIdAndVersion() {
        var skill = Skill.create(new CreateSkillCommand("exists-test", "desc", "alice", "DevOps"));
        skill.recordVersionPublished("1.0.0");
        var sv = SkillVersion.publish(new PublishVersionCommand(
                skill.getId(), "1.0.0", "gs://b/exists/1.0.0.zip", 100, Map.of()));

        helper.saveCrossAggregate(skill, sv);

        assertThat(skillVersionRepo.existsBySkillIdAndVersion(skill.getId(), "1.0.0")).isTrue();
        assertThat(skillVersionRepo.existsBySkillIdAndVersion(skill.getId(), "1.1.0")).isFalse();
        assertThat(skillVersionRepo.existsBySkillIdAndVersion("non-existent-skill", "1.0.0")).isFalse();
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: findBySkillIdOrderByPublishedAtDesc 依 publishedAt 降序")
    void findBySkillIdOrderByPublishedAtDesc() throws InterruptedException {
        var skill = Skill.create(new CreateSkillCommand("desc-order", "desc", "alice", "DevOps"));
        helper.save(skill);

        var sv1 = SkillVersion.publish(new PublishVersionCommand(
                skill.getId(), "1.0.0", "gs://b/o/1.0.0.zip", 100, Map.of()));
        helper.save(skill);   // no-op for skill
        helper.saveVersionOnly(sv1);
        Thread.sleep(20);   // 確保 publishedAt 嚴格遞增

        var sv2 = SkillVersion.publish(new PublishVersionCommand(
                skill.getId(), "1.1.0", "gs://b/o/1.1.0.zip", 100, Map.of()));
        helper.saveVersionOnly(sv2);
        Thread.sleep(20);

        var sv3 = SkillVersion.publish(new PublishVersionCommand(
                skill.getId(), "2.0.0", "gs://b/o/2.0.0.zip", 100, Map.of()));
        helper.saveVersionOnly(sv3);

        var versions = skillVersionRepo.findBySkillIdOrderByPublishedAtDesc(skill.getId());

        assertThat(versions).hasSize(3);
        assertThat(versions.get(0).getVersion()).isEqualTo("2.0.0");
        assertThat(versions.get(1).getVersion()).isEqualTo("1.1.0");
        assertThat(versions.get(2).getVersion()).isEqualTo("1.0.0");
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: findBySkillIdAndVersion 命中與不命中")
    void findBySkillIdAndVersion() {
        var skill = Skill.create(new CreateSkillCommand("find-test", "desc", "alice", "DevOps"));
        skill.recordVersionPublished("1.0.0");
        var sv = SkillVersion.publish(new PublishVersionCommand(
                skill.getId(), "1.0.0", "gs://b/find/1.0.0.zip", 100, Map.of()));
        helper.saveCrossAggregate(skill, sv);

        var found = skillVersionRepo.findBySkillIdAndVersion(skill.getId(), "1.0.0");
        assertThat(found).isPresent();
        assertThat(found.get().getStoragePath()).isEqualTo("gs://b/find/1.0.0.zip");

        var notFound = skillVersionRepo.findBySkillIdAndVersion(skill.getId(), "9.9.9");
        assertThat(notFound).isEmpty();
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7 partial: DB UNIQUE (skill_id, version) constraint 兜底重複寫入")
    void uniqueConstraintRejectsDuplicateInsert() {
        var skill = Skill.create(new CreateSkillCommand("uniq-test", "desc", "alice", "DevOps"));
        skill.recordVersionPublished("1.0.0");
        var sv1 = SkillVersion.publish(new PublishVersionCommand(
                skill.getId(), "1.0.0", "gs://b/uniq/1.0.0.zip", 100, Map.of()));
        helper.saveCrossAggregate(skill, sv1);

        // 第二筆 SkillVersion 相同 (skill_id, version) — 預期 DB UNIQUE 兜底
        var sv2 = SkillVersion.publish(new PublishVersionCommand(
                skill.getId(), "1.0.0", "gs://b/uniq/1.0.0-dup.zip", 200, Map.of()));

        assertThatThrownBy(() -> helper.saveVersionOnly(sv2))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 仍只有 1 筆
        var versions = skillVersionRepo.findBySkillIdOrderByPublishedAtDesc(skill.getId());
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).getStoragePath()).isEqualTo("gs://b/uniq/1.0.0.zip");
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: attachRiskAssessment + save UPDATE risk_assessment column")
    void attachRiskAssessmentUpdatesColumn() {
        var skill = Skill.create(new CreateSkillCommand("risk-test", "desc", "alice", "DevOps"));
        skill.recordVersionPublished("1.0.0");
        var sv = SkillVersion.publish(new PublishVersionCommand(
                skill.getId(), "1.0.0", "gs://b/risk/1.0.0.zip", 100, Map.of()));
        helper.saveCrossAggregate(skill, sv);

        // 重新 load → mutate → save（測試 UPDATE path；@PersistenceCreator 載入 isNew=false）
        var loaded = skillVersionRepo.findBySkillIdAndVersion(skill.getId(), "1.0.0").orElseThrow();
        var sourceEventId = UUID.randomUUID().toString();
        var assessment = Map.<String, Object>of(
                "level", "MEDIUM",
                "findings", java.util.List.of(),
                "sourceEventId", sourceEventId,
                "scannedAt", "2026-04-29T12:00:00Z");
        helper.attachRiskAssessmentAndSave(loaded, assessment);

        var reloaded = skillVersionRepo.findBySkillIdAndVersion(skill.getId(), "1.0.0").orElseThrow();
        assertThat(reloaded.getRiskAssessment()).isNotNull();
        assertThat(reloaded.getRiskAssessment()).containsEntry("level", "MEDIUM");
        assertThat(reloaded.getRiskAssessment()).containsEntry("sourceEventId", sourceEventId);
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: hasRiskAssessmentFromEvent JSONB query 命中與不命中")
    void hasRiskAssessmentFromEvent() {
        var skill = Skill.create(new CreateSkillCommand("idem-test", "desc", "alice", "DevOps"));
        skill.recordVersionPublished("1.0.0");
        var sv = SkillVersion.publish(new PublishVersionCommand(
                skill.getId(), "1.0.0", "gs://b/idem/1.0.0.zip", 100, Map.of()));
        helper.saveCrossAggregate(skill, sv);

        // 尚未 attachRiskAssessment（risk_assessment IS NULL）→ 任何 sourceEventId 查詢皆 false
        assertThat(skillVersionRepo.hasRiskAssessmentFromEvent(
                skill.getId(), "1.0.0", "any-uuid")).isFalse();

        // 寫入後 → 對應 sourceEventId 查詢 true，其他 sourceEventId 查詢 false
        var sourceEventId = UUID.randomUUID().toString();
        var loaded = skillVersionRepo.findBySkillIdAndVersion(skill.getId(), "1.0.0").orElseThrow();
        helper.attachRiskAssessmentAndSave(loaded, Map.of(
                "level", "LOW", "sourceEventId", sourceEventId, "findings", java.util.List.of()));

        assertThat(skillVersionRepo.hasRiskAssessmentFromEvent(
                skill.getId(), "1.0.0", sourceEventId)).isTrue();
        assertThat(skillVersionRepo.hasRiskAssessmentFromEvent(
                skill.getId(), "1.0.0", "different-source")).isFalse();
        assertThat(skillVersionRepo.hasRiskAssessmentFromEvent(
                skill.getId(), "9.9.9", sourceEventId)).isFalse();
    }
}
