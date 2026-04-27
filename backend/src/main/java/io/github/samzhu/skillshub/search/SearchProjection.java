package io.github.samzhu.skillshub.search;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;

/**
 * 語意搜尋投影 — 監聽技能領域事件，維護 vector_store 表的 embedding。
 *
 * <p>當 {@link SkillCreatedEvent} 發布時，將技能文字（名稱 + 描述）寫入 vector_store；
 * 由 {@link SkillshubPgVectorStore} 一次 6-欄 INSERT（id, content, metadata, embedding,
 * owner, skill_id）— owner 來自 {@link CurrentUserProvider#userId()}、skill_id 來自
 * {@code event.aggregateId()}。
 *
 * <p>當 {@link SkillVersionPublishedEvent} 發布時，先 delete 舊 embedding 再 add 新版本。
 * Delete-then-add 可能造成短暫搜尋空窗，在 MVP 階段屬可接受範圍。
 *
 * <p><strong>Per-request VectorStore 模式（T8）</strong>：每次 listener 用
 * {@link SkillshubPgVectorStore#builder} 建構 instance，owner / skillId 鎖在 instance 裡，
 * 操作完即可被 GC。無 Spring Bean 註冊、無 thread-safety 顧慮、無 singleton state leak。
 *
 * <p>採用同步 {@code @EventListener}（與 {@code SkillProjection} 一致）—
 * {@code spring-modulith-events-api} 未納入 classpath，
 * {@code @ApplicationModuleListener} 需要額外依賴。
 *
 * <p>FK 順序：vector_store.skill_id → skills.id (ON DELETE CASCADE)；
 * {@code SkillProjection.on(SkillCreatedEvent)} 已加 {@code @Order(HIGHEST_PRECEDENCE)}
 * 確保 skills row 先寫入再給本 listener 寫 vector_store。
 *
 * @see SkillshubPgVectorStore
 * @see SearchConfig
 * @see SemanticSearchService
 */
@Component
class SearchProjection {

    private static final Logger log = LoggerFactory.getLogger(SearchProjection.class);

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final CurrentUserProvider currentUserProvider;

    SearchProjection(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel,
                     CurrentUserProvider currentUserProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * 處理 SkillCreatedEvent — 新增技能 embedding 至 vector_store。
     * 文字格式：「name description」，供 embedding 模型理解技能用途。
     */
    @EventListener
    void onSkillCreated(SkillCreatedEvent event) {
        log.info("SearchProjection onSkillCreated skillId={} name={}", event.aggregateId(), event.name());
        var doc = buildDocument(
                event.aggregateId(),
                event.name(),
                event.description(),
                event.author(),
                event.category(),
                null,   // latestVersion — 尚未發布版本
                null    // riskLevel — 尚未完成評估
        );
        // Per-request：owner / skillId 鎖在這個 instance 裡，操作完 GC（無 singleton state）
        SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel)
                .owner(currentUserProvider.userId())
                .skillId(event.aggregateId())
                .build()
                .add(List.of(doc));
        log.info("SearchProjection onSkillCreated done skillId={}", event.aggregateId());
    }

    /**
     * 處理 SkillVersionPublishedEvent — 刪除舊 embedding 並以 frontmatter 重建。
     * frontmatter 可能含更新後的 description，因此必須重新 embed。
     */
    @EventListener
    void onVersionPublished(SkillVersionPublishedEvent event) {
        log.info("SearchProjection onVersionPublished skillId={} version={}", event.aggregateId(), event.version());

        // delete + re-add 用同一個 instance（owner/skillId context 共享）
        var vectorStore = SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel)
                .owner(currentUserProvider.userId())
                .skillId(event.aggregateId())
                .build();

        // Delete-then-add: 移除舊向量再建立新向量（有短暫空窗，MVP 可接受）
        vectorStore.delete(List.of(event.aggregateId()));

        var fm = event.frontmatter();
        String name = getString(fm, "name", event.aggregateId());
        String description = getString(fm, "description", "");
        String author = getString(fm, "author", "");
        String category = getString(fm, "category", "");

        var doc = buildDocument(event.aggregateId(), name, description, author, category,
                event.version(), null);
        vectorStore.add(List.of(doc));
        log.info("SearchProjection onVersionPublished done skillId={}", event.aggregateId());
    }

    /**
     * 建立 VectorStore Document。
     * 文字格式為「name description」，提供足夠語意讓 embedding 模型理解技能用途。
     * metadata 鍵名必須與 {@link SemanticSearchService#toResult} 讀取的鍵名一致。
     */
    private static Document buildDocument(String skillId, String name, String description,
                                          String author, String category,
                                          String latestVersion, String riskLevel) {
        String text = (name + " " + description).trim();
        // Use HashMap to allow null values for optional fields (latestVersion, riskLevel)
        var meta = new HashMap<String, Object>();
        meta.put("skillId", skillId);
        meta.put("name", name);
        meta.put("description", description);
        meta.put("author", author);
        meta.put("category", category);
        if (latestVersion != null) meta.put("latestVersion", latestVersion);
        if (riskLevel != null) meta.put("riskLevel", riskLevel);

        return Document.builder()
                .id(skillId)
                .text(text)
                .metadata(meta)
                .build();
    }

    /** 安全地從 Map 讀取字串，缺少或非字串型別時回傳 defaultValue。 */
    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return v != null ? v.toString() : defaultValue;
    }
}
