package io.github.samzhu.skillshub.skill.query;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S074：Skill zip 內檔案瀏覽 REST endpoint。
 *
 * <p>SkillDetailPage 用此兩個 endpoint 取代「下載整包再解壓」的 user flow：
 * <ul>
 *   <li>{@code GET /api/v1/skills/{id}/files} — 列出 entries</li>
 *   <li>{@code GET /api/v1/skills/{id}/files/{*path}} — 讀單一 entry 內容</li>
 * </ul>
 *
 * <p>ACL 守門：與 {@code SkillQueryController} 一致用 read 權限（公開 *:read 預設打開），
 * MVP permit-all 階段不啟用 {@code @PreAuthorize}。
 */
@RestController
@RequestMapping("/api/v1/skills/{id}/files")
public class FileBrowserController {

	private final FileBrowserService fileBrowserService;

	public FileBrowserController(FileBrowserService fileBrowserService) {
		this.fileBrowserService = fileBrowserService;
	}

	/** 列出 skill 最新版本 zip 內所有可預覽 entries。 */
	@GetMapping
	List<FileEntryResponse> list(@PathVariable String id) {
		return fileBrowserService.listFiles(id);
	}

	/**
	 * 讀單一 entry 內容。
	 *
	 * <p>{@code @PathVariable("path") String path} 搭配 {@code {*path}} pattern 捕獲整段子路徑（含 {@code /}）。
	 * Spring 6+ wildcard 變數匹配；service 端再做 zip-slip 防禦。Content-Type 來自 service 推斷的 MIME。
	 */
	@GetMapping("/{*path}")
	ResponseEntity<byte[]> read(@PathVariable String id, @PathVariable("path") String path) {
		// {*path} 會把 leading "/" 帶入；strip 掉以對齊 zip entry name 慣例
		var cleaned = path.startsWith("/") ? path.substring(1) : path;
		var preview = fileBrowserService.readFile(id, cleaned);
		var headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType(preview.contentType()));
		headers.setContentLength(preview.content().length);
		return new ResponseEntity<>(preview.content(), headers, 200);
	}
}
