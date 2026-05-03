package io.github.samzhu.skillshub.community;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S125b — SkillSubscription HTTP endpoints。
 *
 * <p>對齊既驗 community controller pattern（RequestCommandController / CollectionCommandController）：
 * package-private class + ctor injection + 純 HTTP 翻譯（業務邏輯走 SkillSubscriptionService）。
 *
 * <h3>端點一覽</h3>
 * <ul>
 *   <li>{@code POST /api/v1/skills/{id}/subscribe} — 訂閱指定 skill；@PreAuthorize 守 read 權限</li>
 *   <li>{@code DELETE /api/v1/skills/{id}/subscribe} — 取消訂閱；不需 @PreAuthorize（unsubscribe noop 對未訂閱無傷）</li>
 *   <li>{@code GET /api/v1/me/subscriptions} — 列當前 user 訂閱的所有 skillId</li>
 * </ul>
 *
 * <p><b>@PreAuthorize 設計</b>：subscribe 加 read 權限守則 — 對齊 S122 既驗 read-side ACL chain
 * （anonymous 對 PUBLIC skill 仍可訂閱，因 *:read 自動加進 patterns；對 PRIVATE 則 401/403）。
 * unsubscribe 不加 — 因 service.unsubscribe 對未訂閱 skill 已是 noop，無 sensitive leak。
 */
@RestController
class SkillSubscriptionController {

    /**
     * S126: skill id UUID 格式驗證 走 {@link UUID} {@code @PathVariable} 內建 converter；
     * invalid format 走 Spring `MethodArgumentTypeMismatchException` → 400 早於 @PreAuthorize。
     * 對齊 SkillQueryController 既驗 pattern。
     */
    private final SkillSubscriptionService service;

    SkillSubscriptionController(SkillSubscriptionService service) {
        this.service = service;
    }

    /**
     * AC-S125b-1 — Subscribe 指定 skill。201 = 新訂閱；對既訂閱 skill 仍回 201（idempotent）。
     * @PreAuthorize 守 read 權限：anonymous + PUBLIC=200/201；anonymous + PRIVATE=401；
     * authenticated 無 grant + PRIVATE=403；granted/owner=201。
     */
    @PostMapping("/api/v1/skills/{id}/subscribe")
    @PreAuthorize("hasPermission(#id, 'Skill', 'read')")
    ResponseEntity<Void> subscribe(@PathVariable UUID id) {
        service.subscribe(id.toString());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * AC-S125b-2 — Unsubscribe 指定 skill。204 always — 對未訂閱 skill 安靜 noop。
     */
    @DeleteMapping("/api/v1/skills/{id}/subscribe")
    ResponseEntity<Void> unsubscribe(@PathVariable UUID id) {
        service.unsubscribe(id.toString());
        return ResponseEntity.noContent().build();
    }

    /**
     * AC-S125b-3 — 列當前 user 訂閱的所有 skill。回 skillId list（順序由 DB 決定）。
     * 不需 @PreAuthorize — 個人訂閱清單只暴露給自己（service 走 currentUserProvider.userId()）。
     */
    @GetMapping("/api/v1/me/subscriptions")
    List<String> mySubscriptions() {
        return service.findSubscriptionsOfCurrentUser();
    }
}
