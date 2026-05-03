package io.github.samzhu.skillshub.skill.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.AbstractAggregateRoot;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import io.github.samzhu.skillshub.skill.command.PublishVersionCommand;

/**
 * SkillVersion 為**獨立 aggregate**（S024 引入；ADR-002 §2.3）— append-only 不可變版本快照。
 *
 * <p>設計核心：與 {@link Skill} 透過 plain {@code String skillId} FK 引用；**不**用
 * {@code @MappedCollection} 也**不**用 {@code AggregateReference}，避免框架介入子集合
 * lifecycle（{@code WritingContext.update()} delete-and-reinsert 雷 — 詳
 * {@code docs/deepwiki/spring-data-jdbc-modulith/aggregate-design.md §2}）。
 *
 * <p>跨 aggregate 一致性（同 skill 不能有重複 version）由：
 * <ol>
 *   <li>application service {@code SkillCommandService.publishVersion} 在
 *       {@code @Transactional} 內 {@code SkillVersionRepository.existsBySkillIdAndVersion}
 *       預檢（friendly error → {@code VersionExistsException}）</li>
 *   <li>schema 層 {@code UNIQUE (skill_id, version)} constraint（V1 既有索引）兜底
 *       — bypass service 直接呼叫 repo.save 重複 → {@code DataIntegrityViolationException}</li>
 * </ol>
 *
 * <p>{@link #publish(PublishVersionCommand)} factory 註冊
 * {@link SkillVersionPublishedEvent}（既有 record，含完整 storagePath / fileSize /
 * frontmatter / allowedTools / sourceEventId 載荷）；
 * {@code skillVersionRepository.save(skillVersion)} 透過 {@code @DomainEvents}
 * 自動 publish 至 Modulith outbox。
 *
 * <p>T1 minimal — {@code attachRiskAssessment} 充血方法（ScanOrchestrator 改造路徑）+
 * 完整 read accessors 留 T3。
 *
 * @see Skill
 * @see SkillVersionRepository
 * @see <a href="../../../../../../../../../docs/grimo/adr/ADR-002-skill-aggregate-state-based.md">ADR-002</a>
 */
@Table("skill_versions")
public class SkillVersion extends AbstractAggregateRoot<SkillVersion> implements Persistable<String> {

    @Id
    private String id;

    /**
     * Persistable.isNew() 自訂 backing — 預設 Spring Data JDBC「@Id 非 null = existing」規則
     * 不適用於 client-generated UUID PK（{@link #publish} factory 預設 id）。
     *
     * <p>{@code @Transient} → Spring Data 不持久化；
     * {@code publish} factory 設 {@code true}（INSERT path）；
     * {@code @PersistenceCreator} 透過 reflection 載入既有 row 時保持預設 {@code false}（UPDATE path
     * — T3 attachRiskAssessment 場景）。
     */
    @Transient
    private boolean isNew = false;

    @Column("skill_id")
    private String skillId;

    private String version;

    @Column("storage_path")
    private String storagePath;

    @Column("file_size")
    private long fileSize;

    /** S098a3-2: zip 檔案 entry 數（排除 directories）；0 = legacy row（V13 migration default）。 */
    @Column("file_count")
    private int fileCount;

    @Column("frontmatter")
    private Map<String, Object> frontmatter;

    @Column("risk_assessment")
    private Map<String, Object> riskAssessment;

    @Column("published_at")
    private Instant publishedAt;

    @Column("allowed_tools")
    private List<String> allowedTools;

    /** Spring Data JDBC entity creator — 透過 reflection 呼叫，再以 field reflection 填入持久化欄位。 */
    @PersistenceCreator
    private SkillVersion() {}

    /**
     * Factory — 建立新 SkillVersion aggregate；註冊 {@link SkillVersionPublishedEvent}（含
     * 自動產生的 {@code sourceEventId} for ScanOrchestrator idempotency）。
     *
     * <p>{@code riskAssessment} 初始 {@code null}，由 ScanOrchestrator multi-engine pipeline
     * 完成後透過 {@code attachRiskAssessment}（T3 加）寫入。
     *
     * @param cmd 攜帶 skillId / version / storagePath / fileSize / frontmatter
     * @return 已 register {@link SkillVersionPublishedEvent} 的新 aggregate；
     *         呼叫端透過 {@code skillVersionRepository.save(...)} 觸發 publish 至 outbox
     */
    public static SkillVersion publish(PublishVersionCommand cmd) {
        // S054: IllegalArgumentException → 400 VALIDATION_ERROR（aggregate factory 守 user input；NPE 語意錯）
        if (cmd.skillId() == null) throw new IllegalArgumentException("skillId is required");
        if (cmd.version() == null) throw new IllegalArgumentException("version is required");
        if (cmd.storagePath() == null) throw new IllegalArgumentException("storagePath is required");

        var sv = new SkillVersion();
        sv.id = UUID.randomUUID().toString();
        sv.skillId = cmd.skillId();
        sv.version = cmd.version();
        sv.storagePath = cmd.storagePath();
        sv.fileSize = cmd.fileSize();
        sv.fileCount = cmd.fileCount();
        sv.frontmatter = cmd.frontmatter() == null ? Map.of() : cmd.frontmatter();
        sv.riskAssessment = null;
        sv.publishedAt = Instant.now();
        sv.allowedTools = parseAllowedTools(cmd.frontmatter());
        sv.isNew = true;
        sv.registerEvent(SkillVersionPublishedEvent.of(
                cmd.skillId(), cmd.version(), cmd.storagePath(),
                cmd.fileSize(), sv.frontmatter, sv.allowedTools));
        return sv;
    }

    /**
     * S024 T3 充血方法 — 寫入 risk_assessment JSONB 欄位 + register {@link SkillRiskAssessedEvent}。
     *
     * <p>呼叫端：T5 改造後的 {@code ScanOrchestrator.persist} —
     * <pre>{@code
     *   var sv = skillVersionRepo.findBySkillIdAndVersion(skillId, version).orElseThrow();
     *   sv.attachRiskAssessment(riskAssessment);
     *   skillVersionRepo.save(sv);   // UPDATE skill_versions.risk_assessment + publish event
     * }</pre>
     *
     * <p>{@code assessment} Map 預期含至少 {@code level} / {@code findings} / {@code sourceEventId}
     * 三個 key（per ScanOrchestrator.persist v1.5.0 schema）；其他 key 透傳保留（如 sarif、notices、scannedAt）。
     */
    public void attachRiskAssessment(java.util.Map<String, Object> assessment) {
        this.riskAssessment = assessment;
        registerEvent(new SkillRiskAssessedEvent(
                skillId, version, (String) assessment.get("level"), assessment.get("findings")));
    }

    @Override
    public String getId() { return id; }

    /** {@inheritDoc} 透過 {@link #isNew} transient flag 自訂 — factory 設 true、reflection 載入保持 false。
     *  S062: @JsonIgnore — Persistable artifact 不該暴露於 API JSON */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Override
    public boolean isNew() { return isNew; }

    public String getSkillId() { return skillId; }
    public String getVersion() { return version; }

    /** S062: @JsonIgnore — 內部 GCS/FS 路徑不該暴露於 API JSON（資訊洩漏） */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getStoragePath() { return storagePath; }
    public long getFileSize() { return fileSize; }
    public int getFileCount() { return fileCount; }
    public Map<String, Object> getFrontmatter() { return frontmatter; }
    public Map<String, Object> getRiskAssessment() { return riskAssessment; }
    public Instant getPublishedAt() { return publishedAt; }
    public List<String> getAllowedTools() { return allowedTools == null ? List.of() : allowedTools; }

    /** S018 既有解析邏輯：space-separated string → List；null/empty → 空 list（fail-secure）。 */
    private static List<String> parseAllowedTools(Map<String, Object> frontmatter) {
        if (frontmatter == null) return List.of();
        var raw = frontmatter.get("allowed-tools");
        if (raw == null) return List.of();
        var asString = raw.toString().trim();
        if (asString.isEmpty()) return List.of();
        return List.of(asString.split("\\s+"));
    }
}
