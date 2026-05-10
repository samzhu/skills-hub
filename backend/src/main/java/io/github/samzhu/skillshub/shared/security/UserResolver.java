package io.github.samzhu.skillshub.shared.security;

import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * S154 — 使用者識別字串 → platform {@code user_id} 解析器。
 *
 * <p>單一輸入字串可能是 user_id / handle / email 三種其中之一；本 service 依序嘗試，
 * 回傳第一個 match 的 user_id（無 match → {@link Optional#empty()}）。
 *
 * <p>使用場景：
 * <ul>
 *   <li>{@code GET /api/v1/skills/{author}/{name}} 第一段 path 可能是 handle 或 user_id（向下相容）</li>
 *   <li>後台找人 by email</li>
 * </ul>
 *
 * <p>Resolution order（spec §2.6）：
 * <ol>
 *   <li>看起來像 user_id（{@code u_<6hex>} regex）→ {@link UserRepository#findById}</li>
 *   <li>含 {@code @} → 視為 email，{@link UserRepository#findFirstByEmail}</li>
 *   <li>其他 → 視為 handle，{@link UserRepository#findByHandle}</li>
 * </ol>
 *
 * @see UserRepository
 * @see DisplayNameResolver
 */
@Service
public class UserResolver {

    /** Platform user_id 格式：u_ 加 6 個小寫 hex。對齊 V18 schema 與 spec §2.2。*/
    private static final Pattern USER_ID = Pattern.compile("^u_[0-9a-f]{6}$");

    private final UserRepository repo;

    public UserResolver(UserRepository repo) {
        this.repo = repo;
    }

    /**
     * 解析輸入字串 → user_id。
     *
     * @param input 可能是 user_id / email / handle 其中之一
     * @return 第一個 match 的 user_id；無 match 回 empty
     */
    public Optional<String> resolveByEmailHandleOrId(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        // 1. user_id 格式直走 PK lookup（最快路徑）
        if (USER_ID.matcher(input).matches()) {
            return repo.findById(input).map(User::getId);
        }
        // 2. 含 @ → email lookup（idx_users_email 加速）
        if (input.contains("@")) {
            return repo.findFirstByEmail(input).map(User::getId);
        }
        // 3. 其他一律視為 handle（UNIQUE constraint 保證最多 1 筆）
        return repo.findByHandle(input).map(User::getId);
    }
}
