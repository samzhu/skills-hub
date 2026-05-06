package io.github.samzhu.skillshub.skill.domain;

/**
 * S116 — Skill visibility model（GitHub repo 可見性 model）。
 *
 * <p>derived from {@code acl_entries} JSONB 是否含 {@code "public:*:read"} entry —
 * S114a 統一改用 3-segment 格式（V17 backfill 轉換既有 {@code "*:read"} 資料）。
 * {@code is_public} GENERATED ALWAYS column 在 V16 migration 加入，從 acl_entries derive。
 *
 * <p><b>Default = PUBLIC</b>：對齊 v3.x 既有行為（所有 skill 預設加 {@code "public:*:read"}）；
 * 既有 4-arg {@link io.github.samzhu.skillshub.skill.command.CreateSkillCommand}
 * ctor delegate to 5-arg with PUBLIC default，零 caller migration。
 */
public enum Visibility {

    /** 任何人可讀（含 anonymous）— acl_entries 含 {@code "public:*:read"}；S116 default + v3.x 既有行為。 */
    PUBLIC,

    /** 僅 owner + 顯式 grant 的 user 可讀；acl_entries 不含 {@code "public:*:read"}。 */
    PRIVATE;

    public static Visibility defaultValue() {
        return PUBLIC;
    }
}
