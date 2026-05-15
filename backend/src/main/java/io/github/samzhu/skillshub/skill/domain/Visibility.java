package io.github.samzhu.skillshub.skill.domain;

/**
 * S116 — Skill visibility model（GitHub repo 可見性 model）。
 *
 * <p>S177 起由 {@code skills.is_public} ordinary boolean 直接保存公開狀態；
 * {@code acl_entries} 只保存 user/group/company 明確授權，不再包含 {@code "public:*:read"}。
 *
 * <p><b>Default = PUBLIC</b>：對齊 v3.x 既有行為（新 skill 預設公開可讀）；
 * 既有 4-arg {@link io.github.samzhu.skillshub.skill.command.CreateSkillCommand}
 * ctor delegate to 5-arg with PUBLIC default，零 caller migration。
 */
public enum Visibility {

    /** 任何人可讀（含 anonymous）— {@code skills.is_public=true}。 */
    PUBLIC,

    /** 僅 owner + 顯式 grant 的 user 可讀；{@code skills.is_public=false}。 */
    PRIVATE;

    public static Visibility defaultValue() {
        return PUBLIC;
    }
}
