package io.github.samzhu.skillshub.skill.query;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.samzhu.skillshub.shared.api.FileTooLargeException;
import io.github.samzhu.skillshub.shared.api.SkillSuspendedException;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillStatus;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import io.github.samzhu.skillshub.storage.StorageService;

/**
 * S074：Skill zip 檔案瀏覽器服務。
 *
 * <p>提供 SkillDetailPage 在不下載整包 zip 的前提下，瀏覽其內個別檔案的能力：
 * <ul>
 *   <li>{@link #listFiles}：列出最新 PUBLISHED 版本 zip 內所有 entries 的 metadata（path / size / MIME）</li>
 *   <li>{@link #readFile}：讀取單一 entry 內容（含 zip-slip 防禦 + 1 MB 預覽上限）</li>
 * </ul>
 *
 * <p>read-only metadata：本 service 不呼叫 {@code Skill.recordDownload}，
 * 不觸發 {@code SkillDownloadedEvent}（瀏覽 ≠ 下載）。
 *
 * <p>SUSPENDED guard 與 {@link SkillQueryService#downloadAndRecord} 一致 — fail-fast 在 storage
 * read 之前，避免無謂的 GCS bandwidth；錯誤 mapping 也共用 {@link SkillSuspendedException}
 * → 403 SKILL_SUSPENDED。
 */
@Service
public class FileBrowserService {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	/** S074：preview 單檔上限。超過此大小的檔案不在 UI 呈現（避免 server-side 大檔解壓 + 前端渲染負擔）。 */
	static final long MAX_FILE_SIZE = 1_048_576L;  // 1 MB

	private final SkillRepository skillRepo;
	private final SkillVersionRepository skillVersionRepo;
	private final StorageService storageService;

	public FileBrowserService(SkillRepository skillRepo,
			SkillVersionRepository skillVersionRepo,
			StorageService storageService) {
		this.skillRepo = skillRepo;
		this.skillVersionRepo = skillVersionRepo;
		this.storageService = storageService;
	}

	/**
	 * 列出 skill 最新版本 zip 內的所有 entries。
	 *
	 * <p>過濾：directory entries（zip 內以 {@code /} 結尾）/ zip-slip 違規路徑（{@code ..} 或開頭 {@code /}）
	 * 都跳過 + log warn。
	 *
	 * @throws NoSuchElementException skill 不存在或無已發佈版本
	 * @throws SkillSuspendedException skill 為 SUSPENDED
	 */
	public List<FileEntryResponse> listFiles(String skillId) {
		var zipBytes = loadZipBytes(skillId);
		var result = new ArrayList<FileEntryResponse>();
		try (var zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
			ZipEntry entry;
			while ((entry = zin.getNextEntry()) != null) {
				if (entry.isDirectory()) continue;
				var path = entry.getName();
				if (isUnsafePath(path)) {
					log.atWarn()
							.addKeyValue("skillId", skillId)
							.addKeyValue("path", path)
							.log("Zip entry has unsafe path, skipping (zip-slip defense)");
					continue;
				}
				result.add(new FileEntryResponse(path, entry.getSize(), inferMimeType(path)));
			}
		} catch (IOException e) {
			throw new IllegalStateException("Failed to enumerate zip entries: " + e.getMessage(), e);
		}
		return result;
	}

	/**
	 * 讀取 skill 最新版本 zip 內的單一 entry 內容。
	 *
	 * @param path zip entry 路徑（須由 caller decode；本 service 額外做 zip-slip + 大小防禦）
	 * @return entry 內容位元組
	 * @throws IllegalArgumentException path 含 {@code ..} 或開頭 {@code /}（zip-slip）
	 * @throws NoSuchElementException entry 不存在
	 * @throws FileTooLargeException entry 超過 {@link #MAX_FILE_SIZE}
	 * @throws SkillSuspendedException skill 為 SUSPENDED
	 */
	public FilePreview readFile(String skillId, String path) {
		if (isUnsafePath(path)) {
			throw new IllegalArgumentException("Invalid file path (zip-slip defense): " + path);
		}
		var zipBytes = loadZipBytes(skillId);
		try (var zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
			ZipEntry entry;
			while ((entry = zin.getNextEntry()) != null) {
				if (entry.isDirectory() || !entry.getName().equals(path)) continue;
				if (entry.getSize() > MAX_FILE_SIZE) {
					throw new FileTooLargeException(path, entry.getSize(), MAX_FILE_SIZE);
				}
				// 以 baos buffer 讀取（先用 entry.getSize() 預估，但壓縮 entry 可能 size=-1）
				var baos = new java.io.ByteArrayOutputStream();
				var buf = new byte[8192];
				int read;
				long total = 0;
				while ((read = zin.read(buf)) > 0) {
					total += read;
					if (total > MAX_FILE_SIZE) {
						throw new FileTooLargeException(path, total, MAX_FILE_SIZE);
					}
					baos.write(buf, 0, read);
				}
				return new FilePreview(baos.toByteArray(), inferMimeType(path));
			}
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read zip entry: " + e.getMessage(), e);
		}
		throw new NoSuchElementException("File not found in skill package: " + path);
	}

	/**
	 * 共通 fail-fast：findById + status guard + storage download。
	 */
	private byte[] loadZipBytes(String skillId) {
		var skill = skillRepo.findById(skillId)
				.orElseThrow(() -> new NoSuchElementException("Skill not found: " + skillId));
		if (skill.getStatus() == SkillStatus.SUSPENDED) {
			throw new SkillSuspendedException(skillId);
		}
		var versions = skillVersionRepo.findBySkillIdOrderByPublishedAtDesc(skillId);
		if (versions.isEmpty()) {
			throw new NoSuchElementException("No versions found for skill: " + skillId);
		}
		return storageService.download(versions.getFirst().getStoragePath());
	}

	/**
	 * Zip-slip 防禦：拒收絕對路徑或含 {@code ..} 的相對路徑。
	 * 測試覆蓋 AC-5 path traversal 場景。
	 */
	static boolean isUnsafePath(String path) {
		if (path == null || path.isBlank()) return true;
		if (path.startsWith("/") || path.startsWith("\\")) return true;
		// 拒 ".." 段（含 Windows reverse slash 變體）
		var normalized = path.replace('\\', '/');
		for (var segment : normalized.split("/")) {
			if ("..".equals(segment)) return true;
		}
		return false;
	}

	/**
	 * 副檔名 → MIME inference。覆蓋 SKILL.md / 文字類 / 常見 binary。
	 * 未列出的副檔名退回 {@code application/octet-stream}（前端決定是否預覽）。
	 */
	static String inferMimeType(String path) {
		var lower = path.toLowerCase();
		if (lower.endsWith(".md")) return "text/markdown";
		if (lower.endsWith(".txt") || lower.endsWith(".log")) return "text/plain";
		if (lower.endsWith(".json")) return "application/json";
		if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "application/yaml";
		if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript";
		if (lower.endsWith(".ts")) return "application/typescript";
		if (lower.endsWith(".py")) return "text/x-python";
		if (lower.endsWith(".sh")) return "application/x-sh";
		if (lower.endsWith(".toml")) return "application/toml";
		if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
		if (lower.endsWith(".css")) return "text/css";
		if (lower.endsWith(".xml")) return "application/xml";
		if (lower.endsWith(".csv")) return "text/csv";
		if (lower.endsWith(".png")) return "image/png";
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
		if (lower.endsWith(".gif")) return "image/gif";
		if (lower.endsWith(".svg")) return "image/svg+xml";
		return "application/octet-stream";
	}

	/**
	 * Internal carrier for {@link #readFile} — bytes 與推斷的 Content-Type 一起回傳，
	 * 避免 controller 重複呼叫 {@link #inferMimeType}。
	 */
	public record FilePreview(byte[] content, String contentType) {}
}
