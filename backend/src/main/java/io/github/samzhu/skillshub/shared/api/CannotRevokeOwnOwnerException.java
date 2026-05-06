package io.github.samzhu.skillshub.shared.api;

/** S114a: owner cannot revoke their own OWNER grant — GlobalExceptionHandler maps to 403 cannot_revoke_own_owner. */
public class CannotRevokeOwnOwnerException extends RuntimeException {

    public CannotRevokeOwnOwnerException() {
        super("cannot_revoke_own_owner");
    }
}
