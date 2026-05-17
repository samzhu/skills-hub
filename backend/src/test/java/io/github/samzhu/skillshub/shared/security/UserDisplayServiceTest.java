package io.github.samzhu.skillshub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;

/**
 * S192-T01 — {@link UserDisplayService} user-facing display projection guard.
 *
 * <p>{@link DisplayNameResolver} may still return raw platform user id for
 * technical call sites. This service is the DTO-facing guard that keeps that
 * fallback out of normal UI fields.
 */
class UserDisplayServiceTest extends RepositorySliceTestBase {

    @Autowired
    private UserRepository users;

    @Test
    @DisplayName("AC-S192-2: user display service resolves human-readable name and optional email")
    @Tag("AC-S192-2")
    void resolvesDisplayNameHandleAndEmailPolicy() {
        users.save(newUser("u_f7eb3a", "sam@example.com", "Sam Zhu", "samzhu"));
        var service = new UserDisplayService(users);

        var hiddenEmail = service.resolve("u_f7eb3a", false);
        var visibleEmail = service.resolve("u_f7eb3a", true);

        assertThat(hiddenEmail.displayName()).isEqualTo("Sam Zhu");
        assertThat(hiddenEmail.handle()).isEqualTo("samzhu");
        assertThat(hiddenEmail.email()).isNull();
        assertThat(visibleEmail.email()).isEqualTo("sam@example.com");
    }

    @Test
    @DisplayName("AC-S192-11: user-facing display service never returns raw platform user id as displayName")
    @Tag("AC-S192-11")
    void displayNameDoesNotFallBackToRawPlatformUserId() {
        users.save(newUser("u_a3f9c1", "", null, ""));
        var service = new UserDisplayService(users);

        var display = service.resolve("u_a3f9c1", false);

        assertThat(DisplayNameResolver.resolve(null, null, null, null, null, "u_a3f9c1"))
                .as("low-level resolver may keep technical fallback")
                .isEqualTo("u_a3f9c1");
        assertThat(display.displayName())
                .as("user-facing DTO display field must not expose raw id")
                .isNull();
    }

    @Test
    @DisplayName("AC-S192-10: missing user row returns no display label so fixtures must seed actor data")
    @Tag("AC-S192-10")
    void missingUserHasNoDisplayLabel() {
        var service = new UserDisplayService(users);

        var display = service.resolve("u_missing", false);

        assertThat(display.userId()).isEqualTo("u_missing");
        assertThat(display.displayName()).isNull();
        assertThat(display.handle()).isNull();
        assertThat(display.email()).isNull();
    }

    @Test
    @DisplayName("AC-S192-2: resolveAll de-duplicates ids and preserves result lookup by user id")
    @Tag("AC-S192-2")
    void resolveAllDeduplicatesIds() {
        users.save(newUser("u_192bbb", "bob@example.com", "Bob Lee", "bob-s192"));
        var service = new UserDisplayService(users);

        var results = service.resolveAll(List.of("u_192bbb", "u_192bbb", "u_absent"), false);

        assertThat(results).containsOnlyKeys("u_192bbb", "u_absent");
        assertThat(results.get("u_192bbb").displayName()).isEqualTo("Bob Lee");
        assertThat(results.get("u_absent").displayName()).isNull();
    }

    private static User newUser(String id, String email, String name, String handle) {
        return User.createNew(id, "google", id + "-sub", email, name, handle, null, Instant.now());
    }
}
