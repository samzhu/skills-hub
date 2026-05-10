package io.github.samzhu.skillshub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;

/**
 * S154-T02 Scenario E — {@link UserResolver} resolve 三種輸入路徑（user_id / email / handle）。
 *
 * <p>Slice test：{@code @Import(UserResolver.class)} 引入 service（{@code @DataJdbcTest} 預設只掃 repo），
 * 對 Testcontainers PostgreSQL 跑真 query。
 */
@Import(UserResolver.class)
class UserResolverTest extends RepositorySliceTestBase {

    @Autowired private UserResolver resolver;
    @Autowired private UserRepository repo;

    @Test
    @DisplayName("AC-7: 輸入 email → 回對應 user_id")
    @Tag("AC-7")
    void resolveByEmail() {
        var userId = "u_aaa111";
        var email = "alice-" + uniqueSuffix() + "@example.com";
        repo.save(User.createNew(userId, "google", "sub-" + uniqueSuffix(),
                email, "Alice", "alice-" + uniqueSuffix(), null, Instant.now()));

        assertThat(resolver.resolveByEmailHandleOrId(email)).contains(userId);
    }

    @Test
    @DisplayName("AC-7: 輸入 handle → 回對應 user_id")
    @Tag("AC-7")
    void resolveByHandle() {
        var userId = "u_bbb222";
        var handle = "bob-" + uniqueSuffix();
        repo.save(User.createNew(userId, "google", "sub-" + uniqueSuffix(),
                "bob@example.com", "Bob", handle, null, Instant.now()));

        assertThat(resolver.resolveByEmailHandleOrId(handle)).contains(userId);
    }

    @Test
    @DisplayName("AC-7: 輸入 user_id 格式直走 PK lookup")
    @Tag("AC-7")
    void resolveByUserId() {
        var userId = "u_ccc333";
        repo.save(User.createNew(userId, "google", "sub-" + uniqueSuffix(),
                "carol@example.com", "Carol", "carol-" + uniqueSuffix(), null, Instant.now()));

        assertThat(resolver.resolveByEmailHandleOrId(userId)).contains(userId);
    }

    @Test
    @DisplayName("AC-7: T05 sub fallback — 老 install command 用 Google sub raw 仍 resolve")
    @Tag("AC-7")
    void resolveByOauthSubBackwardCompat() {
        // 老 install command 用 OAuth sub raw（"111161306011023995106"）— 4 條 fallback 鏈最後一條
        var userId = "u_dddff";
        var googleSub = "111161306011023995106";
        repo.save(User.createNew(userId, "google", googleSub,
                "alice-sub@example.com", "Alice", "alice-sub-" + uniqueSuffix(),
                null, Instant.now()));

        // 直接傳 sub raw — 不像 user_id 格式、不含 @、handle 表沒對應 → 最後 fallback findByOauthProviderAndSub
        assertThat(resolver.resolveByEmailHandleOrId(googleSub)).contains(userId);
    }

    @Test
    @DisplayName("AC-7: 不存在 → Optional.empty()")
    @Tag("AC-7")
    void resolveNonexistent() {
        assertThat(resolver.resolveByEmailHandleOrId("nonexistent-" + uniqueSuffix() + "@x.com"))
                .isEmpty();
        assertThat(resolver.resolveByEmailHandleOrId("nonexistent-handle-" + uniqueSuffix()))
                .isEmpty();
        assertThat(resolver.resolveByEmailHandleOrId("u_zzzzzz")).isEmpty();
    }

    @Test
    @DisplayName("AC-7: 輸入 null / 空字串 → Optional.empty()")
    @Tag("AC-7")
    void resolveBlankInput() {
        assertThat(resolver.resolveByEmailHandleOrId(null)).isEmpty();
        assertThat(resolver.resolveByEmailHandleOrId("")).isEmpty();
        assertThat(resolver.resolveByEmailHandleOrId("   ")).isEmpty();
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
