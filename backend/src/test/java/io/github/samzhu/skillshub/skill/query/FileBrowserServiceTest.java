package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.shared.api.FileTooLargeException;
import io.github.samzhu.skillshub.shared.api.SkillSuspendedException;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersion;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import io.github.samzhu.skillshub.storage.StorageService;

/**
 * S074：FileBrowserService 純函數單元測試（zip-slip 防禦 + MIME inference）+
 * V03 ratchet：listFiles / readFile instance methods 用 Mockito 補齊 47 missed lines。
 *
 * <p>整合行為（SpringBoot autoconfig + 真 storage）留 e2e smoke 覆蓋；本檔鎖純邏輯 +
 * 各分支 fail-fast 守則（SUSPENDED / not found / oversize / zip-slip）。
 */
class FileBrowserServiceTest {

	private final SkillRepository skillRepo = mock(SkillRepository.class);
	private final SkillVersionRepository versionRepo = mock(SkillVersionRepository.class);
	private final StorageService storage = mock(StorageService.class);
	private final FileBrowserService service = new FileBrowserService(skillRepo, versionRepo, storage);

	// ─────────────────────────────────────────────────────────────
	// Pure helper tests（既有；保留以鎖 path-traversal + MIME 邏輯）
	// ─────────────────────────────────────────────────────────────

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

	// ─────────────────────────────────────────────────────────────
	// V03 ratchet — listFiles / readFile instance methods（mock-based）
	// ─────────────────────────────────────────────────────────────

	@Test
	@DisplayName("listFiles: skill 不存在 → NoSuchElementException")
	void listFiles_skillNotFound_throws() {
		when(skillRepo.findById("missing")).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.listFiles("missing"))
				.isInstanceOf(NoSuchElementException.class)
				.hasMessageContaining("Skill not found");
	}

	@Test
	@DisplayName("listFiles: SUSPENDED skill → SkillSuspendedException")
	void listFiles_suspended_throws() {
		when(skillRepo.findById("s-1")).thenReturn(Optional.of(skillWithStatus("s-1", "SUSPENDED")));
		assertThatThrownBy(() -> service.listFiles("s-1"))
				.isInstanceOf(SkillSuspendedException.class);
	}

	@Test
	@DisplayName("listFiles: skill 存在但無已發佈版本 → NoSuchElementException")
	void listFiles_noVersions_throws() {
		when(skillRepo.findById("s-1")).thenReturn(Optional.of(skillWithStatus("s-1", "PUBLISHED")));
		when(versionRepo.findBySkillIdOrderByPublishedAtDesc("s-1")).thenReturn(List.of());
		assertThatThrownBy(() -> service.listFiles("s-1"))
				.isInstanceOf(NoSuchElementException.class)
				.hasMessageContaining("No versions found");
	}

	@Test
	@DisplayName("listFiles: 正常 zip → 列出 entries（過濾 directory + zip-slip）")
	void listFiles_normalZip_returnsEntries() throws Exception {
		seedHappyPath("s-1", zipBytes(
				new TestEntry("SKILL.md", "# title".getBytes()),
				new TestEntry("references/", new byte[0]),  // directory entry → 過濾
				new TestEntry("../evil.txt", "bad".getBytes()),  // zip-slip → 過濾
				new TestEntry("scripts/run.sh", "echo hi".getBytes())
		));

		var entries = service.listFiles("s-1");

		assertThat(entries).hasSize(2);
		assertThat(entries).extracting(FileEntryResponse::path)
				.containsExactly("SKILL.md", "scripts/run.sh");
		assertThat(entries.get(0).type()).isEqualTo("text/markdown");
	}

	@Test
	@DisplayName("readFile: zip-slip path → IllegalArgumentException")
	void readFile_unsafePath_throws() {
		assertThatThrownBy(() -> service.readFile("s-1", "../escape"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("zip-slip");
	}

	@Test
	@DisplayName("readFile: SUSPENDED skill → SkillSuspendedException（fail-fast 早於 storage）")
	void readFile_suspended_throws() {
		when(skillRepo.findById("s-1")).thenReturn(Optional.of(skillWithStatus("s-1", "SUSPENDED")));
		assertThatThrownBy(() -> service.readFile("s-1", "SKILL.md"))
				.isInstanceOf(SkillSuspendedException.class);
	}

	@Test
	@DisplayName("readFile: 找到匹配 entry → 回傳內容 + MIME")
	void readFile_match_returnsPreview() throws Exception {
		seedHappyPath("s-1", zipBytes(
				new TestEntry("SKILL.md", "hello".getBytes())
		));

		var preview = service.readFile("s-1", "SKILL.md");

		assertThat(new String(preview.content())).isEqualTo("hello");
		assertThat(preview.contentType()).isEqualTo("text/markdown");
	}

	@Test
	@DisplayName("readFile: 找不到 entry → NoSuchElementException")
	void readFile_entryMissing_throws() throws Exception {
		seedHappyPath("s-1", zipBytes(
				new TestEntry("OTHER.md", "x".getBytes())
		));
		assertThatThrownBy(() -> service.readFile("s-1", "SKILL.md"))
				.isInstanceOf(NoSuchElementException.class)
				.hasMessageContaining("not found");
	}

	@Test
	@DisplayName("readFile: 大小超過 1MB → FileTooLargeException")
	void readFile_oversize_throws() throws Exception {
		// 製造 > 1MB 的 entry：1.1MB binary，不可壓縮（隨機 byte）避免 zip 把 size 壓回 < 1MB
		var huge = new byte[(int) (FileBrowserService.MAX_FILE_SIZE + 1024)];
		// 隨機 fill → 不壓縮（純 0x00 fill 會被 deflate 壓到 < 1MB）
		java.util.concurrent.ThreadLocalRandom.current().nextBytes(huge);
		seedHappyPath("s-1", zipBytes(new TestEntry("big.bin", huge)));

		assertThatThrownBy(() -> service.readFile("s-1", "big.bin"))
				.isInstanceOf(FileTooLargeException.class);
	}

	// ─────────────────────────────────────────────────────────────
	// Test helpers
	// ─────────────────────────────────────────────────────────────

	/** 建 minimal Skill aggregate with given status — 用 fromRow factory 繞過 invariant 檢查。 */
	private static Skill skillWithStatus(String id, String status) {
		return Skill.fromRow(
				id, "name-" + id, "desc", "alice", "testing",
				null, null, status, 0L,
				Instant.now(), Instant.now(),
				List.of(), null);
	}

	/** Happy-path mock seed：skill PUBLISHED + 一個 version + storage 回 zipBytes。 */
	private void seedHappyPath(String skillId, byte[] zipBytes) {
		when(skillRepo.findById(skillId)).thenReturn(Optional.of(skillWithStatus(skillId, "PUBLISHED")));
		var version = mock(SkillVersion.class);
		when(version.getStoragePath()).thenReturn("gs://test/" + skillId + ".zip");
		when(versionRepo.findBySkillIdOrderByPublishedAtDesc(skillId)).thenReturn(List.of(version));
		when(storage.download(any(String.class))).thenReturn(zipBytes);
	}

	/** 建合成 zip：caller 指定 entries（path + bytes）。 */
	private static byte[] zipBytes(TestEntry... entries) throws Exception {
		var baos = new ByteArrayOutputStream();
		try (var zos = new ZipOutputStream(baos)) {
			for (var e : entries) {
				zos.putNextEntry(new ZipEntry(e.path));
				if (e.bytes.length > 0) zos.write(e.bytes);
				zos.closeEntry();
			}
		}
		return baos.toByteArray();
	}

	private record TestEntry(String path, byte[] bytes) {}
}
