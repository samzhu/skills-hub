package io.github.samzhu.skillshub.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class PackageServiceTextFilesTest {

	private final PackageService service = new PackageService();

	@Test
	@DisplayName("AC-S147-PACKAGE-FILES: package service extracts all UTF-8 zip entries")
	@Tag("AC-S147-PACKAGE-FILES")
	void packageServiceExtractsAllUtf8ZipEntries() throws Exception {
		var zip = zipBytes(Map.of(
				"SKILL.md", "# Demo",
				"references/prompt.md", "load instructions",
				"assets/install.sh", "echo install",
				"scripts/setup.sh", "echo setup"));

		var files = service.extractTextFiles(zip);

		assertThat(files).containsEntry("SKILL.md", "# Demo");
		assertThat(files).containsEntry("references/prompt.md", "load instructions");
		assertThat(files).containsEntry("assets/install.sh", "echo install");
		assertThat(files).containsEntry("scripts/setup.sh", "echo setup");
	}

	@Test
	@DisplayName("AC-S147-PACKAGE-FILES: binary zip entry is skipped")
	@Tag("AC-S147-PACKAGE-FILES")
	void binaryZipEntryIsSkipped() throws Exception {
		var baos = new ByteArrayOutputStream();
		try (var zos = new ZipOutputStream(baos)) {
			zos.putNextEntry(new ZipEntry("SKILL.md"));
			zos.write("# Demo".getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();
			zos.putNextEntry(new ZipEntry("assets/image.bin"));
			zos.write(new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x01});
			zos.closeEntry();
		}

		var files = service.extractTextFiles(baos.toByteArray());

		assertThat(files).containsOnlyKeys("SKILL.md");
	}

	private static byte[] zipBytes(Map<String, String> entries) throws Exception {
		var baos = new ByteArrayOutputStream();
		try (var zos = new ZipOutputStream(baos)) {
			for (var entry : entries.entrySet()) {
				zos.putNextEntry(new ZipEntry(entry.getKey()));
				zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				zos.closeEntry();
			}
		}
		return baos.toByteArray();
	}
}
