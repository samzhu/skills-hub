package io.github.samzhu.skillshub.skill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.skill.command.CreateSkillCommand;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.Visibility;

/**
 * S154-T04 AC-5 — `Skill.authorNameSnapshot` field freeze + republish refresh 驗證。
 *
 * <p>本檔同時是 T05 LEFT JOIN 整合測試的容器：T05 加 query-side {@code authorDisplayName /
 * authorHandle / authorEmail conditional} 場景時補進。本 task 只填 snapshot scenarios（C + D）。
 *
 * <p>Pure unit test on {@link Skill} aggregate — 無 Spring context、無 DB；驗 publish/republish
 * 路徑下的 {@code authorNameSnapshot} 狀態變化。
 */
class SkillAuthorJoinIntegrationTest {

    @Test
    @DisplayName("AC-5 Scenario C: initial publish freeze authorNameSnapshot")
    @Tag("AC-5")
    void initialPublishFreezesAuthorNameSnapshot() {
        // Alice publish skill (CurrentUserProvider.name() = "Alice Chen")
        var cmd = new CreateSkillCommand("docker-helper", "test description",
                "u_alice1", "DevOps", Visibility.PUBLIC, "Alice Chen");
        var skill = Skill.create(cmd);

        skill.recordVersionPublished("1.0.0", "Alice Chen");

        assertThat(skill.getAuthorNameSnapshot())
                .as("initial publish 應 freeze CurrentUserProvider.name() 入 skills.author_name_snapshot")
                .isEqualTo("Alice Chen");
    }

    @Test
    @DisplayName("AC-5 Scenario D: republish 改名後 snapshot refresh 為新值")
    @Tag("AC-5")
    void republishRefreshesAuthorNameSnapshot() {
        // 既有 skill snapshot="Alice Chen"
        var cmd = new CreateSkillCommand("docker-helper", "test description",
                "u_alice1", "DevOps", Visibility.PUBLIC, "Alice Chen");
        var skill = Skill.create(cmd);
        skill.recordVersionPublished("1.0.0", "Alice Chen");
        assertThat(skill.getAuthorNameSnapshot()).isEqualTo("Alice Chen");

        // Alice 改名後 republish 新版本（v1.1.0）— currentUser.name() 此時是 "Alice Liu"
        skill.recordVersionPublished("1.1.0", "Alice Liu");

        assertThat(skill.getAuthorNameSnapshot())
                .as("republish 應 refresh snapshot 為新顯示名稱")
                .isEqualTo("Alice Liu");
    }

    @Test
    @DisplayName("AC-5 edge: recordVersionPublished(version, null) → snapshot 保留既有值（避免誤清）")
    @Tag("AC-5")
    void republishWithNullSnapshotKeepsExistingValue() {
        var cmd = new CreateSkillCommand("docker-helper", "test description",
                "u_alice1", "DevOps", Visibility.PUBLIC, "Alice Chen");
        var skill = Skill.create(cmd);
        skill.recordVersionPublished("1.0.0", "Alice Chen");

        // 模擬 caller 沒提供 snapshot（如 OIDC name claim 缺失場景）
        skill.recordVersionPublished("1.1.0", null);

        assertThat(skill.getAuthorNameSnapshot())
                .as("null snapshot 不覆寫既有值（避免 OIDC 偶爾缺 name 而資料倒退）")
                .isEqualTo("Alice Chen");
    }

    @Test
    @DisplayName("AC-5: 1-arg overload recordVersionPublished(version) backward-compat — snapshot 不動")
    @Tag("AC-5")
    void singleArgOverloadDoesNotTouchSnapshot() {
        var cmd = new CreateSkillCommand("docker-helper", "test description",
                "u_alice1", "DevOps", Visibility.PUBLIC, "Alice Chen");
        var skill = Skill.create(cmd);
        skill.recordVersionPublished("1.0.0", "Alice Chen");

        // 既有 caller path（service 內 publish）走 1-arg overload
        skill.recordVersionPublished("1.1.0");

        assertThat(skill.getAuthorNameSnapshot())
                .as("1-arg overload backward compat：等同 (version, null) → snapshot 保留")
                .isEqualTo("Alice Chen");
    }

    @Test
    @DisplayName("AC-5: Skill.create 帶 null snapshot → field 為 null（schema NULLABLE 容許）")
    @Tag("AC-5")
    void createWithNullSnapshotResultsInNullField() {
        var cmd = new CreateSkillCommand("docker-helper", "test description",
                "u_alice1", "DevOps", Visibility.PUBLIC, null);
        var skill = Skill.create(cmd);

        assertThat(skill.getAuthorNameSnapshot())
                .as("null snapshot 在 V18 schema 是合法（NULLABLE column）— 用於 test fixture / OIDC name 缺場景")
                .isNull();
    }
}
