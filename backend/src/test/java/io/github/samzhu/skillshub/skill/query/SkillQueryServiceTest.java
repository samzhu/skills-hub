package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import tools.jackson.databind.ObjectMapper;

import io.github.samzhu.skillshub.shared.security.AclPrincipalExpander;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.skill.command.PublishVersionCommand;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersion;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import io.github.samzhu.skillshub.storage.StorageService;

/**
 * S142b T04 AC-S142b-1/2/3 — SkillQueryService.findById 6-field detail enrichment unit tests。
 */
@ExtendWith(MockitoExtension.class)
class SkillQueryServiceTest {

    @Mock SkillRepository skillRepo;
    @Mock SkillVersionRepository skillVersionRepo;
    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock StorageService storageService;
    @Mock ObjectMapper objectMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock CurrentUserProvider currentUserProvider;
    @Mock AclPrincipalExpander aclExpander;
    /** S154-T05: enrichAuthorIdentity 走 userRepo.findById；mock 預設返 empty → snapshot fallback path */
    @Mock io.github.samzhu.skillshub.shared.security.UserRepository userRepo;

    @InjectMocks
    SkillQueryService queryService;

    private static final String ID = "skill-abc";

    @Test
    @DisplayName("AC-S142b-1: PUBLISHED + riskLevel=LOW + 3 versions → verified=true + all 6 fields populated")
    @Tag("AC-S142b-1")
    void findById_publishedWithRiskLevel_returnsVerifiedTrueAndAllFields() {
        var skill = Skill.fromRow(ID, "test-skill", "desc", "alice", "devops",
                "1.2.0", "LOW", "PUBLISHED", 0L, Instant.now(), Instant.now(), List.of(), null);

        var frontmatter = Map.<String, Object>of(
                "license", "MIT",
                "compatibility", List.of("claude-code", "cursor"));
        var v1 = SkillVersion.publish(new PublishVersionCommand(ID, "1.2.0", "path", 100L, 2, frontmatter));
        var v2 = SkillVersion.publish(new PublishVersionCommand(ID, "1.1.0", "path", 100L, 2, Map.of()));
        var v3 = SkillVersion.publish(new PublishVersionCommand(ID, "1.0.0", "path", 100L, 2, Map.of()));

        when(skillRepo.findById(ID)).thenReturn(Optional.of(skill));
        when(skillVersionRepo.findBySkillIdOrderByPublishedAtDesc(ID)).thenReturn(List.of(v1, v2, v3));
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(0L);

        var result = queryService.findById(ID);

        assertThat(result.isVerified()).isTrue();
        assertThat(result.getLatestVersionPublishedAt()).isNotNull();
        assertThat(result.getLicense()).isEqualTo("MIT");
        assertThat(result.getCompatibility()).containsExactly("claude-code", "cursor");
        assertThat(result.getVersionCount()).isEqualTo(3L);
        assertThat(result.getOpenFlagCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("AC-S142b-2: DRAFT + riskLevel=null + 0 versions → verified=false + empty fields")
    @Tag("AC-S142b-2")
    void findById_draftNoVersions_returnsVerifiedFalseAndEmptyFields() {
        var skill = Skill.fromRow(ID, "test-skill", "desc", "alice", "devops",
                null, null, "DRAFT", 0L, Instant.now(), Instant.now(), List.of(), null);

        when(skillRepo.findById(ID)).thenReturn(Optional.of(skill));
        when(skillVersionRepo.findBySkillIdOrderByPublishedAtDesc(ID)).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(0L);

        var result = queryService.findById(ID);

        assertThat(result.isVerified()).isFalse();
        assertThat(result.getLatestVersionPublishedAt()).isNull();
        assertThat(result.getLicense()).isNull();
        assertThat(result.getCompatibility()).isEmpty();
        assertThat(result.getVersionCount()).isEqualTo(0L);
        assertThat(result.getOpenFlagCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("AC-S142b-3: SUSPENDED (status != PUBLISHED) → verified=false even with riskLevel")
    @Tag("AC-S142b-3")
    void findById_suspended_returnsVerifiedFalse() {
        var skill = Skill.fromRow(ID, "test-skill", "desc", "alice", "devops",
                "1.0.0", "LOW", "SUSPENDED", 0L, Instant.now(), Instant.now(), List.of(), null);

        var v1 = SkillVersion.publish(new PublishVersionCommand(ID, "1.0.0", "path", 100L, 2, Map.of()));

        when(skillRepo.findById(ID)).thenReturn(Optional.of(skill));
        when(skillVersionRepo.findBySkillIdOrderByPublishedAtDesc(ID)).thenReturn(List.of(v1));
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(0L);

        var result = queryService.findById(ID);

        assertThat(result.isVerified()).isFalse();
        assertThat(result.getVersionCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-S142b-1: openFlagCount reflects actual open flags count")
    @Tag("AC-S142b-1")
    void findById_withOpenFlags_returnsOpenFlagCount() {
        var skill = Skill.fromRow(ID, "test-skill", "desc", "alice", "devops",
                "1.0.0", "LOW", "PUBLISHED", 0L, Instant.now(), Instant.now(), List.of(), null);

        when(skillRepo.findById(ID)).thenReturn(Optional.of(skill));
        when(skillVersionRepo.findBySkillIdOrderByPublishedAtDesc(ID)).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(2L);

        var result = queryService.findById(ID);

        assertThat(result.getOpenFlagCount()).isEqualTo(2L);
    }
}
