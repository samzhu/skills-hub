package io.github.samzhu.skillshub.skill.security;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S114a — REST endpoints for managing skill grants (RBAC ACL).
 *
 * <p>POST/DELETE require the actor to be authenticated; ownership is
 * verified inside {@link SkillGrantService}. GET first requires read permission
 * on the skill, then the service enforces owner-only grants metadata access.
 *
 * @see SkillGrantService
 */
@RestController
@RequestMapping("/api/v1/skills/{id}/grants")
public class SkillGrantController {

    private final SkillGrantService service;

    public SkillGrantController(SkillGrantService service) {
        this.service = service;
    }

    /**
     * Grant access to a skill.
     *
     * @param id  skill id
     * @param req grant request body
     * @return 202 with body {@code {"grantId": "..."}}
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, String>> grant(
            @PathVariable String id,
            @RequestBody SkillGrantService.GrantRequest req) {
        var grantId = service.grant(id, req);
        return ResponseEntity.accepted().body(Map.of("grantId", grantId));
    }

    /**
     * Revoke an existing grant.
     *
     * @param id      skill id
     * @param grantId grant row id
     * @return 202 empty body
     */
    @DeleteMapping("/{grantId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> revoke(
            @PathVariable String id,
            @PathVariable String grantId) {
        service.revoke(id, grantId);
        return ResponseEntity.accepted().build();
    }

    /**
     * List all grants for a skill — requires read permission and owner identity.
     *
     * @param id skill id
     * @return 200 list of grant entries
     */
    @GetMapping
    @PreAuthorize("hasPermission(#id, 'Skill', 'read')")
    public ResponseEntity<List<SkillGrant>> listGrants(@PathVariable String id) {
        return ResponseEntity.ok(service.listGrants(id));
    }
}
