package io.github.samzhu.skillshub.skill.command;

import java.lang.invoke.MethodHandles;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.skill.query.AclEntryResponse;
import io.github.samzhu.skillshub.skill.query.SkillAclQueryService;

/**
 * @deprecated S016 legacy endpoint — 已知 dual-source-of-truth race bug：
 *             aggregate 直寫 {@code skills.acl_entries} JSONB；但 {@code SkillAclProjectionListener}
 *             {@code .onSkillCreated()} 的 {@code rebuildAcl()} 從 {@code skill_grants} 重建 acl_entries
 *             會覆蓋掉本端點的直寫（race timing 不可預期）。
 *
 *             <p>遷移路徑：改用 S114a {@code /api/v1/skills/{id}/grants} (SkillGrantController) —
 *             該端點透過 SkillGrantService 寫 skill_grants source-of-truth + publish SkillGrantedEvent
 *             → projection rebuild 一致；不會被 onSkillCreated rebuild 蓋掉。
 *
 *             <p>注意：本端點 3-tuple {@code (type, principal, permission)} 細粒度語義 vs
 *             {@code /grants} Role-based 不完全 1:1。Future spec 需先擴 Role enum（如新增 EDITOR）
 *             或加 per-permission grant 列才能真正 remove；本 iteration 僅加 @Deprecated + warning log。
 *
 * <p>S016：Skill ACL CRUD REST Controller — 管理單一 Skill 的 acl_entries 條目。
 *
 * <p>提供 grant（POST）/ revoke（DELETE）/ list（GET）三個端點。
 *
 * <p>寫入端 {@code @PreAuthorize("hasPermission(#id, 'Skill', 'write')")} 守門：
 * 呼叫者本身須對該 Skill 具 {@code write} 權限才能調整其 ACL（防止匿名或低權使用者
 * 自行 grant 自己更高權限）。GET 列舉端用 {@code 'read'} 守門：列 ACL 即視為敏感資訊，
 * 只有有 read 權限者能看。{@code grantedBy} / {@code revokedBy} 從 {@link CurrentUserProvider}
 * 取得，不採用 request body 任何欄位以防 spoof。
 *
 * @see SkillCommandService#grantAcl
 * @see SkillCommandService#revokeAcl
 * @see io.github.samzhu.skillshub.skill.security.SkillGrantController preferred replacement
 */
@Deprecated(since = "v4.42.0", forRemoval = true)
@RestController
@RequestMapping("/api/v1/skills/{id}/acl")
public class SkillAclController {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final SkillCommandService commandService;
    private final SkillAclQueryService queryService;
    private final CurrentUserProvider currentUserProvider;

    private void warnDeprecated(String op, String skillId) {
        log.atWarn()
                .addKeyValue("deprecated_endpoint", "/api/v1/skills/{id}/acl")
                .addKeyValue("op", op)
                .addKeyValue("skillId", skillId)
                .addKeyValue("replacement", "/api/v1/skills/{id}/grants (SkillGrantController)")
                .log("Deprecated endpoint hit — known dual-source-of-truth race; migrate to /grants");
    }

    public SkillAclController(SkillCommandService commandService,
            SkillAclQueryService queryService,
            CurrentUserProvider currentUserProvider) {
        this.commandService = commandService;
        this.queryService = queryService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * 新增 ACL entry — 觸發 SkillAclGranted 領域事件。
     *
     * @return 201 Created（重複 grant 由 aggregate 拋 IllegalStateException → 由全域 handler 處理）
     */
    @PostMapping
    @PreAuthorize("hasPermission(#id, 'Skill', 'write')")
    ResponseEntity<Void> grant(@PathVariable String id, @RequestBody AclEntryRequest req) {
        warnDeprecated("grant", id);
        commandService.grantAcl(new GrantAclCommand(
                id, req.type(), req.principal(), req.permission(),
                currentUserProvider.userId()));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * 移除 ACL entry — 觸發 SkillAclRevoked 領域事件。
     *
     * <p>使用 query params 而非 path：DELETE 對複合 key 的慣例避免 URL encode 衝突。
     *
     * @return 204 No Content
     */
    @DeleteMapping
    @PreAuthorize("hasPermission(#id, 'Skill', 'write')")
    ResponseEntity<Void> revoke(@PathVariable String id,
            @RequestParam String type,
            @RequestParam String principal,
            @RequestParam String permission) {
        warnDeprecated("revoke", id);
        commandService.revokeAcl(new RevokeAclCommand(
                id, type, principal, permission, currentUserProvider.userId()));
        return ResponseEntity.noContent().build();
    }

    /**
     * 列出指定 Skill 的所有 ACL entries（已拆解為結構化 tuple）。
     *
     * @return 200 OK + AclEntryResponse list（empty list 視為合法）
     */
    @GetMapping
    @PreAuthorize("hasPermission(#id, 'Skill', 'read')")
    ResponseEntity<List<AclEntryResponse>> list(@PathVariable String id) {
        warnDeprecated("list", id);
        return ResponseEntity.ok(queryService.listEntries(id));
    }

    /**
     * Grant ACL entry 的 request body — type:principal:permission 三段。
     */
    public record AclEntryRequest(String type, String principal, String permission) {}
}
