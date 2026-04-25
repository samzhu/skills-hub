package io.github.samzhu.skillshub.shared.api;

import java.time.Instant;

/**
 * 統一 API 錯誤回應格式。
 *
 * <p>所有 REST API 在發生錯誤時，應透過 {@link GlobalExceptionHandler} 包裝成此格式回傳，
 * 使前端能一致地解析並轉譯錯誤訊息。
 *
 * @param error     錯誤代碼（英文大寫，供前端識別類型，例如 {@code "VALIDATION_ERROR"}）
 * @param message   錯誤描述（英文，供前端轉譯為繁體中文顯示）
 * @param timestamp 錯誤發生的 UTC 時間戳記
 */
public record ErrorResponse(
		String error,
		String message,
		Instant timestamp
) {}
