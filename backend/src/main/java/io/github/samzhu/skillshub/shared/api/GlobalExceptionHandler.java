package io.github.samzhu.skillshub.shared.api;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.zip.ZipException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全域例外處理器。
 *
 * <p>攔截所有 Controller 層拋出的例外，將其統一轉換為 {@link ErrorResponse} 格式，
 * 並記錄 WARN 等級日誌以利問題追蹤。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	/**
	 * 處理輸入驗證失敗（{@link IllegalArgumentException}）。
	 *
	 * <p>通常由業務邏輯驗證不通過時拋出，回傳 HTTP 400 Bad Request。
	 *
	 * @param ex 驗證例外
	 * @return HTTP 400 及錯誤代碼 {@code VALIDATION_ERROR} 的回應
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<ErrorResponse> handleValidationError(IllegalArgumentException ex) {
		log.atWarn()
				.addKeyValue("errorCode", "VALIDATION_ERROR")
				.addKeyValue("message", ex.getMessage())
				.log("Validation error occurred");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("VALIDATION_ERROR", ex.getMessage(), Instant.now()));
	}

	/**
	 * 處理資源不存在（{@link NoSuchElementException}）。
	 *
	 * <p>通常由查詢服務找不到對應資源時拋出，回傳 HTTP 404 Not Found。
	 *
	 * @param ex 資源不存在例外
	 * @return HTTP 404 及錯誤代碼 {@code NOT_FOUND} 的回應
	 */
	@ExceptionHandler(NoSuchElementException.class)
	ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException ex) {
		log.atWarn()
				.addKeyValue("errorCode", "NOT_FOUND")
				.addKeyValue("message", ex.getMessage())
				.log("Resource not found");
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse("NOT_FOUND", ex.getMessage(), Instant.now()));
	}

	/**
	 * S029：處理 SUSPENDED skill 下載被拒（{@link SkillSuspendedException}）。
	 *
	 * <p>回傳 HTTP 403 Forbidden + {@code SKILL_SUSPENDED} error code，與 generic 403
	 * （auth fail）做語意區分。403 而非 410：SUSPENDED 可被 admin reactivate（非永久），
	 * 與 410 Gone「permanent removal」語意不符。
	 */
	@ExceptionHandler(SkillSuspendedException.class)
	ResponseEntity<ErrorResponse> handleSuspended(SkillSuspendedException ex) {
		log.atWarn()
				.addKeyValue("errorCode", "SKILL_SUSPENDED")
				.addKeyValue("skillId", ex.getSkillId())
				.log("Download blocked: skill suspended");
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(new ErrorResponse("SKILL_SUSPENDED", ex.getMessage(), Instant.now()));
	}

	/**
	 * S098e2 AC-7 — Review 操作被拒（requester 非原作者）。403 + error code
	 * 直接帶 {@code ex.getMessage()}（caller 傳入如 {@code "not_review_author"}）便於 FE i18n 對應。
	 */
	@ExceptionHandler(ReviewForbiddenException.class)
	ResponseEntity<ErrorResponse> handleReviewForbidden(ReviewForbiddenException ex) {
		log.atWarn()
				.addKeyValue("errorCode", ex.getMessage())
				.log("Review operation forbidden");
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(new ErrorResponse(ex.getMessage(), ex.getMessage(), Instant.now()));
	}

	/**
	 * S098e3 AC-7 — Flag status transition 違規（如 RESOLVED → OPEN）或 unknown status。
	 * 400 + error code {@code "invalid_status_transition"} 對齊 spec error code naming。
	 */
	@ExceptionHandler(InvalidStatusTransitionException.class)
	ResponseEntity<ErrorResponse> handleInvalidStatusTransition(InvalidStatusTransitionException ex) {
		log.atWarn()
				.addKeyValue("errorCode", "invalid_status_transition")
				.addKeyValue("message", ex.getMessage())
				.log("Invalid flag status transition");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("invalid_status_transition", ex.getMessage(), Instant.now()));
	}

	/**
	 * S098e3 AC-8 — Flag id 不存在。404 + error code {@code "flag_not_found"}。
	 */
	@ExceptionHandler(FlagNotFoundException.class)
	ResponseEntity<ErrorResponse> handleFlagNotFound(FlagNotFoundException ex) {
		log.atWarn()
				.addKeyValue("errorCode", "flag_not_found")
				.log("Flag not found");
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse("flag_not_found", ex.getMessage(), Instant.now()));
	}

	/** S096g2 — Request id 不存在 → 404 request_not_found。 */
	@ExceptionHandler(RequestNotFoundException.class)
	ResponseEntity<ErrorResponse> handleRequestNotFound(RequestNotFoundException ex) {
		log.atWarn().addKeyValue("errorCode", "request_not_found").log("Request not found");
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse("request_not_found", ex.getMessage(), Instant.now()));
	}

	/** S096g2 AC-9/AC-11 — release/fulfill requester 非 claimer → 403 not_request_claimer。 */
	@ExceptionHandler(NotRequestClaimerException.class)
	ResponseEntity<ErrorResponse> handleNotClaimer(NotRequestClaimerException ex) {
		log.atWarn().addKeyValue("errorCode", "not_request_claimer").log("Request operation forbidden");
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(new ErrorResponse("not_request_claimer", ex.getMessage(), Instant.now()));
	}

	/** S096f2-T02 AC-8 — Collection id 不存在 → 404 collection_not_found。 */
	@ExceptionHandler(CollectionNotFoundException.class)
	ResponseEntity<ErrorResponse> handleCollectionNotFound(CollectionNotFoundException ex) {
		log.atWarn().addKeyValue("errorCode", "collection_not_found").log("Collection not found");
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse("collection_not_found", ex.getMessage(), Instant.now()));
	}

	/**
	 * S096f2-T02 AC-3 / S096g2 AC-12 caller migration — 一個或多個 skillId 非 PUBLISHED
	 * 或不存在 → 400。invalidSkillIds 已 join 進 message 字串便於 frontend 解析（structured
	 * field 留 polish；ErrorResponse shape 不擴）。
	 */
	@ExceptionHandler(SkillNotPublishableException.class)
	ResponseEntity<ErrorResponse> handleSkillNotPublishable(SkillNotPublishableException ex) {
		log.atWarn().addKeyValue("errorCode", "skill_not_publishable")
				.addKeyValue("invalidSkillIds", ex.getInvalidSkillIds())
				.log("Skill not publishable");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("skill_not_publishable", ex.getMessage(), Instant.now()));
	}

	/** S096h2-T03 AC-6/AC-8 — Notification id 不存在 → 404 notification_not_found。 */
	@ExceptionHandler(NotificationNotFoundException.class)
	ResponseEntity<ErrorResponse> handleNotificationNotFound(NotificationNotFoundException ex) {
		log.atWarn().addKeyValue("errorCode", "notification_not_found").log("Notification not found");
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse("notification_not_found", ex.getMessage(), Instant.now()));
	}

	/** S096h2-T03 AC-6/AC-8 — Notification mark-read/delete actor 非 recipient → 403。 */
	@ExceptionHandler(NotNotificationRecipientException.class)
	ResponseEntity<ErrorResponse> handleNotNotificationRecipient(NotNotificationRecipientException ex) {
		log.atWarn().addKeyValue("errorCode", "not_notification_recipient").log("Notification operation forbidden");
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(new ErrorResponse("not_notification_recipient", ex.getMessage(), Instant.now()));
	}

	/** S114a — actor 非 skill owner → 403 not_skill_owner。 */
	@ExceptionHandler(NotSkillOwnerException.class)
	ResponseEntity<ErrorResponse> handleNotSkillOwner(NotSkillOwnerException ex) {
		log.atWarn().addKeyValue("errorCode", "not_skill_owner").log("Grant/revoke denied: not skill owner");
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(new ErrorResponse("not_skill_owner", ex.getMessage(), Instant.now()));
	}

	/** S114a — skill 已有 OWNER grant → 409 owner_already_exists。 */
	@ExceptionHandler(OwnerAlreadyExistsException.class)
	ResponseEntity<ErrorResponse> handleOwnerAlreadyExists(OwnerAlreadyExistsException ex) {
		log.atWarn().addKeyValue("errorCode", "owner_already_exists").log("Grant denied: OWNER already exists");
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse("owner_already_exists", ex.getMessage(), Instant.now()));
	}

	/** S114a — grant row 不存在 → 404 grant_not_found。 */
	@ExceptionHandler(GrantNotFoundException.class)
	ResponseEntity<ErrorResponse> handleGrantNotFound(GrantNotFoundException ex) {
		log.atWarn().addKeyValue("errorCode", "grant_not_found").log("Grant not found");
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse("grant_not_found", ex.getMessage(), Instant.now()));
	}

	/** S114a — owner 試圖撤銷自己的 OWNER grant → 403 cannot_revoke_own_owner。 */
	@ExceptionHandler(CannotRevokeOwnOwnerException.class)
	ResponseEntity<ErrorResponse> handleCannotRevokeOwnOwner(CannotRevokeOwnOwnerException ex) {
		log.atWarn().addKeyValue("errorCode", "cannot_revoke_own_owner").log("Revoke denied: cannot revoke own owner grant");
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(new ErrorResponse("cannot_revoke_own_owner", ex.getMessage(), Instant.now()));
	}

	/** S135a AC-S135a-4 — 品質評分尚未計算 → 404 QUALITY_NOT_EVALUATED。 */
	@ExceptionHandler(QualityNotEvaluatedException.class)
	ResponseEntity<ErrorResponse> handleNotEvaluated(QualityNotEvaluatedException ex) {
		log.atWarn().addKeyValue("errorCode", "QUALITY_NOT_EVALUATED").log("Quality score not yet available");
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse("QUALITY_NOT_EVALUATED", ex.getMessage(), Instant.now()));
	}

	/** S098a3-2 AC-3 — skill 存在但無 published version → 404 bundle_not_published（區分於 skill_not_found）。 */
	@ExceptionHandler(BundleNotPublishedException.class)
	ResponseEntity<ErrorResponse> handleBundleNotPublished(BundleNotPublishedException ex) {
		log.atWarn().addKeyValue("errorCode", "bundle_not_published").log("Bundle metadata unavailable: skill has no published version");
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse("bundle_not_published", ex.getMessage(), Instant.now()));
	}

	/**
	 * S115 AC-1 — JWT 通過簽名驗證但 sub claim 缺失 / blank → 401。
	 *
	 * <p>RFC 6750 Bearer error="invalid_token"；取代既有 {@code jwt.getName()} 對 sub=null 的
	 * NPE 路徑（500 → 改 401 + 結構化錯誤 body）。WWW-Authenticate header 對齊 Spring Security
	 * BearerTokenAuthenticationEntryPoint default。
	 */
	@ExceptionHandler(MissingJwtSubException.class)
	ResponseEntity<ErrorResponse> handleMissingJwtSub(MissingJwtSubException ex) {
		log.atError().addKeyValue("errorCode", "invalid_token")
				.log("JWT missing required sub claim; request rejected");
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.header("WWW-Authenticate",
						"Bearer error=\"invalid_token\", error_description=\"missing sub claim\"")
				.body(new ErrorResponse("invalid_token", ex.getMessage(), Instant.now()));
	}

	/**
	 * S037：處理 multipart 超 size 限制（{@link MaxUploadSizeExceededException}）。
	 *
	 * <p>{@code @ExceptionHandler} most-specific-first 規則 — 此 handler 必早於
	 * {@link #handleMultipartParseError}（其 super class）與 {@link #handleStateConflict}
	 * （Tomcat 會在 multipart 解析失敗時包成 IllegalStateException）匹配。
	 *
	 * <p>HTTP 413 PAYLOAD_TOO_LARGE per RFC 9110 §15.5.14；message 含實際 byte 限制讓 user 知道上限。
	 */
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<ErrorResponse> handlePayloadTooLarge(MaxUploadSizeExceededException ex) {
		log.atWarn()
				.addKeyValue("errorCode", "PAYLOAD_TOO_LARGE")
				.addKeyValue("maxSize", ex.getMaxUploadSize())
				.log("Upload size exceeded");
		var maxBytes = ex.getMaxUploadSize();
		// 後端 application.yaml 設 10MB；MaxUploadSizeExceededException.getMaxUploadSize() 回 -1 表 framework 不知，
		// fallback 至硬編碼 10MB（唯一 user-visible 數字一定要對齊 yaml）。
		var maxMb = maxBytes > 0 ? maxBytes / 1_048_576 : 10;
		var msg = "Upload size exceeds the " + maxMb + " MB limit";
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
				.body(new ErrorResponse("PAYLOAD_TOO_LARGE", msg, Instant.now()));
	}

	/**
	 * S080：處理 missing required @RequestParam / @RequestPart（{@link MissingServletRequestParameterException}
	 * / {@link MissingServletRequestPartException}）。
	 *
	 * <p>Spring 預設 handler 會直接回應 {@code {timestamp, status, error: "Bad Request", message, path}}
	 * 預設 shape，繞過本 handler 的標準 ErrorResponse 結構（{@code {error: "VALIDATION_ERROR", message, timestamp}}）。
	 * Frontend i18n 用 {@code error} code 對應 localized message，「Bad Request」不在白名單→ silent fallthrough。
	 * 顯式 handle 統一 shape，與既有 {@code IllegalArgumentException} → VALIDATION_ERROR 路徑語意對齊。
	 */
	@ExceptionHandler({
			MissingServletRequestParameterException.class,
			MissingServletRequestPartException.class
	})
	ResponseEntity<ErrorResponse> handleMissingParam(Exception ex) {
		log.atWarn()
				.addKeyValue("errorCode", "VALIDATION_ERROR")
				.addKeyValue("message", ex.getMessage())
				.log("Missing required parameter");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("VALIDATION_ERROR", ex.getMessage(), Instant.now()));
	}

	/**
	 * S126：處理 {@code @PathVariable} 型別轉換失敗（{@link MethodArgumentTypeMismatchException}）。
	 *
	 * <p>當 controller 宣告 {@code @PathVariable UUID id} 但 user 傳入 invalid UUID format
	 * （如 {@code null}、{@code undefined}、非 UUID 字串），Spring 內建 UUID converter 在
	 * argument resolution 階段（早於 @PreAuthorize 等 method 級 interceptor）就拋出此例外。
	 *
	 * <p>本 handler 翻譯為標準 ErrorResponse + 400 修補 Bug AX (Mode B Round 39 finding LOW UX)：
	 * Spring Security {@code @PreAuthorize} interceptor **早於** method validation interceptor
	 * fire；故 {@code @Validated + @Pattern} 對 path var 的驗證會被 fail-secure 路徑搶先 → 401/403。
	 * 改用 UUID type + 內建 converter，是唯一在 arg resolution 階段就 throw 的乾淨 fast-fail 路徑。
	 *
	 * <p><b>合法 UUID 但不存在的 id 不在此 handler 範圍</b> — 仍走 @PreAuthorize → 401/403
	 * （security-first；Spring Security 預設「hide existence」設計避免 anonymous 探測）。
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		log.atWarn()
				.addKeyValue("errorCode", "VALIDATION_ERROR")
				.addKeyValue("paramName", ex.getName())
				.addKeyValue("rejectedValue", String.valueOf(ex.getValue()))
				.log("Path variable / query param type mismatch");
		var msg = "Invalid format for parameter '" + ex.getName() + "': " + ex.getValue();
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("VALIDATION_ERROR", msg, Instant.now()));
	}

	/**
	 * S127：處理 Spring 6+ {@link NoResourceFoundException}（trailing slash / 不存在路徑 default 404）。
	 *
	 * <p>原 Spring DefaultErrorAttributes 走 {@code BasicErrorController} 回 {@code {timestamp,
	 * status, error: "Not Found", message: "No static resource ...", path}} — 與既有
	 * {@link NoSuchElementException} → NOT_FOUND ErrorResponse shape 不一致；frontend i18n
	 * `error` code 對 "Not Found" 字串無對應翻譯，silent fallthrough。本 handler 統一翻譯為
	 * 標準 ErrorResponse shape 對齊 Mode B Round 39 finding (Bug AY)。
	 *
	 * <p><b>Tomcat-level limitation</b>：empty path segment（如 {@code /api/v1/skills//foo}）
	 * 由 Tomcat 直接拒絕回 400 不進 Spring exception flow — 本 handler 無法 cover；
	 * 屬 known limitation，frontend 不會生 double-slash URL，影響面 acceptable。
	 */
	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
		log.atDebug()
				.addKeyValue("errorCode", "NOT_FOUND")
				.addKeyValue("path", ex.getResourcePath())
				.log("No resource found at path");
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse("NOT_FOUND", ex.getMessage(), Instant.now()));
	}

	/**
	 * S074：處理單檔預覽超 size 上限（{@link FileTooLargeException}）。
	 *
	 * <p>與 {@link MaxUploadSizeExceededException}（write-side multipart 上限）區分：本例外是
	 * read-side 單檔預覽 1 MB 上限，由 {@code FileBrowserService.readFile} 拋出。
	 * 兩者共用 PAYLOAD_TOO_LARGE error code 但訊息不同（前者「Upload size exceeds」、後者「File ... exceeds preview limit」），
	 * frontend i18n 可從 message 區分 user 動作（上傳 vs 瀏覽）。
	 */
	@ExceptionHandler(FileTooLargeException.class)
	ResponseEntity<ErrorResponse> handleFileTooLarge(FileTooLargeException ex) {
		log.atWarn()
				.addKeyValue("errorCode", "PAYLOAD_TOO_LARGE")
				.addKeyValue("actualSize", ex.getActualSize())
				.addKeyValue("maxSize", ex.getMaxSize())
				.log("File preview blocked: too large");
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
				.body(new ErrorResponse("PAYLOAD_TOO_LARGE", ex.getMessage(), Instant.now()));
	}

	/**
	 * S037：處理其他 multipart 解析錯誤（缺 boundary、缺必要 part 等）。
	 *
	 * <p>{@link MaxUploadSizeExceededException} 的父類；most-specific-first 規則保證 size-exceeded 先匹配。
	 * 其他 multipart 錯誤屬 client-supplied bad data → 400。
	 */
	@ExceptionHandler(MultipartException.class)
	ResponseEntity<ErrorResponse> handleMultipartParseError(MultipartException ex) {
		log.atWarn()
				.addKeyValue("errorCode", "MULTIPART_ERROR")
				.addKeyValue("message", ex.getMessage())
				.log("Multipart parse error");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("MULTIPART_ERROR",
						"Invalid multipart request: " + ex.getMessage(), Instant.now()));
	}

	/**
	 * S030：處理 aggregate state machine 違規（{@link IllegalStateException}）。
	 *
	 * <p>涵蓋：duplicate ACL grant、revoke missing ACL、suspend DRAFT、reactivate non-SUSPENDED 等。
	 * aggregate 統一拋 {@code IllegalStateException} with descriptive message；HTTP 層映射至
	 * 409 Conflict（per RFC 9110 §15.5.10：「conflict with the current state of the target resource」）。
	 */
	@ExceptionHandler(IllegalStateException.class)
	ResponseEntity<ErrorResponse> handleStateConflict(IllegalStateException ex) {
		log.atWarn()
				.addKeyValue("errorCode", "STATE_CONFLICT")
				.addKeyValue("message", ex.getMessage())
				.log("State conflict");
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse("STATE_CONFLICT", ex.getMessage(), Instant.now()));
	}

	/**
	 * S052：處理請求 body 缺失或格式錯誤（{@link HttpMessageNotReadableException}）。
	 *
	 * <p>Spring Boot 預設訊息 echo controller 完整 method 簽名（fully-qualified class name +
	 * 巢狀類別 name + 參數 type list）— 屬資訊洩漏，attacker 可掃描所有 endpoint 列出 internal
	 * class 結構。本 handler 統一 normalize 至固定 user-friendly message；raw message 留 log
	 * 利 ops 排查。涵蓋 missing body / malformed JSON / type mismatch 三場景。
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
		log.atWarn()
				.addKeyValue("errorCode", "INVALID_REQUEST_BODY")
				.addKeyValue("rawMessage", ex.getMessage())
				.log("Unreadable request body");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("INVALID_REQUEST_BODY",
						"Request body is missing or malformed",
						Instant.now()));
	}

	/**
	 * S057：catch-all 處理其他 DB 完整性違反（{@link DataIntegrityViolationException}）。
	 *
	 * <p>S051 既有 {@link DuplicateKeyException} handler 走 most-specific-first 規則優先匹配；
	 * 本 handler 只處理 length / NOT NULL / FK 等其他子類。固定 user-friendly message
	 * 不暴露 SQL detail（INSERT/UPDATE 語句、column 列、constraint 名）；raw `ex.getMessage()`
	 * 留 log 利 ops 排查。
	 *
	 * <p>RFC 9110：value too long / FK violation / NOT NULL 屬 input 不符合 schema → 400。
	 * S051 dup key 為 conflict-class 仍 409。
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
		log.atWarn()
				.addKeyValue("errorCode", "CONSTRAINT_VIOLATION")
				.addKeyValue("rawMessage", ex.getMessage())
				.log("Data integrity violation");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("CONSTRAINT_VIOLATION",
						"Submitted data exceeds allowed length or format constraints",
						Instant.now()));
	}

	/**
	 * S051：處理 unique constraint 違反（{@link DuplicateKeyException}）。
	 *
	 * <p>RFC 9110 §15.5.10 — 「conflict with the current state of the target resource」屬
	 * 409 Conflict。Spring 預設未攔此例外，response 暴露完整 SQL（INSERT 語句、column 列、
	 * constraint 名）— 屬資訊洩漏。本 handler 固定 user-friendly message 不暴露 DB detail；
	 * raw `ex.getMessage()` 留至 log 利 ops 排查。
	 */
	@ExceptionHandler(DuplicateKeyException.class)
	ResponseEntity<ErrorResponse> handleDuplicateKey(DuplicateKeyException ex) {
		log.atWarn()
				.addKeyValue("errorCode", "DUPLICATE_RESOURCE")
				.addKeyValue("rawMessage", ex.getMessage())
				.log("Duplicate key violation");
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse("DUPLICATE_RESOURCE",
						"A resource with the same identifier already exists",
						Instant.now()));
	}

	/**
	 * S049：處理 corrupt / 無效 zip 上傳（{@link ZipException}）。
	 *
	 * <p>{@code ZipException} extends {@code IOException}；most-specific-first 規則保證
	 * 此 handler 優先匹配。固定 user-friendly message，不暴露 ex.getMessage()
	 * （含 Java 內部「invalid stored block lengths」/ ZIP magic offset 等 detail）。
	 * Frontend 走既有 `VALIDATION_ERROR` i18n map 顯繁中「zip 套件驗證失敗，請確認格式正確。」
	 */
	@ExceptionHandler(ZipException.class)
	ResponseEntity<ErrorResponse> handleInvalidZip(ZipException ex) {
		log.atWarn()
				.addKeyValue("errorCode", "VALIDATION_ERROR")
				.addKeyValue("zipError", ex.getMessage())
				.log("Invalid zip upload");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("VALIDATION_ERROR",
						"Invalid zip file: cannot read package contents",
						Instant.now()));
	}

	/**
	 * S045：處理 HTTP method 不允許（{@link HttpRequestMethodNotSupportedException}）。
	 *
	 * <p>未顯式攔截時 Spring 落 BasicErrorController；其預設 body 含完整 stack trace
	 * （filter chain class name 屬資訊洩漏）。本 handler 攔截後 normalize 至既有
	 * ErrorResponse 格式。yaml `server.error.include-stacktrace: never` 為 defense-in-depth
	 * — 即使其他未攔截錯誤落 BasicErrorController 也不噴 trace。
	 */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
		log.atWarn()
				.addKeyValue("errorCode", "METHOD_NOT_ALLOWED")
				.addKeyValue("method", ex.getMethod())
				.log("HTTP method not supported");
		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
				.body(new ErrorResponse("METHOD_NOT_ALLOWED",
						"HTTP method '" + ex.getMethod() + "' is not supported for this resource",
						Instant.now()));
	}

	/**
	 * S030：處理 Spring Data {@link OptimisticLockingFailureException}（{@code @Version} 競態）。
	 *
	 * <p>並行 update 同一 aggregate 時版本號衝突；client 應重試（idempotent operation 的話）或
	 * 重新讀取最新狀態後再嘗試。HTTP 409 Conflict + retry hint message。
	 *
	 * <p>不在此處 auto-retry — 自動 retry 可能 mask 真正衝突（同一 client 連發兩次該 fail），
	 * 屬 future spec scope。
	 */
	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<ErrorResponse> handleConcurrentModification(OptimisticLockingFailureException ex) {
		log.atWarn()
				.addKeyValue("errorCode", "CONCURRENT_MODIFICATION")
				.addKeyValue("message", ex.getMessage())
				.log("Optimistic lock conflict");
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse("CONCURRENT_MODIFICATION",
						"Resource was modified concurrently. Retry the request.",
						Instant.now()));
	}

}
