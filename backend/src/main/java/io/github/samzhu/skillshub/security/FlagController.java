package io.github.samzhu.skillshub.security;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flag（舉報）REST Controller，提供 Skill 舉報的建立與查詢端點。
 *
 * <p>基礎路徑：{@code /api/v1/skills/{skillId}/flags}</p>
 */
@RestController
@RequestMapping("/api/v1/skills/{skillId}/flags")
public class FlagController {

	private final FlagService flagService;

	public FlagController(FlagService flagService) {
		this.flagService = flagService;
	}

	/**
	 * 建立新的舉報（Flag）。
	 *
	 * @param skillId 被舉報的 Skill 路徑參數
	 * @param request 包含舉報類型與說明的請求本體
	 * @return HTTP 201 Created，回應體含新建立的 Flag ID
	 */
	@PostMapping
	ResponseEntity<Map<String, String>> createFlag(
			@PathVariable String skillId,
			@RequestBody CreateFlagRequest request) {
		var flagId = flagService.createFlag(skillId, request.type(), request.description());
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", flagId));
	}

	/**
	 * 查詢指定 Skill 的所有 Flag，依建立時間降冪排序。
	 *
	 * @param skillId 目標 Skill 的路徑參數
	 * @return Flag 清單
	 */
	@GetMapping
	List<FlagReadModel> getFlags(@PathVariable String skillId) {
		return flagService.getFlagsBySkillId(skillId);
	}

	/**
	 * 建立 Flag 的請求本體結構。
	 *
	 * @param type        舉報類型代碼
	 * @param description 舉報原因說明
	 */
	record CreateFlagRequest(String type, String description) {}

}
