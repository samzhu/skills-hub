package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("S186")
class VectorStoreRuntimeRemovalTest {

    private static final String LEGACY_TABLE = "vector" + "_store";
    private static final String LEGACY_CLASS = "Skillshub" + "PgVectorStore";

    @Test
    @DisplayName("AC-S186-6: runtime and active tests no longer depend on legacy vector table")
    void runtimeAndActiveTestsNoLongerDependOnLegacyVectorTable() throws Exception {
        var hits = Stream.of(Path.of("src/main/java"), Path.of("src/test/java"))
                .flatMap(VectorStoreRuntimeRemovalTest::walk)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(VectorStoreRuntimeRemovalTest::containsLegacyReference)
                .filter(path -> !path.toString().contains("src/test/java/io/github/samzhu/skillshub/db/"))
                .map(Path::toString)
                .sorted()
                .toList();

        assertThat(hits).isEmpty();
    }

    private static boolean containsLegacyReference(Path path) {
        try {
            var source = Files.readString(path);
            return source.contains(LEGACY_TABLE) || source.contains(LEGACY_CLASS);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Stream<Path> walk(Path root) {
        try {
            return Files.walk(root);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
