package io.github.samzhu.skillshub.shared.api;

/** S114a AC-5: actor is not the skill owner — GlobalExceptionHandler maps to 403 not_skill_owner. */
public class NotSkillOwnerException extends RuntimeException {

    public NotSkillOwnerException() {
        super("not_skill_owner");
    }
}
