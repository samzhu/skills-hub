package io.github.samzhu.skillshub.security;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S098e3 AC-3 / AC-4 — Cross-skill Flag query endpoint，給 reviewer queue page 用。
 *
 * <p>{@code GET /api/v1/flags?status=OPEN} 列所有 OPEN flags（跨 skill）；
 * 無 status 回全部。獨立 controller 因 path 跳出 {@code /api/v1/skills/.../flags} 階層。
 *
 * <p>MVP 階段任何登入用戶皆可看（per spec §2.1）；未來 reviewer role gate 由
 * {@code @PreAuthorize} 補。
 */
@RestController
@RequestMapping("/api/v1/flags")
class FlagAdminQueryController {

    private final FlagService flagService;

    FlagAdminQueryController(FlagService flagService) {
        this.flagService = flagService;
    }

    @GetMapping
    List<FlagReadModel> list(@RequestParam(required = false) String status) {
        return flagService.listAllFlags(status);
    }
}
