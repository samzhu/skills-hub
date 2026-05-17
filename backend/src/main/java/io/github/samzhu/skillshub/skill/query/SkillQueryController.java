package io.github.samzhu.skillshub.skill.query;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonView;

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

	/**
	 * S126: Skill id 格式驗證 走 {@link UUID} {@code @PathVariable} 內建 converter — invalid format
	 * （{@code null}、{@code undefined}、非 UUID 字串）走 Spring `MethodArgumentTypeMismatchException`
	 * → 400 VALIDATION_ERROR（per GlobalExceptionHandler S126 handler），早於 {@code @PreAuthorize}
	 * 走 SkillPermissionStrategy SQL 評估的 fail-secure 路徑（會誤回 401/403 致 LAB UX 混淆）。
	 *
	 * <p>{@code @Validated + @Pattern} 不可行 — Spring Security {@code @PreAuthorize} interceptor
	 * 在 method validation interceptor **之前** fire，無法早於 fail-secure。`UUID` 內建 converter
	 * 在 argument resolution 階段就 throw，是唯一乾淨的 pre-PreAuthorize fast-fail 路徑。
	 *
	 * <p>合法 UUID 但不存在的 id 仍走 @PreAuthorize → 401/403（security-first，per Spring Security
	 * 預設「hide existence」設計，避免 anonymous 探測 PRIVATE skill 存在性）。
	 */
	private final SkillQueryService queryService;
	private final BundleInfoQueryService bundleInfoService;
	private final SkillDiffQueryService diffQueryService;
	private final SkillFileDiffService fileDiffService;
	/** S154 — author path segment 解析鏈 (handle / user_id / email / OAuth sub backward-compat)。 */
	private final io.github.samzhu.skillshub.shared.security.UserResolver userResolver;

	public SkillQueryController(SkillQueryService queryService, BundleInfoQueryService bundleInfoService,
			SkillDiffQueryService diffQueryService, SkillFileDiffService fileDiffService,
			io.github.samzhu.skillshub.shared.security.UserResolver userResolver) {
		this.queryService = queryService;
		this.bundleInfoService = bundleInfoService;
		this.diffQueryService = diffQueryService;
		this.fileDiffService = fileDiffService;
		this.userResolver = userResolver;
	}

	/**
	 * 依 ID 取得單一技能詳情。找不到時回傳 404；存在但無權讀取時回傳 401/403。
	 *
	 * <p>S174: 先 resolve 再用 {@code @PostAuthorize} 檢查 returnObject.id；missing UUID
	 * 由 {@link SkillQueryService#findById(String)} 進 {@code NoSuchElementException → 404}，
	 * private existing skill 仍由 read permission gate 擋住。
	 */
	@GetMapping("/skills/{id}")
	@PostAuthorize("hasPermission(returnObject.id, 'Skill', 'read')")
	Skill getById(@PathVariable UUID id) {
		return queryService.findById(id.toString());
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
	BundleInfoQueryService.BundleInfoResponse bundleInfo(@PathVariable UUID id) {
		return bundleInfoService.get(id.toString());
	}

	/**
	 * S096c → S176 — 依 author/name legacy alias 取得 Skill。
	 * `/skills/:id` 是 canonical identity；重名後本 route 回 deterministic latest row。
	 * Path 區分：UUID 為 single-segment（既有 endpoint），author/name 為 two-segment（本 endpoint）。
	 *
	 * <p>S124: 走 {@code @PostAuthorize} 對 returnObject.id 套 ACL — 因 {@code @PreAuthorize} 對
	 * (author, name) 兩 path variable 沒對應的 hasPermission 評估器（既有 SkillPermissionStrategy
	 * 走 skillId String）。Resolve-then-check pattern：先 findByAuthorAndName 拿 Skill，再對 id
	 * 走 hasPermission；不存在拋 NoSuchElementException → 404 in handler；無權拋 AccessDenied →
	 * 401/403。對齊 S121/S122/S123 既驗 ACL chain；補完 read-side 最後 1 個 endpoint 的 ACL gap。
	 *
	 * <p>Trade-off：相較 @PreAuthorize 多 1 次 DB read（resolve），但 hasPermission 評估器本身
	 * 也走 EXISTS sub-query (per SkillPermissionStrategy SQL) — 與 PreAuthorize 路徑成本對齊。
	 * 安全性：findByAuthorAndName 結果不會 leak 到 client（PostAuthorize 拒絕後 ExceptionTranslationFilter
	 * 翻 401/403 + 空 body）。
	 */
	@GetMapping("/skills/{author}/{name}")
	@PostAuthorize("hasPermission(returnObject.id, 'Skill', 'read')")
	Skill getByAuthorAndName(@PathVariable String author, @PathVariable String name) {
		// S154 AC-7 — author path 段 4-path resolve：handle → user_id → email → OAuth sub backward-compat
		// （UserResolver 內建 4 條 fallback 鏈）。Caller 傳「alice」/「u_a3f9c1」/「111161306...」皆可命中。
		// resolve miss → 用原 input 走 SkillRepository.findByAuthorAndName（既有 case-insensitive LIKE）
		// — 對應未 onboard users 表的 LAB / 舊資料路徑保留。
		var resolvedAuthor = userResolver.resolveByEmailHandleOrId(author).orElse(author);
		return queryService.findByAuthorAndName(resolvedAuthor, name);
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
	/** S158: list endpoint 走 List view — 不暴露 aclEntries / ownerId（internal authorization 不對外）。 */
	@GetMapping("/skills")
	@JsonView(Skill.Views.List.class)
	Page<Skill> search(
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String category,
			@RequestParam(required = false) String author,
			@PageableDefault(size = 20) Pageable pageable) {
		// S159d: page<0 / size<=0 / size>100 已由 PageableValidationInterceptor 在 preHandle
		// 階段 fail-fast（resolver 會 silent clamp，故 controller 端檢查抓不到，須前移）
		// S159b: caller `?category=Testing` 起手 lowercase — DB row 已 V20 lowercase 化，
		// 若不接住大寫 query 會 0 hit（false negative）。null 維持 null（不過濾）。
		var normalizedCategory = category == null ? null : category.trim().toLowerCase();
		return queryService.search(keyword, normalizedCategory, author, pageable);
	}

	/**
	 * 取得某技能的版本歷史，按發佈時間降序排列；無權讀取時回 403。
	 *
	 * <p>S122: 加 row-level ACL 守則 — 版本歷史含 publishedAt / fileCount 等
	 * 對 PRIVATE skill 等同洩漏發佈節奏；走 read-permission gate 對齊 getById。
	 */
	@GetMapping("/skills/{id}/versions")
	@PreAuthorize("hasPermission(#id, 'Skill', 'read')")
	List<SkillVersion> getVersions(@PathVariable UUID id) {
		return queryService.findVersionsBySkillId(id.toString());
	}

	/**
	 * 下載某技能的最新版本 zip。回傳 {@code application/octet-stream}；無權讀取時回 403。
	 * 同時透過 aggregate 充血方法 {@code Skill.recordDownload} 觸發 {@code SkillDownloaded} 事件供 analytics 消費。
	 *
	 * <p>S123: 加 row-level ACL 守則 — 對應 S121 list path + S122 single GET path 三 endpoint
	 * ACL chain 收尾。Anonymous 對 PRIVATE skill 走 401（per ExceptionTranslationFilter）；
	 * authenticated 但無 grant 走 403。download_count 累計 invariant 不變（仍走 atomic SQL）。
	 */
	@GetMapping("/skills/{id}/download")
	@PreAuthorize("hasPermission(#id, 'Skill', 'read')")
	ResponseEntity<byte[]> downloadLatest(@PathVariable UUID id) {
		// S061: filename 含 skill name + version 區分 — name 已限 [a-z0-9-]{1,64}（S041）filename 安全
		var idStr = id.toString();
		var skill = queryService.findById(idStr);
		var bytes = queryService.downloadLatest(idStr);
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.header(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=" + skill.getName() + "-" + skill.getLatestVersion() + ".zip")
				.body(bytes);
	}

	/**
	 * 下載某技能的指定版本 zip；無權讀取時回 403。
	 *
	 * <p>S123: 同 {@link #downloadLatest} ACL 守則 — 歷史版本對非 grantee 一視同仁不可下載。
	 */
	@GetMapping("/skills/{id}/versions/{version}/download")
	@PreAuthorize("hasPermission(#id, 'Skill', 'read')")
	ResponseEntity<byte[]> downloadVersion(@PathVariable UUID id, @PathVariable String version) {
		// S061: filename 含 skill name + 指定 version
		var idStr = id.toString();
		var skill = queryService.findById(idStr);
		var bytes = queryService.downloadVersion(idStr, version);
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

	/** S098c2 — 計算兩版本之間的欄位級差異；from/to 不存在時回 400。 */
	@GetMapping("/skills/{id}/diff")
	@PreAuthorize("hasPermission(#id, 'Skill', 'read')")
	VersionDiffResponse diff(
			@PathVariable UUID id,
			@RequestParam String from,
			@RequestParam String to) {
		return diffQueryService.diff(id.toString(), from, to);
	}

	/** S098c3 — 兩版本 zip 包的檔案列表差異；from/to 不存在時回 400。 */
	@GetMapping("/skills/{id}/file-list-diff")
	@PreAuthorize("hasPermission(#id, 'Skill', 'read')")
	FileListDiffResponse fileListDiff(
			@PathVariable UUID id,
			@RequestParam String from,
			@RequestParam String to) {
		return fileDiffService.listDiff(id.toString(), from, to);
	}

}
