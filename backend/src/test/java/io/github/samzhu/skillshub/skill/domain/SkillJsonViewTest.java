package io.github.samzhu.skillshub.skill.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.samzhu.skillshub.skill.command.CreateSkillCommand;

/**
 * S158 #1 / S165 hardening — Skill aggregate 對 Jackson @JsonView 的序列化合約驗證。
 *
 * <p>純 Jackson 單元測試；無 Spring context；快速；確保 list view 不洩漏
 * {@code aclEntries} / {@code ownerId} 兩個 internal authorization 欄位。
 * Detail view（含 list extends 關係）保留全部欄位。
 *
 * <p>對應 spec §3 AC-1：list response 完全不出現 aclEntries / ownerId 字串。
 *
 * <p><b>S165 教訓</b>：本 test 第一版的 ObjectMapper 註解說「對齊 Spring Boot 預設行為：
 * default-view-inclusion=true」— 對 Jackson 2 / Spring Boot 3 是對的，但 Boot 4 / Jackson 3
 * 預設改為 false。S158 ship 時 prod 因此壞掉（{@code Page<Skill>} 序列成 {@code {}}）。
 * 修正：本 test 顯式 enable {@code MapperFeature.DEFAULT_VIEW_INCLUSION}，與 prod 端
 * {@link io.github.samzhu.skillshub.shared.api.JacksonConfiguration} 的 customizer 對齊；
 * prod-mapper-真實-config 由 {@code JacksonViewInclusionDiagnosticTest} 守。
 *
 * <p>per development-standards.md「JSON contract 必走 Spring auto-configured JsonMapper」— 本 test
 * 仍保 unit-test scope（驗 aggregate @JsonView 標註本身），但 mapper 設定明確不依賴版本預設。
 */
class SkillJsonViewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // 顯式 enable — 對齊 prod JacksonConfiguration bean；不依賴 Jackson 2 vs 3 default 變動
            .configure(MapperFeature.DEFAULT_VIEW_INCLUSION, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .findAndRegisterModules();

    private Skill sampleSkill() {
        Skill skill = Skill.create(new CreateSkillCommand(
                "test-skill",
                "Test description for view serialization.",
                "alice",
                "testing"));
        // create() 已 seed 4 條 ACL（owner read/write/delete + public:*:read）+ ownerId="alice"
        return skill;
    }

    @Test
    @DisplayName("S158 AC-1: list view 不暴露 aclEntries / ownerId")
    void listViewExcludesInternalAuthFields() throws JsonProcessingException {
        Skill skill = sampleSkill();
        ObjectWriter writer = MAPPER.writerWithView(Skill.Views.List.class);

        String json = writer.writeValueAsString(skill);

        // 內部 authorization 結構不洩漏
        assertThat(json).doesNotContain("aclEntries");
        assertThat(json).doesNotContain("ownerId");
        // 公開欄位仍正常序列化
        assertThat(json).contains("\"name\":\"test-skill\"");
        assertThat(json).contains("\"author\":\"alice\"");
        assertThat(json).contains("\"description\"");
    }

    @Test
    @DisplayName("S158: detail view 包含完整 aclEntries + ownerId（既有 detail endpoint 行為不變）")
    void detailViewIncludesAllFields() throws JsonProcessingException {
        Skill skill = sampleSkill();
        ObjectWriter writer = MAPPER.writerWithView(Skill.Views.Detail.class);

        String json = writer.writeValueAsString(skill);

        // Detail extends List → 公開欄位全部 + internal auth 欄位也露
        assertThat(json).contains("\"aclEntries\"");
        assertThat(json).contains("\"ownerId\":\"alice\"");
        assertThat(json).contains("user:alice:read");
        assertThat(json).contains("public:*:read");
    }

    @Test
    @DisplayName("S158: 無 view 設定時 default-view-inclusion 行為 — 全欄位序列化")
    void noViewIncludesAllFields() throws JsonProcessingException {
        Skill skill = sampleSkill();

        String json = MAPPER.writeValueAsString(skill);

        // 不啟用 view 時所有欄位序列化（Spring Boot 預設行為一致）
        assertThat(json).contains("\"aclEntries\"");
        assertThat(json).contains("\"ownerId\"");
    }
}
