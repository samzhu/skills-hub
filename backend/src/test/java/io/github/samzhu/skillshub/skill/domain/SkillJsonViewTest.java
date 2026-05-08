package io.github.samzhu.skillshub.skill.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.samzhu.skillshub.skill.command.CreateSkillCommand;

/**
 * S158 #1 — Skill aggregate 對 Jackson @JsonView 的序列化合約驗證。
 *
 * <p>純 Jackson 單元測試；無 Spring context；快速；確保 list view 不洩漏
 * {@code aclEntries} / {@code ownerId} 兩個 internal authorization 欄位。
 * Detail view（含 list extends 關係）保留全部欄位。
 *
 * <p>對應 spec §3 AC-1：list response 完全不出現 aclEntries / ownerId 字串。
 */
class SkillJsonViewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // 對齊 Spring Boot 預設行為：default-view-inclusion=true → 沒 @JsonView 的欄位
            // 在任何 view 下都會被序列化；只有 @JsonView(Detail) 會被 list view 排除。
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .findAndRegisterModules();

    private Skill sampleSkill() {
        Skill skill = Skill.create(new CreateSkillCommand(
                "test-skill",
                "Test description for view serialization.",
                "alice",
                "Testing"));
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
