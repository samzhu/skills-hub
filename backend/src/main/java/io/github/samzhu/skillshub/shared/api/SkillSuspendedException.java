package io.github.samzhu.skillshub.shared.api;

/**
 * S029：SUSPENDED skill 操作被拒之 sentinel exception。
 *
 * <p>{@link io.github.samzhu.skillshub.skill.domain.SkillStatus#SUSPENDED} 設計意圖
 * 為「因安全風險或違規而下架，不可被任何 read 路徑訪問」（per S018 state machine Javadoc）。
 * {@link io.github.samzhu.skillshub.skill.query.SkillQueryService#downloadLatest} /
 * {@code downloadVersion} 與 S074 引入的
 * {@link io.github.samzhu.skillshub.skill.query.FileBrowserService}
 * (list / read 兩 endpoint) 在取 aggregate 後若 status=SUSPENDED 皆拋本例外。
 *
 * <p>由 {@link GlobalExceptionHandler#handleSuspended} 攔截 → HTTP 403 Forbidden +
 * {@code ErrorResponse{code:"SKILL_SUSPENDED"}}；403 而非 410 因 SUSPENDED 可被
 * admin reactivate（非永久），與 410 Gone 的「permanent removal」語意不符。
 *
 * <p>S079: message 改為 operation-agnostic 「is suspended and not accessible」，
 * 同時涵蓋 download 與 file-browser 路徑（先前寫死「cannot be downloaded」對 /files
 * 路徑誤導）。FE i18n 用 error code 對應 localized string，不依賴 message 內容。
 *
 * @see io.github.samzhu.skillshub.skill.domain.SkillStatus#SUSPENDED
 * @see GlobalExceptionHandler#handleSuspended
 */
public class SkillSuspendedException extends RuntimeException {

    private final String skillId;

    public SkillSuspendedException(String skillId) {
        super("Skill is suspended and not accessible: " + skillId);
        this.skillId = skillId;
    }

    public String getSkillId() {
        return skillId;
    }
}
