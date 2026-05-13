package io.github.samzhu.skillshub.shared.api;

import java.util.List;

/**
 * S096f2-T02 — 一個或多個 skillId 不存在或非 PUBLISHED 狀態（不可被加入 Collection）。
 * GlobalExceptionHandler → 400 + {@code error: "skill_not_publishable"}。
 *
 * <p>對齊 RequestNotFoundException naming convention；攜帶 {@code invalidSkillIds} list
 * 給 frontend 顯示哪些 skill IDs invalid（CollectionService.create 多 skillIds 場景特別有用）。
 *
 * <p><b>S156c voting-board pivot</b>：原 RequestService.fulfill caller 已隨 claim/release/fulfill
 * 機制拆除（spec S156c §2.3）；現僅 CollectionService 使用此 exception。
 */
public class SkillNotPublishableException extends RuntimeException {

    private final List<String> invalidSkillIds;

    public SkillNotPublishableException(List<String> invalidSkillIds) {
        super("skill_not_publishable: " + String.join(",", invalidSkillIds));
        this.invalidSkillIds = invalidSkillIds == null ? List.of() : List.copyOf(invalidSkillIds);
    }

    /** Single-skill convenience — Request.fulfill 只驗一個 skill 用此 ctor。 */
    public SkillNotPublishableException(String invalidSkillId, String reason) {
        super("skill_not_publishable: " + invalidSkillId + " (" + reason + ")");
        this.invalidSkillIds = List.of(invalidSkillId);
    }

    public List<String> getInvalidSkillIds() {
        return invalidSkillIds;
    }
}
