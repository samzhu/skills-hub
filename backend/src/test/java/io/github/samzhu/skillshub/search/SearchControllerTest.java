package io.github.samzhu.skillshub.search;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

/**
 * S203-T01 — semantic search endpoint returns Spring Data Slice JSON.
 */
@WebMvcTest(SearchController.class)
class SearchControllerTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SemanticSearchService searchService;

    @Test
    @DisplayName("AC-S203-1: semantic Slice JSON exposes content number size last without totals")
    @Tag("AC-S203-1")
    void semanticSliceJsonExposesShapeWithoutTotals() throws Exception {
        var pageable = PageRequest.of(0, 2);
        when(searchService.search(eq("qa"), argThat(p -> p.getPageNumber() == 0 && p.getPageSize() == 2)))
                .thenReturn(new SliceImpl<>(List.of(result("skill-a"), result("skill-b")), pageable, true));

        mockMvc.perform(get("/api/v1/search/semantic")
                        .param("q", "qa")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("skill-a"))
                .andExpect(jsonPath("$.content[1].id").value("skill-b"))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.numberOfElements").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.totalElements").doesNotExist())
                .andExpect(jsonPath("$.totalPages").doesNotExist());
    }

    @Test
    @DisplayName("AC-S203-2: GET /semantic page 1 returns next slice")
    @Tag("AC-S203-2")
    void semanticPageOneReturnsNextSlice() throws Exception {
        var pageable = PageRequest.of(1, 2);
        when(searchService.search(eq("qa"), argThat(p -> p.getPageNumber() == 1 && p.getPageSize() == 2)))
                .thenReturn(new SliceImpl<>(List.of(result("skill-c")), pageable, false));

        mockMvc.perform(get("/api/v1/search/semantic")
                        .param("q", "qa")
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("skill-c"))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.numberOfElements").value(1))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("AC-S203-2: legacy limit param is rejected for semantic Slice API")
    @Tag("AC-S203-2")
    void semanticSearchRejectsLegacyLimitParam() throws Exception {
        mockMvc.perform(get("/api/v1/search/semantic")
                        .param("q", "qa")
                        .param("limit", "50"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Unknown query parameter")));
    }

    @Test
    @DisplayName("AC-S203-3: semantic search rejects client sort")
    @Tag("AC-S203-3")
    void semanticSearchRejectsClientSort() throws Exception {
        mockMvc.perform(get("/api/v1/search/semantic")
                        .param("q", "qa")
                        .param("page", "0")
                        .param("size", "2")
                        .param("sort", "name,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("sort is not supported for semantic search"));
    }

    private static SemanticSearchResult result(String id) {
        return new SemanticSearchResult(id, id, "description", "u_current", "Current User", "current",
                "testing", "Testing", "1", "LOW", 0L, 1.0d);
    }
}
