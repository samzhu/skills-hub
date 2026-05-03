package io.github.samzhu.skillshub.security;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;

/**
 * S112 — {@code /api/v1/me/flags-summary} 端點，回傳當前 user 名下 PUBLISHED skill 的 OPEN flag 總數。
 *
 * <p>用於 MySkillsPage「待處理回報」MetricCard，避免前端 N+1 fetch。
 *
 * <p>放在 {@code security} 模組（而非 {@link io.github.samzhu.skillshub.shared.security.MeController}），
 * 因 endpoint 邏輯依賴 {@link FlagService} — 將呼叫保持 {@code security → shared::security} 單向，
 * 避免 {@code shared → security} 反向依賴破壞 Modulith 結構。
 */
@RestController
@RequestMapping("/api/v1/me")
class MeFlagsController {

	private final CurrentUserProvider users;
	private final FlagService flagService;

	MeFlagsController(CurrentUserProvider users, FlagService flagService) {
		this.users = users;
		this.flagService = flagService;
	}

	@GetMapping("/flags-summary")
	Map<String, Object> flagsSummary() {
		var userId = users.current().userId();
		long openCount = flagService.countOpenFlagsForAuthor(userId);
		return Map.of("openCount", openCount);
	}
}
