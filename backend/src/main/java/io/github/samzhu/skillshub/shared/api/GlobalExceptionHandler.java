package io.github.samzhu.skillshub.shared.api;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
