package io.github.samzhu.skillshub.skill.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkillValidatorTest {

	private final SkillValidator validator = new SkillValidator();

	@Test
	@DisplayName("AC-3: SKILL.md frontmatter 驗證 — 成功")
	void validFrontmatter() {
		var content = """
				---
				name: docker-helper
				description: Docker compose helper
				---
				# Docker Helper
				This skill helps with docker compose.
				""";

		var result = validator.validate(content);

		assertThat(result.valid()).isTrue();
		assertThat(result.metadata()).containsEntry("name", "docker-helper");
		assertThat(result.metadata()).containsEntry("description", "Docker compose helper");
		assertThat(result.errors()).isEmpty();
	}

	@Test
	@DisplayName("AC-4: SKILL.md frontmatter 驗證 — 失敗（缺少 name）")
	void missingNameField() {
		var content = """
				---
				description: Some skill
				---
				# Some Skill
				""";

		var result = validator.validate(content);

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).containsExactly("Missing required field: name");
	}

	@Test
	@DisplayName("AC-4: SKILL.md frontmatter 驗證 — 失敗（缺少 description）")
	void missingDescriptionField() {
		var content = """
				---
				name: my-skill
				---
				# My Skill
				""";

		var result = validator.validate(content);

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).containsExactly("Missing required field: description");
	}

	@Test
	@DisplayName("AC-4: SKILL.md frontmatter 驗證 — 失敗（無 frontmatter）")
	void noFrontmatter() {
		var content = "# Just a markdown file";

		var result = validator.validate(content);

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).contains("No YAML frontmatter found (expected --- delimiters)");
	}

}
