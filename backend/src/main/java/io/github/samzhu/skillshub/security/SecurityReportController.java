package io.github.samzhu.skillshub.security;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S142b §4.4 — GET /api/v1/skills/{id}/security-report
 * 4-quad security report (shell / paths / secrets / deps)。
 */
@RestController
@RequestMapping("/api/v1/skills/{skillId}/security-report")
class SecurityReportController {

    private final SecurityReportService service;

    SecurityReportController(SecurityReportService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasPermission(#skillId, 'Skill', 'read')")
    ResponseEntity<SecurityReportResponse> getReport(
            @PathVariable String skillId,
            @RequestParam(required = false) String versionId) {

        return ResponseEntity.ok(service.getReport(skillId, versionId));
    }
}
