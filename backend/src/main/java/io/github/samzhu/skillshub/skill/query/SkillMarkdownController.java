package io.github.samzhu.skillshub.skill.query;

import java.time.Duration;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S133: Agent-friendly alias to retrieve a skill's SKILL.md directly.
 * Delegates to FileBrowserService — no duplication of zip-slip / size / SUSPENDED guards.
 */
@RestController
@RequestMapping("/api/v1")
class SkillMarkdownController {

    private final FileBrowserService fileBrowserService;

    SkillMarkdownController(FileBrowserService fileBrowserService) {
        this.fileBrowserService = fileBrowserService;
    }

    @GetMapping(value = "/skills/{id}/skill.md", produces = "text/markdown")
    @PreAuthorize("hasPermission(#id, 'Skill', 'read')")
    ResponseEntity<byte[]> getSkillMarkdown(@PathVariable UUID id) {
        var preview = fileBrowserService.readFile(id.toString(), "SKILL.md");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown"))
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(preview.content());
    }
}
