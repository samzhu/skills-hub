package io.github.samzhu.skillshub.skill.command;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S016 AC-7 — {@link SkillCommandController#addVersion} 加 {@code @PreAuthorize}
 * 之後的 row-level ACL gate 行為驗證。
 *
 * <p>對應 spec §4.13：PUT {@code /api/v1/skills/{id}/versions} 需 {@code hasPermission(#id, 'Skill', 'write')}；
 * acl_entries 含 {@code user:alice:write} 的 skill 對 alice 開放、對 bob 拒絕。
 *
 * <p>採 MockMvc {@code .with(jwt())} 合成 {@link org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken}；
 * 此 path 不過 {@code JwtAuthenticationConverter}（生產 path 由 E2E 測試覆蓋），
 * 故須顯式 set {@code .authorities(ROLE_xxx)} 對齊 production 行為。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SkillCommandControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SkillRepository skillRepo;

    @Test
    @DisplayName("AC-7: alice (user:alice:write 已 grant) PUT /skills/{id}/versions → 通過 @PreAuthorize gate（非 403）")
    @Tag("AC-7")
    void ownerPutVersion_passesAuthorizationGate() throws Exception {
        var skillId = seedSkill(List.of("user:alice:read", "user:alice:write"));
        var validZip = createValidSkillZip("acl-test-skill-" + skillId.substring(0, 8));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/skills/" + skillId + "/versions")
                .file(new MockMultipartFile("file", "v.zip", "application/zip", validZip))
                .param("version", "1.1.0")
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 403) {
                        throw new AssertionError(
                                "alice 應通過 @PreAuthorize gate 但實得 403 — 表示 ACL 檢查紅");
                    }
                });
    }

    @Test
    @DisplayName("AC-7: bob (無 user:bob:write) PUT /skills/{id}/versions → 403 Forbidden")
    @Tag("AC-7")
    void nonOwnerPutVersion_returns403() throws Exception {
        var skillId = seedSkill(List.of("user:alice:read", "user:alice:write"));
        var validZip = createValidSkillZip("acl-test-skill-" + skillId.substring(0, 8));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/skills/" + skillId + "/versions")
                .file(new MockMultipartFile("file", "v.zip", "application/zip", validZip))
                .param("version", "1.1.0")
                .with(jwt()
                        .jwt(j -> j.subject("bob")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isForbidden());
    }

    // ---- helpers ----

    private String seedSkill(List<String> aclEntries) {
        var id = UUID.randomUUID().toString();
        var now = Instant.now();
        // skills.name 有 UNIQUE constraint — 用 id 前綴避免跨測試衝突
        skillRepo.save(Skill.fromRow(
                id,
                "acl-test-skill-" + id.substring(0, 8),
                "ACL gate test fixture",
                "test-author",
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

    /**
     * 產生最小可通過 SkillValidator 的 zip：含 SKILL.md frontmatter（name + description）。
     * SKILL.md 的 name 須與 seedSkill 一致，否則 versionService 會 reject。
     */
    private byte[] createValidSkillZip(String skillName) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("SKILL.md"));
            var content = "---\nname: " + skillName + "\ndescription: ACL test version\n---\n# " + skillName;
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
