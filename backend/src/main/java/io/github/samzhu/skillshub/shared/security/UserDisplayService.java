package io.github.samzhu.skillshub.shared.security;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * S192 — resolves platform user ids into user-facing display companions.
 *
 * <p>Low-level {@link DisplayNameResolver} may fall back to raw {@code u_<id>}
 * for technical/debug call sites. This service is the DTO-facing guard: normal
 * UI display fields get {@code null} when no human label exists, forcing the
 * projection or fixture to provide real display data.
 *
 * @see User
 * @see UserRepository
 */
@Service
public class UserDisplayService {

    private static final Logger log = LoggerFactory.getLogger(UserDisplayService.class);

    private final UserRepository users;

    public UserDisplayService(UserRepository users) {
        this.users = users;
    }

    /**
     * Resolves one platform user id.
     *
     * @param userId platform user id, e.g. {@code u_f7eb3a}
     * @param exposeEmail whether the returned companion may include email
     * @return display companion preserving {@code userId} for behavior checks
     */
    public UserDisplay resolve(String userId, boolean exposeEmail) {
        return users.findById(userId)
                .map(user -> fromUser(user, exposeEmail))
                .orElseGet(() -> {
                    log.debug("S192 user_display_missing userId={}", userId);
                    return new UserDisplay(userId, null, null, null);
                });
    }

    /**
     * Resolves distinct ids and keeps first-seen key order in the returned map.
     *
     * @param userIds platform user ids; null entries are ignored
     * @param exposeEmail whether returned companions may include email
     * @return map keyed by platform user id
     */
    public Map<String, UserDisplay> resolveAll(Collection<String> userIds, boolean exposeEmail) {
        var distinctIds = userIds.stream().filter(Objects::nonNull).distinct().toList();
        var resolved = new LinkedHashMap<String, UserDisplay>();
        for (User user : users.findAllById(distinctIds)) {
            resolved.put(user.getId(), fromUser(user, exposeEmail));
        }

        var result = new LinkedHashMap<String, UserDisplay>();
        for (String userId : distinctIds) {
            var display = resolved.get(userId);
            if (display == null) {
                log.debug("S192 user_display_missing userId={}", userId);
                display = new UserDisplay(userId, null, null, null);
            }
            result.put(userId, display);
        }
        return result;
    }

    private static UserDisplay fromUser(User user, boolean exposeEmail) {
        var displayName = DisplayNameResolver.resolve(
                user.getName(),
                null,
                null,
                user.getEmail(),
                user.getHandle(),
                user.getId());
        var guardedDisplayName = user.getId().equals(displayName) ? null : displayName;
        return new UserDisplay(
                user.getId(),
                blankToNull(guardedDisplayName),
                blankToNull(user.getHandle()),
                exposeEmail ? blankToNull(user.getEmail()) : null);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
