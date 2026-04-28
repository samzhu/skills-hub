package io.github.samzhu.skillshub.skill.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * S018 T1 — SkillStatus enum transition methods（pure unit；無 Spring Context）。
 *
 * <p>對應 spec §3 AC-5/6/8/9：state machine 不變量由 enum guard 集中表達；
 * 違規 transition 拋 IllegalStateException 並由 aggregate 端 propagate。
 */
class SkillStatusTest {

    @Test
    @DisplayName("AC-5/8: DRAFT.publish() → PUBLISHED；DRAFT.suspend()/.reactivate() throws")
    @Tag("AC-5")
    void draftTransitions() {
        assertThat(SkillStatus.DRAFT.publish()).isEqualTo(SkillStatus.PUBLISHED);
        assertThatThrownBy(() -> SkillStatus.DRAFT.suspend())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT");
        assertThatThrownBy(() -> SkillStatus.DRAFT.reactivate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    @DisplayName("AC-3/6: PUBLISHED.publish() → PUBLISHED（idempotent）；PUBLISHED.suspend() → SUSPENDED；PUBLISHED.reactivate() throws")
    @Tag("AC-3")
    @Tag("AC-6")
    void publishedTransitions() {
        // publish() idempotent — 後續發版不重複改 status
        assertThat(SkillStatus.PUBLISHED.publish()).isEqualTo(SkillStatus.PUBLISHED);
        assertThat(SkillStatus.PUBLISHED.suspend()).isEqualTo(SkillStatus.SUSPENDED);
        assertThatThrownBy(() -> SkillStatus.PUBLISHED.reactivate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLISHED");
    }

    @Test
    @DisplayName("AC-6/8/9: SUSPENDED.suspend()/.publish() throws；SUSPENDED.reactivate() → PUBLISHED")
    @Tag("AC-6")
    @Tag("AC-8")
    @Tag("AC-9")
    void suspendedTransitions() {
        assertThatThrownBy(() -> SkillStatus.SUSPENDED.suspend())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUSPENDED");
        assertThatThrownBy(() -> SkillStatus.SUSPENDED.publish())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUSPENDED");
        assertThat(SkillStatus.SUSPENDED.reactivate()).isEqualTo(SkillStatus.PUBLISHED);
    }
}
