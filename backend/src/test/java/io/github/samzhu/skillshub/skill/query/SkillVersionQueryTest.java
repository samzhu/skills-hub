package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.shared.security.AclPrincipalExpander;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.skill.command.CreateSkillCommand;
import io.github.samzhu.skillshub.skill.command.PublishVersionCommand;
import io.github.samzhu.skillshub.skill.command.SkillCommandService;
import io.github.samzhu.skillshub.skill.validation.SkillValidator;
import io.github.samzhu.skillshub.storage.PackageService;

/**
 * S025b T04 demote — 從 {@code @SpringBootTest(RANDOM_PORT) + TestRestTemplate + LAB mode}
 * 改 {@link RepositorySliceTestBase}（{@code @DataJdbcTest} slice）。原 HTTP-bound
 * upload + PUT version assertion 已被 {@link io.github.samzhu.skillshub.S016EndToEndSmokeTest}
 * E2E 涵蓋；本 test 收斂為 {@link SkillQueryService#findVersionsBySkillId} 純 SQL 邏輯
 * 驗證（version sort by publishedAt DESC + storagePath/fileSize 投影完整性）。
 *
 * <p>Seed 直接走 {@link SkillCommandService#publishVersion} 避開 multipart 上傳與 storage
 * 行為（已由 S016 e2e 覆蓋）；publishedAt 由 {@code SkillVersion.publish} 內 {@code Instant.now()}
 * 自動產生 — 兩次 publish 之間需 1ms 才能保證 sort 穩定。
 */
@Import({SkillQueryService.class, SkillCommandService.class, PackageService.class, SkillValidator.class})
class SkillVersionQueryTest extends RepositorySliceTestBase {

    @Autowired
    private SkillQueryService queryService;

    @Autowired
    private SkillCommandService commandService;

    // SkillQueryService ctor param 6+7 — S025b 後加入，slice 不掃 @Component 須顯式 mock
    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private AclPrincipalExpander aclPrincipalExpander;

    @Test
    @DisplayName("AC-5: 取得版本歷史 — findVersionsBySkillId returns sorted by publishedAt DESC")
    void getVersionHistory() throws InterruptedException {
        var skillId = commandService.createSkill(
                new CreateSkillCommand("version-query-skill", "Test", "tester", "Testing"));

        commandService.publishVersion(new PublishVersionCommand(
                skillId, "1.0.0", "gs://bucket/" + skillId + "/1.0.0.zip", 100L, 0, Map.of()));
        // Ensure distinct publishedAt timestamps for stable DESC sort
        Thread.sleep(2);
        commandService.publishVersion(new PublishVersionCommand(
                skillId, "1.1.0", "gs://bucket/" + skillId + "/1.1.0.zip", 150L, 0, Map.of()));

        var versions = queryService.findVersionsBySkillId(skillId);

        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).getVersion()).isEqualTo("1.1.0");
        assertThat(versions.get(1).getVersion()).isEqualTo("1.0.0");
        assertThat(versions.get(0).getStoragePath()).contains(skillId);
        assertThat(versions.get(0).getFileSize()).isGreaterThan(0L);
    }
}
