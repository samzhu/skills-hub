package io.github.samzhu.skillshub.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;

/**
 * S159a — {@link UnknownQueryParamInterceptor} unit test。
 *
 * <p>驗證 AC-5 / AC-6：unknown param 拒收（400）+ Pageable reserved（page/size/sort）
 * 不被誤拒。
 */
class UnknownQueryParamInterceptorTest {

	private final UnknownQueryParamInterceptor interceptor = new UnknownQueryParamInterceptor();

	static class FakeController {

		public void search(
				@RequestParam(required = false) String keyword,
				@RequestParam(required = false) String category,
				@RequestParam(required = false) String author,
				Pageable pageable) {
			// no-op
		}

		public void noParams() {
			// no-op
		}

		public void renamed(@RequestParam(name = "q") String alias) {
			// no-op
		}
	}

	private HandlerMethod handlerFor(String methodName, Class<?>... paramTypes) throws Exception {
		Method m = FakeController.class.getDeclaredMethod(methodName, paramTypes);
		return new HandlerMethod(new FakeController(), m);
	}

	@Test
	@DisplayName("AC-5: typo param 'categroy' → 拋 UnknownQueryParamException")
	void unknownParamThrows() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/skills");
		req.setParameter("categroy", "Security");
		MockHttpServletResponse res = new MockHttpServletResponse();

		HandlerMethod hm = handlerFor("search", String.class, String.class, String.class, Pageable.class);

		assertThatThrownBy(() -> interceptor.preHandle(req, res, hm))
				.isInstanceOf(UnknownQueryParamException.class)
				.hasMessageContaining("categroy");
	}

	@Test
	@DisplayName("AC-5: 多個 unknown param 全列出")
	void multipleUnknownParamsAllListed() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/skills");
		req.setParameter("categroy", "x");
		req.setParameter("fooBar", "y");
		MockHttpServletResponse res = new MockHttpServletResponse();

		HandlerMethod hm = handlerFor("search", String.class, String.class, String.class, Pageable.class);

		assertThatThrownBy(() -> interceptor.preHandle(req, res, hm))
				.isInstanceOf(UnknownQueryParamException.class)
				.hasMessageContaining("categroy")
				.hasMessageContaining("fooBar");
	}

	@Test
	@DisplayName("AC-5: 已知 param（keyword/category/author）→ 通過")
	void knownParamsPass() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/skills");
		req.setParameter("keyword", "k");
		req.setParameter("category", "security");
		req.setParameter("author", "a");
		MockHttpServletResponse res = new MockHttpServletResponse();

		HandlerMethod hm = handlerFor("search", String.class, String.class, String.class, Pageable.class);

		boolean result = interceptor.preHandle(req, res, hm);
		assertThat(result).isTrue();
	}

	@Test
	@DisplayName("AC-6: page/size/sort（Pageable reserved）不被誤拒")
	void pageableReservedParamsAccepted() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/skills");
		req.setParameter("page", "0");
		req.setParameter("size", "10");
		req.setParameter("sort", "createdAt,desc");
		MockHttpServletResponse res = new MockHttpServletResponse();

		HandlerMethod hm = handlerFor("search", String.class, String.class, String.class, Pageable.class);

		boolean result = interceptor.preHandle(req, res, hm);
		assertThat(result).isTrue();
	}

	@Test
	@DisplayName("Method 無 Pageable 時 page/size/sort 仍視為 unknown")
	void noPageableThenPageRejected() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/skills/x");
		req.setParameter("page", "0");
		MockHttpServletResponse res = new MockHttpServletResponse();

		HandlerMethod hm = handlerFor("noParams");

		assertThatThrownBy(() -> interceptor.preHandle(req, res, hm))
				.isInstanceOf(UnknownQueryParamException.class)
				.hasMessageContaining("page");
	}

	@Test
	@DisplayName("@RequestParam(name=\"q\") rename — 用 annotation name，不用 method param 原名")
	void requestParamWithExplicitName() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/skills");
		req.setParameter("q", "search");
		MockHttpServletResponse res = new MockHttpServletResponse();

		HandlerMethod hm = handlerFor("renamed", String.class);

		boolean result = interceptor.preHandle(req, res, hm);
		assertThat(result).isTrue();
	}

	@Test
	@DisplayName("@RequestParam(name=\"q\") — 傳 alias 原名 'alias' 應被拒")
	void requestParamOriginalNameRejected() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/skills");
		req.setParameter("alias", "x");
		MockHttpServletResponse res = new MockHttpServletResponse();

		HandlerMethod hm = handlerFor("renamed", String.class);

		assertThatThrownBy(() -> interceptor.preHandle(req, res, hm))
				.isInstanceOf(UnknownQueryParamException.class)
				.hasMessageContaining("alias");
	}

	@Test
	@DisplayName("Empty query string → 通過（不檢查）")
	void emptyQueryStringPasses() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/skills");
		MockHttpServletResponse res = new MockHttpServletResponse();

		HandlerMethod hm = handlerFor("search", String.class, String.class, String.class, Pageable.class);

		boolean result = interceptor.preHandle(req, res, hm);
		assertThat(result).isTrue();
	}

	@Test
	@DisplayName("Non-GET method（POST）跳過攔截")
	void nonGetSkipsCheck() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/skills");
		req.setParameter("anything", "x");
		MockHttpServletResponse res = new MockHttpServletResponse();

		HandlerMethod hm = handlerFor("noParams");

		boolean result = interceptor.preHandle(req, res, hm);
		assertThat(result).isTrue();
	}

	@Test
	@DisplayName("Handler 非 HandlerMethod（static resource）→ 跳過攔截")
	void nonHandlerMethodSkipsCheck() {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/static/foo.css");
		req.setParameter("anything", "x");
		MockHttpServletResponse res = new MockHttpServletResponse();

		Object plainHandler = new Object();

		boolean result = interceptor.preHandle(req, res, plainHandler);
		assertThat(result).isTrue();
	}

	@Test
	@DisplayName("UnknownQueryParamException.unknownParams 為 immutable")
	void unknownParamsIsImmutable() {
		var src = new java.util.HashSet<>(Map.of("foo", "1", "bar", "2").keySet());
		var ex = new UnknownQueryParamException(src);
		src.add("baz");

		assertThat(ex.getUnknownParams()).hasSize(2).contains("foo", "bar");
	}
}
