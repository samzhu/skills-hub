package io.github.samzhu.skillshub.security;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.github.samzhu.skillshub.shared.api.PlainTextDeserializer;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;

/**
 * Flag（舉報）REST Controller，提供 Skill 舉報的建立、查詢、status mutation 端點。
 *
 * <p>基礎路徑：{@code /api/v1/skills/{skillId}/flags}</p>
 */
@RestController
@RequestMapping("/api/v1/skills/{skillId}/flags")
public class FlagController {

	private final FlagService flagService;
	private final CurrentUserProvider users;

	public FlagController(FlagService flagService, CurrentUserProvider users) {
		this.flagService = flagService;
		this.users = users;
	}

	/**
	 * 建立新的舉報（Flag）。S098e3 AC-1：reporter 從 CurrentUserProvider 抽 sub。
	 *
	 * @param skillId 被舉報的 Skill 路徑參數
	 * @param request 包含舉報類型與說明的請求本體
	 * @return HTTP 201 Created，回應體含新建立的 Flag ID
	 */
	@PostMapping
	ResponseEntity<Map<String, String>> createFlag(
			@PathVariable String skillId,
			@RequestBody CreateFlagRequest request) {
		var reporter = users.current().userId();
		var flagId = flagService.createFlag(skillId, request.type(), request.description(), reporter);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", flagId));
	}

	/**
	 * 查詢指定 Skill 的所有 Flag，依建立時間降冪排序。
	 *
	 * <p>S098e3 AC-5：可帶 {@code ?status=OPEN|RESOLVED|DISMISSED} 過濾；無 query 維持
	 * 既有「全 status」行為相容。
	 *
	 * @param skillId 目標 Skill 的路徑參數
	 * @param status  選填 status filter
	 * @return Flag 清單
	 */
	@GetMapping
	List<FlagReadModel> getFlags(
			@PathVariable String skillId,
			@RequestParam(required = false) String status) {
		return flagService.getFlagsBySkillId(skillId, status);
	}

	/**
	 * S098e3 AC-6/7/8 — Flag status mutation（reviewer queue 使用）。
	 *
	 * @return HTTP 204 No Content；OPEN→RESOLVED/DISMISSED 之外皆 400 / 404
	 */
	@PatchMapping("/{flagId}")
	ResponseEntity<Void> updateStatus(
			@PathVariable String skillId,
			@PathVariable String flagId,
			@RequestBody UpdateFlagStatusRequest request) {
		var actor = users.current().userId();
		flagService.updateStatus(flagId, request.status(), actor);
		return ResponseEntity.noContent().build();
	}

	/**
	 * 建立 Flag 的請求本體結構。
	 *
	 * <p>S161b：{@code description} 走 {@link PlainTextDeserializer} silently strip HTML markup
	 * （防 stored XSS — 即使審核者 UI 改用 markdown renderer 也擋住）。
	 *
	 * @param type        舉報類型代碼
	 * @param description 舉報原因說明（會被 strip HTML tag）
	 */
	record CreateFlagRequest(
			String type,
			@JsonDeserialize(using = PlainTextDeserializer.class) String description) {}

	/** S098e3：PATCH body — 目標 status name（必為 RESOLVED 或 DISMISSED）。 */
	record UpdateFlagStatusRequest(String status) {}

}
