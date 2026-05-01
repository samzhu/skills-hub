package io.github.samzhu.skillshub.shared.api;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.zip.ZipException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

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
