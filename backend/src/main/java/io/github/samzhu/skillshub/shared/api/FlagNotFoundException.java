package io.github.samzhu.skillshub.shared.api;

/**
 * S098e3 AC-8 — Flag id 不存在。GlobalExceptionHandler → 404 + {@code error: "flag_not_found"}。
 */
public class FlagNotFoundException extends RuntimeException {
    public FlagNotFoundException(String flagId) {
        super("flag_not_found: " + flagId);
    }
}
