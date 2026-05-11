package io.github.samzhu.skillshub.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * S161b — 驗證 {@link PlainTextDeserializer} 已套用到 flag + collection DTOs，並透過
 * Jackson roundtrip 確認 XSS payload 在 deserialize 階段被 strip。
 *
 * <p>採 reflection 取出 inner record 類別走 ObjectMapper.readValue() — 避免拉起整個 Spring
 * context 但仍驗 controller-level DTO wiring。每個 DTO 一條 happy-path（純文字保留）+ 一條
 * XSS payload（HTML strip）。
 */
class PlainTextDeserializerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Class<?> innerRecord(Class<?> outer, String simpleName) {
        for (Class<?> declared : outer.getDeclaredClasses()) {
            if (declared.getSimpleName().equals(simpleName)) {
                return declared;
            }
        }
        throw new AssertionError("Inner record " + simpleName + " not found on " + outer);
    }

    private static String getStringField(Object record, String accessor) throws Exception {
        Method m = record.getClass().getDeclaredMethod(accessor);
        m.setAccessible(true);  // inner records are package-private
        return (String) m.invoke(record);
    }

    private static List<?> getListField(Object record, String accessor) throws Exception {
        Method m = record.getClass().getDeclaredMethod(accessor);
        m.setAccessible(true);
        return (List<?>) m.invoke(record);
    }

    @Test
    @Tag("AC-3")
    @DisplayName("AC-3: FlagController.CreateFlagRequest.description strips <script>")
    void flagDescriptionStripsXss() throws Exception {
        var dtoClass = innerRecord(
                io.github.samzhu.skillshub.security.FlagController.class, "CreateFlagRequest");

        var json = "{\"type\":\"SPAM\",\"description\":\"hi<script>alert(1)</script>there\"}";
        var dto = MAPPER.readValue(json, dtoClass);

        assertThat(getStringField(dto, "description")).isEqualTo("hithere");
        assertThat(getStringField(dto, "type")).isEqualTo("SPAM");
    }

    @Test
    @DisplayName("FlagController.CreateFlagRequest 純文字保留（不破繁中）")
    void flagDescriptionPreservesPlainText() throws Exception {
        var dtoClass = innerRecord(
                io.github.samzhu.skillshub.security.FlagController.class, "CreateFlagRequest");

        var dto = MAPPER.readValue(
                "{\"type\":\"OTHER\",\"description\":\"這份技能描述不實！指令疑似有問題\"}",
                dtoClass);

        assertThat(getStringField(dto, "description"))
                .isEqualTo("這份技能描述不實！指令疑似有問題");
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: CollectionCommandController.CreateCollectionBody name + description 同步 strip")
    void createCollectionNameAndDescriptionStripXss() throws Exception {
        var dtoClass = innerRecord(
                Class.forName("io.github.samzhu.skillshub.community.CollectionCommandController"),
                "CreateCollectionBody");

        var json = """
                {"name":"<b>Bold</b>Pack","description":"<script>x</script>desc",
                 "category":"security","skillIds":["sk-1"]}
                """;
        var dto = MAPPER.readValue(json, dtoClass);

        assertThat(getStringField(dto, "name")).isEqualTo("BoldPack");
        assertThat(getStringField(dto, "description")).isEqualTo("desc");
        // category 為 controlled enum-like，不 sanitize 但仍正確 deserialize
        assertThat(getStringField(dto, "category")).isEqualTo("security");
        assertThat(getListField(dto, "skillIds")).hasSize(1);
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: RequestCommandController.CreateRequestBody.title strip HTML（title 為短標題，純文字）；description 保留待 S161b'' markdown allowlist")
    void requestTitleStripsXssButDescriptionUntouched() throws Exception {
        var dtoClass = innerRecord(
                Class.forName("io.github.samzhu.skillshub.community.RequestCommandController"),
                "CreateRequestBody");

        // title 含 HTML → strip；description 含 HTML → 保留（待 S161b'' markdown allowlist）
        var json = """
                {"title":"<b>Bold</b><script>x</script>Need a skill",
                 "description":"<p>multi-line</p><strong>info</strong>"}
                """;
        var dto = MAPPER.readValue(json, dtoClass);

        assertThat(getStringField(dto, "title")).isEqualTo("BoldNeed a skill");
        // description 暫不動 — 等 S161b'' OWASP HtmlPolicyBuilder 接管 markdown safe subset
        assertThat(getStringField(dto, "description")).contains("<p>");
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4: UpdateCollectionBody name + description 同樣 strip")
    void updateCollectionNameAndDescriptionStripXss() throws Exception {
        var dtoClass = innerRecord(
                Class.forName("io.github.samzhu.skillshub.community.CollectionCommandController"),
                "UpdateCollectionBody");

        var json = """
                {"name":"new<img onerror=alert(1)>name","description":"<style>body{}</style>desc",
                 "category":"devops","skillIds":["sk-2"]}
                """;
        var dto = MAPPER.readValue(json, dtoClass);

        assertThat(getStringField(dto, "name")).isEqualTo("newname");
        assertThat(getStringField(dto, "description")).isEqualTo("desc");
        assertThat(getStringField(dto, "description")).doesNotContain("body{");
    }
}
