package io.github.samzhu.skillshub.shared.security;

import java.util.Optional;

import org.springframework.data.repository.ListCrudRepository;

/**
 * S154 — Spring Data JDBC repository for {@link User} platform identity rows.
 *
 * <p>Derived query methods 用於：
 * <ul>
 *   <li>{@link #findByOauthProviderAndSub} — {@link UserUpsertService} 第一查詢點，決定 INSERT vs UPDATE</li>
 *   <li>{@link #findByHandle} — {@link UserResolver} resolve 「handle → user_id」；
 *       {@link UserUpsertService} handle collision retry 用</li>
 *   <li>{@link #findByEmail} — {@link UserResolver} resolve 「email → user_id」</li>
 * </ul>
 *
 * @see User
 */
public interface UserRepository extends ListCrudRepository<User, String> {

    /** 同 provider 內 sub 唯一（V18 schema UNIQUE constraint）— 第一查詢點：判斷該 OIDC subject 是否已建 user。*/
    Optional<User> findByOauthProviderAndSub(String oauthProvider, String sub);

    /** Handle 全平台 UNIQUE — UserResolver / UpsertService collision retry 用。*/
    Optional<User> findByHandle(String handle);

    /** Email 非 UNIQUE（同 email 跨 provider 理論上可能）— 第一筆 match 為 resolver fallback。*/
    Optional<User> findFirstByEmail(String email);
}
