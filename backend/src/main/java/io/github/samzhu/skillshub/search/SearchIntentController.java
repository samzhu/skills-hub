package io.github.samzhu.skillshub.search;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S094b — Search intent endpoint for the dedicated `/search?q=...` results page.
 *
 * <p>POST 而非 GET：query 內容可能含 SQL/HTML/特殊字元，URL-encode 在某些代理會 bug；
 * POST body 簡單一致。Content-Type: application/json。
 *
 * <p>Response shape：{@code {summary, concepts: string[]}}。LLM 不可用時 graceful
 * fallback 為 {@code summary=query, concepts=[]} — 前端可據此判斷是否顯示 "intent summary card"
 * （concepts 為空表示 fallback 模式，不顯卡片或顯較簡單版本）。
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchIntentController {

    private final SearchIntentService service;

    public SearchIntentController(SearchIntentService service) {
        this.service = service;
    }

    @PostMapping("/intent")
    SearchIntentService.IntentResponse intent(@RequestBody IntentRequest req) {
        return service.summarize(req.query());
    }

    public record IntentRequest(String query) {}
}
