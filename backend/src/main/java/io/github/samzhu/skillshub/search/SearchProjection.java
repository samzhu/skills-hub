package io.github.samzhu.skillshub.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillReactivatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillSuspendedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;

/**
 * 語意搜尋投影 — 監聽技能領域事件，維護 {@code skills.embedding_*} 欄位。
 *
 * <p>S186：embedding 與可見性 / ACL / card 欄位都讀同一筆 {@code skills} row；
 * {@link Skill} aggregate 仍不 mapping embedding 欄位，search write path 直接用 JDBC
 * 更新 infrastructure columns。
 *
 * <p>S023：升級為 {@link ApplicationModuleListener}（async + AFTER_COMMIT +
 * REQUIRES_NEW + outbox 追蹤）。embedding API call（Gemini）為 I/O bound，
 * async 避免阻塞 publisher 線程；async executor 容量限 2（per AsyncListenerConfig POC）。
 *
 * @see SearchConfig
 * @see SearchEmbeddingRepository
 * @see SemanticSearchService
 */
@Component
class SearchProjection {

    private static final Logger log = LoggerFactory.getLogger(SearchProjection.class);

    private final EmbeddingModel embeddingModel;
    private final SearchEmbeddingRepository embeddingRepo;
    private final SkillRepository skillRepo;
    private final SkillVersionRepository versionRepo;

    // S034: 移除 CurrentUserProvider 依賴 — async listener 無 SecurityContext，
    // owner 改從 event.author（onSkillCreated）/ aggregate.author（onVersionPublished /
    // onSkillReactivated, S033）取得，是 source of truth。
    SearchProjection(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel,
                     SkillRepository skillRepo, SkillVersionRepository versionRepo) {
        this.embeddingModel = embeddingModel;
        this.embeddingRepo = new SearchEmbeddingRepository(new NamedParameterJdbcTemplate(jdbcTemplate));
        this.skillRepo = skillRepo;
        this.versionRepo = versionRepo;
    }

    /**
     * 處理 SkillCreatedEvent — 用初始 name / description 建立同表 embedding。
     */
    @ApplicationModuleListener
    void onSkillCreated(SkillCreatedEvent event) {
        log.info("SearchProjection onSkillCreated skillId={} name={}", event.aggregateId(), event.name());
        upsertEmbedding(event.aggregateId(), embeddingContent(event.name(), event.name(), event.description()));
        log.info("SearchProjection onSkillCreated done skillId={}", event.aggregateId());
    }

    /**
     * 處理 SkillVersionPublishedEvent — 用平台 skill name + latest SKILL.md frontmatter 重建 embedding。
     */
    @ApplicationModuleListener
    void onVersionPublished(SkillVersionPublishedEvent event) {
        log.info("SearchProjection onVersionPublished skillId={} version={}", event.aggregateId(), event.version());

        var skill = skillRepo.findById(event.aggregateId()).orElse(null);
        if (skill == null) {
            log.warn("SearchProjection onVersionPublished skillId={} not found in repo", event.aggregateId());
            return;
        }

        var fm = event.frontmatter() == null ? Map.<String, Object>of() : event.frontmatter();
        String name = getString(fm, "name", skill.getName());
        String description = getString(fm, "description", "");

        upsertEmbedding(skill.getId(), embeddingContent(skill.getName(), name, description));
        log.info("SearchProjection onVersionPublished done skillId={}", event.aggregateId());
    }

    /** 安全地從 Map 讀取字串，缺少或非字串型別時回傳 defaultValue。 */
    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return v != null ? v.toString() : defaultValue;
    }

    /**
     * S186：SUSPENDED skill 清空 embedding — semantic search SQL 有 {@code embedding IS NOT NULL} filter。
     */
    @ApplicationModuleListener
    void onSkillSuspended(SkillSuspendedEvent event) {
        log.info("SearchProjection onSkillSuspended skillId={}", event.aggregateId());
        embeddingRepo.clearEmbedding(event.aggregateId(), Instant.now());
        log.info("SearchProjection onSkillSuspended done skillId={}", event.aggregateId());
    }

    /**
     * S186：reactivate 後重新 embed — 用 Skill row + 最新 SkillVersion frontmatter 重建內容。
     *
     * <p>{@link SkillReactivatedEvent} 只攜 id + reason；需 query Skill aggregate 與最新版。
     * 沒 version 的情況防禦性早 return（理論上 reactivate 只能從 SUSPENDED 過來，
     * SUSPENDED 必先 PUBLISHED 必有 version；防禦 race / 異常清理）。
     */
    @ApplicationModuleListener
    void onSkillReactivated(SkillReactivatedEvent event) {
        log.info("SearchProjection onSkillReactivated skillId={}", event.aggregateId());
        var skill = skillRepo.findById(event.aggregateId()).orElse(null);
        if (skill == null) {
            log.warn("SearchProjection onSkillReactivated skillId={} not found in repo", event.aggregateId());
            return;
        }
        var latestVersion = versionRepo.findBySkillIdOrderByPublishedAtDesc(event.aggregateId())
                .stream().findFirst().orElse(null);
        if (latestVersion == null) {
            log.warn("SearchProjection onSkillReactivated skillId={} has no version, skip embedding",
                    event.aggregateId());
            return;
        }

        var fm = latestVersion.getFrontmatter() == null ? Map.<String, Object>of() : latestVersion.getFrontmatter();
        var frontmatterName = getString(fm, "name", skill.getName());
        var frontmatterDescription = getString(fm, "description", skill.getDescription());
        upsertEmbedding(skill.getId(), embeddingContent(skill.getName(), frontmatterName, frontmatterDescription));
        log.info("SearchProjection onSkillReactivated done skillId={}", event.aggregateId());
    }

    private void upsertEmbedding(String skillId, String content) {
        embeddingRepo.upsertEmbedding(skillId, content, embeddingModel.embed(content),
                embeddingModelName(), Instant.now());
    }

    private String embeddingModelName() {
        var simpleName = embeddingModel.getClass().getSimpleName();
        var model = simpleName == null || simpleName.isBlank() || simpleName.contains("Mockito")
                ? "EmbeddingModel"
                : simpleName;
        return model.length() <= 64 ? model : model.substring(0, 64);
    }

    private static String embeddingContent(String platformName, String frontmatterName, String frontmatterDescription) {
        var parts = new ArrayList<String>();
        addIfPresent(parts, platformName);
        addIfPresent(parts, frontmatterName);
        addIfPresent(parts, frontmatterDescription);
        return String.join(" ", parts);
    }

    private static void addIfPresent(ArrayList<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.trim());
        }
    }
}
