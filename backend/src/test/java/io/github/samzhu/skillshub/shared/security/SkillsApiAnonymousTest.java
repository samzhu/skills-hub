package io.github.samzhu.skillshub.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.query.BundleInfoQueryService;
import io.github.samzhu.skillshub.skill.query.SkillDiffQueryService;
import io.github.samzhu.skillshub.skill.query.SkillFileDiffService;
import io.github.samzhu.skillshub.skill.query.SkillQueryController;
import io.github.samzhu.skillshub.skill.query.SkillQueryService;

/**
 * AC-8：驗證 OAuth2 Resource Server starter 加入後，S001~S010 既有匿名 endpoint 仍可訪問。
 *
 * <p>遵循 CLAUDE.md「Feature First, Security Later」：本 spec 不應打斷既有 API 的匿名可達性。
 * SecurityConfig 用 {@code anyRequest().permitAll()} 兜底，確保只有 {@code /api/v1/me} 與
 * {@code /api/v1/admin/**} 受 JWT 驗證影響。
 *
 * <p>S025b T03 — extends {@link WebMvcSliceTestBase}：slice 載 SkillQueryController + mock
 * SkillQueryService；驗 OAuth filter chain 對 anonymous /api/v1/skills 不擋。
 */
@WebMvcTest(SkillQueryController.class)
class SkillsApiAnonymousTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SkillQueryService skillQueryService;

    // S098a3-2 ship 後 SkillQueryController ctor 多了 BundleInfoQueryService dep；slice 不掃 @Service 須顯式宣告
    @MockitoBean
    private BundleInfoQueryService bundleInfoQueryService;

    // S142b ship 後 SkillQueryController ctor 多了 SkillDiffQueryService + SkillFileDiffService deps
    @MockitoBean
    private SkillDiffQueryService skillDiffQueryService;

    @MockitoBean
    private SkillFileDiffService skillFileDiffService;

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: GET /api/v1/skills 無 token → 200 (S001 行為保留)")
    void skillsApi_withoutJwt_returns200() throws Exception {
        // mock service 回空 page 即可，本 test 只驗 SecurityConfig permitAll 鏈路不擋
        Page<Skill> emptyPage = new PageImpl<>(java.util.List.of());
        Mockito.when(skillQueryService.search(
                        ArgumentMatchers.isNull(), ArgumentMatchers.isNull(),
                        ArgumentMatchers.isNull(), ArgumentMatchers.any())).thenReturn(emptyPage);

        mockMvc.perform(get("/api/v1/skills"))
            .andExpect(status().isOk());
    }
}
