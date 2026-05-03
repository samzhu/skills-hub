package io.github.samzhu.skillshub.skill.query;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillVersion;

/**
 * 技能讀取端 REST Controller — 處理所有唯讀查詢。
 *
 * <p>遵循 CQRS 原則，此 Controller 只接受 GET 請求（讀取端）。S024 ship 後
 * response type 改為 {@link Skill} / {@link SkillVersion} aggregate（取代原 ReadModel record；
 * per ADR-002 §2.4 — single skills row 同時為 write/read model）。{@code @Version} 欄位
 * 由 {@code @JsonIgnore} 隱藏，API contract shape 與 v1.5.0 保持一致。
 *
 * <h3>端點一覽</h3>
 * <ul>
 *   <li>{@code GET /api/v1/skills} — 關鍵字搜尋 + 分類篩選（分頁）</li>
 *   <li>{@code GET /api/v1/skills/{id}} — 單一技能詳情</li>
 *   <li>{@code GET /api/v1/skills/{id}/versions} — 版本歷史</li>
 *   <li>{@code GET /api/v1/skills/{id}/download} — 下載最新版本 zip</li>
 *   <li>{@code GET /api/v1/skills/{id}/versions/{ver}/download} — 下載指定版本 zip</li>
 *   <li>{@code GET /api/v1/categories} — 分類清單（含技能數量）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class SkillQueryController {

	private final SkillQueryService queryService;
	private final BundleInfoQueryService bundleInfoService;

	public SkillQueryController(SkillQueryService queryService, BundleInfoQueryService bundleInfoService) {
		this.queryService = queryService;
		this.bundleInfoService = bundleInfoService;
	}

	/**
	 * 依 ID 取得單一技能詳情。找不到時回傳 404；無權讀取時回傳 403。
	 *
	 * <p>S122: 加 row-level ACL 守則 — anonymous 對 PRIVATE skill 走 401（per Spring
	 * Security ExceptionTranslationFilter 在 AnonymousAuthenticationToken 場景轉為
	 * AuthenticationException）；authenticated user 無 grant 走 403。對齊 S121 list endpoint
	 * ACL filter，補完 read-side 單筆 path 的 LAB-blocker gap。
	 */
	@GetMapping("/skills/{id}")
	@PreAuthorize("hasPermission(#id, 'Skill', 'read')")
	Skill getById(@PathVariable String id) {
		return queryService.findById(id);
	}

	/**
	 * S098a3-2 — Bundle metadata for PublishValidatePage upload-strip。回 canonical
	 * filename + fileSize + fileCount + uploadedAt；404 = skill 不存在 OR 無 published version；
	 * 無權讀取 → 403。
	 *
	 * <p>S122: 加 row-level ACL 守則 — bundle metadata 含 filename / fileSize / fileCount
	 * 對 PRIVATE skill 等同洩漏 skill 名稱與檔案存在性；走 read-permission gate 對齊 getById。
	 */
	@GetMapping("/skills/{id}/bundle-info")
	@PreAuthorize("hasPermission(#id, 'Skill', 'read')")
	BundleInfoQueryService.BundleInfoResponse bundleInfo(@PathVariable String id) {
		return bundleInfoService.get(id);
	}

	/**
	 * S096c — 依 author/name canonical route 取得 Skill (per ADR-003).
	 * `/skills/:id` 為永久 alias，`/skills/:author/:name` 為 canonical；兩者 resolve 同 aggregate。
	 * Path 區分：UUID 為 single-segment（既有 endpoint），author/name 為 two-segment（本 endpoint）。
	 */
	@GetMapping("/skills/{author}/{name}")
	Skill getByAuthorAndName(@PathVariable String author, @PathVariable String name) {
		return queryService.findByAuthorAndName(author, name);
	}

	/**
	 * 搜尋技能 — 支援 keyword（name/description 模糊匹配）、category（精確匹配）、author（精確匹配）。
	 * 三個參數皆為可選，都不帶則回傳全部。
	 *
	 * <p>S094a: 加 {@code author} 參數對齊 P6 SBE「作者查看自己的數據」。當 {@code author} 帶值時，
	 * 跳過 {@code status='PUBLISHED'} 過濾（讓作者看到自己的 DRAFT / SUSPENDED）；不帶則保留 S031
	 * 公開查詢只露 PUBLISHED 行為。LAB 模式無 auth gate，任何 user 可查任何 author 的全狀態 — 此屬
	 * 已知 MVP 限制（Feature First），future spec 加入 auth 後 author filter 會 gated by current user.
	 */
	@GetMapping("/skills")
	Page<Skill> search(
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String category,
			@RequestParam(required = false) String author,
			@PageableDefault(size = 20) Pageable pageable) {
		return queryService.search(keyword, category, author, pageable);
	}

	/**
	 * 取得某技能的版本歷史，按發佈時間降序排列；無權讀取時回 403。
	 *
	 * <p>S122: 加 row-level ACL 守則 — 版本歷史含 publishedAt / fileCount 等
	 * 對 PRIVATE skill 等同洩漏發佈節奏；走 read-permission gate 對齊 getById。
	 */
	@GetMapping("/skills/{id}/versions")
	@PreAuthorize("hasPermission(#id, 'Skill', 'read')")
	List<SkillVersion> getVersions(@PathVariable String id) {
		return queryService.findVersionsBySkillId(id);
	}

	/**
	 * 下載某技能的最新版本 zip。回傳 {@code application/octet-stream}。
	 * 同時透過 aggregate 充血方法 {@code Skill.recordDownload} 觸發 {@code SkillDownloaded} 事件供 analytics 消費。
	 */
	@GetMapping("/skills/{id}/download")
	ResponseEntity<byte[]> downloadLatest(@PathVariable String id) {
		// S061: filename 含 skill name + version 區分 — name 已限 [a-z0-9-]{1,64}（S041）filename 安全
		var skill = queryService.findById(id);
		var bytes = queryService.downloadLatest(id);
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.header(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=" + skill.getName() + "-" + skill.getLatestVersion() + ".zip")
				.body(bytes);
	}

	/** 下載某技能的指定版本 zip。 */
	@GetMapping("/skills/{id}/versions/{version}/download")
	ResponseEntity<byte[]> downloadVersion(@PathVariable String id, @PathVariable String version) {
		// S061: filename 含 skill name + 指定 version
		var skill = queryService.findById(id);
		var bytes = queryService.downloadVersion(id, version);
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.header(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=" + skill.getName() + "-" + version + ".zip")
				.body(bytes);
	}

	/** 取得所有分類及其技能數量，按數量降序排列。 */
	@GetMapping("/categories")
	List<CategoryCount> categories() {
		return queryService.getCategoryCounts();
	}

}
