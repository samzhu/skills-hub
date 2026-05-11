package io.github.samzhu.skillshub.security.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

import com.google.genai.Client;

import io.github.samzhu.skillshub.SkillshubProperties;

/**
 * Spring AI Manual Configuration for the LlmJudge scanner engine — 建立 Gemini
 * {@link ChatClient} bean 用 API key 模式（非 Vertex AI）。
 *
 * <p>S157：拿掉 build-time {@code @Conditional(LlmEnabledCondition.class)} — 該 nested condition
 * 同時檢查 {@code skillshub.scanner.engines.llm.enabled} 與 {@code skillshub.genai.api-key}，兩者在
 * Spring AOT native image build 階段都讀不到真實值（CI 無 sm@ resolver；engine.enabled 走
 * {@code @DefaultValue("true")} 不寫入 Environment property source），導致 bean 永遠 baked
 * 排除，runtime 無法救回。改為 factory body runtime branch — engine 關閉或 api-key 缺則
 * return null，Spring 將該 bean 視為 absent，consumer 端 {@code Optional<ChatClient>} 自動 empty。
 *
 * <p>遵循 S009 規範：「Spring AI Manual Configuration — 不混用 auto-config 與 manual config」。
 * 對應的 auto-config（{@code GoogleGenAiChatAutoConfiguration}）已透過 {@code spring.ai.model.chat: none}
 * 在測試環境停用；生產環境仰賴本檔案直接建構 bean。
 *
 * @see io.github.samzhu.skillshub.security.scan.engines.LlmJudge
 * @see io.github.samzhu.skillshub.score.judge.QualityJudgeConfig
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
	@Bean
	@Nullable
	ChatClient scannerChatClient(SkillshubProperties props) {
		var llmEnabled = props.scanner().engines().llm().enabled();
		var apiKey = props.genai().apiKey();
		if (!llmEnabled) {
			log.info("Scanner LLM engine disabled — ChatClient not registered (consumer Optional empty)");
			return null;
		}
		if (apiKey == null || apiKey.isBlank()) {
			log.info("Scanner LLM enabled but skillshub.genai.api-key absent — ChatClient not registered");
			return null;
		}
		log.info("Initialising scanner ChatClient (Manual Config, API key mode, model=GEMINI_2_5_FLASH)");
		var client = Client.builder()
				.apiKey(apiKey)
				.build();
		var chatModel = GoogleGenAiChatModel.builder()
				.genAiClient(client)
				.defaultOptions(GoogleGenAiChatOptions.builder()
						.model(GoogleGenAiChatModel.ChatModel.GEMINI_2_5_FLASH)
						.temperature(0.0)
						.build())
				.build();
		return ChatClient.create(chatModel);
	}
}
