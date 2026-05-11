package io.github.samzhu.skillshub.shared.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * S160 AC-8 — CSP violation report receiver。
 *
 * <p>Browser 端 CSP Report-Only header 含 {@code report-uri /api/v1/csp-report} →
 * 違規時 browser 自動 POST violation JSON 到本路徑。本 controller 純接 + WARN log，
 * 預留 hook 給未來把 log 串接 ELK / Cloud Logging filter 做集中分析。
 *
 * <p>**為何 raw String body** 而非 typed record：CSP-Violation 規格各 browser 略有差異
 * （Chrome / Firefox / Safari 欄位 naming 不完全一致；新版 Reporting API 用
 * {@code application/reports+json} group format，舊版用 {@code application/csp-report}），
 * 嚴格 typed 會排除部分 browser report。Raw body 保證 log 完整 captures 給離線分析。
 *
 * <p>**LAB profile 也接** — 雖 LAB 流量低，CSP report 仍代表潛在前端 inline 行為 leak，
 * 啟用收集無壞處。Production 路徑期望大量 report 時可加 rate limit（拆 sub-spec）。
 */
@RestController
class CspReportController {

    private static final Logger log = LoggerFactory.getLogger(CspReportController.class);

    @PostMapping(path = "/api/v1/csp-report",
            consumes = {"application/csp-report", "application/json", "application/reports+json"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cspReport(@RequestBody(required = false) String report) {
        // 結構化 log 讓後續 logging filter 可以 grep "event=csp_violation"
        log.atWarn()
                .addKeyValue("event", "csp_violation")
                .addKeyValue("payload", report == null ? "<empty>" : report)
                .log("CSP violation reported");
    }
}
