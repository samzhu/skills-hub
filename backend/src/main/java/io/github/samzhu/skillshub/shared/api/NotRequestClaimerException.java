package io.github.samzhu.skillshub.shared.api;

/**
 * S096g2 AC-9 / AC-11 — Request release/fulfill 操作被拒（requester 非 claimer）。
 * GlobalExceptionHandler → 403 + {@code error: "not_request_claimer"}。
 */
public class NotRequestClaimerException extends RuntimeException {
    public NotRequestClaimerException() {
        super("not_request_claimer");
    }
}
