package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VersionLabelPolicyTest {

	private final VersionLabelPolicy policy = new VersionLabelPolicy();

	@Test
	@DisplayName("AC-S188-1: initial blank version becomes 1")
	void initialBlankVersionBecomesOne() {
		assertThat(policy.initialOrRequested(null)).isEqualTo("1");
		assertThat(policy.initialOrRequested("   ")).isEqualTo("1");
	}

	@Test
	@DisplayName("AC-S188-2: blank next version uses max numeric plus one")
	void blankNextVersionUsesMaxNumericPlusOne() {
		var existing = List.of("1", "2", "2026.05-hotfix");

		assertThat(policy.nextOrRequested(null, existing)).isEqualTo("3");
		assertThat(policy.nextOrRequested("   ", existing)).isEqualTo("3");
	}

	@Test
	@DisplayName("AC-S188-3: custom version label is preserved")
	void customVersionLabelIsPreserved() {
		var existing = List.of("1", "2");

		assertThat(policy.initialOrRequested("2026.05-hotfix")).isEqualTo("2026.05-hotfix");
		assertThat(policy.nextOrRequested("release-1", existing)).isEqualTo("release-1");
		assertThat(policy.nextOrRequested("0.1.0", existing)).isEqualTo("0.1.0");
	}

	@Test
	@DisplayName("AC-S188-4: unsafe version label is rejected")
	void unsafeVersionLabelIsRejected() {
		assertThatThrownBy(() -> policy.initialOrRequested("../prod"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage(VersionLabelPolicy.INVALID_VERSION_MESSAGE);
		assertThatThrownBy(() -> policy.initialOrRequested("release 1"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage(VersionLabelPolicy.INVALID_VERSION_MESSAGE);
		assertThatThrownBy(() -> policy.initialOrRequested("0"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage(VersionLabelPolicy.INVALID_VERSION_MESSAGE);
		assertThatThrownBy(() -> policy.initialOrRequested("123456789012345678901"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage(VersionLabelPolicy.INVALID_VERSION_MESSAGE);
	}
}
