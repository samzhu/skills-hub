package io.github.samzhu.skillshub.shared.api;

/** S114a AC-4: grant row not found by id — GlobalExceptionHandler maps to 404 grant_not_found. */
public class GrantNotFoundException extends RuntimeException {

    public GrantNotFoundException() {
        super("grant_not_found");
    }
}
