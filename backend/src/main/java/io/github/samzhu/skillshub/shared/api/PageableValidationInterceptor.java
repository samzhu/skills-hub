package io.github.samzhu.skillshub.shared.api;

import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * S159d — 攔截 GET 請求中 raw {@code page} / {@code size} query 參數的非法數值
 * （{@code page<0} / {@code size<=0} / {@code size>100}），拋
 * {@link InvalidPageableException}（轉成 HTTP 400）。
 *
 * <p><b>為何走 interceptor 而非 controller-level helper</b>：Spring Data Web 的
 * {@code PageableHandlerMethodArgumentResolver} 預設行為對 {@code page=-1} silent clamp
 * 為 0、{@code size=0} 走 default、{@code size>maxPageSize} clamp 為 max — 等到 controller
 * 拿到 {@link Pageable} 物件時，違規值已被 silent fix。controller-level 檢查抓不到 raw 違規。
 * 唯一在 resolver 之前看到 raw 數值的時機是 {@code preHandle} 階段（早於 argument resolution）。
 *
 * <p>套用範圍：透過 {@code WebMvcConfig.addInterceptors} 攔截 method 含 {@link Pageable}
 * 參數的 controller；目前僅 SkillQueryController.search 命中。
 *
 * <p>對齊 {@link UnknownQueryParamInterceptor}（S159a）pattern，符合 S159 META 「query API
 * hardening」漸進化擴展。
 */
public class PageableValidationInterceptor implements HandlerInterceptor {

	/** 單頁最大筆數上限 — 防 DB row fetch 與 JSON 反序列化造成 OOM。 */
	public static final int MAX_PAGE_SIZE = 100;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!"GET".equalsIgnoreCase(request.getMethod())) {
			return true;
		}
		if (!(handler instanceof HandlerMethod hm)) {
			return true;
		}
		if (!hasPageableParam(hm)) {
			return true;
		}

		validatePageParam(request.getParameter("page"));
		validateSizeParam(request.getParameter("size"));
		return true;
	}

	private boolean hasPageableParam(HandlerMethod hm) {
		for (MethodParameter mp : hm.getMethodParameters()) {
			if (Pageable.class.isAssignableFrom(mp.getParameterType())) {
				return true;
			}
		}
		return false;
	}

	private void validatePageParam(String raw) {
		if (raw == null || raw.isBlank()) {
			return;
		}
		int page;
		try {
			page = Integer.parseInt(raw.trim());
		} catch (NumberFormatException ex) {
			// 非數值：交還給 resolver fallback 為 0（與 unknown query param 區隔，不是本 interceptor 範圍）
			return;
		}
		if (page < 0) {
			throw new InvalidPageableException("page must be >= 0");
		}
	}

	private void validateSizeParam(String raw) {
		if (raw == null || raw.isBlank()) {
			return;
		}
		int size;
		try {
			size = Integer.parseInt(raw.trim());
		} catch (NumberFormatException ex) {
			return;
		}
		if (size <= 0) {
			throw new InvalidPageableException("size must be > 0");
		}
		if (size > MAX_PAGE_SIZE) {
			throw new InvalidPageableException("size must be <= " + MAX_PAGE_SIZE);
		}
	}
}
