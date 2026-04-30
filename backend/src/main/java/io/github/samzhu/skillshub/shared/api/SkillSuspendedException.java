package io.github.samzhu.skillshub.shared.api;

/**
 * S029：SUSPENDED skill 下載被拒之 sentinel exception。
 *
 * <p>{@link io.github.samzhu.skillshub.skill.domain.SkillStatus#SUSPENDED} 設計意圖
 * 為「因安全風險或違規而下架，不可下載」（per S018 state machine Javadoc）；
 * {@link io.github.samzhu.skillshub.skill.query.SkillQueryService#downloadLatest} /
 * {@code downloadVersion} 在取 aggregate 後若 status=SUSPENDED 即拋本例外。
 *
 * <p>由 {@link GlobalExceptionHandler#handleSuspended} 攔截 → HTTP 403 Forbidden +
 * {@code ErrorResponse{code:"SKILL_SUSPENDED"}}；403 而非 410 因 SUSPENDED 可被
 * admin reactivate（非永久），與 410 Gone 的「permanent removal」語意不符。
 *
 * @see io.github.samzhu.skillshub.skill.domain.SkillStatus#SUSPENDED
 * @see GlobalExceptionHandler#handleSuspended
 */
public class SkillSuspendedException extends RuntimeException {

    private final String skillId;

    public SkillSuspendedException(String skillId) {
        super("Skill is suspended and cannot be downloaded: " + skillId);
        this.skillId = skillId;
    }

    public String getSkillId() {
        return skillId;
    }
}
