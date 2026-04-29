package io.github.samzhu.skillshub.skill.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.AbstractAggregateRoot;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.skill.command.CreateSkillCommand;
import io.github.samzhu.skillshub.skill.command.GrantAclCommand;
import io.github.samzhu.skillshub.skill.command.ReactivateCommand;
import io.github.samzhu.skillshub.skill.command.RevokeAclCommand;
import io.github.samzhu.skillshub.skill.command.SuspendCommand;
import io.github.samzhu.skillshub.skill.command.VersionExistsException;

/**
 * Skill Aggregate Root — S024 起轉為 Spring Data JDBC 充血聚合（state-based；per ADR-002 Phase 2）。
 *
 * <p>本類同時持有 <b>新</b>（充血聚合 path；S024 T1 引入）+ <b>舊</b>（純 ES POJO path；
 * v1.5.0 為止使用）兩組 API，以支援 T1-T4 之間的 transitional state：
 * <ul>
 *   <li><b>新 path</b>（S024）：{@link #create(CreateSkillCommand)} factory + {@link #recordVersionPublished(String)}
 *       充血方法 — mutate state + {@code registerEvent(...)}；呼叫端透過
 *       {@code SkillRepository.save(skill)} 觸發 {@code @DomainEvents} 自動 publish
 *       至 Modulith {@code event_publication} outbox（同 TX）</li>
 *   <li><b>舊 path</b>（v1.5.0）：{@link #Skill(String, java.util.List)} replay constructor +
 *       {@link #create(String, String, String, String)} 舊 factory（回傳 event；不 mutate state）—
 *       仍由 {@code SkillCommandService.loadAggregate / createSkill / uploadSkill} 使用；T4 移除呼叫端後刪除</li>
 * </ul>
 *
 * <p>Spring Data JDBC mapping（持久化欄位）：{@code skills} 表 row 1:1 對應 aggregate state；
 * {@code @Version Long version} 提供樂觀鎖（V6 migration 加入）。Replay-only state
 * （{@code publishedVersions} / {@code currentAclEntries} / {@code latestSequence}）
 * 標 {@link Transient} 不持久化。
 *
 * <p>T2 將加入完整充血方法（suspend / reactivate / grantAcl / revokeAcl / recordDownload）
 * 並廢除舊 path。
 *
 * @see SkillRepository
 * @see SkillCreatedEvent
 * @see SkillVersionPublishedFromAggregate
 * @see <a href="../../../../../../../../../docs/grimo/adr/ADR-002-skill-aggregate-state-based.md">ADR-002</a>
 */
@Table("skills")
public class Skill extends AbstractAggregateRoot<Skill> implements Persistable<String> {

    @Id
    private String id;
    private String name;
    private String description;
    private String author;
    private String category;
    private SkillStatus status;
    @Column("latest_version")
    private String latestVersion;
    @Column("risk_level")
    private String riskLevel;
    @Column("download_count")
    private long downloadCount;
    @Column("acl_entries")
    private List<String> aclEntries;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;
    @Version
    @JsonIgnore
    private Long version;

    // -- Replay-only state（@Transient — Spring Data JDBC 不持久化）--
    // T4 移除 SkillCommandService.loadAggregate 後不再需要；保留為 transitional state
    @Transient
    private final Set<String> publishedVersions = new HashSet<>();
    @Transient
    private final Set<String> currentAclEntries = new HashSet<>();
    @Transient
    private long latestSequence;

    /** Spring Data JDBC entity creator — 框架透過 reflection 呼叫，再以 field reflection 填入持久化欄位。 */
    @PersistenceCreator
    private Skill() {
        // No-op — 持久化欄位由 Spring Data JDBC 透過 field reflection 填入
    }

    /**
     * 從 event store 載入 aggregate — replay events 重建狀態（業務不變量檢查所需的 set / status）。
     *
     * @deprecated since v2.0.0 — S024 改為 state-based；正常路徑請用 {@code SkillRepository.findById}。
     *             本 constructor 保留為 emergency rollback path（per ADR-002 §5.2）+ T4 之前的
     *             {@code SkillCommandService.loadAggregate} 呼叫；T4 移除呼叫端後改 private static fromHistory。
     */
    @Deprecated(since = "v2.0.0", forRemoval = false)
    public Skill(String aggregateId, List<DomainEvent> events) {
        this.id = aggregateId;
        this.status = SkillStatus.DRAFT;
        for (var event : events) {
            switch (event.eventType()) {
                case "SkillCreated" ->
                    this.status = SkillStatus.DRAFT;
                case "SkillVersionPublished" -> {
                    publishedVersions.add((String) event.payload().get("version"));
                    this.status = this.status.publish();
                }
                case "SkillSuspended" ->
                    this.status = this.status.suspend();
                case "SkillReactivated" ->
                    this.status = this.status.reactivate();
                case "SkillAclGranted" ->
                    currentAclEntries.add(formatEntry(event.payload()));
                case "SkillAclRevoked" ->
                    currentAclEntries.remove(formatEntry(event.payload()));
                default -> {
                    // 其他事件類型於本 aggregate 不需要 replay state（只取 maxSeq）
                }
            }
            if (event.sequence() > latestSequence) {
                latestSequence = event.sequence();
            }
        }
    }

    /**
     * S024 充血聚合 factory — 建立新 Skill aggregate；註冊 {@link SkillCreatedEvent}。
     *
     * <p>呼叫端：T4 之後的 {@code SkillCommandService.createSkill} 透過
     * {@code skillRepository.save(skill)} 觸發 {@code @DomainEvents} publish 至 outbox。
     *
     * <p>S016 既有設計：作者自動 seed 為 owner（read + write + delete 三 ACL entries）。
     * 與既有 {@code SkillProjection.on(SkillCreatedEvent)} 同邏輯保持一致；T5 SkillProjection
     * 整檔刪除後僅本 factory 為唯一 seed point。
     */
    public static Skill create(CreateSkillCommand cmd) {
        Objects.requireNonNull(cmd.name(), "name is required");
        Objects.requireNonNull(cmd.description(), "description is required");
        var skill = new Skill();
        skill.id = UUID.randomUUID().toString();
        skill.name = cmd.name();
        skill.description = cmd.description();
        skill.author = cmd.author();
        skill.category = cmd.category();
        skill.status = SkillStatus.DRAFT;
        skill.latestVersion = null;
        skill.riskLevel = null;
        skill.downloadCount = 0;
        // S016 自動 seed owner ACL（與 legacy SkillProjection.on(SkillCreatedEvent) 同邏輯）
        skill.aclEntries = cmd.author() == null
                ? new ArrayList<>()
                : new ArrayList<>(List.of(
                        "user:" + cmd.author() + ":read",
                        "user:" + cmd.author() + ":write",
                        "user:" + cmd.author() + ":delete"));
        skill.createdAt = Instant.now();
        skill.updatedAt = skill.createdAt;
        // version=null → Persistable.isNew()=true → INSERT path；Spring Data prepareVersionForInsert
        // 在 INSERT 後寫回 version=0（per deepwiki/aggregate-design.md §1.@Version + §4.isNew）
        skill.version = null;
        skill.registerEvent(new SkillCreatedEvent(
                skill.id, cmd.name(), cmd.description(), cmd.author(), cmd.category()));
        return skill;
    }

    /**
     * S024 充血方法 — 記錄新版本發布。
     * <ol>
     *   <li>State machine 守護：{@code SUSPENDED} 拋 {@code IllegalStateException}；
     *       {@code DRAFT} → {@code PUBLISHED}（首版 transition）；
     *       {@code PUBLISHED} idempotent 維持</li>
     *   <li>Mutate state：{@code latestVersion} 更新 + {@code updatedAt} 寫入</li>
     *   <li>Register {@link SkillVersionPublishedFromAggregate}（標 Skill 自身 state 變化；
     *       與 SkillVersion aggregate 自己發的 {@link SkillVersionPublishedEvent} 區分）</li>
     * </ol>
     *
     * <p>版本唯一性檢查（同 skill 不能有重複 version）由 application service
     * {@code SkillCommandService.publishVersion} 在 {@code @Transactional} boundary 內透過
     * {@code SkillVersionRepository.existsBySkillIdAndVersion} 預檢；DB 層
     * {@code UNIQUE (skill_id, version)} constraint 兜底。
     */
    public void recordVersionPublished(String version) {
        // state machine guard — SUSPENDED throw；DRAFT/PUBLISHED 都允許
        SkillStatus next = this.status.publish();
        this.latestVersion = version;
        this.status = next;
        this.updatedAt = Instant.now();
        registerEvent(new SkillVersionPublishedFromAggregate(id, version));
    }

    /**
     * S024 充血方法（T2）— 停用 skill。
     * <ul>
     *   <li>State machine 守護：{@code PUBLISHED} → {@code SUSPENDED} 唯一合法 transition；
     *       {@code DRAFT} / {@code SUSPENDED} 拋 {@code IllegalStateException}（per {@link SkillStatus#suspend()}）</li>
     *   <li>Mutate state + register {@link SkillSuspendedEvent}</li>
     * </ul>
     *
     * <p>命名為 {@code recordSuspended}（非 {@code suspend}）以與 v1.5.0 同名 deprecated method 共存；
     * T4 移除 {@link #suspend} 後 rename 為 {@code suspend} 對齊 spec §4.1 final design。
     */
    public void recordSuspended(SuspendCommand cmd) {
        this.status = this.status.suspend();
        this.updatedAt = Instant.now();
        registerEvent(new SkillSuspendedEvent(id, cmd.reason(), cmd.suspendedBy()));
    }

    /**
     * S024 充血方法（T2）— 重啟 skill。
     * State machine 守護：{@code SUSPENDED} → {@code PUBLISHED} 唯一合法 transition。
     */
    public void recordReactivated(ReactivateCommand cmd) {
        this.status = this.status.reactivate();
        this.updatedAt = Instant.now();
        registerEvent(new SkillReactivatedEvent(id, cmd.reason()));
    }

    /**
     * S024 充血方法（T2）— 授權 ACL entry。
     * <ul>
     *   <li>業務不變量：相同 {@code type:principal:permission} 不可重複 grant</li>
     *   <li>違反拋 {@link IllegalStateException}（與 {@link SkillStatus} state machine 一致風格）</li>
     *   <li>Mutate {@code aclEntries} list + register {@link SkillAclGrantedEvent}</li>
     * </ul>
     *
     * <p>{@code aclEntries} 為 {@link java.util.ArrayList}（mutable）；行內 add — {@code repo.save()}
     * 觸發單條 {@code UPDATE skills SET acl_entries = ?, ...}（per ADR-002 §2.4 — 避開
     * {@code @MappedCollection} delete-and-reinsert 雷）。
     */
    public void recordAclGranted(GrantAclCommand cmd) {
        var entry = entry(cmd.type(), cmd.principal(), cmd.permission());
        if (aclEntries.contains(entry)) {
            throw new IllegalStateException("ACL entry already exists: " + entry);
        }
        aclEntries.add(entry);
        this.updatedAt = Instant.now();
        registerEvent(new SkillAclGrantedEvent(
                id, cmd.type(), cmd.principal(), cmd.permission(), cmd.grantedBy()));
    }

    /**
     * S024 充血方法（T2）— 撤銷 ACL entry。
     * 業務不變量：必須存在對應 entry 才可 revoke；不存在拋 {@link IllegalStateException}。
     */
    public void recordAclRevoked(RevokeAclCommand cmd) {
        var entry = entry(cmd.type(), cmd.principal(), cmd.permission());
        if (!aclEntries.remove(entry)) {
            throw new IllegalStateException("ACL entry not found: " + entry);
        }
        this.updatedAt = Instant.now();
        registerEvent(new SkillAclRevokedEvent(
                id, cmd.type(), cmd.principal(), cmd.permission(), cmd.revokedBy()));
    }

    /**
     * S024 充血方法（T2）— 累計下載計數。
     *
     * <p>每呼叫一次 {@code downloadCount++}（non-atomic at JVM level，但 aggregate 在
     * {@code @Transactional} boundary 內單 thread 處理）+ register {@link SkillDownloadedEvent}。
     * {@code latestVersion} 用作 event 的 version 欄位（必須先 publish 過版本）。
     *
     * <p>命名直接為 {@code recordDownload} — 與 spec §4.1 final design 一致，無 deprecated 衝突。
     */
    public void recordDownload() {
        this.downloadCount++;
        this.updatedAt = Instant.now();
        registerEvent(SkillDownloadedEvent.of(id, latestVersion));
    }

    // ============================================================================
    // 以下為 v1.5.0 ES path API — T4 移除呼叫端（SkillCommandService）後刪除
    // ============================================================================

    /**
     * Old factory — 仍由 v1.5.0 {@code SkillCommandService.createSkill / uploadSkill} 使用。
     * @deprecated since v2.0.0 — 改用 {@link #create(CreateSkillCommand)}
     */
    @Deprecated(since = "v2.0.0", forRemoval = false)
    public static SkillCreatedEvent create(String name, String description, String author, String category) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
        return new SkillCreatedEvent(UUID.randomUUID().toString(), name, description, author, category);
    }

    /**
     * v1.5.0 業務方法 — 驗版本不變量 + 回傳 SkillVersionPublishedEvent（不 mutate state）。
     *
     * <p>S024 充血路徑改用 {@link #recordVersionPublished(String)} mutate state +
     * {@code SkillCommandService.publishVersion} 在 service 端 orchestrate
     * {@code SkillVersion.publish + skillVersionRepo.save}。本方法 T4 移除。
     */
    @Deprecated(since = "v2.0.0", forRemoval = false)
    public SkillVersionPublishedEvent publishVersion(String version, String storagePath,
            long fileSize, Map<String, Object> frontmatter) {
        this.status.publish();
        if (publishedVersions.contains(version)) {
            throw new VersionExistsException("Version " + version + " already exists");
        }
        return SkillVersionPublishedEvent.of(id, version, storagePath, fileSize, frontmatter,
                parseAllowedTools(frontmatter));
    }

    @Deprecated(since = "v2.0.0", forRemoval = false)
    public SkillSuspendedEvent suspend(SuspendCommand cmd) {
        this.status.suspend();
        return new SkillSuspendedEvent(id, cmd.reason(), cmd.suspendedBy());
    }

    @Deprecated(since = "v2.0.0", forRemoval = false)
    public SkillReactivatedEvent reactivate(ReactivateCommand cmd) {
        this.status.reactivate();
        return new SkillReactivatedEvent(id, cmd.reason());
    }

    @Deprecated(since = "v2.0.0", forRemoval = false)
    public SkillAclGrantedEvent grantAcl(GrantAclCommand cmd) {
        var entry = entry(cmd.type(), cmd.principal(), cmd.permission());
        if (currentAclEntries.contains(entry)) {
            throw new IllegalStateException("ACL entry already exists: " + entry);
        }
        return new SkillAclGrantedEvent(
                id, cmd.type(), cmd.principal(), cmd.permission(), cmd.grantedBy());
    }

    @Deprecated(since = "v2.0.0", forRemoval = false)
    public SkillAclRevokedEvent revokeAcl(RevokeAclCommand cmd) {
        var entry = entry(cmd.type(), cmd.principal(), cmd.permission());
        if (!currentAclEntries.contains(entry)) {
            throw new IllegalStateException("ACL entry not found: " + entry);
        }
        return new SkillAclRevokedEvent(
                id, cmd.type(), cmd.principal(), cmd.permission(), cmd.revokedBy());
    }

    // ============================================================================
    // Read accessors
    // ============================================================================

    @Override
    public String getId() { return id; }

    /**
     * {@link Persistable#isNew()} 自訂 — 預設 Spring Data JDBC 規則「@Id 非 null = existing」
     * 不適用於 client-generated UUID PK（Skill.create factory 預設 id）。改以 {@link #version}
     * (@Version) 是否 null 判斷：null = 尚未持久化（INSERT path）；non-null = 已持久化（UPDATE path）。
     *
     * <p>per deepwiki/spring-data-jdbc-modulith/aggregate-design.md §1.@Version + §4.isNew。
     */
    @Override
    public boolean isNew() {
        return this.version == null;
    }

    /** Legacy alias — v1.5.0 程式碼用此名稱；新程式碼建議用 {@link #getId()}。 */
    public String aggregateId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getAuthor() { return author; }
    public String getCategory() { return category; }
    public SkillStatus status() { return status; }
    public SkillStatus getStatus() { return status; }
    public String getLatestVersion() { return latestVersion; }
    public String getRiskLevel() { return riskLevel; }
    public long getDownloadCount() { return downloadCount; }
    public List<String> getAclEntries() {
        return aclEntries == null ? List.of() : List.copyOf(aclEntries);
    }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** {@code @Version} 樂觀鎖；不 expose 至 JSON response。 */
    @JsonIgnore
    public Long getVersion() { return version; }

    /** Legacy ES sequence — replay constructor 用；T4 移除呼叫端後刪除。 */
    @Deprecated(since = "v2.0.0", forRemoval = false)
    public long nextSequence() { return latestSequence + 1; }

    // ============================================================================
    // Helpers
    // ============================================================================

    private static List<String> parseAllowedTools(Map<String, Object> frontmatter) {
        if (frontmatter == null) return List.of();
        var raw = frontmatter.get("allowed-tools");
        if (raw == null) return List.of();
        var asString = raw.toString().trim();
        if (asString.isEmpty()) return List.of();
        return List.of(asString.split("\\s+"));
    }

    private static String entry(String type, String principal, String permission) {
        return type + ":" + principal + ":" + permission;
    }

    /** 從 SkillAclGranted/Revoked event payload 重組 entry 字串（給 replay constructor 用）。 */
    private static String formatEntry(Map<String, Object> payload) {
        return entry(
                (String) payload.get("type"),
                (String) payload.get("principal"),
                (String) payload.get("permission"));
    }
}
