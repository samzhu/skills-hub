package io.github.samzhu.skillshub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;

/**
 * S154-T02 — {@link UserRepository} CRUD + 三條 derived query 驗證。
 *
 * <p>{@code @DataJdbcTest} slice via {@link RepositorySliceTestBase}：Flyway 自動跑 V1-V18 對
 * Testcontainers PostgreSQL；本 test focus 單一 repository 的 query 行為，不啟動完整 application context。
 */
class UserRepositoryTest extends RepositorySliceTestBase {

    @Autowired
    private UserRepository repo;

    @Test
    @DisplayName("AC-8: save() INSERT 新 user — isNew=true 走 INSERT 路徑")
    @Tag("AC-8")
    void saveInsertsNewUser() {
        var user = newUser("u_aaaaaa", "google", "sub-1", "alice@example.com",
                "Alice Chen", "alice");

        var saved = repo.save(user);

        assertThat(saved.getId()).isEqualTo("u_aaaaaa");
        assertThat(repo.findById("u_aaaaaa")).isPresent();
    }

    @Test
    @DisplayName("AC-8: findByOauthProviderAndSub — UNIQUE pair 第一查詢點")
    @Tag("AC-8")
    void findByOauthProviderAndSub() {
        repo.save(newUser("u_bbbbbb", "google", "sub-google-2", "bob@example.com",
                "Bob Smith", "bob-" + uniqueSuffix()));

        var found = repo.findByOauthProviderAndSub("google", "sub-google-2");
        var notFound = repo.findByOauthProviderAndSub("github", "sub-google-2");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo("u_bbbbbb");
        // 不同 provider 同 sub → 不 match（V18 UNIQUE pair 設計）
        assertThat(notFound).isEmpty();
    }

    @Test
    @DisplayName("AC-8: findByHandle — handle UNIQUE 全平台唯一")
    @Tag("AC-8")
    void findByHandle() {
        var handle = "carol-" + uniqueSuffix();
        repo.save(newUser("u_cccccc", "google", "sub-3", "carol@example.com",
                "Carol", handle));

        var found = repo.findByHandle(handle);
        var notFound = repo.findByHandle("nonexistent-" + uniqueSuffix());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo("u_cccccc");
        assertThat(notFound).isEmpty();
    }

    @Test
    @DisplayName("AC-8: findFirstByEmail — email 非 UNIQUE 取第一筆")
    @Tag("AC-8")
    void findFirstByEmail() {
        var email = "shared-" + uniqueSuffix() + "@example.com";
        repo.save(newUser("u_dddddd", "google", "sub-4a", email, "User A", "user-a-" + uniqueSuffix()));

        var found = repo.findFirstByEmail(email);

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo(email);
    }

    @Test
    @DisplayName("AC-8: refresh + save() UPDATE 路徑 — isNew=false 不會重複 INSERT")
    @Tag("AC-8")
    void refreshUpdatesExistingUser() {
        var user = newUser("u_eeeeee", "google", "sub-5", "old@example.com",
                "Old Name", "user-e-" + uniqueSuffix());
        repo.save(user);

        // load → refresh → save → 應走 UPDATE（isNew=false 由 @PersistenceCreator 載入時設定）
        var loaded = repo.findById("u_eeeeee").orElseThrow();
        loaded.refreshFromOidc("new@example.com", "New Name", "https://avatar/x.png", Instant.now());
        repo.save(loaded);

        var refreshed = repo.findById("u_eeeeee").orElseThrow();
        assertThat(refreshed.getEmail()).isEqualTo("new@example.com");
        assertThat(refreshed.getName()).isEqualTo("New Name");
        assertThat(refreshed.getAvatarUrl()).isEqualTo("https://avatar/x.png");
    }

    private static User newUser(String id, String provider, String sub, String email,
                                String name, String handle) {
        return User.createNew(id, provider, sub, email, name, handle, null, Instant.now());
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
