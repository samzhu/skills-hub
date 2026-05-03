package io.github.samzhu.skillshub.community;

import java.util.HashSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.skillshub.shared.api.CollectionNotFoundException;
import io.github.samzhu.skillshub.shared.api.SkillNotPublishableException;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillStatus;

/**
 * S096f2-T02 — Collection 應用服務（per spec §4.5；3-line orchestration ADR-002 canonical）。
 *
 * <p>Cross-module 守則：create 預檢全 skillIds 必須 PUBLISHED（呼叫
 * {@link SkillRepository#findAllByIdInAndStatus}），missing / non-PUBLISHED 收成
 * {@link SkillNotPublishableException} 回 400。Install 走 spec §1 Approach C：service 端
 * 只 record event + bump install_count + 回 download URLs；frontend 接管 N 個 browser
 * download trigger（自然累計 each skill 的 download_count）。
 */
@Service
public class CollectionService {

    private final CollectionRepository repo;
    private final SkillRepository skillRepo;
    private final CurrentUserProvider users;

    public CollectionService(CollectionRepository repo, SkillRepository skillRepo, CurrentUserProvider users) {
        this.repo = repo;
        this.skillRepo = skillRepo;
        this.users = users;
    }

    /**
     * AC-1/2/3/4 — create with skillIds 全 PUBLISHED 預檢。
     *
     * @return 新 Collection id
     */
    @Transactional
    public String create(String name, String description, String category, List<String> skillIds) {
        // 1. validate skillIds 全 PUBLISHED（cross-module skill::domain lookup）
        var found = skillRepo.findAllByIdInAndStatus(skillIds, SkillStatus.PUBLISHED);
        var foundIds = new HashSet<>(found.stream().map(Skill::getId).toList());
        var invalid = skillIds.stream().filter(id -> !foundIds.contains(id)).toList();
        if (!invalid.isEmpty()) {
            throw new SkillNotPublishableException(invalid);
        }
        // 2. factory + save (outbox auto-publish via @DomainEvents)
        var collection = Collection.create(name, description, category, users.userId(), skillIds);
        return repo.save(collection).getId();
    }

    /**
     * AC-7/8 — install：record event + bump install_count + 回 N 個 download URLs。
     * frontend 接管 browser download trigger（per spec §1 Approach C）。
     */
    @Transactional
    public List<String> install(String collectionId) {
        var collection = repo.findById(collectionId)
                .orElseThrow(() -> new CollectionNotFoundException(collectionId));
        collection.recordInstall(users.userId());
        var saved = repo.save(collection);
        return saved.skillIds().stream()
                .map(skillId -> "/api/v1/skills/" + skillId + "/download")
                .toList();
    }

    /** AC-5 — list with optional category filter；service 端決定 derived query 走哪條。 */
    public List<Collection> list(String categoryFilter) {
        if (categoryFilter == null || categoryFilter.isBlank()) {
            return repo.findAllByOrderByCreatedAtDesc();
        }
        return repo.findAllByCategoryOrderByCreatedAtDesc(categoryFilter);
    }

    /** AC-6 — single collection；不存在 → 404。 */
    public Collection get(String id) {
        return repo.findById(id).orElseThrow(() -> new CollectionNotFoundException(id));
    }

    /** AC-6 helper — 對應 collection skills 的 Skill aggregate read（保留 collection 順序）。 */
    public List<Skill> getCollectionSkills(Collection collection) {
        var ids = collection.skillIds();
        if (ids.isEmpty()) return List.of();
        // findAllById 不保證順序；用 Map 重排回 collection 內 position 順序。對應 SUSPENDED
        // 後既有 collection 仍保留 historical reference — 但 skill 從 DB 不存在則 skip（防外洩）。
        var byId = skillRepo.findAllById(ids).stream()
                .collect(java.util.stream.Collectors.toMap(Skill::getId, s -> s));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }
}
