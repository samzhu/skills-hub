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
import io.github.samzhu.skillshub.skill.command.ReactivateCommand;
import io.github.samzhu.skillshub.skill.command.SuspendCommand;
import io.github.samzhu.skillshub.skill.command.UpdateSkillCommand;
import io.github.samzhu.skillshub.skill.command.VersionLabelPolicy;
import io.github.samzhu.skillshub.skill.query.ViewerPermissions;

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
 *   <li>{@link #recordDownload()} — 累計下載計數 + 發 SkillDownloadedEvent</li>
 * </ul>
 *
 * <p>S167b — ACL grant/revoke 經 aggregate 充血方法的舊 path 已移除（v4.42.0 之 S167 拿掉 HTTP
 * controller，留下的 aggregate / event / handler dead code 全清）。寫 ACL 路徑唯一入口為 S114a
 * {@code SkillGrantController} → {@code SkillGrantService} → {@code skill_grants} 表 +
 * {@code SkillAclProjectionListener} 重建 {@code aclEntries} 投影。S177 起公開狀態由
 * {@code skills.is_public} 直接保存，ACL 只保留明確授權。
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
     * detail 不該對 list browser 公開（least-privilege response）。
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
    /**
     * S159b Round 2 — 保留原始 CamelCase（"DevOps" / "DataOps"）給 UI display；
     * {@link #category} 仍是 lowercase canonical（V20 CHECK enforce）給 search 用。
     * Nullable — V21 schema 允許 null（舊資料 backfill 後可能仍 null；frontend 走
     * `categoryDisplay ?? capitalize(category)` fallback）。
     */
    @Column("category_display")
    private String categoryDisplay;
    /**
     * S154 — publish/republish 時 freeze 的 author 顯示名稱（從 {@code CurrentUserProvider.current().name()}
     * 取得；後續 user 改名 {@link #author} 對應 user 改名時，已 publish 的 snapshot 不變）。Nullable —
     * V18 schema {@code author_name_snapshot VARCHAR(255) NULLABLE}；無 OIDC name claim 時可為 null
     * （回 SkillResponse 時走 DisplayNameResolver fallback）。
     */
    @Column("author_name_snapshot")
    private String authorNameSnapshot;
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
    /** S169: aclEntries 只給 SQL 授權用，不出 API JSON；detail action 改看 viewerPermissions。 */
    @JsonIgnore
    @Column("acl_entries")
    private List<String> aclEntries;
    /**
     * S180/S168 — public visibility source-of-truth；Boolean wrapper avoids GraalVM native
     * primitive boolean MethodHandle corruption. DB column is NOT NULL, getter unboxing is safe.
     */
    @JsonIgnore
    @Column("is_public")
    private Boolean publicSkill;
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
    // S154 — author identity enrichment fields (LEFT JOIN users at query time，per AC-6)；
    // 來源 SkillQueryService.enrichAuthorIdentity()；users row 缺時走 authorNameSnapshot fallback。
    @Transient @org.jspecify.annotations.Nullable private String authorDisplayName;
    @Transient @org.jspecify.annotations.Nullable private String authorHandle;
    @Transient @org.jspecify.annotations.Nullable private String authorEmail;
    @Transient
    @JsonView(Views.Detail.class)
    private ViewerPermissions viewerPermissions;
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
     *
     * <p>S154-T04：{@link CreateSkillCommand#authorNameSnapshot} 於此處 freeze 至
     * {@link #authorNameSnapshot} field，後續 user 改名 OAuth name 不影響本 skill 已 publish 的
     * 顯示名稱（query 端 LEFT JOIN users 取 live name；users row 缺則 fallback 此 snapshot）。
     * Nullable — test fixture / 無 OIDC name claim 場景可傳 null。
     */
    public static Skill create(CreateSkillCommand cmd) {
        // S054: 用 IllegalArgumentException 而非 NPE — 走 GlobalExceptionHandler 既有 400 VALIDATION_ERROR 路徑。
        // Aggregate factory 為 user input 守門點；NPE 屬 programmer bug 語意不對。
        if (cmd.name() == null) throw new IllegalArgumentException("name is required");
        if (cmd.description() == null) throw new IllegalArgumentException("description is required");
        // S176-T07: skills.name 是平台顯示名稱，不是 SKILL.md package name；package name
        // 仍由 SkillValidator 依 agentskills.io regex 驗證。
        var name = cmd.name().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Skill name must not be blank");
        }
        if (name.length() > NAME_MAX) {
            throw new IllegalArgumentException(
                    "Skill name exceeds " + NAME_MAX + " characters (got: " + name.length() + ")");
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Skill name must not contain control characters");
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
        // S042 trim + S159b lowercase：DB V20 加 CHECK constraint，category 必須 lowercase；
        // null 允許；非 null 但 blank → reject（mirror S041 author）
        // S159b Round 2 — 同時保留 categoryDisplay（trim 但不 lowercase）給 UI display
        var rawCategory = cmd.category() == null ? null : cmd.category().trim();
        if (rawCategory != null && rawCategory.isEmpty()) {
            throw new IllegalArgumentException(
                    "Skill category must not be blank (got: " + cmd.category() + ")");
        }
        var category = rawCategory == null ? null : rawCategory.toLowerCase();
        var skill = new Skill();
        skill.id = UUID.randomUUID().toString();
        skill.name = name;
        skill.description = description;
        skill.author = author;
        skill.authorNameSnapshot = cmd.authorNameSnapshot();  // S154 — nullable；test fixture 常傳 null
        skill.category = category;
        skill.categoryDisplay = rawCategory;  // S159b Round 2 — trim 但不 lowercase，給 UI display 保留原 case
        skill.status = SkillStatus.DRAFT;
        skill.latestVersion = null;
        skill.riskLevel = null;
        skill.downloadCount = 0;
        // S177: public visibility 寫 skills.is_public；aclEntries 只保存 explicit owner/grant permissions。
        // author=null + PRIVATE 不允許（無 owner 即無人可讀；早於 ACL seed reject）。
        var visibility = cmd.visibility() == null ? Visibility.PUBLIC : cmd.visibility();
        if (visibility == Visibility.PRIVATE && author == null) {
            throw new IllegalArgumentException(
                    "visibility=PRIVATE 必須提供 author（無 owner 即無人可讀）");
        }
        skill.publicSkill = visibility == Visibility.PUBLIC;
        var entries = new ArrayList<String>();
        if (author != null) {
            entries.add("user:" + author + ":read");
            entries.add("user:" + author + ":write");
            entries.add("user:" + author + ":delete");
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

    /** S176-T07: platform display name length limit; SKILL.md package-name regex lives in SkillValidator. */
    private static final int NAME_MAX = 64;

    /** S042: description 長度上限（與 {@code SkillValidator.DESCRIPTION_MAX} 同值）。 */
    private static final int DESCRIPTION_MAX = 1024;

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
     *
     * <p>S159b Round 2：本 15-arg overload 不收 categoryDisplay → delegate 16-arg 設 null；
     * fallback 由 frontend 處理（categoryDisplay ?? capitalize(category)）。新 caller 應走 16-arg。
     */
    public static Skill fromRow(String id, String name, String description, String author, String category,
            String latestVersion, String riskLevel, String status, long downloadCount,
            Instant createdAt, Instant updatedAt, List<String> aclEntries, Long version,
            double averageRating, long reviewCount) {
        return fromRow(id, name, description, author, category, null, latestVersion, riskLevel, status,
                downloadCount, createdAt, updatedAt, aclEntries, version, averageRating, reviewCount);
    }

    /**
     * S159b Round 2 — 16-arg full row factory 多帶 categoryDisplay（原 CamelCase 保留）。
     * `SkillQueryService.mapSkillRow` 走此 overload，SELECT 含 category_display column。
     */
    public static Skill fromRow(String id, String name, String description, String author, String category,
            String categoryDisplay, String latestVersion, String riskLevel, String status, long downloadCount,
            Instant createdAt, Instant updatedAt, List<String> aclEntries, Long version,
            double averageRating, long reviewCount) {
        return fromRow(id, name, description, author, category, categoryDisplay, latestVersion, riskLevel, status,
                downloadCount, createdAt, updatedAt, aclEntries, version, averageRating, reviewCount, false);
    }

    /**
     * S185 — list raw SQL path must preserve {@code skills.is_public}; ACL entries no longer imply
     * public visibility after S177, so callers that selected the column pass it explicitly.
     */
    public static Skill fromRow(String id, String name, String description, String author, String category,
            String categoryDisplay, String latestVersion, String riskLevel, String status, long downloadCount,
            Instant createdAt, Instant updatedAt, List<String> aclEntries, Long version,
            double averageRating, long reviewCount, Boolean publicSkill) {
        var skill = new Skill();
        skill.id = id;
        skill.name = name;
        skill.description = description;
        skill.author = author;
        skill.category = category;
        skill.categoryDisplay = categoryDisplay;
        skill.latestVersion = latestVersion;
        skill.riskLevel = riskLevel;
        skill.status = status == null ? null : SkillStatus.valueOf(status);
        skill.downloadCount = downloadCount;
        skill.createdAt = createdAt;
        skill.updatedAt = updatedAt;
        // mutable ArrayList — 物化後不應再 mutate（query path 唯讀），但保持與 create() 行為一致避免後續混淆
        skill.aclEntries = aclEntries == null ? new ArrayList<>() : new ArrayList<>(aclEntries);
        skill.publicSkill = Boolean.TRUE.equals(publicSkill);
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
        // S154 backward-compat overload — 不更新 snapshot；既有 caller（含 service 內 publish path）
        // 走此 ctor 行為與 v3.x 完全一致。新 caller（T04 controller）走 2-arg version 帶 snapshot。
        recordVersionPublished(version, null);
    }

    /**
     * S154 — publish/republish 同時更新 {@link #authorNameSnapshot}（per AC-5）。
     *
     * <p>{@code snapshot} 由 {@code SkillCommandService} 從 {@code currentUserProvider.current().name()}
     * 取得後透傳；null 代表「不更新 snapshot」（既有值維持不動），non-null 代表 freeze 該值。
     */
    public void recordVersionPublished(String version, @org.jspecify.annotations.Nullable String snapshot) {
        var versionLabel = VersionLabelPolicy.validateLabel(version);
        SkillStatus next = this.status.publish();
        this.latestVersion = versionLabel;
        this.status = next;
        // S154: snapshot != null → freeze；null → 維持既有（避免 republish 路徑誤清 snapshot）
        if (snapshot != null) {
            this.authorNameSnapshot = snapshot;
        }
        // S187-T06: 新版本需重新掃描；避免 validate page 讀舊 riskLevel 直接跳轉。
        this.riskLevel = null;
        this.updatedAt = Instant.now();
        registerEvent(new SkillVersionPublishedFromAggregate(id, versionLabel));
    }

    /**
     * S187-T04 — 新版本 SKILL.md frontmatter.description 是 skill card 的最新描述 snapshot。
     */
    public void refreshDescriptionSnapshot(String description, String updatedBy) {
        var desc = normalizeDescription(description);
        if (desc.equals(this.description)) {
            return;
        }
        this.description = desc;
        this.updatedAt = Instant.now();
        registerEvent(new SkillUpdatedEvent(id, this.description, this.category, updatedBy, this.updatedAt));
    }

    /**
     * S187 — owner 只能改 category。description snapshot 由新版本 SKILL.md frontmatter 更新。
     *
     * <p>category null 表示本次不動。trim 後落地；blank 視為「想清空」reject（mirror create）。
     * 同 category 一字未動則 skip event。
     */
    public void update(UpdateSkillCommand cmd, String updatedBy) {
        if (cmd == null) {
            throw new IllegalArgumentException("UpdateSkillCommand must not be null");
        }
        boolean changed = false;
        if (cmd.category() != null) {
            // S042 trim + S159b lowercase — 對齊 create()；V20 CHECK 要求 lowercase
            // S159b Round 2 — 同時 dual-write categoryDisplay 保留原 case
            var rawCat = cmd.category().trim();
            if (rawCat.isEmpty()) {
                throw new IllegalArgumentException("Skill category must not be blank");
            }
            var cat = rawCat.toLowerCase();
            if (!cat.equals(this.category)) {
                this.category = cat;
                this.categoryDisplay = rawCat;
                changed = true;
            } else if (!rawCat.equals(this.categoryDisplay)) {
                // canonical category 沒變但 display case 有改（"DevOps" → "devops" input → 仍 update display）
                this.categoryDisplay = rawCat;
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        this.updatedAt = Instant.now();
        registerEvent(new SkillUpdatedEvent(id, this.description, this.category, updatedBy, this.updatedAt));
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
     * 累計下載計數 + register {@link SkillDownloadedEvent}。
     * {@code latestVersion} 用作 event 的 version 欄位；呼叫前必須已發布過版本。
     */
    public void recordDownload() {
        this.downloadCount++;
        this.updatedAt = Instant.now();
        registerEvent(SkillDownloadedEvent.of(id, latestVersion));
    }

    /**
     * S177 — public grant mirror toggles the source-of-truth column.
     */
    public void makePublic(String changedBy, String grantId) {
        if (Boolean.TRUE.equals(this.publicSkill)) {
            return;
        }
        this.publicSkill = true;
        this.updatedAt = Instant.now();
        registerEvent(new SkillVisibilityChangedEvent(id, true, grantId, changedBy, this.updatedAt));
    }

    /**
     * S177 — revoking the public grant makes the skill private again.
     */
    public void makePrivate(String changedBy, String grantId) {
        if (!Boolean.TRUE.equals(this.publicSkill)) {
            return;
        }
        this.publicSkill = false;
        this.updatedAt = Instant.now();
        registerEvent(new SkillVisibilityChangedEvent(id, false, grantId, changedBy, this.updatedAt));
    }

    /**
     * S144 — register hard-delete event before repository deletes the aggregate row.
     *
     * <p>No state field is mutated because {@code skills} is removed in the same transaction.
     */
    public void markDeleted(String deletedBy, List<String> storagePaths) {
        registerEvent(new SkillDeletedEvent(id, name, deletedBy, Instant.now(),
                storagePaths == null ? List.of() : List.copyOf(storagePaths)));
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
    /** S159b Round 2 — UI display name 保留原 CamelCase（"DevOps"）；nullable，fallback 由 frontend 處理。 */
    public String getCategoryDisplay() { return categoryDisplay; }
    /** S154 — publish 時 freeze 的 author 顯示名稱；null 為合法（無 OIDC name claim 場景）。*/
    @org.jspecify.annotations.Nullable
    public String getAuthorNameSnapshot() { return authorNameSnapshot; }
    public SkillStatus getStatus() { return status; }
    public String getLatestVersion() { return latestVersion; }
    public String getRiskLevel() { return riskLevel; }
    public long getDownloadCount() { return downloadCount; }
    public double getAverageRating() { return averageRating; }
    public long getReviewCount() { return reviewCount; }
    @JsonIgnore
    public List<String> getAclEntries() {
        return aclEntries == null ? List.of() : List.copyOf(aclEntries);
    }
    @JsonIgnore
    public boolean isPublic() { return Boolean.TRUE.equals(publicSkill); }
    public Visibility getVisibility() { return isPublic() ? Visibility.PUBLIC : Visibility.PRIVATE; }
    public String getOwnerId() { return ownerId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isVerified() { return verified; }
    public Instant getLatestVersionPublishedAt() { return latestVersionPublishedAt; }
    public String getLicense() { return license; }
    public List<String> getCompatibility() { return compatibility == null ? List.of() : List.copyOf(compatibility); }
    public long getVersionCount() { return versionCount; }
    public long getOpenFlagCount() { return openFlagCount; }
    /** S154 — display name from users LEFT JOIN（fallback authorNameSnapshot 當 users row 已 deleted）。*/
    @org.jspecify.annotations.Nullable
    public String getAuthorDisplayName() { return authorDisplayName; }
    /** S154 — platform handle from users LEFT JOIN；users row 缺時 null（snapshot 不含 handle）。*/
    @org.jspecify.annotations.Nullable
    public String getAuthorHandle() { return authorHandle; }
    /** S154 — author email；只在 users.contact_email_public=true 時 set，否則 null（API 隱藏）。*/
    @org.jspecify.annotations.Nullable
    public String getAuthorEmail() { return authorEmail; }
    public ViewerPermissions getViewerPermissions() { return viewerPermissions; }

    /**
     * S154 — populate author identity fields from users LEFT JOIN result（per AC-6）。
     *
     * @param displayName  從 {@link io.github.samzhu.skillshub.shared.security.DisplayNameResolver} 計算（5-layer fallback）；
     *                     users row 缺時可傳 {@code authorNameSnapshot} 當 fallback；caller responsibility
     * @param handle       users.handle；無 row 傳 null
     * @param email        users.email；只在 {@code contact_email_public=true} 時傳；否則傳 null（API 隱藏 author email）
     */
    public Skill withAuthorIdentity(@org.jspecify.annotations.Nullable String displayName,
                                    @org.jspecify.annotations.Nullable String handle,
                                    @org.jspecify.annotations.Nullable String email) {
        this.authorDisplayName = displayName;
        this.authorHandle = handle;
        this.authorEmail = email;
        return this;
    }

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

    public Skill withViewerPermissions(ViewerPermissions viewerPermissions) {
        this.viewerPermissions = viewerPermissions;
        return this;
    }

    /** {@code @Version} 樂觀鎖；不 expose 至 JSON response（per spec §AC-11）。 */
    @JsonIgnore
    public Long getVersion() { return version; }

    // ============================================================================
    // Helpers
    // ============================================================================

    private static String normalizeDescription(String rawDescription) {
        if (rawDescription == null) {
            throw new IllegalArgumentException("description is required");
        }
        var desc = rawDescription.trim();
        if (desc.isEmpty()) {
            throw new IllegalArgumentException("Skill description must not be blank");
        }
        if (desc.length() > DESCRIPTION_MAX) {
            throw new IllegalArgumentException(
                    "Skill description exceeds " + DESCRIPTION_MAX + " characters (got: " + desc.length() + ")");
        }
        return desc;
    }

    /** 從 frontmatter Map 解析 {@code allowed-tools}；支援 YAML list 與 legacy space-separated string。 */
    public static List<String> parseAllowedTools(Map<String, Object> frontmatter) {
        if (frontmatter == null) return List.of();
        var raw = frontmatter.get("allowed-tools");
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(token -> !token.isEmpty())
                    .toList();
        }
        var asString = raw.toString().trim();
        if (asString.isEmpty()) return List.of();
        return List.of(asString.split("\\s+"));
    }
}
