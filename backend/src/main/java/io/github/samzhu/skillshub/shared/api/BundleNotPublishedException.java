package io.github.samzhu.skillshub.shared.api;

/**
 * S098a3-2: skill 存在但無 published version（DRAFT 狀態 / 從未發版）。
 * GlobalExceptionHandler → 404 + {@code error: "bundle_not_published"}（區分於 skill_not_found）。
 *
 * <p>對齊既有 RequestNotFoundException / CollectionNotFoundException naming 慣例；
 * 給 frontend i18n key 對應 zh-TW 訊息（如「此技能尚未發布版本」）用。
 */
public class BundleNotPublishedException extends RuntimeException {
    public BundleNotPublishedException(String skillId) {
        super("bundle_not_published: " + skillId);
    }
}
