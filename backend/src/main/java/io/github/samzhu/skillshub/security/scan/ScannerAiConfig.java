package io.github.samzhu.skillshub.security.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Configuration;
import org.jspecify.annotations.Nullable;

import io.github.samzhu.skillshub.SkillshubProperties;
import io.github.samzhu.skillshub.shared.ai.AiModelConfig;

/**
 * Scanner AI compatibility config.
 *
 * <p>S171 moves the real {@code scannerChatClient} bean to {@link AiModelConfig}.
 * This class keeps the old factory method as a non-bean delegate for S157 unit tests
 * until the scanner config tests are folded into the shared AI config tests.
 *
 * @see io.github.samzhu.skillshub.security.scan.engines.LlmJudge
 * @see AiModelConfig
 */
@Configuration
public class ScannerAiConfig {

	private static final Logger log = LoggerFactory.getLogger(ScannerAiConfig.class);

	/**
	 * ChatClient factory — runtime 依 engine.enabled × api-key 兩條件 branch。
	 *
	 * <p>缺任一條件 → return null：Spring 將該 bean 註冊為 NullBean placeholder，consumer
	 * 端 {@code Optional<ChatClient>} 注入為 empty，{@code LlmJudge} 走 graceful skip。
	 * Spring Framework Reference (Core / IoC Container / @Bean) 官方支援此寫法。
	 *
	 * <p>API key 模式（非 Vertex AI），固定使用 GEMINI_2_5_FLASH（速度 + 成本平衡），
	 * temperature=0.0 求確定性。
	 *
	 * @param props 已 resolve 完 placeholder 的 properties
	 * @return 真實 ChatClient 或 null（缺條件時）
	 */
	@Nullable
	ChatClient scannerChatClient(SkillshubProperties props) {
		var llmEnabled = props.scanner().engines().llm().enabled();
		if (!llmEnabled) {
			log.info("Scanner LLM engine disabled — ChatClient not registered (consumer Optional empty)");
			return null;
		}
		var chatModel = AiModelConfig.createChatModel(props);
		if (chatModel == null) {
			log.info("Scanner LLM enabled but skillshub.genai.api-key absent — ChatClient not registered");
			return null;
		}
		return ChatClient.builder(chatModel).build();
	}
}
