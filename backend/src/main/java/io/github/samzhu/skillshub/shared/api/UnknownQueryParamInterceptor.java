package io.github.samzhu.skillshub.shared.api;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * S159a — 攔截 GET 請求中 controller method 未宣告的 query 參數並拋
 * {@link UnknownQueryParamException}（轉成 HTTP 400）。
 *
 * <p>偵測規則：
 * <ul>
 *   <li>從 {@link HandlerMethod#getMethodParameters()} 蒐集所有 {@code @RequestParam}
 *       的 {@code name() / value()}（empty 時 fallback method parameter name —
 *       須 {@code -parameters} flag 編譯，本專案 build 已啟用）。</li>
 *   <li>method 含 {@link Pageable} / {@link Sort} 參數時加入 framework reserved
 *       {@code page / size / sort}（Spring Data 預設名）。</li>
 *   <li>request 實際 query param keys 不在 known set 即視為 unknown。</li>
 * </ul>
 *
 * <p>套用範圍：透過 {@code WebMvcConfig.addInterceptors} 限定 SkillQuery /
 * categories 等 GET 端點，避免老 CLI client 帶 stale param 被全平台 reject
 * （per spec §2.3 漸進化擴展策略）。
 */
public class UnknownQueryParamInterceptor implements HandlerInterceptor {

	private static final Set<String> PAGEABLE_RESERVED = Set.of("page", "size", "sort");

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!"GET".equalsIgnoreCase(request.getMethod())) {
			return true;
		}
		if (!(handler instanceof HandlerMethod hm)) {
			return true;
		}

		Set<String> known = collectKnownParams(hm);
		Set<String> actual = request.getParameterMap().keySet();
		if (actual.isEmpty()) {
			return true;
		}

		Set<String> unknown = new LinkedHashSet<>(actual);
		unknown.removeAll(known);
		if (!unknown.isEmpty()) {
			throw new UnknownQueryParamException(unknown);
		}
		return true;
	}

	private Set<String> collectKnownParams(HandlerMethod hm) {
		Set<String> known = new HashSet<>();
		boolean hasPageable = false;

		for (MethodParameter mp : hm.getMethodParameters()) {
			RequestParam rp = mp.getParameterAnnotation(RequestParam.class);
			if (rp != null) {
				String name = !rp.name().isEmpty() ? rp.name()
						: !rp.value().isEmpty() ? rp.value() : mp.getParameterName();
				if (name != null) {
					known.add(name);
				}
				continue;
			}
			Class<?> type = mp.getParameterType();
			if (Pageable.class.isAssignableFrom(type) || Sort.class.isAssignableFrom(type)) {
				hasPageable = true;
			}
		}

		if (hasPageable) {
			known.addAll(PAGEABLE_RESERVED);
		}
		return Collections.unmodifiableSet(known);
	}
}
