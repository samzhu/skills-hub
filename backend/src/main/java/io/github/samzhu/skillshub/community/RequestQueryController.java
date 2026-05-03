package io.github.samzhu.skillshub.community;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S096g2 — Request query endpoints（取代 S096g1 stub RequestController）。
 *
 * <ul>
 *   <li>{@code GET /api/v1/requests?status=&sort=} — AC-3/AC-4 list with sort + filter</li>
 *   <li>{@code GET /api/v1/requests/{id}} — single detail</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/requests")
class RequestQueryController {

    private final RequestService service;

    RequestQueryController(RequestService service) {
        this.service = service;
    }

    @GetMapping
    List<RequestResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort) {
        return service.listRequests(status, sort).stream()
                .map(RequestResponse::from)
                .toList();
    }

    @GetMapping("/{requestId}")
    RequestResponse getOne(@PathVariable String requestId) {
        return RequestResponse.from(service.getRequest(requestId));
    }

    /** Public DTO — frontend SkillRequest type 對應；Persistable.isNew() / version 不外洩。 */
    record RequestResponse(
            String id,
            String title,
            String description,
            String requesterId,
            String status,
            String claimerId,
            String fulfilledSkillId,
            long voteCount,
            Instant createdAt,
            Instant updatedAt
    ) {
        static RequestResponse from(Request r) {
            return new RequestResponse(r.getId(), r.getTitle(), r.getDescription(), r.getRequesterId(),
                    r.getStatus(), r.getClaimerId(), r.getFulfilledSkillId(), r.getVoteCount(),
                    r.getCreatedAt(), r.getUpdatedAt());
        }
    }
}
