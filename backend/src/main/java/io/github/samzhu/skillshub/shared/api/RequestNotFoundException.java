package io.github.samzhu.skillshub.shared.api;

/**
 * S096g2 — Request id 不存在。GlobalExceptionHandler → 404 + {@code error: "request_not_found"}。
 */
public class RequestNotFoundException extends RuntimeException {
    public RequestNotFoundException(String requestId) {
        super("request_not_found: " + requestId);
    }
}
