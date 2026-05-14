package io.github.samzhu.skillshub.security.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class ScanContextPackageFilesTest {

	@Test
	@DisplayName("AC-S147-PACKAGE-FILES: backward constructor builds package files from SKILL.md and scripts")
	@Tag("AC-S147-PACKAGE-FILES")
	void backwardConstructorBuildsPackageFilesFromSkillMdAndScripts() {
		var context = new ScanContext(
				"skill-1",
				"1.0.0",
				Map.of("name", "demo"),
				"# Demo",
				Map.of("scripts/setup.sh", "echo setup"),
				List.of());

		assertThat(context.packageFiles()).containsEntry("SKILL.md", "# Demo");
		assertThat(context.packageFiles()).containsEntry("scripts/setup.sh", "echo setup");
	}

	@Test
	@DisplayName("AC-S147-PACKAGE-FILES: canonical constructor preserves all package text files")
	@Tag("AC-S147-PACKAGE-FILES")
	void canonicalConstructorPreservesAllPackageTextFiles() {
		var context = new ScanContext(
				"skill-1",
				"1.0.0",
				Map.of("name", "demo"),
				"# Demo",
				Map.of("scripts/setup.sh", "echo setup"),
				Map.of(
						"SKILL.md", "# Demo",
						"references/prompt.md", "prompt",
						"assets/install.sh", "echo install",
						"scripts/setup.sh", "echo setup"),
				List.of("SKILL.md", "references/prompt.md", "assets/install.sh", "scripts/setup.sh"),
				List.of());

		assertThat(context.packageFiles()).containsKeys(
				"SKILL.md",
				"references/prompt.md",
				"assets/install.sh",
				"scripts/setup.sh");
	}
}
