package io.github.samzhu.skillshub.search;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

/**
 * S094b — LLM-powered intent summarization for semantic search results page.
 *
 * <p>對齊 prototype `semantic_search_results_page.html`：給 user query
 * 產出 1 句中文 intent summary + 4 個英文 concept tags（顯 chip 給 user 看
 * 系統如何理解他的 query）。對齊 README ll.117 「AI intent summary 是語意搜尋
 * 最關鍵的 UX 差異化 — 純關鍵字搜尋不會告訴你『我怎麼理解你』」。
 *
 * <p>Graceful degradation：{@link ChatClient} 是 conditional bean
 * （per {@code ScannerAiConfig.LlmEnabledCondition}）。若 LLM 未啟用 / API key 未設，
 * `Optional<ChatClient>` 為 empty，service 直接以 query 為 summary、空 concepts list 回應 —
 * page 仍可運作（只是無 AI 解釋）。不阻塞 ship，POC HALT 風險規避。
 *
 * <p>In-memory cache：以 query 為 key，避免重複 LLM call。dev / single-instance prod
 * 簡單 ConcurrentHashMap 足夠；多 instance 場景應改 Redis（future spec）。
 * 不限大小（5min idle 清除留 future polish）— 預期 query 多樣性低，cache size bounded by query distribution.
 */
@Service
public class SearchIntentService {

    private static final Logger log = LoggerFactory.getLogger(SearchIntentService.class);

    /** Per-instance cache for {query → IntentResponse}. */
    private final ConcurrentHashMap<String, IntentResponse> cache = new ConcurrentHashMap<>();

    private final Optional<ChatClient> chatClient;

    public SearchIntentService(Optional<ChatClient> chatClient) {
        this.chatClient = chatClient;
        if (chatClient.isEmpty()) {
            log.info("SearchIntent: LLM ChatClient unavailable — running in fallback mode (echo query, no concepts)");
        }
    }

    public IntentResponse summarize(String query) {
        if (query == null || query.isBlank()) {
            return new IntentResponse("", List.of());
        }
        var trimmed = query.trim();
        return cache.computeIfAbsent(trimmed, this::compute);
    }

    private IntentResponse compute(String query) {
        if (chatClient.isEmpty()) {
            return new IntentResponse(query, List.of());
        }
        try {
            var converter = new BeanOutputConverter<>(LlmIntentOutput.class);
            var prompt = """
                    使用者查詢一個 AI agent skill registry，請幫他做意圖摘要。

                    Query: "%s"

                    產出：
                    1. summary：1 句繁體中文「我聽懂你想做的是…」風格的 intent 摘要，
                       不超過 50 字。
                    2. concepts：3-4 個英文 keyword tags（單字或短詞，lowercase），
                       對應使用者意圖可能涵蓋的領域 / 動作 / 工具範疇。

                    %s
                    """.formatted(query, converter.getFormat());
            var raw = chatClient.get().prompt().user(prompt).call().content();
            var parsed = converter.convert(raw);
            if (parsed == null) {
                log.warn("SearchIntent: LLM returned null parse for query='{}'", query);
                return new IntentResponse(query, List.of());
            }
            log.debug("SearchIntent: query='{}' summary='{}' concepts={}", query, parsed.summary(), parsed.concepts());
            return new IntentResponse(parsed.summary(), parsed.concepts() != null ? parsed.concepts() : List.of());
        } catch (Exception e) {
            // Graceful: 任何 LLM 錯誤一律 fallback，不擋 page render
            log.warn("SearchIntent: LLM call failed for query='{}', falling back. err={}", query, e.getMessage());
            return new IntentResponse(query, List.of());
        }
    }

    /** LLM raw output schema (BeanOutputConverter parses JSON into this record). */
    public record LlmIntentOutput(String summary, List<String> concepts) {}

    /** Public API contract — 對外 controller / 前端用。 */
    public record IntentResponse(String summary, List<String> concepts) {}
}
