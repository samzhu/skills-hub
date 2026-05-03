package io.github.samzhu.skillshub.shared.api;

import java.util.List;

/**
 * S096f2-T02 — 一個或多個 skillId 不存在或非 PUBLISHED 狀態（不可被加入 Collection /
 * 不可作為 Request fulfillment）。GlobalExceptionHandler → 400 + {@code error: "skill_not_publishable"}。
 *
 * <p>取代 S096g2 RequestService.fulfill 既有 `IllegalArgumentException("skill_not_publishable: ...")`
 * 路徑：升級為獨立 exception class 對齊 RequestNotFoundException / NotRequestClaimerException naming，
 * 給 GlobalExceptionHandler 路由更精確 + 攜帶 {@code invalidSkillIds} list 給 frontend 顯示
 * 哪些 skill IDs invalid（CollectionService.create 多 skillIds 場景特別有用）。
 *
 * <p><b>Caller migration</b>：S096g2 RequestService.fulfill 同 commit 一併改用此 exception；
 * RequestServiceTest AC-12 assertion 同步從 `IllegalArgumentException` 改為本 class。
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
