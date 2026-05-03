package io.github.samzhu.skillshub.community;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.AbstractAggregateRoot;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.github.samzhu.skillshub.community.events.CollectionCreatedEvent;
import io.github.samzhu.skillshub.community.events.CollectionInstalledEvent;

/**
 * S096f2 — Collection aggregate root（curated bundle of skills）。
 *
 * <p>對齊 ADR-002 canonical pattern：
 * <ul>
 *   <li>{@code extends AbstractAggregateRoot<Collection>} — 提供 {@code registerEvent(...)}
 *       讓 {@code repo.save()} 自動 publish 至 Modulith {@code event_publication} outbox</li>
 *   <li>{@code @Version Long version} — Spring Data JDBC 標準 INSERT/UPDATE 區分
 *       （version=null INSERT；loaded UPDATE）；對齊 Request aggregate (S096g2-T01) 既驗。
 *       <b>不</b>走 spec §4.3 範本的 {@code Persistable + 自訂 isNew()}：factory 設
 *       {@code createdAt=Instant.now()} 會破 isNew flag（已是 codebase 第 4 次踩坑教訓）</li>
 *   <li>{@code @MappedCollection(idColumn="collection_id", keyColumn="position")}
 *       — Spring Data JDBC 一對多 list 標準路徑；save() 走 delete-and-reinsert，
 *       position 由 list 索引自動派生。對應 {@code collection_skills} 表
 *       PK (collection_id, position) + UNIQUE (collection_id, skill_id) 雙保護</li>
 * </ul>
 *
 * <p>Domain events：
 * <ul>
 *   <li>{@link CollectionCreatedEvent} — factory {@link #create} 觸發；
 *       payload 含 skillIds list（給 future S101b Impact Score 計算）</li>
 *   <li>{@link CollectionInstalledEvent} — {@link #recordInstall} 觸發；
 *       install_count 同 TX bump + outbox publish 同步</li>
 * </ul>
 */
@Table("collections")
public class Collection extends AbstractAggregateRoot<Collection> {

    private static final int NAME_MAX = 200;
    private static final int DESCRIPTION_MAX = 2000;
    private static final int CATEGORY_MAX = 100;

    @Id
    private String id;
    private String name;
    private String description;
    @Column("owner_id")
    private String ownerId;
    private String category;
    @Column("install_count")
    private int installCount;
    @Column("created_at")
    private Instant createdAt;
    @Version
    @JsonIgnore
    private Long version;

    @MappedCollection(idColumn = "collection_id", keyColumn = "position")
    private List<CollectionSkillRef> skills = new ArrayList<>();

    @PersistenceCreator
    private Collection() {}

    /**
     * S096f2 AC-1/2/3/4 — 建立新 Collection；驗 name / description / category 長度上限
     * 與 skillIds 非空且 unique；註冊 {@link CollectionCreatedEvent}。
     *
     * <p>Factory 設 {@code createdAt = Instant.now()} 對齊 Request (S096g2-T01) 既驗
     * canonical pattern — INSERT/UPDATE 區分依靠 {@code @Version} (version=null INSERT；
     * loaded version=N UPDATE)，非 {@code createdAt}。Spring Data JDBC explicit
     * INSERT 不會觸發 DB DEFAULT NOW()，必須由 entity 提供值；否則 NOT NULL 違規。
     *
     * @param skillIds 須 ≥ 1 且 unique；caller (Service) 另外驗 全 PUBLISHED status
     */
    public static Collection create(String name, String description, String category,
                                    String ownerId, List<String> skillIds) {
        validateName(name);
        validateDescription(description);
        validateCategory(category);
        validateOwner(ownerId);
        validateSkillIds(skillIds);

        var c = new Collection();
        c.id = UUID.randomUUID().toString();
        c.name = name.trim();
        c.description = description == null ? null : description.trim();
        c.category = category.trim();
        c.ownerId = ownerId;
        c.installCount = 0;
        c.createdAt = Instant.now();
        c.skills = IntStream.range(0, skillIds.size())
                .mapToObj(i -> new CollectionSkillRef(skillIds.get(i)))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        c.version = null; // INSERT path — @Version 為 INSERT/UPDATE 唯一區分器

        c.registerEvent(new CollectionCreatedEvent(c.id, c.name, c.ownerId,
                List.copyOf(skillIds), c.createdAt));
        return c;
    }

    /**
     * S096f2 AC-7 — 記錄一次 install：bump install_count + 註冊
     * {@link CollectionInstalledEvent}。實際 N 個 skill 的下載觸發由 frontend
     * orchestration 接管（per spec §1 Approach C），本 method 不返回 download URLs
     * （那是 service 端從 {@link #skills} 派生的 view 邏輯）。
     */
    public void recordInstall(String installerId) {
        if (installerId == null || installerId.isBlank()) {
            throw new IllegalArgumentException("installerId is required");
        }
        this.installCount++;
        registerEvent(new CollectionInstalledEvent(this.id, installerId, Instant.now()));
    }

    /** Service 端用 — 取 skill IDs 順序保留 list 派生 download URLs。 */
    public List<String> skillIds() {
        return skills.stream().map(CollectionSkillRef::skillId).toList();
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name_required");
        }
        if (name.trim().length() > NAME_MAX) {
            throw new IllegalArgumentException(
                    "name_too_long: name exceeds " + NAME_MAX + " characters (got: " + name.trim().length() + ")");
        }
    }

    private static void validateDescription(String description) {
        if (description != null && description.trim().length() > DESCRIPTION_MAX) {
            throw new IllegalArgumentException(
                    "description_too_long: description exceeds " + DESCRIPTION_MAX + " characters");
        }
    }

    private static void validateCategory(String category) {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category_required");
        }
        if (category.trim().length() > CATEGORY_MAX) {
            throw new IllegalArgumentException(
                    "category_too_long: category exceeds " + CATEGORY_MAX + " characters");
        }
    }

    private static void validateOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId is required");
        }
    }

    private static void validateSkillIds(List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            throw new IllegalArgumentException("collection_must_have_skills");
        }
        // 同 collection 內 skill 不重複（DB UNIQUE constraint 雙保險，但 factory 預檢
        // 給 caller 友善 message + 避免 INSERT 失敗讓 UNIQUE 跳出來）
        var unique = new HashSet<>(skillIds);
        if (unique.size() != skillIds.size()) {
            throw new IllegalArgumentException("collection_must_have_unique_skills");
        }
        for (var id : skillIds) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("collection_must_have_skills");
            }
        }
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getOwnerId() { return ownerId; }
    public String getCategory() { return category; }
    public int getInstallCount() { return installCount; }
    public Instant getCreatedAt() { return createdAt; }

    /** 防外洩 mutable internal list — 對齊 Skill aggregate 既驗 defensive copy 慣例。 */
    public List<CollectionSkillRef> getSkills() {
        return skills == null ? List.of() : List.copyOf(skills);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Collection that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
