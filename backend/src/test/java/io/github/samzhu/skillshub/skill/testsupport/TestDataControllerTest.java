package io.github.samzhu.skillshub.skill.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;
import io.github.samzhu.skillshub.skill.command.SkillCommandService;
import io.github.samzhu.skillshub.skill.domain.Visibility;
import io.github.samzhu.skillshub.storage.PackageService;

/**
 * S140 T01 — {@link TestDataController} WebMvc slice。
 *
 * <p>{@code @ActiveProfiles({"test","e2e"})} 啟用 e2e profile 讓 controller bean 註冊
 * （{@code @Profile({"local","dev","e2e"})} 守門通過）。Slice 只掃 controller，service /
 * jdbc 由 {@code @MockitoBean} mock。
 *
 * <p>S148e: 既有本地 {@code CacheStubConfig} 已移除 — {@link WebMvcSliceTestBase.AotStubBeans}
 * 提供同型 {@code ConcurrentMapCacheManager} stub；雙重定義在 AOT 階段觸發
 * {@code BeanDefinitionOverrideException}。
 */
@WebMvcTest(TestDataController.class)
@ActiveProfiles({"test", "e2e"})
class TestDataControllerTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SkillCommandService skillCommandService;

    @MockitoBean
    private PackageService packageService;

    @MockitoBean
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @DisplayName("POST /internal/test/reset → 200，single TRUNCATE 涵蓋 allowlist 全表")
    void reset_truncatesAllowlistTables() throws Exception {
        mockMvc.perform(post("/internal/test/reset"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tablesCleared").isArray())
            .andExpect(jsonPath("$.tablesCleared[?(@ == 'skills')]").exists())
            .andExpect(jsonPath("$.tablesCleared[?(@ == 'download_events')]").exists())
            .andExpect(jsonPath("$.tablesCleared[?(@ == 'domain_events')]").exists())
            .andExpect(jsonPath("$.tablesCleared[?(@ == 'event_publication')]").exists());

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), any(Map.class));
        var sql = sqlCaptor.getValue();
        assertThat(sql).startsWith("TRUNCATE TABLE")
            .contains("skills", "skill_versions",
                      "download_events", "domain_events", "event_publication")
            .contains("CASCADE");
    }

    @Test
    @DisplayName("POST /internal/test/seed/skill → seed author display row + delegate uploadSkill + 回傳 id")
    void seedSkill_invokesUploadSkill() throws Exception {
        var fakeId = "skill-uuid-001";
        var fakeZip = new byte[]{1, 2, 3};
        when(packageService.normalizeToZip(any(byte[].class))).thenReturn(fakeZip);
        when(skillCommandService.uploadSkill(any(byte[].class), anyString(), anyString(),
                anyString(), anyString(), eq(Visibility.PUBLIC), any()))
            .thenReturn(fakeId);

        var body = """
                {
                    "name": "docker-helper",
                    "description": "Docker compose helper skill",
                    "author": "alice",
                    "authorDisplayName": "Alice",
                    "authorHandle": "alice",
                    "authorEmail": "alice@example.test",
                    "category": "devops",
                    "version": "1.0.0"
                }
                """;

        mockMvc.perform(post("/internal/test/seed/skill")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(fakeId));

        verify(skillCommandService, times(1)).uploadSkill(
            any(byte[].class), eq("docker-helper"), eq("1.0.0"),
            eq("alice"), eq("devops"), eq(Visibility.PUBLIC), eq("Alice"));

        verify(jdbc).update(anyString(), any(Map.class));
    }

    @Test
    @DisplayName("POST /internal/test/seed/skill version null → defaults 1.0.0；visibility null → PUBLIC")
    void seedSkill_appliesDefaults() throws Exception {
        when(packageService.normalizeToZip(any(byte[].class))).thenReturn(new byte[]{0});
        when(skillCommandService.uploadSkill(any(byte[].class), anyString(), anyString(),
                anyString(), anyString(), eq(Visibility.PUBLIC), any()))
            .thenReturn("id-002");

        var body = """
                {
                    "name": "test-skill",
                    "description": "desc",
                    "author": "bob",
                    "category": "testing"
                }
                """;

        mockMvc.perform(post("/internal/test/seed/skill")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());

        verify(skillCommandService).uploadSkill(
            any(byte[].class), eq("test-skill"), eq("1.0.0"),
            eq("bob"), eq("testing"), eq(Visibility.PUBLIC), eq(null));
    }

    @Test
    @DisplayName("POST /internal/test/seed/download-event → INSERT N rows，回傳 count")
    void seedDownloadEvent_insertsRowsAndReturnsCount() throws Exception {
        var body = """
                {
                    "skillId": "skill-001",
                    "count": 5,
                    "daysAgo": 7
                }
                """;

        mockMvc.perform(post("/internal/test/seed/download-event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(5));

        verify(jdbc, atLeast(5)).update(anyString(), any(Map.class));
    }
}
