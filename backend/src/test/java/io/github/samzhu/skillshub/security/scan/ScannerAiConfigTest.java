package io.github.samzhu.skillshub.security.scan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import io.github.samzhu.skillshub.SkillshubProperties;

class ScannerAiConfigTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
			.withUserConfiguration(TestRoot.class, ScannerAiConfig.class);

	@Test
	@DisplayName("AC-2.1: engine.llm.enabled=true AND genai.api-key 存在 → bean 建立")
	@Tag("AC-2")
	void bothPropertiesPresentCreatesBean() {
		runner.withPropertyValues(
				"skillshub.scanner.engines.llm.enabled=true",
				"skillshub.genai.api-key=AIzaTestKey")
				.run(ctx -> {
					assertThat(ctx).hasBean("scannerChatModel");
					assertThat(ctx).hasBean("scannerChatClient");
				});
	}

	@Test
	@DisplayName("AC-2.1: engine.llm.enabled=false (api-key 存在) → bean 不建立")
	@Tag("AC-2")
	void engineDisabledSkipsBean() {
		runner.withPropertyValues(
				"skillshub.scanner.engines.llm.enabled=false",
				"skillshub.genai.api-key=AIzaTestKey")
				.run(ctx -> {
					assertThat(ctx).doesNotHaveBean("scannerChatModel");
					assertThat(ctx).doesNotHaveBean("scannerChatClient");
				});
	}

	@Test
	@DisplayName("AC-2.1: api-key 缺席 (engine 開啟) → bean 不建立")
	@Tag("AC-2")
	void missingApiKeySkipsBean() {
		runner.withPropertyValues("skillshub.scanner.engines.llm.enabled=true")
				.run(ctx -> {
					assertThat(ctx).doesNotHaveBean("scannerChatModel");
					assertThat(ctx).doesNotHaveBean("scannerChatClient");
				});
	}

	@Test
	@DisplayName("AC-2.1: 兩屬性都缺 → bean 不建立")
	@Tag("AC-2")
	void bothAbsentSkipsBean() {
		runner.run(ctx -> {
			assertThat(ctx).doesNotHaveBean("scannerChatModel");
			assertThat(ctx).doesNotHaveBean("scannerChatClient");
		});
	}

	/** 啟用 SkillshubProperties relaxed binding，否則 props bean 不存在。 */
	@Configuration
	@EnableConfigurationProperties(SkillshubProperties.class)
	static class TestRoot {}
}
