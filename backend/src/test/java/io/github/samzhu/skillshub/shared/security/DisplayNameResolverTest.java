package io.github.samzhu.skillshub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * S154-T02 Scenario D — {@link DisplayNameResolver} 5-layer fallback。
 *
 * <p>Pure unit test：無 Spring context，無 DB；驗證每層 fallback 在前面 layers null 時觸發。
 */
class DisplayNameResolverTest {

    @Test
    @DisplayName("AC-8: Layer 1 — name claim 直接回傳")
    @Tag("AC-8")
    void layer1_oidcName() {
        var result = DisplayNameResolver.resolve(
                "Alice Chen", "Alice", "Chen",
                "alice@example.com", "alice", "u_a3f9c1");
        assertThat(result).isEqualTo("Alice Chen");
    }

    @Test
    @DisplayName("AC-8: Layer 2 — name=null → given_name + family_name")
    @Tag("AC-8")
    void layer2_givenAndFamilyName() {
        var result = DisplayNameResolver.resolve(
                null, "Alice", "Chen",
                "alice@example.com", "alice", "u_a3f9c1");
        assertThat(result).isEqualTo("Alice Chen");
    }

    @Test
    @DisplayName("AC-8: Layer 2 — 只有 given_name 也行（family_name null）")
    @Tag("AC-8")
    void layer2_givenNameOnly() {
        var result = DisplayNameResolver.resolve(
                null, "Alice", null,
                "alice@example.com", "alice", "u_a3f9c1");
        assertThat(result).isEqualTo("Alice");
    }

    @Test
    @DisplayName("AC-8: Layer 3 — 前兩層 null → email local-part 首字大寫")
    @Tag("AC-8")
    void layer3_emailLocalPart() {
        var result = DisplayNameResolver.resolve(
                null, null, null,
                "alice@example.com", "alice", "u_a3f9c1");
        assertThat(result).isEqualTo("Alice");
    }

    @Test
    @DisplayName("AC-8: Layer 4 — 前三層 null → handle")
    @Tag("AC-8")
    void layer4_handle() {
        var result = DisplayNameResolver.resolve(
                null, null, null,
                null, "alice", "u_a3f9c1");
        assertThat(result).isEqualTo("alice");
    }

    @Test
    @DisplayName("AC-8: Layer 5 — 全部 null → user_id（永不 fall through 到 raw sub）")
    @Tag("AC-8")
    void layer5_userId() {
        var result = DisplayNameResolver.resolve(
                null, null, null, null, null, "u_a3f9c1");
        assertThat(result).isEqualTo("u_a3f9c1");
    }

    @Test
    @DisplayName("AC-8: 空白字串視同 null（trim 後空）")
    @Tag("AC-8")
    void blankStringsTreatedAsNull() {
        var result = DisplayNameResolver.resolve(
                "   ", "  ", "",
                "alice@example.com", "alice", "u_a3f9c1");
        assertThat(result).isEqualTo("Alice");
    }
}
