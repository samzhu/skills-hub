package io.github.samzhu.skillshub.shared.api;

/** S114a AC-5: skill already has an OWNER grant — GlobalExceptionHandler maps to 409 owner_already_exists. */
public class OwnerAlreadyExistsException extends RuntimeException {

    public OwnerAlreadyExistsException() {
        super("owner_already_exists");
    }
}
