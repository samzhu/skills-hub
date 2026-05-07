package io.github.samzhu.skillshub.skill.testsupport;

import io.github.samzhu.skillshub.skill.domain.Visibility;

/**
 * S140 T01 — {@code POST /internal/test/seed/skill} 入參。
 *
 * <p>{@code skillMdContent} 為 null 時，{@link TestDataController#seedSkill} 會用
 * {@code name / description / author} 合成 minimal SKILL.md。
 *
 * @param name           skill 顯示名（必填）
 * @param description    skill 簡述（必填；用於合成 SKILL.md frontmatter）
 * @param author         作者識別（必填）
 * @param category       分類，例如 {@code "DevOps"} / {@code "Testing"}（必填）
 * @param version        版本號；null → {@code "1.0.0"}
 * @param visibility     可見性；null → {@link Visibility#PUBLIC}
 * @param skillMdContent 完整 SKILL.md（含 frontmatter）內容；null → controller 自動合成
 */
public record SeedSkillRequest(
        String name,
        String description,
        String author,
        String category,
        String version,
        Visibility visibility,
        String skillMdContent) {}
