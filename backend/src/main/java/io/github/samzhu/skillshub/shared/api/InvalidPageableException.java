package io.github.samzhu.skillshub.shared.api;

/**
 * S159d — Pageable 參數超出合法範圍（{@code page<0} / {@code size<=0} / {@code size>100}）時拋出。
 *
 * <p>由 {@code PageableValidator} 在 controller 起手檢出，經
 * {@link GlobalExceptionHandler} 轉成 HTTP 400 + {@code INVALID_PAGEABLE}。
 *
 * <p>extends {@link IllegalArgumentException} 保 fallback：未來若新 controller 漏加
 * validator 呼叫，仍走 generic {@code IllegalArgumentException} → 400 路徑（VALIDATION_ERROR
 * code）；明確 handler 命中時改回 INVALID_PAGEABLE 給前端 i18n 細分。
 */
public class InvalidPageableException extends IllegalArgumentException {

	public InvalidPageableException(String message) {
		super(message);
	}
}
