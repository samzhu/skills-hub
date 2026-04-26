package io.github.samzhu.skillshub.security.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import com.google.genai.Client;

import io.github.samzhu.skillshub.SkillshubProperties;

/**
 * Spring AI Manual Configuration for the LlmJudge scanner engine — builds a Gemini
 * {@link GoogleGenAiChatModel} 與 {@link ChatClient} bean 用 API key 模式（非 Vertex AI）。
 *
 * <p>遵循 S009 規範：「Spring AI Manual Configuration — 不混用 auto-config 與 manual config」。
 * 對應的 auto-config（{@code GoogleGenAiChatAutoConfiguration}）已透過 {@code spring.ai.model.chat: none}
 * 在測試環境停用；生產環境仰賴本檔案直接建構 bean。
 *
 * <p>雙條件 bean 啟動 — 必須同時滿足：
 * <ul>
 *   <li>{@code skillshub.scanner.engines.llm.enabled=true} — engine 開關</li>
 *   <li>{@code skillshub.genai.api-key} 非空 — API key 已設定</li>
 * </ul>
 * Java 不允許同型別 annotation 兩次套用同方法，因此使用 Spring 的 {@link AllNestedConditions}
 * 子類，把兩個 {@code @ConditionalOnProperty} 包成單一 {@code @Conditional}。
 *
 * <p>POC（spec §6 H1, H3）已驗證：
 * <ul>
 *   <li>{@code Client.builder().apiKey()} + {@code GoogleGenAiChatModel.builder()} 在 Boot 4.0.6 正常建構</li>
 *   <li>{@code AllNestedConditions} 對 4 種屬性組合正確 evaluation</li>
 * </ul>
 *
 * @see LlmEnabledCondition
 * @see io.github.samzhu.skillshub.security.scan.engines.LlmJudge
 */
@Configuration
public class ScannerAiConfig {

	private static final Logger log = LoggerFactory.getLogger(ScannerAiConfig.class);

	/**
	 * Gemini ChatModel — 透過 API key 模式直接建構（非 Vertex AI）。
	 * 模型固定使用 GEMINI_2_5_FLASH（速度 + 成本平衡），temperature=0.0 求確定性。
	 *
	 * <p>POC 確認：{@code GoogleGenAiChatOptions.Builder.model()} 只接受 {@code ChatModel} enum，
	 * 不接受字串；spec §4.5 已對此修正。
	 *
	 * @param props {@link SkillshubProperties} 提供 API key（已被 LlmEnabledCondition 確保非空）
	 * @return 已配置好的 GoogleGenAiChatModel
	 */
	@Bean
	@Conditional(LlmEnabledCondition.class)
	GoogleGenAiChatModel scannerChatModel(SkillshubProperties props) {
		log.info("Initialising scanner ChatModel (Manual Config, API key mode, model=GEMINI_2_5_FLASH)");
		var client = Client.builder()
				.apiKey(props.genai().apiKey())
				.build();
		var options = GoogleGenAiChatOptions.builder()
				.model(GoogleGenAiChatModel.ChatModel.GEMINI_2_5_FLASH)
				.temperature(0.0)
				.build();
		return GoogleGenAiChatModel.builder()
				.genAiClient(client)
				.defaultOptions(options)
				.build();
	}

	/**
	 * 高階 ChatClient wrapper — 提供 fluent API 與 BeanOutputConverter 結構化輸出能力。
	 * LlmJudge 透過此 bean 呼叫 LLM，無需處理 prompt / response 低階細節。
	 */
	@Bean
	@Conditional(LlmEnabledCondition.class)
	ChatClient scannerChatClient(GoogleGenAiChatModel chatModel) {
		return ChatClient.create(chatModel);
	}

	/**
	 * 雙條件 gate — 兩個 nested {@code @ConditionalOnProperty} 必須同時 match 才建立相關 bean。
	 *
	 * <p>Java 不允許同 annotation 重複套用單一 method/bean，{@link AllNestedConditions} 是 Spring
	 * 官方推薦解法（POC §6 H3 已驗證 4 種屬性組合行為正確）。
	 *
	 * <p>使用 {@code REGISTER_BEAN} phase 確保條件評估時 {@code SkillshubProperties} 已注入，
	 * 避免循環引用。
	 */
	public static class LlmEnabledCondition extends AllNestedConditions {
		public LlmEnabledCondition() {
			super(ConfigurationPhase.REGISTER_BEAN);
		}

		/** Engine 開關必須為 true。 */
		@ConditionalOnProperty(name = "skillshub.scanner.engines.llm.enabled", havingValue = "true")
		static class EngineEnabled {}

		/** API key 必須存在（非空字串）。 */
		@ConditionalOnProperty(name = "skillshub.genai.api-key")
		static class ApiKeyPresent {}
	}
}
