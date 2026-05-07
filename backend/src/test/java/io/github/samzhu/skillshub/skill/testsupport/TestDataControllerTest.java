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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
 * <p>{@link CacheStubConfig} stub {@link CacheManager} — {@code SkillshubApplication} 標
 * {@code @EnableCaching}，slice 不載 {@code CacheAutoConfiguration} 會在
 * {@code CacheAspectSupport.afterSingletonsInstantiated} 找不到 CacheManager 而 fail context；
 * 提供 {@link ConcurrentMapCacheManager} 滿足契約即可（slice 不驗 cache 行為）。
 */
@WebMvcTest(TestDataController.class)
@ActiveProfiles({"test", "e2e"})
@Import(TestDataControllerTest.CacheStubConfig.class)
class TestDataControllerTest extends WebMvcSliceTestBase {

    @TestConfiguration
    static class CacheStubConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }
    }

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
            .andExpect(jsonPath("$.tablesCleared[?(@ == 'vector_store')]").exists())
            .andExpect(jsonPath("$.tablesCleared[?(@ == 'download_events')]").exists())
            .andExpect(jsonPath("$.tablesCleared[?(@ == 'domain_events')]").exists())
            .andExpect(jsonPath("$.tablesCleared[?(@ == 'event_publication')]").exists());

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), any(Map.class));
        var sql = sqlCaptor.getValue();
        assertThat(sql).startsWith("TRUNCATE TABLE")
            .contains("skills", "skill_versions", "vector_store",
                      "download_events", "domain_events", "event_publication")
            .contains("CASCADE");
    }

    @Test
    @DisplayName("POST /internal/test/seed/skill → delegate to SkillCommandService.uploadSkill + 回傳 id")
    void seedSkill_invokesUploadSkill() throws Exception {
        var fakeId = "skill-uuid-001";
        var fakeZip = new byte[]{1, 2, 3};
        when(packageService.normalizeToZip(any(byte[].class))).thenReturn(fakeZip);
        when(skillCommandService.uploadSkill(any(byte[].class), anyString(), anyString(),
                anyString(), eq(Visibility.PUBLIC)))
            .thenReturn(fakeId);

        var body = """
                {
                    "name": "docker-helper",
                    "description": "Docker compose helper skill",
                    "author": "alice",
                    "category": "DevOps",
                    "version": "1.0.0"
                }
                """;

        mockMvc.perform(post("/internal/test/seed/skill")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(fakeId));

        verify(skillCommandService, times(1)).uploadSkill(
            any(byte[].class), eq("1.0.0"), eq("alice"), eq("DevOps"), eq(Visibility.PUBLIC));
    }

    @Test
    @DisplayName("POST /internal/test/seed/skill version null → defaults 1.0.0；visibility null → PUBLIC")
    void seedSkill_appliesDefaults() throws Exception {
        when(packageService.normalizeToZip(any(byte[].class))).thenReturn(new byte[]{0});
        when(skillCommandService.uploadSkill(any(byte[].class), anyString(), anyString(),
                anyString(), eq(Visibility.PUBLIC)))
            .thenReturn("id-002");

        var body = """
                {
                    "name": "test-skill",
                    "description": "desc",
                    "author": "bob",
                    "category": "Testing"
                }
                """;

        mockMvc.perform(post("/internal/test/seed/skill")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());

        verify(skillCommandService).uploadSkill(
            any(byte[].class), eq("1.0.0"), eq("bob"), eq("Testing"), eq(Visibility.PUBLIC));
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
