package io.github.samzhu.skillshub.score;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.samzhu.skillshub.security.SecurityCategoryMapper;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * S142b T03 — SkillScoreCalculator pure computation tests (AC-S142b-4 / AC-S142b-5).
 */
@ExtendWith(MockitoExtension.class)
class SkillScoreCalculatorTest {

    @Mock SkillVersionRepository versionRepo;
    @Mock SecurityCategoryMapper securityMapper;
    @Mock ObjectMapper objectMapper;

    @InjectMocks
    SkillScoreCalculator calculator;

    @Test
    void compute_qualityTotal92_security100_returns95() {
        // AC-S142b-4: round(0.6×92 + 0.4×100) = round(55.2 + 40) = 95
        assertThat(calculator.compute(92, 100)).isEqualTo(95);
    }

    @Test
    void compute_qualityTotal85_security100_returns91() {
        // round(0.6×85 + 0.4×100) = round(51 + 40) = 91
        assertThat(calculator.compute(85, 100)).isEqualTo(91);
    }

    @Test
    void compute_qualityTotal92_security75_1warn_returns85() {
        // round(0.6×92 + 0.4×75) = round(55.2 + 30) = 85
        assertThat(calculator.compute(92, 75)).isEqualTo(85);
    }

    @Test
    void compute_securityNull_returnsNull() {
        // AC-S142b-5: security not scanned → skillScore null
        assertThat(calculator.compute(92, null)).isNull();
    }

    @Test
    void compute_zeroQuality_zeroSecurity_returnsZero() {
        assertThat(calculator.compute(0, 0)).isEqualTo(0);
    }

    @Test
    void compute_perfectScore_returns100() {
        // round(0.6×100 + 0.4×100) = 100
        assertThat(calculator.compute(100, 100)).isEqualTo(100);
    }
}
