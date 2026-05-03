package io.github.samzhu.skillshub.security;

import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.security.CurrentUser;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

/**
 * S112 AC-5 — {@code GET /api/v1/me/flags-summary} HTTP 契約。
 *
 * <p>Service 層計數邏輯（AC-6 user 隔離 + AC-7 0-skill graceful）由
 * {@link FlagServiceTest} 涵蓋；本 slice 只驗 controller 把 user 抽取
 * 與 service 結果包成正確 JSON shape。
 */
@WebMvcTest(MeFlagsController.class)
class MeFlagsControllerTest extends WebMvcSliceTestBase {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private FlagService flagService;

	@MockitoBean
	private CurrentUserProvider currentUserProvider;

	@Test
	@Tag("AC-5")
	@DisplayName("AC-5: GET /me/flags-summary → 200 + {openCount:N} JSON")
	void flagsSummary_returnsOpenCount() throws Exception {
		Mockito.when(currentUserProvider.current())
				.thenReturn(new CurrentUser("alice", List.of("user"), List.of()));
		Mockito.when(flagService.countOpenFlagsForAuthor(eq("alice"))).thenReturn(7L);

		mockMvc.perform(get("/api/v1/me/flags-summary")
						.with(jwt().jwt(j -> j.subject("alice"))))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.openCount").value(7));
	}
}
