package io.github.samzhu.skillshub.skill.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

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

	@Test
	@DisplayName("S073 AC-1: allowed-tools YAML block sequence（canonical Anthropic 形狀）→ valid")
	@Tag("S073")
	void allowedToolsBlockSequenceValid() {
		var content = """
				---
				name: ok-name
				description: ok
				allowed-tools:
				  - Read
				  - Edit
				  - Bash(git:*)
				---
				# Body content present.
				""";

		var result = validator.validate(content);

		assertThat(result.valid()).isTrue();
		assertThat(result.errors()).isEmpty();
	}

	@Test
	@DisplayName("S073 AC-2: allowed-tools YAML flow sequence → valid")
	@Tag("S073")
	void allowedToolsFlowSequenceValid() {
		var content = """
				---
				name: ok-name
				description: ok
				allowed-tools: [Read, Edit, "Bash(npm:test)"]
				---
				# Body content present.
				""";

		var result = validator.validate(content);

		assertThat(result.valid()).isTrue();
		assertThat(result.errors()).isEmpty();
	}

	@Test
	@DisplayName("S073 AC-4: allowed-tools list 含 injection token → invalid 並指向違規 token")
	@Tag("S073")
	void allowedToolsListInjectionRejected() {
		var content = """
				---
				name: ok-name
				description: ok
				allowed-tools:
				  - Read
				  - "Bash(; rm -rf /)"
				---
				""";

		var result = validator.validate(content);

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).anyMatch(e -> e.contains("allowed-tools") && e.contains("Bash(;"));
	}

	// =========== S135a AC-S135a-5: 6 hard error rules ===========

	@Test
	@DisplayName("AC-S135a-5: SKILL.md 超過 500 行 → skill_md_line_count error")
	@Tag("AC-S135a-5")
	void lineCountExceedsMax() {
		var lines = "---\nname: ok\ndescription: ok\n---\n" + "line\n".repeat(498);
		// 4 frontmatter + 498 body = 502 lines > 500
		var result = validator.validate(lines);

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).anyMatch(e -> e.startsWith("skill_md_line_count:") && e.contains("max 500"));
	}

	@Test
	@DisplayName("AC-S135a-5: name 有連續 hyphen → consecutive hyphens error")
	@Tag("AC-S135a-5")
	void nameConsecutiveHyphens() {
		var content = """
				---
				name: my--skill
				description: ok
				---
				# Body
				""";

		var result = validator.validate(content);

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).contains("name: consecutive hyphens not allowed");
	}

	@Test
	@DisplayName("AC-S135a-5: name 以 hyphen 開頭 → must not start or end with hyphen error")
	@Tag("AC-S135a-5")
	void nameLeadingHyphen() {
		var content = """
				---
				name: -leading-hyphen
				description: ok
				---
				# Body
				""";

		var result = validator.validate(content);

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).contains("name: must not start or end with hyphen");
	}

	@Test
	@DisplayName("AC-S135a-5: description 為空白字串 → description: must not be blank")
	@Tag("AC-S135a-5")
	void descriptionBlank() {
		var content = "---\nname: ok-name\ndescription: \"  \"\n---\n# Body\n";

		var result = validator.validate(content);

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).contains("description: must not be blank");
	}

	@Test
	@DisplayName("AC-S135a-5: compatibility 提供但為空字串 → compatibility: must not be blank if provided")
	@Tag("AC-S135a-5")
	void compatibilityBlankWhenProvided() {
		var content = "---\nname: ok-name\ndescription: ok\ncompatibility: \"\"\n---\n# Body\n";

		var result = validator.validate(content);

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).contains("compatibility: must not be blank if provided");
	}

	@Test
	@DisplayName("AC-S135a-5: metadata.foo = 123（int 非 string）→ metadata key value must be string")
	@Tag("AC-S135a-5")
	void metadataValueNonString() {
		var content = """
				---
				name: ok-name
				description: ok
				metadata:
				  foo: 123
				---
				# Body
				""";

		var result = validator.validate(content);

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).contains("metadata: key 'foo' value must be a string");
	}

	@Test
	@DisplayName("AC-S135a-5: frontmatter 後無 body content → body_present error")
	@Tag("AC-S135a-5")
	void bodyAbsent() {
		var content = "---\nname: ok-name\ndescription: ok\n---\n";

		var result = validator.validate(content);

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).contains("body_present: SKILL.md has no body content after frontmatter");
	}

	// =========== S135a AC-S135a-6: 3 soft warning rules ===========

	@Test
	@DisplayName("AC-S135a-6: body 無 example heading 或 code fence → body_examples warning（不擋 publish）")
	@Tag("AC-S135a-6")
	void bodyNoExamples() {
		var content = """
				---
				name: ok-name
				description: ok
				---
				# My Skill
				This skill does something.
				""";

		var result = validator.validate(content);

		assertThat(result.valid()).isTrue();
		assertThat(result.warnings()).contains("body_examples: no example heading or code fence detected");
	}

	@Test
	@DisplayName("AC-S135a-6: body 無 numbered list 也無 ## Steps → body_steps warning")
	@Tag("AC-S135a-6")
	void bodyNoSteps() {
		var content = """
				---
				name: ok-name
				description: ok
				---
				# My Skill
				This skill does something useful.
				""";

		var result = validator.validate(content);

		assertThat(result.valid()).isTrue();
		assertThat(result.warnings()).contains("body_steps: no step-by-step structure detected");
	}

	@Test
	@DisplayName("AC-S135a-6: body 無 output/format keyword 也無 code block → body_output_format warning")
	@Tag("AC-S135a-6")
	void bodyNoOutputFormat() {
		var content = """
				---
				name: ok-name
				description: ok
				---
				# My Skill
				This skill does something useful.
				""";

		var result = validator.validate(content);

		assertThat(result.valid()).isTrue();
		assertThat(result.warnings()).contains("body_output_format: no output format guidance detected");
	}

	// =========== S135a AC-S135a-7: backward compat ===========

	@Test
	@DisplayName("AC-S135a-7: ValidationResult.of(3-arg) factory — warnings 預設 List.of()；SkillCommandService caller 零改動")
	@Tag("AC-S135a-7")
	void backwardCompatThreeArgFactory() {
		var result = ValidationResult.of(true, Map.of("name", "ok"), List.of());

		assertThat(result.valid()).isTrue();
		assertThat(result.errors()).isEmpty();
		assertThat(result.warnings()).isEmpty();
	}

}
