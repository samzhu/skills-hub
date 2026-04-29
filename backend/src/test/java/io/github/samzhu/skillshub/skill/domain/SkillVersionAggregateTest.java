package io.github.samzhu.skillshub.skill.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.AbstractAggregateRoot;

import io.github.samzhu.skillshub.skill.command.PublishVersionCommand;

/**
 * S024 T3 — SkillVersion aggregate unit test（純 unit；無 Spring；快速）。
 *
 * <p>覆蓋：
 * <ul>
 *   <li>AC-5 partial — {@link SkillVersion#publish(PublishVersionCommand)} factory 設 state +
 *       register {@link SkillVersionPublishedEvent}</li>
 *   <li>AC-5 partial — {@link SkillVersion#attachRiskAssessment(Map)} mutate state + register
 *       {@link SkillRiskAssessedEvent}</li>
 *   <li>{@link org.springframework.data.domain.Persistable#isNew()} — factory 出來 isNew=true；
 *       attachRiskAssessment 不改 isNew flag（仍 true 直到 repo.save 後刷回 false）</li>
 * </ul>
 *
 * <p>跨 aggregate 行為（同 TX 內 skillRepo.save + skillVersionRepo.save）由 T1
 * {@code SkillCommandServiceCrossAggregateTest} 涵蓋。
 */
class SkillVersionAggregateTest {

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5 partial: SkillVersion.publish(...) factory 設 state + register SkillVersionPublishedEvent")
    void publishFactorySetsStateAndRegistersEvent() {
        var cmd = new PublishVersionCommand(
                "skill-uuid-1", "1.0.0", "gs://bucket/skill-uuid-1/1.0.0.zip", 1024,
                Map.of("name", "test-skill", "description", "desc",
                        "allowed-tools", "Bash Edit Read"));

        var sv = SkillVersion.publish(cmd);

        assertThat(sv.getId()).isNotBlank();
        assertThat(sv.getSkillId()).isEqualTo("skill-uuid-1");
        assertThat(sv.getVersion()).isEqualTo("1.0.0");
        assertThat(sv.getStoragePath()).isEqualTo("gs://bucket/skill-uuid-1/1.0.0.zip");
        assertThat(sv.getFileSize()).isEqualTo(1024);
        assertThat(sv.getFrontmatter()).containsEntry("name", "test-skill");
        assertThat(sv.getRiskAssessment()).isNull();   // initial null until ScanOrchestrator 寫入
        assertThat(sv.getPublishedAt()).isNotNull();
        assertThat(sv.getAllowedTools()).containsExactly("Bash", "Edit", "Read");
        assertThat(sv.isNew()).isTrue();   // Persistable: factory 出來 isNew=true → INSERT path

        Collection<Object> events = retrieveDomainEvents(sv);
        assertThat(events).hasSize(1);
        assertThat(events.iterator().next()).isInstanceOf(SkillVersionPublishedEvent.class);
        var event = (SkillVersionPublishedEvent) events.iterator().next();
        assertThat(event.aggregateId()).isEqualTo("skill-uuid-1");   // 注意：record 的 aggregateId field 對應 skillId
        assertThat(event.version()).isEqualTo("1.0.0");
        assertThat(event.sourceEventId()).isNotBlank();   // factory 自動產 UUID
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5 partial: publish(null frontmatter) → frontmatter 為空 Map + allowedTools 為空 list")
    void publishWithNullFrontmatter() {
        var cmd = new PublishVersionCommand(
                "skill-uuid-2", "1.0.0", "gs://b/s/1.0.0.zip", 100, null);

        var sv = SkillVersion.publish(cmd);

        assertThat(sv.getFrontmatter()).isEmpty();
        assertThat(sv.getAllowedTools()).isEmpty();
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5 partial: attachRiskAssessment mutate riskAssessment + register SkillRiskAssessedEvent")
    void attachRiskAssessmentMutatesStateAndRegistersEvent() {
        var sv = SkillVersion.publish(new PublishVersionCommand(
                "skill-1", "1.0.0", "gs://b/s/1.0.0.zip", 100, Map.of()));
        clearDomainEvents(sv);   // 清 publish event

        var assessment = Map.<String, Object>of(
                "level", "HIGH",
                "findings", List.of("finding-1", "finding-2"),
                "sourceEventId", "src-event-uuid",
                "scannedAt", "2026-04-29T12:00:00Z");

        sv.attachRiskAssessment(assessment);

        assertThat(sv.getRiskAssessment()).isEqualTo(assessment);

        Collection<Object> events = retrieveDomainEvents(sv);
        assertThat(events).hasSize(1);
        assertThat(events.iterator().next()).isInstanceOf(SkillRiskAssessedEvent.class);
        var event = (SkillRiskAssessedEvent) events.iterator().next();
        assertThat(event.skillId()).isEqualTo("skill-1");
        assertThat(event.version()).isEqualTo("1.0.0");
        assertThat(event.level()).isEqualTo("HIGH");
        assertThat(event.findings()).isEqualTo(List.of("finding-1", "finding-2"));
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5 partial: allowedTools 解析 — space-separated string 切割正確")
    void allowedToolsParsing() {
        var cmd = new PublishVersionCommand(
                "skill-3", "1.0.0", "gs://b/s/1.0.0.zip", 100,
                Map.of("allowed-tools", "Bash(git:*) Edit Read WebFetch"));

        var sv = SkillVersion.publish(cmd);

        assertThat(sv.getAllowedTools()).containsExactly("Bash(git:*)", "Edit", "Read", "WebFetch");
    }

    // ============================================================================
    // Reflection helpers
    // ============================================================================

    @SuppressWarnings("unchecked")
    private static Collection<Object> retrieveDomainEvents(SkillVersion sv) {
        try {
            var method = AbstractAggregateRoot.class.getDeclaredMethod("domainEvents");
            method.setAccessible(true);
            return (Collection<Object>) method.invoke(sv);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("Failed to invoke domainEvents() via reflection", e);
        }
    }

    private static void clearDomainEvents(SkillVersion sv) {
        try {
            var method = AbstractAggregateRoot.class.getDeclaredMethod("clearDomainEvents");
            method.setAccessible(true);
            method.invoke(sv);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("Failed to invoke clearDomainEvents() via reflection", e);
        }
    }
}
