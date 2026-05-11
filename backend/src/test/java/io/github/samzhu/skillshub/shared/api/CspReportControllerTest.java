package io.github.samzhu.skillshub.shared.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.shared.security.WebMvcSliceTestBase;

/**
 * S160 AC-8 — CSP violation report endpoint 行為驗證。
 *
 * <p>覆蓋 spec §3 AC-8：browser 違規時 POST /api/v1/csp-report → 204 + backend log
 * 含 violation detail。Also verifies CSP-Report-Only header 已含 report-uri directive
 * 告訴 browser 該 endpoint 位置。
 */
@WebMvcTest(CspReportController.class)
class CspReportControllerTest extends WebMvcSliceTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: POST /api/v1/csp-report application/csp-report → 204")
    void cspReportPostReturns204() throws Exception {
        var violation = """
                {"csp-report":{"document-uri":"https://example.com/","violated-directive":"script-src 'self'",
                  "blocked-uri":"inline","original-policy":"default-src 'self'"}}
                """;

        mockMvc.perform(post("/api/v1/csp-report")
                .contentType(MediaType.parseMediaType("application/csp-report"))
                .content(violation))
            .andExpect(status().isNoContent());
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: 同 endpoint 也接 application/json（Chrome 舊版 + 一般 fetch report 模式）")
    void cspReportPostAcceptsJsonContentType() throws Exception {
        mockMvc.perform(post("/api/v1/csp-report")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"violation\":\"x\"}"))
            .andExpect(status().isNoContent());
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: 同 endpoint 接 application/reports+json（新版 Reporting API group format）")
    void cspReportPostAcceptsReportsJsonContentType() throws Exception {
        mockMvc.perform(post("/api/v1/csp-report")
                .contentType(MediaType.parseMediaType("application/reports+json"))
                .content("[{\"type\":\"csp-violation\",\"body\":{}}]"))
            .andExpect(status().isNoContent());
    }

    @Test
    @Tag("AC-4")
    @DisplayName("AC-4 補強：CSP Report-Only header 含 report-uri /api/v1/csp-report directive")
    void cspHeaderIncludesReportUri() throws Exception {
        mockMvc.perform(post("/api/v1/csp-report")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(header().string("Content-Security-Policy-Report-Only",
                    containsString("report-uri /api/v1/csp-report")));
    }
}
