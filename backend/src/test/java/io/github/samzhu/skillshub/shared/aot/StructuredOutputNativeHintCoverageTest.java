package io.github.samzhu.skillshub.shared.aot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class StructuredOutputNativeHintCoverageTest {

	private static final Path MAIN_JAVA = Path.of("src/main/java");
	private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+);");
	private static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+([\\w.]+);");
	private static final Pattern TOP_LEVEL_TYPE = Pattern.compile("(?m)^public\\s+(?:class|record|interface|enum)\\s+(\\w+)");
	private static final Pattern STRUCTURED_ENTITY_TARGET =
			Pattern.compile("\\.entity\\(\\s*([\\w.]+)\\.class\\s*\\)");
	private static final Pattern BEAN_OUTPUT_CONVERTER_TARGET =
			Pattern.compile("new\\s+BeanOutputConverter\\s*<>\\s*\\(\\s*([\\w.]+)\\.class\\s*\\)");
	private static final Pattern REGISTER_REFLECTION_TARGET =
			Pattern.compile("@RegisterReflectionForBinding\\s*\\((.*?)\\)", Pattern.DOTALL);
	private static final Pattern CLASS_TOKEN = Pattern.compile("([A-Z][\\w]*(?:\\.[A-Z][\\w]*)*)\\.class");

	@Test
	@Tag("AC-S173-6")
	@DisplayName("AC-S173-6: guard knows current structured output targets")
	void guardKnowsCurrentStructuredOutputTargets() throws IOException {
		var targets = structuredOutputTargets();

		assertThat(targets).contains(
				"io.github.samzhu.skillshub.score.judge.JudgeResponse",
				"io.github.samzhu.skillshub.security.scan.engines.LlmJudgement");
	}

	@Test
	@Tag("AC-S173-6")
	@DisplayName("AC-S173-6: all structured output binding targets have RegisterReflectionForBinding hints")
	void allStructuredOutputBindingTargetsHaveRegisterReflectionForBindingHints() throws IOException {
		var targets = structuredOutputTargets();
		var hinted = registerReflectionForBindingTargets();

		assertThat(hinted)
				.as("Every ChatClient.entity(...) or BeanOutputConverter<>(...) target must be registered for native binding")
				.containsAll(targets);
	}

	private static Set<String> structuredOutputTargets() throws IOException {
		var targets = new LinkedHashSet<String>();
		for (var source : javaSources()) {
			var content = Files.readString(source);
			var context = SourceContext.from(content);
			addMatches(targets, STRUCTURED_ENTITY_TARGET, content, context);
			addMatches(targets, BEAN_OUTPUT_CONVERTER_TARGET, content, context);
		}
		return targets;
	}

	private static Set<String> registerReflectionForBindingTargets() throws IOException {
		var targets = new LinkedHashSet<String>();
		for (var source : javaSources()) {
			var content = Files.readString(source);
			var context = SourceContext.from(content);
			var annotationMatcher = REGISTER_REFLECTION_TARGET.matcher(content);
			while (annotationMatcher.find()) {
				var classMatcher = CLASS_TOKEN.matcher(annotationMatcher.group(1));
				while (classMatcher.find()) {
					targets.add(context.resolve(classMatcher.group(1), content));
				}
			}
		}
		return targets;
	}

	private static void addMatches(Set<String> targets, Pattern pattern, String content, SourceContext context) {
		var matcher = pattern.matcher(content);
		while (matcher.find()) {
			targets.add(context.resolve(matcher.group(1), content));
		}
	}

	private static java.util.List<Path> javaSources() throws IOException {
		try (var paths = Files.walk(MAIN_JAVA)) {
			return paths
					.filter(path -> path.toString().endsWith(".java"))
					.sorted()
					.toList();
		}
	}

	private record SourceContext(String packageName, String topLevelType, Map<String, String> imports) {
		static SourceContext from(String content) {
			var packageName = matchFirst(PACKAGE, content);
			var topLevelType = matchFirst(TOP_LEVEL_TYPE, content);
			var imports = new HashMap<String, String>();
			var matcher = IMPORT.matcher(content);
			while (matcher.find()) {
				var imported = matcher.group(1);
				imports.put(imported.substring(imported.lastIndexOf('.') + 1), imported);
			}
			return new SourceContext(packageName, topLevelType, imports);
		}

		String resolve(String typeReference, String content) {
			var root = typeReference.contains(".")
					? typeReference.substring(0, typeReference.indexOf('.'))
					: typeReference;
			var suffix = typeReference.contains(".")
					? typeReference.substring(typeReference.indexOf('.'))
					: "";

			if (imports.containsKey(root)) {
				return imports.get(root) + suffix;
			}
			if (!suffix.isEmpty()) {
				return packageName + "." + typeReference;
			}
			if (topLevelType != null
					&& !root.equals(topLevelType)
					&& Pattern.compile("(?m)(?:class|record|interface|enum)\\s+" + Pattern.quote(root) + "\\b")
							.matcher(content)
							.find()) {
				return packageName + "." + topLevelType + "." + root;
			}
			return packageName + "." + typeReference;
		}

		private static String matchFirst(Pattern pattern, String content) {
			var matcher = pattern.matcher(content);
			return matcher.find() ? matcher.group(1) : null;
		}
	}
}
