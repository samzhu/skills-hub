package io.github.samzhu.skillshub.skill.domain;

/**
 * S116 — Skill visibility model（GitHub repo 可見性 model）。
 *
 * <p>derived from {@code acl_entries} JSONB 是否含 {@code "*:read"} synthetic
 * public principal — 對齊 S038 既驗 listEntries 識別 *:read 慣例 + S026
 * public-read default。MVP 不擴 schema 單獨 column；S114a 設計中的
 * {@code is_public} GENERATED column 未來可從同 acl_entries derive 接管。
 *
 * <p><b>Default = PUBLIC</b>：對齊 v3.x 既有行為（所有 skill 預設加 *:read）；
 * 既有 4-arg {@link io.github.samzhu.skillshub.skill.command.CreateSkillCommand}
 * ctor delegate to 5-arg with PUBLIC default，零 caller migration。
 */
public enum Visibility {

    /** 任何人可讀（含 anonymous）— acl_entries 含 {@code "*:read"}；S116 default + v3.x 既有行為。 */
    PUBLIC,

    /** 僅 owner + 顯式 grant 的 user 可讀；acl_entries 不含 {@code "*:read"}。 */
    PRIVATE;

    public static Visibility defaultValue() {
        return PUBLIC;
    }
}
