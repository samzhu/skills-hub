package io.github.samzhu.skillshub.search;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 語意搜尋投影 — 監聽技能領域事件，維護 vector_store 表的 embedding。
 *
 * <p>當 {@link SkillCreatedEvent} 發布時，將技能文字（名稱 + 描述）寫入 vector_store；
 * 由 {@link SkillshubPgVectorStore} 一次 8-欄 INSERT（id, content, metadata, embedding,
 * owner, skill_id, acl_entries, is_public）— owner 來自 aggregate author、skill_id 來自
 * {@code event.aggregateId()}。
 *
 * <p>當 {@link SkillVersionPublishedEvent} 發布時，先 delete 舊 embedding 再 add 新版本。
 * Delete-then-add 可能造成短暫搜尋空窗，在 MVP 階段屬可接受範圍。
 *
 * <p><strong>Per-request VectorStore 模式（T8）</strong>：每次 listener 用
 * {@link SkillshubPgVectorStore#builder} 建構 instance，owner / skillId 鎖在 instance 裡，
 * 操作完即可被 GC。無 Spring Bean 註冊、無 thread-safety 顧慮、無 singleton state leak。
 *
 * <p>S023：升級為 {@link ApplicationModuleListener}（async + AFTER_COMMIT +
 * REQUIRES_NEW + outbox 追蹤）。embedding API call（Gemini）為 I/O bound，
 * async 避免阻塞 publisher 線程；async executor 容量限 2（per AsyncListenerConfig POC）。
 *
 * <p>FK 順序：vector_store.skill_id → skills.id (ON DELETE CASCADE)；
 * S024 起 Skill aggregate 在 publisher TX 內透過 {@code skillRepo.save(skill)} 同步 INSERT
 * skills row，commit 後本 async listener 才觸發，FK 必滿足。S023 hybrid SkillProjection
 * sync listener 結構已隨 T05B read-model 刪除廢除。
 *
 * <p>Idempotency：vector store 既有 {@code ON CONFLICT (id) DO UPDATE}（per S014
 * 自寫 SkillshubPgVectorStore）保證重投時 row 內容覆寫一致；無需新加 dedup 機制。
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
    private final SkillRepository skillRepo;
    private final SkillVersionRepository versionRepo;

    // S034: 移除 CurrentUserProvider 依賴 — async listener 無 SecurityContext，
    // owner 改從 event.author（onSkillCreated）/ aggregate.author（onVersionPublished /
    // onSkillReactivated, S033）取得，是 source of truth。
    SearchProjection(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel,
                     SkillRepository skillRepo, SkillVersionRepository versionRepo) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.skillRepo = skillRepo;
        this.versionRepo = versionRepo;
    }

    /**
     * 處理 SkillCreatedEvent — 新增技能 embedding 至 vector_store。
     * 文字格式：「name description」，供 embedding 模型理解技能用途。
     */
    @ApplicationModuleListener
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
        var scope = projectionScope(event.aggregateId(), event.author());

        // S034: owner 從 event.author() 取（async listener 無 SecurityContext，不能依賴 currentUserProvider —
        // S025b §7 architecture tech debt）；event 已在 publisher TX 內 captured event.author，
        // 來源是 caller 上傳時提供的 author 欄位，是 source of truth。
        // Per-request：owner / skillId / aclEntries 鎖在這個 instance 裡，操作完 GC
        SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel)
                .owner(scope.owner())
                .skillId(event.aggregateId())
                .aclEntries(scope.aclEntries())
                .publicSkill(scope.publicSkill())
                .build()
                .add(List.of(doc));
        log.info("SearchProjection onSkillCreated done skillId={}", event.aggregateId());
    }

    /**
     * 處理 SkillVersionPublishedEvent — 刪除舊 embedding 並以 frontmatter 重建。
     * frontmatter 可能含更新後的 description，因此必須重新 embed。
     */
    @ApplicationModuleListener
    void onVersionPublished(SkillVersionPublishedEvent event) {
        log.info("SearchProjection onVersionPublished skillId={} version={}", event.aggregateId(), event.version());

        // S016：re-embed 也帶 owner-derived acl_entries 維持與 onSkillCreated 一致；
        // delete-then-add 會走新 row 路徑（無 ON CONFLICT 觸發），需顯式提供初始 acl 防止空 array。
        // S034: owner 從 aggregate 取（同 onSkillReactivated 模式；S025b §7 architecture tech debt 完全解決）。
        // SkillVersionPublishedEvent 的 frontmatter 也帶 author/package name；S176 後 package name 可不同於平台 name。
        // owner 仍以 aggregate.author 為準，避免 frontmatter quirk 污染 ACL。
        var scope = projectionScope(event.aggregateId(), null);

        // delete + re-add 用同一個 instance（owner/skillId/aclEntries context 共享）
        var vectorStore = SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel)
                .owner(scope.owner())
                .skillId(event.aggregateId())
                .aclEntries(scope.aclEntries())
                .publicSkill(scope.publicSkill())
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

    /**
     * S033：SUSPENDED skill 移出 vector_store — semantic search 不再命中已下架 skill。
     *
     * <p>純 delete-by-id；ON DELETE CASCADE 關係（{@code vector_store.skill_id → skills.id}）
     * 在實際刪除 skill row 時也會 cascade，但 suspend 不刪 skill row（state machine 維持 record），
     * 故需主動 delete vector_store row。
     */
    @ApplicationModuleListener
    void onSkillSuspended(SkillSuspendedEvent event) {
        log.info("SearchProjection onSkillSuspended skillId={}", event.aggregateId());
        SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel)
                .build()
                .delete(List.of(event.aggregateId()));
        log.info("SearchProjection onSkillSuspended done skillId={}", event.aggregateId());
    }

    /**
     * S033：reactivate 後重新 embed — 用 Skill aggregate metadata + 最新 SkillVersion 重建 doc。
     *
     * <p>{@link SkillReactivatedEvent} 只攜 id + reason；需 query Skill aggregate 與最新版。
     * 沒 version 的情況防禦性早 return（理論上 reactivate 只能從 SUSPENDED 過來，
     * SUSPENDED 必先 PUBLISHED 必有 version；防禦 race / 異常清理）。
     *
     * <p>ACL：S177 起 vector_store.acl_entries 只存 explicit ACL；public visibility 由
     * {@code vector_store.is_public} 表達。owner 從 aggregate.author 取得（非 currentUserProvider
     * — async listener 無 SecurityContext，per S025b §7 tech debt）。
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

        var scope = projectionScope(skill);

        var doc = buildDocument(skill.getId(), skill.getName(), skill.getDescription(),
                skill.getAuthor(), skill.getCategory(),
                latestVersion.getVersion(), skill.getRiskLevel());

        SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel)
                .owner(scope.owner())
                .skillId(skill.getId())
                .aclEntries(scope.aclEntries())
                .publicSkill(scope.publicSkill())
                .build()
                .add(List.of(doc));
        log.info("SearchProjection onSkillReactivated done skillId={}", event.aggregateId());
    }

    private ProjectionScope projectionScope(String skillId, String fallbackOwner) {
        return skillRepo.findById(skillId)
                .map(this::projectionScope)
                .orElseGet(() -> projectionScope(fallbackOwner, false));
    }

    private ProjectionScope projectionScope(Skill skill) {
        return projectionScope(skill.getAuthor(), skill.isPublic());
    }

    private ProjectionScope projectionScope(String owner, boolean publicSkill) {
        var aclEntries = owner == null ? List.<String>of() : List.of("user:" + owner + ":read");
        return new ProjectionScope(owner, publicSkill, aclEntries);
    }

    private record ProjectionScope(String owner, boolean publicSkill, List<String> aclEntries) {}
}
