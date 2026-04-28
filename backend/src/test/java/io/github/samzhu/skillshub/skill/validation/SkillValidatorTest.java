package io.github.samzhu.skillshub.skill.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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

	// =========== S018 AC-14: 嚴格化驗證（agentskills.io spec compliance）===========

	@Test
	@DisplayName("AC-14: name 含大寫 → invalid")
	@Tag("AC-14")
	void invalidNameUppercase() {
		var content = """
				---
				name: Docker-Helper
				description: ok
				---
				""";
		var result = validator.validate(content);

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).anyMatch(e -> e.contains("name") && e.contains("regex"));
	}

	@Test
	@DisplayName("AC-14: name 超過 64 字元 → invalid")
	@Tag("AC-14")
	void invalidNameTooLong() {
		var longName = "a".repeat(65);
		var content = "---\nname: " + longName + "\ndescription: ok\n---\n";

		var result = validator.validate(content);

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).anyMatch(e -> e.contains("name") && e.contains("regex"));
	}

	@Test
	@DisplayName("AC-14: description 超過 1024 字元 → invalid")
	@Tag("AC-14")
	void invalidDescriptionTooLong() {
		var longDesc = "x".repeat(1025);
		var content = "---\nname: ok-name\ndescription: " + longDesc + "\n---\n";

		var result = validator.validate(content);

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).anyMatch(e -> e.contains("description") && e.contains("1024"));
	}

	@Test
	@DisplayName("AC-14: compatibility 超過 500 字元 → invalid")
	@Tag("AC-14")
	void invalidCompatibilityTooLong() {
		var longCompat = "y".repeat(501);
		var content = "---\nname: ok-name\ndescription: ok\ncompatibility: " + longCompat + "\n---\n";

		var result = validator.validate(content);

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).anyMatch(e -> e.contains("compatibility") && e.contains("500"));
	}

	@Test
	@DisplayName("AC-14: allowed-tools 含 invalid syntax（shell injection）→ invalid")
	@Tag("AC-14")
	void invalidAllowedToolsSyntax() {
		var content = """
				---
				name: ok-name
				description: ok
				allowed-tools: "Bash(git:*) ;rm -rf /"
				---
				""";

		var result = validator.validate(content);

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).anyMatch(e -> e.contains("allowed-tools"));
	}

	@Test
	@DisplayName("AC-14: 內容為空 → invalid（既有行為，但與 AC-14 同層級覆蓋）")
	@Tag("AC-14")
	void invalidEmptyContent() {
		var result = validator.validate("");

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).contains("SKILL.md content is empty");
	}

	@Test
	@DisplayName("AC-15: 完全合規 frontmatter（含 allowed-tools 合法）→ valid + metadata 完整")
	@Tag("AC-15")
	void fullyCompliantFrontmatter() {
		var content = """
				---
				name: docker-helper
				description: Docker compose helper for multi-container deployments
				compatibility: claude-3-5
				allowed-tools: "Bash(git:*) Edit Read Write Grep Glob"
				---
				# Docker Helper
				""";

		var result = validator.validate(content);

		assertThat(result.valid()).isTrue();
		assertThat(result.errors()).isEmpty();
		assertThat(result.metadata()).containsEntry("name", "docker-helper");
		assertThat(result.metadata()).containsEntry("compatibility", "claude-3-5");
		assertThat(result.metadata()).containsEntry("allowed-tools", "Bash(git:*) Edit Read Write Grep Glob");
	}

}
