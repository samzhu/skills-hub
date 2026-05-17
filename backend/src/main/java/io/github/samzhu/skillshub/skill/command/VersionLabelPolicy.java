package io.github.samzhu.skillshub.skill.command;

import java.util.Collection;
import java.util.Objects;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves S188 version labels for publish commands.
 *
 * @see SkillCommandService
 */
@Component
public final class VersionLabelPolicy {

	public static final String INVALID_VERSION_MESSAGE =
			"Version must be 1-20 characters and contain only letters, numbers, dot, underscore, or hyphen";

	private static final String INITIAL_VERSION = "1";

	// S188: safe for GCS path segment; custom label is not semver-only.
	private static final Pattern SAFE_LABEL =
			Pattern.compile("^(?!0$)(?!.*\\.\\.)(?!.*[\\\\/\\s])[A-Za-z0-9._-]{1,20}$");
	private static final Pattern AUTO_LABEL = Pattern.compile("^[1-9][0-9]*$");

	public String initialOrRequested(@Nullable String requested) {
		if (isBlank(requested)) {
			return INITIAL_VERSION;
		}
		return validateRequested(requested);
	}

	public String nextOrRequested(@Nullable String requested, Collection<String> existingVersions) {
		if (!isBlank(requested)) {
			return validateRequested(requested);
		}
		var next = existingVersions.stream()
				.filter(Objects::nonNull)
				.filter(AUTO_LABEL.asMatchPredicate())
				.mapToInt(Integer::parseInt)
				.max()
				.orElse(0) + 1;
		return Integer.toString(next);
	}

	public String validateRequested(String requested) {
		var trimmed = requested.trim();
		if (!SAFE_LABEL.matcher(trimmed).matches()) {
			throw new IllegalArgumentException(INVALID_VERSION_MESSAGE);
		}
		return trimmed;
	}

	private static boolean isBlank(@Nullable String value) {
		return value == null || value.isBlank();
	}
}
