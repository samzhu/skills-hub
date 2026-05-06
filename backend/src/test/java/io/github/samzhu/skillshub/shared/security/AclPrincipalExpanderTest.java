package io.github.samzhu.skillshub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * S016 AC-12 — {@link AclPrincipalExpander} 把 {@link CurrentUser} 展為
 * {@code type:principal:permission} patterns（user / role / group 三命名空間）。
 *
 * <p>對應 spec §4.6 — 純 utility 行為驗證；不啟動 Spring 容器。
 */
class AclPrincipalExpanderTest {

    private final AclPrincipalExpander expander = new AclPrincipalExpander();

    @Test
    @DisplayName("AC-12: expand 三命名空間皆有資料 → user + role + group patterns 全展開")
    @Tag("AC-12")
    void expand_allThreeNamespaces() {
        var user = new CurrentUser("alice", List.of("admin"), List.of("engineering", "platform"), null);

        var patterns = expander.expand(user, "read");

        // S026 + S114a: read 一律附 "public:*:read" public-read entry
        assertThat(patterns).containsExactlyInAnyOrder(
                "user:alice:read",
                "role:admin:read",
                "group:engineering:read",
                "group:platform:read",
                "public:*:read");
    }

    @Test
    @DisplayName("AC-12: expand groups 為空 list → 不誤產 group:: patterns")
    @Tag("AC-12")
    void expand_emptyGroups_skipsGroupPrefix() {
        var user = new CurrentUser("bob", List.of("user"), List.of(), null);

        var patterns = expander.expand(user, "write");

        assertThat(patterns).containsExactlyInAnyOrder(
                "user:bob:write",
                "role:user:write");
    }

    @Test
    @DisplayName("AC-12: expand roles 為空 list → 不誤產 role:: patterns")
    @Tag("AC-12")
    void expand_emptyRoles_skipsRolePrefix() {
        var user = new CurrentUser("carol", List.of(), List.of("research"), null);

        var patterns = expander.expand(user, "delete");

        assertThat(patterns).containsExactlyInAnyOrder(
                "user:carol:delete",
                "group:research:delete");
    }

    @Test
    @DisplayName("AC-12: expand 不同 permission 動詞 → suspend / reactivate 也支援（為 S018 鋪路）")
    @Tag("AC-12")
    void expand_supportsAllMvpPermissions() {
        var user = new CurrentUser("dan", List.of("admin"), List.of(), null);

        // S016 spec §2.4 #5：MVP 啟用 verbs = read/write/delete/suspend/reactivate
        // S026 + S114a: read 一律附 "public:*:read"；write/delete/suspend/reactivate 不附
        for (var verb : List.of("read", "write", "delete", "suspend", "reactivate")) {
            var expected = "read".equals(verb)
                    ? List.of("user:dan:" + verb, "role:admin:" + verb, "public:*:read")
                    : List.of("user:dan:" + verb, "role:admin:" + verb);
            assertThat(expander.expand(user, verb))
                    .as("verb=%s 應產出對應 patterns", verb)
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @Test
    @DisplayName("AC-12: expandGroups 只展 group 命名空間（dispatcher 補丁用）")
    @Tag("AC-12")
    void expandGroups_onlyGroupNamespace() {
        var patterns = expander.expandGroups(List.of("engineering"), "write");

        assertThat(patterns).containsExactly("group:engineering:write");
    }

    @Test
    @DisplayName("AC-12: expandGroups 空 list → 空 list（無誤展）")
    @Tag("AC-12")
    void expandGroups_empty_returnsEmpty() {
        assertThat(expander.expandGroups(List.of(), "read")).isEmpty();
    }

    @Test
    @DisplayName("AC-9: expand user with companyId=acme → includes company:acme:read")
    @Tag("AC-9")
    void expand_withCompanyId_includesCompanyPattern() {
        var user = new CurrentUser("bob", List.of("user"), List.of(), "acme");

        var patterns = expander.expand(user, "read");

        assertThat(patterns).contains("company:acme:read");
        assertThat(patterns).contains("user:bob:read");
    }

    @Test
    @DisplayName("AC-9: expand user with null companyId → no company: pattern")
    @Tag("AC-9")
    void expand_nullCompanyId_noCompanyPattern() {
        var user = new CurrentUser("bob", List.of("user"), List.of(), null);

        var patterns = expander.expand(user, "read");

        assertThat(patterns).noneMatch(p -> p.startsWith("company:"));
    }
}
