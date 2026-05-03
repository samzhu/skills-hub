package io.github.samzhu.skillshub.shared.api;

/**
 * S115 AC-1 — JWT 通過簽名驗證但 {@code sub} claim 缺失 / blank。
 *
 * <p>{@code sub} 為唯一 REQUIRED claim（per ADR-006）— 缺即無法 audit / ACL match，
 * 不走 graceful fallback；GlobalExceptionHandler → 401 + WWW-Authenticate header
 * (RFC 6750 Bearer error="invalid_token")。
 *
 * <p>取代既有 `jwt.getName()` 對 sub=null 的 NPE 路徑（500 錯誤 → 改 401）。
 * 對齊 RequestNotFoundException / NotRequestClaimerException naming convention。
 */
public class MissingJwtSubException extends RuntimeException {
    public MissingJwtSubException() {
        super("missing sub claim");
    }
}
