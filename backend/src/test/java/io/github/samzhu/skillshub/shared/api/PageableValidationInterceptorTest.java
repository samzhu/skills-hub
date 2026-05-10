package io.github.samzhu.skillshub.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;

/**
 * S159d — {@link PageableValidationInterceptor} unit test。覆蓋 AC-1~5。
 *
 * <p>對齊 S159a {@code UnknownQueryParamInterceptorTest} 設計：手動構造
 * {@link HandlerMethod} 模擬 controller method 簽名（含/不含 Pageable），
 * 直接呼叫 {@code preHandle} 驗 throw vs. pass。
 */
class PageableValidationInterceptorTest {

	private final PageableValidationInterceptor interceptor = new PageableValidationInterceptor();

	static class FakeController {

		public void search(
				@RequestParam(required = false) String keyword,
				Pageable pageable) {
			// no-op
		}

		public void noPageable(@RequestParam(required = false) String keyword) {
			// no-op
		}
	}

	private HandlerMethod handlerWithPageable() throws Exception {
		Method m = FakeController.class.getDeclaredMethod("search", String.class, Pageable.class);
		return new HandlerMethod(new FakeController(), m);
	}

	private HandlerMethod handlerNoPageable() throws Exception {
		Method m = FakeController.class.getDeclaredMethod("noPageable", String.class);
		return new HandlerMethod(new FakeController(), m);
	}

	@Test
	@DisplayName("AC-1: page=-1 → InvalidPageableException")
	void rejectsNegativePage() throws Exception {
		var req = new MockHttpServletRequest("GET", "/api/v1/skills");
		req.setParameter("page", "-1");

		assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), handlerWithPageable()))
				.isInstanceOf(InvalidPageableException.class)
				.hasMessageContaining("page must be >= 0");
	}

	@Test
	@DisplayName("AC-2: size=0 → InvalidPageableException")
	void rejectsZeroSize() throws Exception {
		var req = new MockHttpServletRequest("GET", "/api/v1/skills");
		req.setParameter("size", "0");

		assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), handlerWithPageable()))
				.isInstanceOf(InvalidPageableException.class)
				.hasMessageContaining("size must be > 0");
	}

	@Test
	@DisplayName("AC-2: size=-5 → InvalidPageableException")
	void rejectsNegativeSize() throws Exception {
		var req = new MockHttpServletRequest("GET", "/api/v1/skills");
		req.setParameter("size", "-5");

		assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), handlerWithPageable()))
				.isInstanceOf(InvalidPageableException.class)
				.hasMessageContaining("size must be > 0");
	}

	@Test
	@DisplayName("AC-3: size=101 → InvalidPageableException")
	void rejectsOversizedPage() throws Exception {
		var req = new MockHttpServletRequest("GET", "/api/v1/skills");
		req.setParameter("size", "101");

		assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), handlerWithPageable()))
				.isInstanceOf(InvalidPageableException.class)
				.hasMessageContaining("size must be <= 100");
	}

	@Test
	@DisplayName("AC-3: size=999999（極端值，OOM 防護）→ InvalidPageableException")
	void rejectsExtremeSize() throws Exception {
		var req = new MockHttpServletRequest("GET", "/api/v1/skills");
		req.setParameter("size", "999999");

		assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), handlerWithPageable()))
				.isInstanceOf(InvalidPageableException.class);
	}

	@Test
	@DisplayName("AC-4: page=0 & size=20 → 通過")
	void acceptsValidPageable() throws Exception {
		var req = new MockHttpServletRequest("GET", "/api/v1/skills");
		req.setParameter("page", "0");
		req.setParameter("size", "20");

		assertThatCode(() -> interceptor.preHandle(req, new MockHttpServletResponse(), handlerWithPageable()))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("AC-4: page=2 & size=50 → 通過")
	void acceptsValidNonDefaultPageable() throws Exception {
		var req = new MockHttpServletRequest("GET", "/api/v1/skills");
		req.setParameter("page", "2");
		req.setParameter("size", "50");

		assertThatCode(() -> interceptor.preHandle(req, new MockHttpServletResponse(), handlerWithPageable()))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("AC-3 邊界: size=100 通過（恰等 MAX）")
	void acceptsBoundarySize() throws Exception {
		var req = new MockHttpServletRequest("GET", "/api/v1/skills");
		req.setParameter("size", "100");

		assertThatCode(() -> interceptor.preHandle(req, new MockHttpServletResponse(), handlerWithPageable()))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("AC-5: 無 page/size param → 通過（預設值由 resolver 套用）")
	void acceptsMissingParams() throws Exception {
		var req = new MockHttpServletRequest("GET", "/api/v1/skills");

		assertThatCode(() -> interceptor.preHandle(req, new MockHttpServletResponse(), handlerWithPageable()))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("非 GET method 不檢查（POST 不過 interceptor 邏輯）")
	void skipsNonGet() throws Exception {
		var req = new MockHttpServletRequest("POST", "/api/v1/skills");
		req.setParameter("page", "-1");

		boolean result = interceptor.preHandle(req, new MockHttpServletResponse(), handlerWithPageable());
		assertThat(result).isTrue();
	}

	@Test
	@DisplayName("Method 不含 Pageable 參數 → 跳過檢查（與本 spec 無關 endpoint）")
	void skipsMethodsWithoutPageable() throws Exception {
		var req = new MockHttpServletRequest("GET", "/api/v1/categories");
		req.setParameter("page", "-1");

		boolean result = interceptor.preHandle(req, new MockHttpServletResponse(), handlerNoPageable());
		assertThat(result).isTrue();
	}

	@Test
	@DisplayName("非 HandlerMethod handler（static resource 等）跳過")
	void skipsNonHandlerMethod() throws Exception {
		var req = new MockHttpServletRequest("GET", "/static/foo.css");
		req.setParameter("page", "-1");

		boolean result = interceptor.preHandle(req, new MockHttpServletResponse(), new Object());
		assertThat(result).isTrue();
	}

	@Test
	@DisplayName("Param 為非數值（page=abc）→ silent fallthrough（resolver 再 fallback 為 0）")
	void ignoresNonNumericParams() throws Exception {
		var req = new MockHttpServletRequest("GET", "/api/v1/skills");
		req.setParameter("page", "abc");

		assertThatCode(() -> interceptor.preHandle(req, new MockHttpServletResponse(), handlerWithPageable()))
				.doesNotThrowAnyException();
	}
}
