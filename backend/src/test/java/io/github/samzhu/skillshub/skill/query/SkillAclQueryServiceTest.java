package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S016 T5 — SkillAclQueryService 解析邏輯驗證。
 *
 * <p>對應 spec §4.12 GET endpoint 的 service 層：把 colon-separated 字串陣列拆回
 * type/principal/permission tuple，畸形 entry 跳過不 throw。
 *
 * <p>S025b T02 — extends {@link RepositorySliceTestBase} + {@code @Import(SkillAclQueryService.class)}
 * （{@code @DataJdbcTest} slice 預設不掃 {@code @Service}）。
 */
@Import(SkillAclQueryService.class)
class SkillAclQueryServiceTest extends RepositorySliceTestBase {

    @Autowired
    private SkillAclQueryService queryService;

    @Autowired
    private SkillRepository skillRepo;

    @Test
    @DisplayName("AC-11: listEntries 解析 colon-separated 字串為 AclEntryResponse list")
    @Tag("AC-11")
    void listEntries_parsesColonSeparatedStrings() {
        var skillId = seedSkill(List.of(
                "user:alice:read",
                "user:alice:write",
                "group:engineering:read"));

        var entries = queryService.listEntries(skillId);

        assertThat(entries).hasSize(3);
        assertThat(entries).extracting("type", "principal", "permission")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("user", "alice", "read"),
                        org.assertj.core.groups.Tuple.tuple("user", "alice", "write"),
                        org.assertj.core.groups.Tuple.tuple("group", "engineering", "read"));
    }

    @Test
    @DisplayName("AC-11: 空 acl_entries 回傳 empty list（不 throw）")
    @Tag("AC-11")
    void listEntries_emptyAcl_returnsEmptyList() {
        var skillId = seedSkill(List.of());

        var entries = queryService.listEntries(skillId);

        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("AC-11: 畸形 entry（非三段）skip 不 throw；其他 entry 仍回傳")
    @Tag("AC-11")
    void listEntries_malformedEntry_skipped() {
        var skillId = seedSkill(List.of(
                "user:alice:read",
                "malformed",                   // 0 colons
                "broken:two-segments",         // 1 colon
                "group:engineering:read"));

        var entries = queryService.listEntries(skillId);

        assertThat(entries).hasSize(2);
        assertThat(entries).extracting("type", "principal", "permission")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("user", "alice", "read"),
                        org.assertj.core.groups.Tuple.tuple("group", "engineering", "read"));
    }

    @Test
    @DisplayName("AC-S038: \"public:*:read\" 3-segment public-read entry 正確解析（S114a 標準格式）")
    @Tag("AC-S038")
    void listEntries_publicReadEntry_recognized() {
        var skillId = seedSkill(List.of(
                "user:alice:read",
                "public:*:read",               // S026 + S114a 3-segment 公開讀取 entry
                "group:engineering:read"));

        var entries = queryService.listEntries(skillId);

        assertThat(entries).hasSize(3);
        assertThat(entries).extracting("type", "principal", "permission")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("user", "alice", "read"),
                        org.assertj.core.groups.Tuple.tuple("public", "*", "read"),
                        org.assertj.core.groups.Tuple.tuple("group", "engineering", "read"));
    }

    private String seedSkill(List<String> aclEntries) {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        skillRepo.save(Skill.fromRow(
                id,
                "qs-acl-" + id.substring(0, 8),
                "Query service test fixture",
                "alice",
                "Testing",
                "1.0.0",
                "LOW",
                "PUBLISHED",
                0L,
                now, now,
                aclEntries,
                null));
        return id;
    }
}
