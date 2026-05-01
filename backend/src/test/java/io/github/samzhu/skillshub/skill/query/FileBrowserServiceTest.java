package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * S074：FileBrowserService 純函數單元測試（zip-slip 防禦 + MIME inference）。
 *
 * <p>整合行為（findById + storage download + zip enumerate）留 SpringBoot integration test
 * 或 e2e smoke 覆蓋；本檔只鎖路徑安全與 MIME 推斷的 edge cases。
 */
class FileBrowserServiceTest {

	@Test
	@DisplayName("S074 AC-5: zip-slip 路徑（含 ..）→ unsafe")
	@Tag("S074")
	void rejectsPathTraversal() {
		assertThat(FileBrowserService.isUnsafePath("../etc/passwd")).isTrue();
		assertThat(FileBrowserService.isUnsafePath("references/../../etc/passwd")).isTrue();
		assertThat(FileBrowserService.isUnsafePath("dir/..")).isTrue();
	}

	@Test
	@DisplayName("S074 AC-5: 絕對路徑（開頭 /）→ unsafe")
	@Tag("S074")
	void rejectsAbsolutePath() {
		assertThat(FileBrowserService.isUnsafePath("/etc/passwd")).isTrue();
		assertThat(FileBrowserService.isUnsafePath("\\windows\\system32")).isTrue();
	}

	@Test
	@DisplayName("S074 AC-5: 空字串或 null → unsafe")
	@Tag("S074")
	void rejectsBlankPath() {
		assertThat(FileBrowserService.isUnsafePath(null)).isTrue();
		assertThat(FileBrowserService.isUnsafePath("")).isTrue();
		assertThat(FileBrowserService.isUnsafePath("   ")).isTrue();
	}

	@Test
	@DisplayName("S074 AC-5: 合法相對路徑 → safe")
	@Tag("S074")
	void acceptsSafeRelativePath() {
		assertThat(FileBrowserService.isUnsafePath("SKILL.md")).isFalse();
		assertThat(FileBrowserService.isUnsafePath("references/lookup.md")).isFalse();
		assertThat(FileBrowserService.isUnsafePath("scripts/bootstrap.sh")).isFalse();
		assertThat(FileBrowserService.isUnsafePath("a/b/c/file.txt")).isFalse();
	}

	@Test
	@DisplayName("S074: MIME inference — 文字類常見副檔名")
	@Tag("S074")
	void inferMimeTextual() {
		assertThat(FileBrowserService.inferMimeType("SKILL.md")).isEqualTo("text/markdown");
		assertThat(FileBrowserService.inferMimeType("notes.txt")).isEqualTo("text/plain");
		assertThat(FileBrowserService.inferMimeType("config.json")).isEqualTo("application/json");
		assertThat(FileBrowserService.inferMimeType("conf.yaml")).isEqualTo("application/yaml");
		assertThat(FileBrowserService.inferMimeType("script.py")).isEqualTo("text/x-python");
		assertThat(FileBrowserService.inferMimeType("install.sh")).isEqualTo("application/x-sh");
	}

	@Test
	@DisplayName("S074: MIME inference — 未知副檔名 fallback application/octet-stream")
	@Tag("S074")
	void inferMimeUnknown() {
		assertThat(FileBrowserService.inferMimeType("data.bin")).isEqualTo("application/octet-stream");
		assertThat(FileBrowserService.inferMimeType("noextension")).isEqualTo("application/octet-stream");
		assertThat(FileBrowserService.inferMimeType("blob.unknown")).isEqualTo("application/octet-stream");
	}

	@Test
	@DisplayName("S074: MIME inference — 大小寫不敏感")
	@Tag("S074")
	void inferMimeCaseInsensitive() {
		assertThat(FileBrowserService.inferMimeType("README.MD")).isEqualTo("text/markdown");
		assertThat(FileBrowserService.inferMimeType("Config.JSON")).isEqualTo("application/json");
	}
}
