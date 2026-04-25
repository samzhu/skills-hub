package io.github.samzhu.skillshub.skill.command;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.github.samzhu.skillshub.shared.api.ErrorResponse;

/**
 * 技能寫入端 REST Controller — 處理所有修改技能狀態的操作。
 *
 * <p>遵循 CQRS 原則，此 Controller 只接受 POST/PUT 請求（寫入端），
 * 所有 GET 請求（讀取端）由 {@link io.github.samzhu.skillshub.skill.query.SkillQueryController} 處理。</p>
 *
 * <h3>端點一覽</h3>
 * <ul>
 *   <li>{@code POST /api/v1/skills} — 以 JSON 建立技能（測試/seeding 用）</li>
 *   <li>{@code POST /api/v1/skills/upload} — 上傳 zip 建立技能 + 首版</li>
 *   <li>{@code PUT /api/v1/skills/{id}/versions} — 為既有技能新增版本</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/skills")
public class SkillCommandController {

	private final SkillCommandService commandService;

	public SkillCommandController(SkillCommandService commandService) {
		this.commandService = commandService;
	}

	/**
	 * 以 JSON body 建立新技能（不含 zip 上傳）。
	 * 主要用於測試和資料 seeding，正式發佈請使用 {@link #uploadSkill}。
	 *
	 * @return 201 Created + {"id": "uuid"}
	 */
	@PostMapping
	ResponseEntity<Map<String, String>> createSkill(@RequestBody CreateSkillCommand command) {
		var id = commandService.createSkill(command);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	/**
	 * 上傳 skill zip 建立新技能並發佈首版。
	 *
	 * <p>流程：接收 multipart → 解壓 zip → 驗證 SKILL.md → 上傳至 GCS
	 * → 產生 SkillCreated + SkillVersionPublished 領域事件。</p>
	 *
	 * @param file     技能套件 zip 檔（最大 10MB）
	 * @param version  語意化版本號（如 "1.0.0"）
	 * @param author   作者名稱
	 * @param category 分類（如 DevOps、Testing）
	 * @return 201 Created + {"id": "uuid"}
	 */
	@PostMapping("/upload")
	ResponseEntity<Map<String, String>> uploadSkill(
			@RequestParam("file") MultipartFile file,
			@RequestParam("version") String version,
			@RequestParam("author") String author,
			@RequestParam("category") String category) throws IOException {
		var id = commandService.uploadSkill(file.getBytes(), version, author, category);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	/**
	 * 為既有技能新增版本。版本號不可重複，否則回傳 409 Conflict。
	 *
	 * @param id      技能 ID
	 * @param file    新版本的 skill zip 檔
	 * @param version 新版本號（如 "1.1.0"）
	 * @return 200 OK
	 */
	@PutMapping("/{id}/versions")
	ResponseEntity<Void> addVersion(
			@PathVariable String id,
			@RequestParam("file") MultipartFile file,
			@RequestParam("version") String version) throws IOException {
		commandService.addVersion(id, file.getBytes(), version);
		return ResponseEntity.ok().build();
	}

	/** 攔截版本重複例外，轉換為 HTTP 409 Conflict。 */
	@ExceptionHandler(VersionExistsException.class)
	ResponseEntity<ErrorResponse> handleVersionExists(VersionExistsException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse("VERSION_EXISTS", ex.getMessage(), Instant.now()));
	}

}
