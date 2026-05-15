package io.github.samzhu.skillshub.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.notification.NotificationPreference;
import io.github.samzhu.skillshub.shared.security.User;
import io.github.samzhu.skillshub.skill.domain.Skill;

/**
 * S168 — Regression guard：所有持久化 boolean column 的 entity field 必須宣告為
 * wrapper {@link Boolean}，**不可**改回 primitive {@code boolean}。
 *
 * <h2>背景</h2>
 *
 * <p>GraalVM SubstrateVM MethodHandle adaptation bug（
 * <a href="https://github.com/oracle/graal/issues/5672">oracle/graal#5672</a> /
 * <a href="https://github.com/spring-projects/spring-data-relational/issues/2186">spring-data-relational#2186</a> /
 * <a href="https://github.com/spring-projects/spring-data-mongodb/issues/5101">spring-data-mongodb#5101</a>）
 * — 在 native image runtime 下，{@link org.springframework.data.mapping.model.ConvertingPropertyAccessor#setProperty}
 * 對 {@code source=Boolean / target=boolean(primitive)} pair 走 {@code ClassUtils.isAssignable}
 * 短路，conversion service 不被呼叫；Boolean 直接傳給 AOT-generated entity accessor
 * （{@code User__Accessor}），其 MethodHandle 適配 {@code (Object,Object)V → (Entity,boolean)V}
 * 在 unboxing adapter 階段 corrupt Boolean → Integer，{@code UnsafeBooleanFieldAccessorImpl.set}
 * 看到 Integer 灌進 primitive boolean field 拋 {@link IllegalArgumentException}。
 *
 * <p>修法：宣告 wrapper {@link Boolean} field 後，AOT setter 變 {@code (Entity,Boolean)V} 純
 * reference cast 無 unboxing → 走 {@code UnsafeObjectFieldAccessorImpl.set}（只查
 * {@code field.getType().isInstance(value)}）→ 不踩 GraalVM bug。Per JobRunr PR #1501
 * production-shipped fix（v8.5.0；同 stacktrace 同根因）。
 *
 * <h2>Test 策略</h2>
 *
 * <p>Field-type assertion：用 reflection 確認所有受影響欄位的宣告型別為 {@code Boolean.class}
 * （非 {@code boolean.class}）。本 test 是 regression guard — 未來 PR 不小心改回 primitive
 * 立即被抓，避免 native image deploy 重炸。**不**直接驗證 GraalVM runtime 行為（需
 * {@code nativeRun} 才能 true reproduce；本 spec ship 前以 local nativeCompile + 手動驗
 * `/browse` 收尾）。
 *
 * @see User#contactEmailPublic
 * @see NotificationPreference#flagsEnabled
 * @see <a href="https://github.com/jobrunr/jobrunr/pull/1501">JobRunr PR #1501 — primitive boolean → boxed Boolean</a>
 */
class JdbcConfigurationConverterTest {

    @Test
    @DisplayName("AC-2: User.contactEmailPublic 必為 Boolean wrapper（非 primitive）防 GraalVM oracle/graal#5672 重現")
    @Tag("AC-2")
    void userContactEmailPublic_mustBeBooleanWrapper() throws NoSuchFieldException {
        var field = User.class.getDeclaredField("contactEmailPublic");

        assertThat(field.getType())
                .as("User.contactEmailPublic 必須是 Boolean (wrapper)。"
                        + "改回 primitive boolean 會在 GraalVM native image runtime 重現 IAE — "
                        + "詳 oracle/graal#5672 + JobRunr PR #1501。")
                .isEqualTo(Boolean.class);
    }

    @Test
    @DisplayName("AC-3: NotificationPreference 4 個 boolean column 必為 Boolean wrapper（同上規避）")
    @Tag("AC-3")
    void notificationPreferenceBooleans_mustBeBooleanWrapper() throws NoSuchFieldException {
        var fields = new String[] {
                "flagsEnabled", "reviewsEnabled", "requestsEnabled", "versionsEnabled"
        };

        for (var fieldName : fields) {
            var field = NotificationPreference.class.getDeclaredField(fieldName);
            assertThat(field.getType())
                    .as("NotificationPreference." + fieldName + " 必須是 Boolean (wrapper)。"
                            + "詳 oracle/graal#5672 + JobRunr PR #1501。")
                    .isEqualTo(Boolean.class);
        }
    }

    @Test
    @DisplayName("AC-S180-1: Skill.publicSkill 必為 Boolean wrapper（同上規避）")
    @Tag("AC-S180-1")
    void skillPublicSkill_mustBeBooleanWrapper() throws NoSuchFieldException {
        var field = Skill.class.getDeclaredField("publicSkill");

        assertThat(field.getType())
                .as("Skill.publicSkill 必須是 Boolean (wrapper)。"
                        + "詳 oracle/graal#5672 + spring-data-relational#2186。")
                .isEqualTo(Boolean.class);
    }
}
