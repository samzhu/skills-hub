package io.github.samzhu.skillshub.skill.command;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;

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
	private final CurrentUserProvider currentUserProvider;

	public SkillCommandController(SkillCommandService commandService,
			CurrentUserProvider currentUserProvider) {
		this.commandService = commandService;
		this.currentUserProvider = currentUserProvider;
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
			@RequestParam("category") String category,
			@RequestParam(name = "visibility", required = false, defaultValue = "PUBLIC")
					io.github.samzhu.skillshub.skill.domain.Visibility visibility) throws IOException {
		// S116: visibility 缺省 PUBLIC 對齊 v3.x 既有行為；前端 PublishPage 顯式透傳
		// PRIVATE 走私人 skill 路徑（acl_entries 不含 *:read，由既有 GIN ?| filter
		// 自動 fail-closed against anonymous / non-grant user）。
		var id = commandService.uploadSkill(file.getBytes(), version, author, category, visibility);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	/**
	 * 為既有技能新增版本。版本號不可重複，否則回傳 409 Conflict。
	 *
	 * <p>S016：套用 row-level ACL — 呼叫者必須對該 skill 具 {@code write} 權限
	 * （acl_entries 含 {@code user:<sub>:write} 或 {@code role:<role>:write} 或
	 * {@code group:<group>:write} 任一 pattern）。授權檢查由
	 * {@link io.github.samzhu.skillshub.skill.security.SkillPermissionStrategy}
	 * 經 {@code DelegatingPermissionEvaluator} 路由執行。
	 *
	 * @param id      技能 ID
	 * @param file    新版本的 skill zip 檔
	 * @param version 新版本號（如 "1.1.0"）
	 * @return 200 OK
	 */
	@PutMapping("/{id}/versions")
	@PreAuthorize("hasPermission(#id, 'Skill', 'write')")
	ResponseEntity<Void> addVersion(
			@PathVariable String id,
			@RequestParam("file") MultipartFile file,
			@RequestParam("version") String version) throws IOException {
		commandService.addVersion(id, file.getBytes(), version);
		return ResponseEntity.ok().build();
	}

	/**
	 * S018：停用 skill — 將 PUBLISHED skill 轉至 SUSPENDED 狀態。
	 *
	 * <p>{@code @PreAuthorize("hasPermission(#id, 'Skill', 'suspend')")} 守門：呼叫者
	 * 須對該 skill 具 {@code suspend} 權限（acl_entries 含 {@code role:admin:suspend} 或
	 * {@code user:<sub>:suspend} pattern）。{@code suspendedBy} 從
	 * {@link CurrentUserProvider#userId()} 取得，不採用 request body 任何欄位以防 spoof。
	 *
	 * @return 200 OK；非 PUBLISHED skill 會由 aggregate 拋 IllegalStateException → 由
	 *         全域 handler 處理（目前 Spring default → 500，後續可加 @ExceptionHandler 轉 409）
	 */
	@PostMapping("/{id}/suspend")
	@PreAuthorize("hasPermission(#id, 'Skill', 'suspend')")
	ResponseEntity<Void> suspend(@PathVariable String id, @RequestBody SuspendRequest req) {
		commandService.suspend(new SuspendCommand(id, req.reason(), currentUserProvider.userId()));
		return ResponseEntity.ok().build();
	}

	/**
	 * S018：重啟 skill — 將 SUSPENDED skill 轉回 PUBLISHED 狀態。
	 *
	 * <p>{@code @PreAuthorize("hasPermission(#id, 'Skill', 'reactivate')")} 守門。
	 *
	 * @return 200 OK；非 SUSPENDED skill 由 aggregate state machine 拋例外
	 */
	@PostMapping("/{id}/reactivate")
	@PreAuthorize("hasPermission(#id, 'Skill', 'reactivate')")
	ResponseEntity<Void> reactivate(@PathVariable String id, @RequestBody ReactivateRequest req) {
		commandService.reactivate(new ReactivateCommand(id, req.reason()));
		return ResponseEntity.ok().build();
	}

	/** 攔截版本重複例外，轉換為 HTTP 409 Conflict。 */
	@ExceptionHandler(VersionExistsException.class)
	ResponseEntity<ErrorResponse> handleVersionExists(VersionExistsException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse("VERSION_EXISTS", ex.getMessage(), Instant.now()));
	}

	/** S018：Suspend endpoint request body — single field reason。 */
	public record SuspendRequest(String reason) {}

	/** S018：Reactivate endpoint request body — single field reason。 */
	public record ReactivateRequest(String reason) {}

}
