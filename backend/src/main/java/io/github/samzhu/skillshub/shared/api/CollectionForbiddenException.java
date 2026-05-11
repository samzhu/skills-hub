package io.github.samzhu.skillshub.shared.api;

/**
 * S164 — Collection 修改 / 刪除被拒（requester 非 owner）。
 *
 * <p>由 {@code CollectionService.update(...)} / {@code .delete(...)} 在 ownerId 不符時拋出；
 * {@link GlobalExceptionHandler} 攔截 → HTTP 403 + {@code ErrorResponse{code:"FORBIDDEN", message:"not_collection_owner"}}。
 *
 * <p>放在 {@code shared/api} package 與 {@link ReviewForbiddenException} 同 pattern —
 * domain exception 但 HTTP layer 需識別，放共享 module 避免 GlobalExceptionHandler
 * 反向依賴 community module。
 */
public class CollectionForbiddenException extends RuntimeException {
    public CollectionForbiddenException(String code) {
        super(code);
    }
}
