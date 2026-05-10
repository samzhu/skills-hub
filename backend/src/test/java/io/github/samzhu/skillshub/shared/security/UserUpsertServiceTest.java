package io.github.samzhu.skillshub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S154-T02 Scenario B + C — {@link UserUpsertService} user_id collision retry + handle slugify retry。
 *
 * <p>{@code @SpringBootTest} + Testcontainers：對齊 {@link io.github.samzhu.skillshub.notification.NotificationServiceTest}
 * 既有 service test pattern。針對 collision retry 的 unit-level 驗證走 visible-for-test ctor 注入 stub supplier。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserUpsertServiceTest {

    @Autowired
    private UserRepository repo;

    @Autowired
    private UserUpsertService production;

    @Test
    @DisplayName("AC-8: user_id format — 100 個不同 sub 全部產出符合 u_<6hex> regex")
    @Tag("AC-8")
    void allGeneratedUserIdsMatchRegex() {
        // 100 個 distinct sub upsert，全走 INSERT 路徑（不撞 oauth_provider+sub UNIQUE）
        for (int i = 0; i < 100; i++) {
            var sub = "test-sub-" + uniqueSuffix();
            var email = "test-" + uniqueSuffix() + "@example.com";
            var user = production.upsertFromOidc("google", sub, email, "Test User " + i, null);
            assertThat(user.getId())
                    .as("user_id 必須符合 u_<6hex> regex")
                    .matches("^u_[0-9a-f]{6}$");
        }
    }

    @Test
    @DisplayName("AC-8: handle collision — 同 email local-part 第二位拿 alice-2 後綴")
    @Tag("AC-8")
    void handleCollisionRetryAddsSuffix() {
        var aliceLocal = "alice-" + uniqueSuffix();
        var aliceEmail = aliceLocal + "@first.com";
        var aliceUser = production.upsertFromOidc("google", "sub-A-" + uniqueSuffix(),
                aliceEmail, "Alice", null);
        assertThat(aliceUser.getHandle()).isEqualTo(aliceLocal);

        // Bob 用同 local-part 但不同 domain（slugify 後同 handle 撞）
        var bobEmail = aliceLocal + "@second.com";
        var bobUser = production.upsertFromOidc("google", "sub-B-" + uniqueSuffix(),
                bobEmail, "Bob", null);
        assertThat(bobUser.getHandle()).isEqualTo(aliceLocal + "-2");
    }

    @Test
    @DisplayName("AC-8: 第二次 /me UPSERT 不重複建 row（同 oauth_provider+sub UNIQUE 走 UPDATE）")
    @Tag("AC-8")
    void secondUpsertRefreshesExisting() {
        var sub = "stable-sub-" + uniqueSuffix();
        var firstEmail = "first-" + uniqueSuffix() + "@example.com";
        var first = production.upsertFromOidc("google", sub, firstEmail, "First Name", null);

        // 模擬第二次登入：name 改 + email 改
        var secondEmail = "second-" + uniqueSuffix() + "@example.com";
        var second = production.upsertFromOidc("google", sub, secondEmail, "Second Name",
                "https://avatar/x.png");

        assertThat(second.getId()).as("user_id 不變").isEqualTo(first.getId());
        assertThat(second.getEmail()).isEqualTo(secondEmail);
        assertThat(second.getName()).isEqualTo("Second Name");
        assertThat(second.getAvatarUrl()).isEqualTo("https://avatar/x.png");
        // DB 層只有 1 row（不會多一筆）
        assertThat(repo.findByOauthProviderAndSub("google", sub)).isPresent();
    }

    @Test
    @DisplayName("AC-8: user_id collision retry — stub supplier 強制 5 次撞 → IllegalStateException")
    @Tag("AC-8")
    void userIdCollisionExhaustsRetryAndThrows() {
        // 先建一個 row 佔住 "u_zzz999"
        var taken = "u_zzz999";
        production.upsertFromOidc("google", "blocker-" + uniqueSuffix(),
                "blocker-" + uniqueSuffix() + "@example.com", "Blocker", null);
        // 直接 force：手動 insert 一個 user_id 為 taken 的 row
        repo.save(User.createNew(taken, "google", "ghost-" + uniqueSuffix(),
                "ghost-" + uniqueSuffix() + "@example.com", "Ghost",
                "ghost-" + uniqueSuffix(), null, java.time.Instant.now()));

        // visible-for-test ctor 注入永遠回傳 taken 的 supplier → 5 次都撞 → throw
        var collisionService = new UserUpsertService(repo,
                () -> taken,
                UserUpsertService::defaultHandleFallbackSupplier);

        assertThatThrownBy(() -> collisionService.upsertFromOidc(
                "google", "new-sub-" + uniqueSuffix(),
                "new-" + uniqueSuffix() + "@example.com", "New User", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to generate unique user_id after 5 retries");
    }

    @Test
    @DisplayName("AC-8: user_id retry — 前 4 次撞、第 5 次成功 → 不 throw")
    @Tag("AC-8")
    void userIdCollisionRetrySucceedsOnLastAttempt() {
        var taken = "u_yyy888";
        repo.save(User.createNew(taken, "google", "y8-sub-" + uniqueSuffix(),
                "y8-" + uniqueSuffix() + "@example.com", "Y8",
                "y8-" + uniqueSuffix(), null, java.time.Instant.now()));

        // Stub supplier：前 4 次回 taken，第 5 次回 unique 值
        var attempts = new AtomicInteger(0);
        var rescueId = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        var retryService = new UserUpsertService(repo,
                () -> attempts.incrementAndGet() < 5 ? taken : rescueId,
                UserUpsertService::defaultHandleFallbackSupplier);

        var user = retryService.upsertFromOidc("google", "rescue-sub-" + uniqueSuffix(),
                "rescue-" + uniqueSuffix() + "@example.com", "Rescue", null);

        assertThat(user.getId()).isEqualTo(rescueId);
        assertThat(attempts.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("AC-8: slugify — 純數字 email local-part fallback 為 user-<6hex>")
    @Tag("AC-8")
    void slugifyDigitsOnlyFallsBackToUserPrefix() {
        var user = production.upsertFromOidc("google", "digits-sub-" + uniqueSuffix(),
                "12345@example.com", "Digits Only", null);

        // "12345" 是純數字 → fallback user-<6hex>
        assertThat(user.getHandle()).matches("^user-[0-9a-f]{6}$");
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
