package io.github.samzhu.skillshub.shared.api;

/**
 * S098e3 AC-7 — Flag status transition 違規（如 RESOLVED → OPEN 或 unknown status）。
 * GlobalExceptionHandler → 400 + {@code error: "invalid_status_transition"}。
 */
public class InvalidStatusTransitionException extends RuntimeException {
    public InvalidStatusTransitionException(String message) {
        super(message);
    }
}
