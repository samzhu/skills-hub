package io.github.samzhu.skillshub.shared.api;

/** S142b AC-S142b-10: 安全掃描尚未完成 → GlobalExceptionHandler 映射為 404 SECURITY_NOT_SCANNED。 */
public class SecurityNotScannedException extends RuntimeException {

    public SecurityNotScannedException(String skillId) {
        super("Security report will be available shortly after publish.");
    }
}
