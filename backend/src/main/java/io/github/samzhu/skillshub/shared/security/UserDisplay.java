package io.github.samzhu.skillshub.shared.security;

import org.jspecify.annotations.Nullable;

/**
 * S192 — user-facing display companion for platform user ids.
 *
 * <p>DTO projections keep {@link #userId()} for behavior checks and use the
 * nullable display fields for normal UI labels.
 *
 * @param userId platform user id, e.g. {@code u_f7eb3a}
 * @param displayName human-readable label; never the same raw {@code userId}
 * @param handle public handle used by links and install commands when present
 * @param email email only when caller explicitly requests exposure
 * @see UserDisplayService
 */
public record UserDisplay(
        String userId,
        @Nullable String displayName,
        @Nullable String handle,
        @Nullable String email
) {
}
