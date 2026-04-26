package io.github.samzhu.skillshub.security.scan.engines;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.SecurityAnalyzer;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class MetadataValidatorTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
	private final MetadataValidator scanner = new MetadataValidator(validator);

	private static ScanContext ctx(Map<String, Object> frontmatter) {
		return new ScanContext("skill-1", "1.0.0", frontmatter, "", Map.of(), List.of());
	}

	@Test
	@DisplayName("AC-5.1: name UPPERCASE 違反 lowercase-hyphen 規則 → notice; 無 finding")
	@Tag("AC-5")
	void nameUppercaseProducesNotice() {
		var frontmatter = Map.<String, Object>of(
				"name", "UPPERCASE-Skill",
				"description", "ok",
				"version", "1.0.0");
		var output = scanner.analyze(ctx(frontmatter));

		assertThat(output.findings()).isEmpty();
		assertThat(output.notices()).anyMatch(n ->
				"metadata".equals(n.source()) && n.message().contains("name"));
	}

	@Test
	@DisplayName("AC-5.2: description 超過 1024 字元 → notice 含 \"description\"")
	@Tag("AC-5")
	void descriptionTooLongProducesNotice() {
		var frontmatter = Map.<String, Object>of(
				"name", "good-skill",
				"description", "x".repeat(1500),
				"version", "1.0.0");
		var output = scanner.analyze(ctx(frontmatter));

		assertThat(output.findings()).isEmpty();
		assertThat(output.notices()).anyMatch(n ->
				"metadata".equals(n.source()) && n.message().contains("description"));
	}

	@Test
	@DisplayName("AC-5.3: 合法 frontmatter — 無 finding 無 notice")
	@Tag("AC-5")
	void validFrontmatterProducesNothing() {
		var frontmatter = Map.<String, Object>of(
				"name", "good-skill",
				"description", "a clean skill",
				"version", "1.0.0");
		var output = scanner.analyze(ctx(frontmatter));

		assertThat(output.findings()).isEmpty();
		assertThat(output.notices()).isEmpty();
	}

	@Test
	@DisplayName("name 太長（>64 chars）觸發 notice")
	@Tag("AC-5")
	void nameTooLongProducesNotice() {
		var longName = "a".repeat(70); // > 64
		var frontmatter = new HashMap<String, Object>();
		frontmatter.put("name", longName);
		frontmatter.put("description", "ok");

		var output = scanner.analyze(ctx(frontmatter));

		assertThat(output.notices()).anyMatch(n -> n.message().contains("name"));
	}

	@Test
	@DisplayName("missing required field 'name' → notice 提到 'name'")
	@Tag("AC-5")
	void missingNameProducesNotice() {
		var frontmatter = new HashMap<String, Object>();
		frontmatter.put("description", "ok");

		var output = scanner.analyze(ctx(frontmatter));

		assertThat(output.notices()).anyMatch(n -> n.message().contains("name"));
	}

	@Test
	@DisplayName("version 不是合法 SemVer → notice")
	@Tag("AC-5")
	void invalidSemverProducesNotice() {
		var frontmatter = Map.<String, Object>of(
				"name", "good-skill",
				"description", "ok",
				"version", "invalid-version");
		var output = scanner.analyze(ctx(frontmatter));

		assertThat(output.notices()).anyMatch(n -> n.message().contains("version"));
	}

	@Test
	@DisplayName("MetadataValidator 是 SecurityAnalyzer，phase=STATIC, name=\"metadata\"")
	void implementsSecurityAnalyzerContract() {
		assertThat(scanner.name()).isEqualTo("metadata");
		assertThat(scanner.phase()).isEqualTo(Phase.STATIC);
	}

	@Test
	@DisplayName("AC-5.4: metadata bean 啟用時建立")
	void beanCreatedWhenEnabled() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
				.withUserConfiguration(MetaReg.class)
				.withPropertyValues("skillshub.scanner.engines.metadata.enabled=true")
				.run(ctx -> assertThat(ctx).hasBean("metadata"));
	}

	@Test
	@DisplayName("AC-5.4: metadata bean 關閉時不建立")
	void beanAbsentWhenDisabled() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
				.withUserConfiguration(MetaReg.class)
				.withPropertyValues("skillshub.scanner.engines.metadata.enabled=false")
				.run(ctx -> assertThat(ctx).doesNotHaveBean("metadata"));
	}

	@Configuration
	static class MetaReg {
		@Bean
		Validator validator() { return Validation.buildDefaultValidatorFactory().getValidator(); }

		@Bean("metadata")
		@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
				name = "skillshub.scanner.engines.metadata.enabled",
				havingValue = "true",
				matchIfMissing = true)
		SecurityAnalyzer metadataValidator(Validator v) { return new MetadataValidator(v); }
	}
}
