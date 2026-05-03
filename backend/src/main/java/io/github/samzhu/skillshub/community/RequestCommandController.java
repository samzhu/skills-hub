package io.github.samzhu.skillshub.community;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;

/**
 * S096g2 — Request command endpoints（POST create / claim / fulfill；DELETE claim release / own request）。
 *
 * <p>Vote toggle endpoint 由 T02 RequestVoteService 加（{@code POST /{id}/vote}）。
 */
@RestController
@RequestMapping("/api/v1/requests")
class RequestCommandController {

    private final RequestService service;
    private final CurrentUserProvider users;

    RequestCommandController(RequestService service, CurrentUserProvider users) {
        this.service = service;
        this.users = users;
    }

    /** AC-1 — 建立新 request；reporter 從 CurrentUserProvider 抽 sub。 */
    @PostMapping
    ResponseEntity<Map<String, String>> create(@RequestBody CreateRequestBody body) {
        var requesterId = users.current().userId();
        var id = service.createRequest(body.title(), body.description(), requesterId);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    /** AC-7/AC-8 — 認領；OPEN→IN_PROGRESS。 */
    @PostMapping("/{requestId}/claim")
    ResponseEntity<Map<String, String>> claim(@PathVariable String requestId) {
        var userId = users.current().userId();
        var request = service.claim(requestId, userId);
        return ResponseEntity.ok(Map.of(
                "claimer", request.getClaimerId(),
                "status", request.getStatus()));
    }

    /** AC-9 — 釋放認領；IN_PROGRESS→OPEN（claimer-only）。 */
    @DeleteMapping("/{requestId}/claim")
    ResponseEntity<Void> release(@PathVariable String requestId) {
        var userId = users.current().userId();
        service.release(requestId, userId);
        return ResponseEntity.noContent().build();
    }

    /** AC-10/AC-11/AC-12 — 完成；IN_PROGRESS→FULFILLED + 綁 PUBLISHED skillId。 */
    @PostMapping("/{requestId}/fulfill")
    ResponseEntity<Map<String, String>> fulfill(
            @PathVariable String requestId,
            @RequestBody FulfillBody body) {
        var userId = users.current().userId();
        var request = service.fulfill(requestId, userId, body.skillId());
        return ResponseEntity.ok(Map.of(
                "status", request.getStatus(),
                "fulfilledSkillId", request.getFulfilledSkillId()));
    }

    /** AC-13 — 刪除 own OPEN request。 */
    @DeleteMapping("/{requestId}")
    ResponseEntity<Void> delete(@PathVariable String requestId) {
        var userId = users.current().userId();
        service.deleteRequest(requestId, userId);
        return ResponseEntity.noContent().build();
    }

    record CreateRequestBody(String title, String description) {}
    record FulfillBody(String skillId) {}
}
