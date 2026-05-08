package io.github.samzhu.skillshub.skill.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import com.fasterxml.jackson.annotation.JsonView;

import io.github.samzhu.skillshub.skill.command.CreateSkillCommand;
import io.github.samzhu.skillshub.skill.command.GrantAclCommand;
import io.github.samzhu.skillshub.skill.command.ReactivateCommand;
import io.github.samzhu.skillshub.skill.command.RevokeAclCommand;
import io.github.samzhu.skillshub.skill.command.SuspendCommand;

/**
 * Skill Aggregate Root — Spring Data JDBC 充血聚合（state-based；per ADR-002 Phase 2）。
 *
 * <p>S024 ship 後唯一 path：透過 {@link SkillRepository#findById} 載入 → 充血方法
 * 修改 state + 註冊事件 → {@code skillRepo.save(skill)} 觸發 {@code @DomainEvents}
 * 自動 publish 至 Modulith {@code event_publication} outbox（同 transaction）。
 *
 * <p>持久化 mapping：{@code skills} 表 row 1:1 對應 aggregate state；
 * {@code @Version Long version} 提供樂觀鎖（V6 migration 加入）。
 *
 * <p>充血方法清單：
 * <ul>
 *   <li>{@link #recordVersionPublished(String)} — 新版發布；DRAFT→PUBLISHED transition</li>
 *   <li>{@link #suspend(SuspendCommand)} — 停用；PUBLISHED→SUSPENDED</li>
 *   <li>{@link #reactivate(ReactivateCommand)} — 重啟；SUSPENDED→PUBLISHED</li>
 *   <li>{@link #grantAcl(GrantAclCommand)} — 授權 ACL entry（業務不變量：不重複）</li>
 *   <li>{@link #revokeAcl(RevokeAclCommand)} — 撤銷 ACL entry（業務不變量：必須存在）</li>
 *   <li>{@link #recordDownload()} — 累計下載計數 + 發 SkillDownloadedEvent</li>
 * </ul>
 *
 * @see SkillRepository
 * @see SkillCreatedEvent
 * @see SkillVersionPublishedFromAggregate
 * @see <a href="../../../../../../../../../docs/grimo/adr/ADR-002-skill-aggregate-state-based.md">ADR-002</a>
 */
@Table("skills")
public class Skill extends AbstractAggregateRoot<Skill> implements Persistable<String> {

    /**
     * S158: Jackson @JsonView marker — 控制 list vs detail endpoint 序列化差異。
     *
     * <p>List view 不暴露 {@code aclEntries} / {@code ownerId} — internal authorization
     * detail 不該對 list browser 公開（least-privilege response）；改 ACL 結構不變成
     * breaking change。Detail extends List → owner 視角的 detail endpoint 仍可看到全部。
     *
     * <p>S158 #1 only: list view limitation；detail view 條件 owner-only 留 follow-up。
     * 目前 detail endpoint 為 unrestricted Detail view（任何 viewer 看 aclEntries）。
     */
    public static final class Views {
        public interface List {}
        public interface Detail extends List {}
    }

    @Id
    private String id;
    private String name;
    private String description;
    private String author;
    private String category;
    private SkillStatus status;
    @Column("latest_version")
    private String latestVersion;
    /**
     * S078：{@code @ReadOnlyProperty} 排除 save() INSERT/UPDATE — 與 S077 `downloadCount` 同模式。
     * `risk_level` 由 {@link SkillRepository#updateRiskLevel}（@Modifying @Query）獨立寫入；
     * aggregate save 不該覆蓋（lost-update preemptive defense per Bug AL pattern）。
     * INSERT path 由 DB schema {@code risk_level VARCHAR(10) NULL} 接管，預設 NULL。
     */
    @org.springframework.data.annotation.ReadOnlyProperty
    @Column("risk_level")
    private String riskLevel;
    /**
     * S077：{@code @ReadOnlyProperty} 排除 save() INSERT/UPDATE，避免並發 suspend/reactivate 的
     * full-row save 覆蓋 atomic SQL increment（lost-update fix per Bug AK）。唯一寫入路徑為
     * {@link SkillRepository#incrementDownloadCount}；INSERT path 由 DB DEFAULT 0 接管。
     */
    @org.springframework.data.annotation.ReadOnlyProperty
    @Column("download_count")
    private long downloadCount;
    /**
     * S098e2-T02：review averageRating projection — 由 review module 的
     * SkillRatingProjectionListener 訂閱 ReviewCreatedEvent / ReviewDeletedEvent
     * 後呼叫 {@link io.github.samzhu.skillshub.skill.query.SkillRatingService#refresh}
     * 走 raw SQL UPDATE 寫入。{@code @ReadOnlyProperty} mirror downloadCount 同 pattern：
     * 防 aggregate full-row save 覆蓋並發 projection update。
     */
    @org.springframework.data.annotation.ReadOnlyProperty
    @Column("average_rating")
    private double averageRating;
    @org.springframework.data.annotation.ReadOnlyProperty
    @Column("review_count")
    private long reviewCount;
    /** S158: aclEntries 僅 Detail view 暴露 — list endpoint 不洩漏 internal authorization 結構。 */
    @JsonView(Views.Detail.class)
    @Column("acl_entries")
    private List<String> aclEntries;
    /** S114a — owner_id maps to V16 schema column; derived from author at create time. */
    /** S158: ownerId 僅 Detail view 暴露 — list endpoint 不洩漏 owner identity（與 author 重複）。 */
    @JsonView(Views.Detail.class)
    @Column("owner_id")
    private String ownerId;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;
    // S142b: detail-enrichment fields — set by SkillQueryService.enrichDetail(); @Transient = not persisted.
    @Transient private boolean verified;
    @Transient private Instant latestVersionPublishedAt;
    @Transient private String license;
    @Transient private List<String> compatibility = List.of();
    @Transient private long versionCount;
    @Transient private long openFlagCount;
    @Version
    @JsonIgnore
    private Long version;

    /** Spring Data JDBC entity creator — 框架透過 reflection 呼叫，再以 field reflection 填入持久化欄位。 */
    @PersistenceCreator
    private Skill() {
        // No-op — 持久化欄位由 Spring Data JDBC 透過 field reflection 填入
    }

    /**
     * S024 充血聚合 factory — 建立新 Skill aggregate；註冊 {@link SkillCreatedEvent}。
     *
     * <p>S016 既有設計：作者自動 seed 為 owner（read + write + delete 三 ACL entries）。
     */
    public static Skill create(CreateSkillCommand cmd) {
        // S054: 用 IllegalArgumentException 而非 NPE — 走 GlobalExceptionHandler 既有 400 VALIDATION_ERROR 路徑。
        // Aggregate factory 為 user input 守門點；NPE 屬 programmer bug 語意不對。
        if (cmd.name() == null) throw new IllegalArgumentException("name is required");
        if (cmd.description() == null) throw new IllegalArgumentException("description is required");
        // S041: name 必符 agentskills.io 正規格式（與 SkillValidator.NAME_REGEX 一致；
        // 不從 SkillValidator import 因 module 邊界 — domain 不依賴 validation 子模組）。
        // 變更時需手動同步兩處 regex literal。
        var name = cmd.name().trim();
        if (!NAME_REGEX.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Skill name must match ^[a-z0-9-]{1,64}$ (got: " + cmd.name() + ")");
        }
        // S041: author trim；非 null 但 blank（"" / "   "）→ 拒絕（IllegalArgumentException → 400）。
        // 不靜默 null-out 因 schema `skills.author NOT NULL`；blank author 也會產生
        // ACL "user: :read" 畸形（principal 段空白），語意不清。null author（caller 顯式不傳）
        // 仍允許 — 用於 unit test fixture 控制 ACL seed 行為；該 path 在 prod schema 下不會持久化成功。
        var author = cmd.author() == null ? null : cmd.author().trim();
        if (author != null && author.isEmpty()) {
            throw new IllegalArgumentException(
                    "Skill author must not be blank (got: " + cmd.author() + ")");
        }
        // S042: description trim + length cap（與 SkillValidator.DESCRIPTION_MAX=1024 對齊；
        // domain 不依賴 validation 子模組，常數複製 + inline 註解提醒同步）
        var description = cmd.description().trim();
        if (description.isEmpty()) {
            throw new IllegalArgumentException("Skill description must not be blank");
        }
        if (description.length() > DESCRIPTION_MAX) {
            throw new IllegalArgumentException(
                    "Skill description exceeds " + DESCRIPTION_MAX + " characters (got: " + description.length() + ")");
        }
        // S042: category trim；非 null 但 blank → reject（mirror S041 author）；null 允許（schema 允許）
        var category = cmd.category() == null ? null : cmd.category().trim();
        if (category != null && category.isEmpty()) {
            throw new IllegalArgumentException(
                    "Skill category must not be blank (got: " + cmd.category() + ")");
        }
        var skill = new Skill();
        skill.id = UUID.randomUUID().toString();
        skill.name = name;
        skill.description = description;
        skill.author = author;
        skill.category = category;
        skill.status = SkillStatus.DRAFT;
        skill.latestVersion = null;
        skill.riskLevel = null;
        skill.downloadCount = 0;
        // S016 自動 seed owner ACL（owner 為 author；無 author 時仍 seed public read）
        // S026: 加 "public:*:read" public-read pseudo-principal — skill 預設對所有使用者開放讀取
        // （write/delete/suspend/reactivate 仍 owner-only）。
        // S114a: 統一改用 3-segment "public:*:read"（與 V17 backfill + is_public generated column 對齊）。
        // S116: 走 cmd.visibility() 條件分支 — PUBLIC 加 public:*:read（v3.x 既有行為）；
        // PRIVATE 不加（fail-closed by 既有 GIN ?| filter）。author=null + PRIVATE
        // 不允許（無 owner 即無人可讀；早於 ACL seed reject）。
        var visibility = cmd.visibility() == null ? Visibility.PUBLIC : cmd.visibility();
        if (visibility == Visibility.PRIVATE && author == null) {
            throw new IllegalArgumentException(
                    "visibility=PRIVATE 必須提供 author（無 owner 即無人可讀）");
        }
        var entries = new ArrayList<String>();
        if (author != null) {
            entries.add("user:" + author + ":read");
            entries.add("user:" + author + ":write");
            entries.add("user:" + author + ":delete");
        }
        if (visibility == Visibility.PUBLIC) {
            entries.add("public:*:read");
        }
        skill.aclEntries = entries;
        // S114a: owner_id NOT NULL in V16 schema — derive from author; fall back to "unknown" for null-author test fixtures
        skill.ownerId = author != null ? author : "unknown";
        skill.createdAt = Instant.now();
        skill.updatedAt = skill.createdAt;
        // version=null → Persistable.isNew()=true → INSERT path；Spring Data prepareVersionForInsert
        // 在 INSERT 後寫回 version=0（per deepwiki/aggregate-design.md §1.@Version + §4.isNew）
        skill.version = null;
        skill.registerEvent(new SkillCreatedEvent(
                skill.id, name, description, author, category));
        return skill;
    }

    /** S041: agentskills.io 正規格式（與 {@code SkillValidator.NAME_REGEX} 同字面）。 */
    private static final java.util.regex.Pattern NAME_REGEX =
            java.util.regex.Pattern.compile("^[a-z0-9-]{1,64}$");

    /** S042: description 長度上限（與 {@code SkillValidator.DESCRIPTION_MAX} 同值）。 */
    private static final int DESCRIPTION_MAX = 1024;

    /**
     * S056: 嚴格 semver MAJOR.MINOR.PATCH 三段（optional 連字 pre-release suffix）。
     * 對齊 npm / Cargo / pip 慣例；DB column varchar(50) 邊界保護。
     */
    private static final java.util.regex.Pattern VERSION_REGEX =
            java.util.regex.Pattern.compile("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$");

    /** S055: ACL principal 命名空間 — controller 預期 user/role/group 三類，aggregate 守 invariant。 */
    private static final java.util.Set<String> ACL_TYPES =
            java.util.Set.of("user", "role", "group");

    /** S055: ACL permission 動詞 — 與 architecture.md ACL spec 對齊。 */
    private static final java.util.Set<String> ACL_PERMISSIONS =
            java.util.Set.of("read", "write", "delete", "suspend", "reactivate");

    /** S055: ACL tuple 統一驗證 — grantAcl + revokeAcl 共用，違反 → 400 VALIDATION_ERROR。 */
    private static void validateAclTuple(String type, String principal, String permission) {
        if (type == null || !ACL_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "ACL type must be one of " + ACL_TYPES + " (got: " + type + ")");
        }
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("ACL principal must not be blank");
        }
        if (permission == null || !ACL_PERMISSIONS.contains(permission)) {
            throw new IllegalArgumentException(
                    "ACL permission must be one of " + ACL_PERMISSIONS + " (got: " + permission + ")");
        }
    }

    /**
     * SkillQueryService 動態 SQL search 場景的 row → entity 物化 factory。
     * 一般查詢路徑（findById）走 {@link SkillRepository}，由 Spring Data JDBC 自動 mapping。
     * 跨 package 因 query 在 {@code skill.query} 而 aggregate 在 {@code skill.domain}，宣告 public。
     */
    public static Skill fromRow(String id, String name, String description, String author, String category,
            String latestVersion, String riskLevel, String status, long downloadCount,
            Instant createdAt, Instant updatedAt, List<String> aclEntries, Long version) {
        // S119: 13-arg backward-compat overload — delegate to 15-arg with averageRating=0.0 / reviewCount=0
        // defaults。既有 12+ 個 caller (production + test) 行為不變。對齊 S116 ctor delegate 既驗 pattern
        // （100x 成本節省 vs 全 callsite migration）。
        return fromRow(id, name, description, author, category, latestVersion, riskLevel, status,
                downloadCount, createdAt, updatedAt, aclEntries, version, 0.0, 0L);
    }

    /**
     * S119 — 15-arg full row factory 含 review rating projection（averageRating / reviewCount）。
     * SkillQueryService.search() raw JDBC SQL SELECT 兩 column 後走此 factory；既有 single-skill
     * findById 走 Spring Data JDBC auto-load 不經此 path。
     */
    public static Skill fromRow(String id, String name, String description, String author, String category,
            String latestVersion, String riskLevel, String status, long downloadCount,
            Instant createdAt, Instant updatedAt, List<String> aclEntries, Long version,
            double averageRating, long reviewCount) {
        var skill = new Skill();
        skill.id = id;
        skill.name = name;
        skill.description = description;
        skill.author = author;
        skill.category = category;
        skill.latestVersion = latestVersion;
        skill.riskLevel = riskLevel;
        skill.status = status == null ? null : SkillStatus.valueOf(status);
        skill.downloadCount = downloadCount;
        skill.createdAt = createdAt;
        skill.updatedAt = updatedAt;
        // mutable ArrayList — 物化後不應再 mutate（query path 唯讀），但保持與 create() 行為一致避免後續混淆
        skill.aclEntries = aclEntries == null ? new ArrayList<>() : new ArrayList<>(aclEntries);
        // S114a: owner_id derives from author for query-side factory (read-model path)
        skill.ownerId = author != null ? author : "unknown";
        skill.version = version;
        skill.averageRating = averageRating;
        skill.reviewCount = reviewCount;
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
        // S056: semver 預驗 — 違反 → IllegalArgumentException → 400 VALIDATION_ERROR
        if (version == null || !VERSION_REGEX.matcher(version).matches()) {
            throw new IllegalArgumentException(
                    "Version must match semver MAJOR.MINOR.PATCH (got: " + version + ")");
        }
        SkillStatus next = this.status.publish();
        this.latestVersion = version;
        this.status = next;
        this.updatedAt = Instant.now();
        registerEvent(new SkillVersionPublishedFromAggregate(id, version));
    }

    /**
     * 停用 skill — PUBLISHED → SUSPENDED 唯一合法 transition；
     * DRAFT / SUSPENDED 拋 {@link IllegalStateException}（per {@link SkillStatus#suspend()}）。
     */
    public void suspend(SuspendCommand cmd) {
        this.status = this.status.suspend();
        this.updatedAt = Instant.now();
        registerEvent(new SkillSuspendedEvent(id, cmd.reason(), cmd.suspendedBy()));
    }

    /** 重啟 skill — SUSPENDED → PUBLISHED 唯一合法 transition。 */
    public void reactivate(ReactivateCommand cmd) {
        this.status = this.status.reactivate();
        this.updatedAt = Instant.now();
        registerEvent(new SkillReactivatedEvent(id, cmd.reason()));
    }

    /**
     * 授權 ACL entry。
     * <ul>
     *   <li>業務不變量：相同 {@code type:principal:permission} 不可重複 grant → 違反拋 {@link IllegalStateException}</li>
     *   <li>{@code aclEntries} 為 {@link java.util.ArrayList}（mutable）；行內 add — {@code repo.save()}
     *       觸發單條 {@code UPDATE skills SET acl_entries = ?, ...}（per ADR-002 §2.4 — 避開
     *       {@code @MappedCollection} delete-and-reinsert 雷）</li>
     * </ul>
     */
    public void grantAcl(GrantAclCommand cmd) {
        // S055: aggregate 守 ACL tuple invariant — type/principal/permission 缺失或不合法 → 400
        validateAclTuple(cmd.type(), cmd.principal(), cmd.permission());
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
     * 撤銷 ACL entry。
     * 業務不變量：必須存在對應 entry 才可 revoke；不存在拋 {@link IllegalStateException}。
     */
    public void revokeAcl(RevokeAclCommand cmd) {
        // S055: 同 grant 守 ACL tuple invariant
        validateAclTuple(cmd.type(), cmd.principal(), cmd.permission());
        var entry = entry(cmd.type(), cmd.principal(), cmd.permission());
        if (!aclEntries.remove(entry)) {
            throw new IllegalStateException("ACL entry not found: " + entry);
        }
        this.updatedAt = Instant.now();
        registerEvent(new SkillAclRevokedEvent(
                id, cmd.type(), cmd.principal(), cmd.permission(), cmd.revokedBy()));
    }

    /**
     * 累計下載計數 + register {@link SkillDownloadedEvent}。
     * {@code latestVersion} 用作 event 的 version 欄位；呼叫前必須已發布過版本。
     */
    public void recordDownload() {
        this.downloadCount++;
        this.updatedAt = Instant.now();
        registerEvent(SkillDownloadedEvent.of(id, latestVersion));
    }

    // ============================================================================
    // Read accessors
    // ============================================================================

    @Override
    public String getId() { return id; }

    /**
     * {@link Persistable#isNew()} 自訂 — 預設 Spring Data JDBC 規則「@Id 非 null = existing」
     * 不適用於 client-generated UUID PK（{@link #create} factory 預設 id）。改以 {@link #version}
     * (@Version) 是否 null 判斷：null = 尚未持久化（INSERT path）；non-null = 已持久化（UPDATE path）。
     *
     * <p>per deepwiki/spring-data-jdbc-modulith/aggregate-design.md §1.@Version + §4.isNew。
     *
     * <p>S063：{@code @JsonIgnore} — Persistable artifact 不該暴露於 API JSON（同 S062 對 SkillVersion 的修復）
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Override
    public boolean isNew() {
        return this.version == null;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getAuthor() { return author; }
    public String getCategory() { return category; }
    public SkillStatus getStatus() { return status; }
    public String getLatestVersion() { return latestVersion; }
    public String getRiskLevel() { return riskLevel; }
    public long getDownloadCount() { return downloadCount; }
    public double getAverageRating() { return averageRating; }
    public long getReviewCount() { return reviewCount; }
    public List<String> getAclEntries() {
        return aclEntries == null ? List.of() : List.copyOf(aclEntries);
    }
    public String getOwnerId() { return ownerId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isVerified() { return verified; }
    public Instant getLatestVersionPublishedAt() { return latestVersionPublishedAt; }
    public String getLicense() { return license; }
    public List<String> getCompatibility() { return compatibility == null ? List.of() : List.copyOf(compatibility); }
    public long getVersionCount() { return versionCount; }
    public long getOpenFlagCount() { return openFlagCount; }

    /** S142b: populate read-only detail fields; returns this for fluent chaining. */
    public Skill withDetail(boolean verified, Instant latestVersionPublishedAt, String license,
            List<String> compatibility, long versionCount, long openFlagCount) {
        this.verified = verified;
        this.latestVersionPublishedAt = latestVersionPublishedAt;
        this.license = license;
        this.compatibility = compatibility;
        this.versionCount = versionCount;
        this.openFlagCount = openFlagCount;
        return this;
    }

    /** {@code @Version} 樂觀鎖；不 expose 至 JSON response（per spec §AC-11）。 */
    @JsonIgnore
    public Long getVersion() { return version; }

    // ============================================================================
    // Helpers
    // ============================================================================

    private static String entry(String type, String principal, String permission) {
        return type + ":" + principal + ":" + permission;
    }

    /**
     * 從 frontmatter Map 解析 {@code allowed-tools} space-separated 字串為 List。
     * {@link SkillVersion#publish} 同邏輯（v1.5.0 既有；保留 public static 供 SkillCommandService.uploadSkill 透傳）。
     */
    public static List<String> parseAllowedTools(Map<String, Object> frontmatter) {
        if (frontmatter == null) return List.of();
        var raw = frontmatter.get("allowed-tools");
        if (raw == null) return List.of();
        var asString = raw.toString().trim();
        if (asString.isEmpty()) return List.of();
        return List.of(asString.split("\\s+"));
    }
}
