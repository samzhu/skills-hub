package io.github.samzhu.skillshub.shared.api;

import java.util.Set;

/**
 * S159a — 客戶端傳入未在 controller method 宣告的 query 參數（例如 typo
 * {@code ?categroy=Security}）時拋出此例外。由
 * {@link UnknownQueryParamInterceptor} 在 {@code preHandle} 階段檢出，
 * 經 {@link GlobalExceptionHandler} 轉成 HTTP 400 + {@code VALIDATION_ERROR}。
 *
 * <p>Spec rationale：避免 silent fall-through — 拼錯參數時 user 會誤以為
 * 「平台沒命中」其實是參數名錯。
 */
public class UnknownQueryParamException extends RuntimeException {

	private final Set<String> unknownParams;

	public UnknownQueryParamException(Set<String> unknownParams) {
		super("Unknown query parameter(s): " + String.join(", ", unknownParams));
		this.unknownParams = Set.copyOf(unknownParams);
	}

	public Set<String> getUnknownParams() {
		return unknownParams;
	}
}
