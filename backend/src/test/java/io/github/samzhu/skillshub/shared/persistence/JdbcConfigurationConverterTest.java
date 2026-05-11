package io.github.samzhu.skillshub.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jdbc.core.convert.JdbcConverter;
import org.springframework.data.relational.domain.RowDocument;

import io.github.samzhu.skillshub.notification.NotificationPreference;
import io.github.samzhu.skillshub.shared.security.User;

/**
 * S168 — Spring Data JDBC custom converter regression tests for the GraalVM
 * native image MethodHandle adaptation workaround.
 *
 * <p>Each test corresponds to one acceptance criterion in the spec:
 * <ul>
 *   <li>AC-1 — pure converter logic</li>
 *   <li>AC-2 — User entity {@code Integer→boolean} read pipeline regression</li>
 *   <li>AC-3 — NotificationPreference 4-boolean field read pipeline regression
 *       (proves global converter scope)</li>
 * </ul>
 *
 * <p>AC-2 / AC-3 模擬 GraalVM SubstrateVM MethodHandle adaptation 把 Boolean
 * column 值 corrupt 成 Integer 的場景：手動建構 {@link RowDocument} 把
 * boolean column 餵 {@code Integer.valueOf(0)} / {@code Integer.valueOf(1)}，
 * 透過 {@link JdbcConverter#read(Class, RowDocument)} 走完整 mapping pipeline，
 * 驗證 {@link JdbcConfiguration.IntegerToBooleanConverter} 在 AOT-generated
 * accessor 之前攔截，entity 收到正確 boolean 值不拋 IAE。
 *
 * <p>Test extends {@link RepositorySliceTestBase} 以拿 Spring 真實組裝的
 * {@code JdbcConverter} bean（含全部 user converters）；context cache 與其他
 * REPO slice 共用，不額外觸發 Testcontainers 啟動成本。
 *
 * @see JdbcConfiguration.IntegerToBooleanConverter
 * @see <a href="https://github.com/oracle/graal/issues/5672">oracle/graal#5672</a>
 * @see <a href="https://github.com/spring-projects/spring-data-relational/issues/2186">spring-data-relational#2186</a>
 */
class JdbcConfigurationConverterTest extends RepositorySliceTestBase {

    @Autowired
    private JdbcConverter jdbcConverter;

    @Test
    @DisplayName("AC-1: IntegerToBooleanConverter null-safe Integer→Boolean conversion")
    @Tag("AC-1")
    void integerToBooleanConverter_nullSafe_zeroFalse_nonZeroTrue() {
        var converter = new JdbcConfiguration.IntegerToBooleanConverter();

        assertThat(converter.convert(0)).as("0 → false").isFalse();
        assertThat(converter.convert(1)).as("1 → true").isTrue();
        assertThat(converter.convert(null)).as("null → false (null-safe)").isFalse();
        assertThat(converter.convert(2)).as("non-zero → true").isTrue();
        assertThat(converter.convert(-1)).as("negative non-zero → true").isTrue();
    }

    @Test
    @DisplayName("AC-2: User entity 在 GraalVM Integer→Boolean corrupt input 下仍正確讀回 (false)")
    @Tag("AC-2")
    void whenIntegerInBooleanColumn_ConverterRecoversBoolean_User_false() {
        // 模擬 GraalVM corrupt input：contact_email_public 應為 Boolean.FALSE，被 corrupt 為 Integer(0)
        var doc = userRowDoc(Integer.valueOf(0));

        User user = jdbcConverter.read(User.class, doc);

        assertThat(user.isContactEmailPublic())
                .as("Integer(0) → boolean false（converter 攔截，無 IAE）").isFalse();
    }

    @Test
    @DisplayName("AC-2: User entity 在 GraalVM Integer→Boolean corrupt input 下仍正確讀回 (true)")
    @Tag("AC-2")
    void whenIntegerInBooleanColumn_ConverterRecoversBoolean_User_true() {
        var doc = userRowDoc(Integer.valueOf(1));

        User user = jdbcConverter.read(User.class, doc);

        assertThat(user.isContactEmailPublic())
                .as("Integer(1) → boolean true（converter 攔截，無 IAE）").isTrue();
    }

    @Test
    @DisplayName("AC-3: NotificationPreference 4 個 boolean field 同樣被 converter 涵蓋（global scope）")
    @Tag("AC-3")
    void whenIntegerInBooleanColumn_ConverterRecoversBoolean_NotificationPreference() {
        // 4 boolean 設不同 0/1 組合，證明每個 field 都各自走 converter
        var doc = new RowDocument(Map.of(
                "user_id", "u_test01",
                "flags_enabled", Integer.valueOf(1),
                "reviews_enabled", Integer.valueOf(0),
                "requests_enabled", Integer.valueOf(0),
                "versions_enabled", Integer.valueOf(1),
                "updated_at", Instant.EPOCH,
                "version", Long.valueOf(0)));

        NotificationPreference pref = jdbcConverter.read(NotificationPreference.class, doc);

        assertThat(pref.isEnabled("flags")).as("flags_enabled Integer(1) → true").isTrue();
        assertThat(pref.isEnabled("reviews")).as("reviews_enabled Integer(0) → false").isFalse();
        assertThat(pref.isEnabled("requests")).as("requests_enabled Integer(0) → false").isFalse();
        assertThat(pref.isEnabled("versions")).as("versions_enabled Integer(1) → true").isTrue();
    }

    /** 建構 User RowDocument — 共用 helper，只變動 contact_email_public 一欄。 */
    private static RowDocument userRowDoc(Object contactEmailPublicValue) {
        return new RowDocument(Map.of(
                "id", "u_test01",
                "oauth_provider", "google",
                "sub", "test-sub",
                "email", "test@example.com",
                "handle", "tester",
                "contact_email_public", contactEmailPublicValue,
                "created_at", Instant.EPOCH,
                "last_seen_at", Instant.EPOCH));
    }
}
