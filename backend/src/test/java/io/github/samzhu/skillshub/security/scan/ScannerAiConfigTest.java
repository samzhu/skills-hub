package io.github.samzhu.skillshub.security.scan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import io.github.samzhu.skillshub.SkillshubProperties;

/**
 * S157: ScannerAiConfig 單一 factory branching 行為驗證。
 *
 * <p>原 build-time {@code @Conditional(LlmEnabledCondition.class)} 已拿掉；bean 永遠註冊，
 * 真假 ChatClient 由 factory body 依 engine.enabled × api-key 兩條件 runtime branch。
 * 缺條件 → return null（Spring NullBean placeholder，consumer Optional empty）。
 *
 * <p>純 JUnit 5 unit test，無 Spring context 啟動。
 */
class ScannerAiConfigTest {

	private final ScannerAiConfig config = new ScannerAiConfig();

	@Test
	@DisplayName("AC-2.1: engine.llm.enabled=true AND api-key 存在 → 真實 ChatClient")
	@Tag("AC-2")
	void bothPropertiesPresentReturnsRealClient() {
		var props = props(true, "AIzaTestKey");

		var client = config.scannerChatClient(props);

		assertThat(client).isNotNull();
		assertThat(client).isInstanceOf(ChatClient.class);
	}

	@Test
	@DisplayName("AC-2.1: engine.llm.enabled=false → null")
	@Tag("AC-2")
	void engineDisabledReturnsNull() {
		var props = props(false, "AIzaTestKey");

		var client = config.scannerChatClient(props);

		assertThat(client).isNull();
	}

	@Test
	@DisplayName("AC-2.1: api-key 缺席（engine 開啟）→ null")
	@Tag("AC-2")
	void missingApiKeyReturnsNull() {
		var props = props(true, null);

		var client = config.scannerChatClient(props);

		assertThat(client).isNull();
	}

	@Test
	@DisplayName("AC-2.1: 兩屬性都缺 → null")
	@Tag("AC-2")
	void bothAbsentReturnsNull() {
		var props = props(false, null);

		var client = config.scannerChatClient(props);

		assertThat(client).isNull();
	}

	@Test
	@DisplayName("AC-2.1: api-key blank（whitespace 視同缺）→ null")
	@Tag("AC-2")
	void blankApiKeyReturnsNull() {
		var props = props(true, "   ");

		var client = config.scannerChatClient(props);

		assertThat(client).isNull();
	}

	private static SkillshubProperties props(boolean llmEnabled, String apiKey) {
		return new SkillshubProperties(
				new SkillshubProperties.Storage("skillshub-packages", "./storage-local"),
				new SkillshubProperties.Search("skill_embeddings"),
				new SkillshubProperties.GenAI("gemini-embedding-2", 768, apiKey),
				new SkillshubProperties.Scanner(new SkillshubProperties.Engines(
						new SkillshubProperties.Engine(true),
						new SkillshubProperties.Engine(true),
						new SkillshubProperties.Engine(true),
						new SkillshubProperties.Engine(llmEnabled),
						new SkillshubProperties.Engine(true))),
				new SkillshubProperties.Security(
						new SkillshubProperties.OAuth(true, new SkillshubProperties.OAuth.Login(false)),
						new SkillshubProperties.Lab("lab-user"),
						new SkillshubProperties.Cors(java.util.List.of(), false),
						new SkillshubProperties.Csrf(false)));
	}
}
