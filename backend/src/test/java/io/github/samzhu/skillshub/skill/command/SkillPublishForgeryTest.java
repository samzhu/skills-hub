package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.security.CurrentUser;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

/**
 * S154-T04 AC-3 — `SkillCommandController` forgery fix（caller 偽造 `author` 必須失效）。
 *
 * <p>兩 path 驗證 server 一律用 `currentUserProvider.userId()` 寫 `skills.author`，無視
 * caller 傳的 query param / JSON body：
 * <ul>
 *   <li>Scenario A — caller 不帶 author（multipart upload）→ server 從 currentUser 取</li>
 *   <li>Scenario B — caller 偽造 author=u_alice_xx（multipart 多塞）→ server ignore，仍寫自己 user_id</li>
 *   <li>Scenario B' — caller 偽造 JSON body author=u_alice_xx（POST /skills body 裡塞）→ server ignore</li>
 * </ul>
 *
 * <p>WebMvcTest slice：mock {@link SkillCommandService} + {@link CurrentUserProvider}；用 ArgumentCaptor
 * 抓 service 接到的 author param 比對。不啟動 DB。
 */
@WebMvcTest(SkillCommandController.class)
class SkillPublishForgeryTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SkillCommandService skillCommandService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    private static final String BOB_USER_ID = "u_bob1ff";
    private static final String BOB_DISPLAY_NAME = "Bob Smith";

    @Test
    @DisplayName("AC-3 Scenario A: Bob multipart upload 不帶 author → service 收到 BOB user_id（從 currentUserProvider）")
    @Tag("AC-3")
    void uploadWithoutAuthorParam_usesCurrentUser() throws Exception {
        // Bob 已登入；currentUser = (u_bob1ff, ..., "Bob Smith")
        Mockito.when(currentUserProvider.current()).thenReturn(new CurrentUser(
                BOB_USER_ID, "bob-google-sub", BOB_DISPLAY_NAME, "bob@example.com",
                "bob", List.of("user"), List.of(), null));
        Mockito.when(skillCommandService.uploadSkill(
                        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn("skill-id-from-bob");

        var fakeZip = new MockMultipartFile("file", "skill.zip", "application/zip", new byte[]{0x50, 0x4b, 0x03, 0x04});

        mockMvc.perform(multipart("/api/v1/skills/upload")
                        .file(fakeZip)
                        .param("version", "1.0.0")
                        .param("category", "testing")
                        .with(jwt().jwt(j -> j.subject("bob-google-sub"))))
                .andExpect(status().isCreated());

        // ArgumentCaptor 驗 service 收到的 author 是 BOB_USER_ID（不是 caller 提供的）
        var authorCaptor = ArgumentCaptor.forClass(String.class);
        var snapshotCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(skillCommandService).uploadSkill(
                ArgumentMatchers.any(), ArgumentMatchers.eq("1.0.0"),
                authorCaptor.capture(), ArgumentMatchers.eq("testing"),
                ArgumentMatchers.any(), snapshotCaptor.capture());

        assertThat(authorCaptor.getValue())
                .as("server 應從 currentUserProvider 取 user_id，非任何 caller 提供")
                .isEqualTo(BOB_USER_ID);
        assertThat(snapshotCaptor.getValue())
                .as("authorNameSnapshot 應從 currentUser.name() freeze")
                .isEqualTo(BOB_DISPLAY_NAME);
    }

    @Test
    @DisplayName("AC-3 Scenario B: Bob multipart 偽造 author=u_alice_xx → 仍寫 BOB user_id")
    @Tag("AC-3")
    void uploadWithForgedAuthorParam_serverOverridesToCurrentUser() throws Exception {
        Mockito.when(currentUserProvider.current()).thenReturn(new CurrentUser(
                BOB_USER_ID, "bob-google-sub", BOB_DISPLAY_NAME, "bob@example.com",
                "bob", List.of("user"), List.of(), null));
        Mockito.when(skillCommandService.uploadSkill(
                        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn("skill-id-still-from-bob");

        var fakeZip = new MockMultipartFile("file", "skill.zip", "application/zip", new byte[]{0x50, 0x4b, 0x03, 0x04});

        mockMvc.perform(multipart("/api/v1/skills/upload")
                        .file(fakeZip)
                        .param("version", "1.0.0")
                        .param("author", "u_alice_xx")  // 偽造！server 應 ignore
                        .param("category", "testing")
                        .with(jwt().jwt(j -> j.subject("bob-google-sub"))))
                .andExpect(status().isCreated());

        // 即使 caller 多塞 ?author=u_alice_xx，service 仍收 BOB_USER_ID
        var authorCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(skillCommandService).uploadSkill(
                ArgumentMatchers.any(), ArgumentMatchers.any(),
                authorCaptor.capture(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());

        assertThat(authorCaptor.getValue())
                .as("Bob 偽造 ?author=u_alice_xx 也擋下；server 一律寫自己 user_id")
                .isEqualTo(BOB_USER_ID)
                .isNotEqualTo("u_alice_xx");
    }

    @Test
    @DisplayName("AC-3 Scenario B': Bob JSON body 偽造 author=u_alice_xx → server override 為 BOB user_id")
    @Tag("AC-3")
    void createSkillWithForgedAuthorBody_serverOverridesToCurrentUser() throws Exception {
        Mockito.when(currentUserProvider.current()).thenReturn(new CurrentUser(
                BOB_USER_ID, "bob-google-sub", BOB_DISPLAY_NAME, "bob@example.com",
                "bob", List.of("user"), List.of(), null));
        Mockito.when(skillCommandService.createSkill(ArgumentMatchers.any()))
                .thenReturn("skill-id-from-bob-json");

        // Bob POST JSON body 含 "author":"u_alice_xx" + "authorNameSnapshot":"Alice Chen"
        var forgedJson = """
                {
                  "name": "test-skill",
                  "description": "test",
                  "author": "u_alice_xx",
                  "category": "testing",
                  "visibility": "PUBLIC",
                  "authorNameSnapshot": "Alice Chen"
                }""";

        mockMvc.perform(post("/api/v1/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgedJson)
                        .with(jwt().jwt(j -> j.subject("bob-google-sub"))))
                .andExpect(status().isCreated());

        // service 收到的 CreateSkillCommand.author / authorNameSnapshot 都被 server override
        var cmdCaptor = ArgumentCaptor.forClass(CreateSkillCommand.class);
        Mockito.verify(skillCommandService).createSkill(cmdCaptor.capture());
        var actual = cmdCaptor.getValue();

        assertThat(actual.author())
                .as("JSON body 內偽造的 author 必須被 server overrride 成 currentUser.userId()")
                .isEqualTo(BOB_USER_ID)
                .isNotEqualTo("u_alice_xx");
        assertThat(actual.authorNameSnapshot())
                .as("authorNameSnapshot 也由 server 從 currentUser.name() 取，不從 body 信任")
                .isEqualTo(BOB_DISPLAY_NAME)
                .isNotEqualTo("Alice Chen");
    }
}
