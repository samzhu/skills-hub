package io.github.samzhu.skillshub.shared.api;

/**
 * S098e2 AC-7 — Review 操作被拒（requester 非原作者）。
 *
 * <p>由 {@code Review.deleteBy(requesterId)} 在 author 不符時拋出；
 * {@link GlobalExceptionHandler} 攔截 → HTTP 403 + {@code ErrorResponse{code:"FORBIDDEN", message:"not_review_author"}}。
 *
 * <p>放在 {@code shared/api} package 與 {@link SkillSuspendedException} 同 pattern —
 * domain exception 但 HTTP layer 需識別，放共享 module 避免 GlobalExceptionHandler
 * 反向依賴 review module。
 */
public class ReviewForbiddenException extends RuntimeException {
    public ReviewForbiddenException(String code) {
        super(code);
    }
}
