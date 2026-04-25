package io.github.samzhu.skillshub.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 本機檔案系統儲存服務（local 環境用）。
 *
 * <p>當 Spring profile 包含 {@code local} 時啟用，以本機目錄模擬 GCS 行為，
 * 無需 GCP Application Default Credentials，適用於本機開發與地端部署。
 *
 * <p>預設儲存目錄為 {@code ./storage-local}（相對於工作目錄），
 * 可透過 {@code skillshub.storage.local-path} 屬性覆蓋。
 *
 * <p>目錄結構與 GCS 物件路徑保持一致（斜線分隔），
 * 方便切換至 GCS 時不需修改業務邏輯。
 *
 * @see GcsStorageService GCP 生產環境實作
 */
@Service
@Profile("local")
public class FileSystemStorageService implements StorageService {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final Path baseDir;

	/**
	 * 建構本機檔案系統儲存服務，並確保基底目錄存在。
	 *
	 * @param baseDirPath 儲存根目錄路徑，預設為 {@code ./storage-local}
	 */
	public FileSystemStorageService(
			@Value("${skillshub.storage.local-path:./storage-local}") String baseDirPath) {
		this.baseDir = Path.of(baseDirPath).toAbsolutePath().normalize();
		try {
			Files.createDirectories(baseDir);
		} catch (IOException e) {
			throw new UncheckedIOException("無法建立本機儲存目錄: " + baseDir, e);
		}
		log.atInfo().addKeyValue("baseDir", baseDir).log("[FileSystem] 本機儲存服務已初始化");
	}

	/**
	 * 將位元組資料寫入本機檔案，自動建立中間目錄。
	 *
	 * @param path 相對於 baseDir 的儲存路徑（例如 {@code skills/my-skill-1.0.0.zip}）
	 * @param data zip 位元組內容
	 */
	@Override
	public void upload(String path, byte[] data) {
		var target = resolve(path);
		try {
			Files.createDirectories(target.getParent());
			Files.write(target, data);
			log.atInfo()
					.addKeyValue("path", path)
					.addKeyValue("sizeBytes", data.length)
					.addKeyValue("target", target)
					.log("[FileSystem] 上傳完成");
		} catch (IOException e) {
			throw new UncheckedIOException("上傳失敗: " + path, e);
		}
	}

	/**
	 * 從本機讀取指定路徑的檔案內容。
	 *
	 * @param path 相對於 baseDir 的儲存路徑
	 * @return 檔案的位元組陣列
	 * @throws RuntimeException 檔案不存在時拋出（模擬 GCS 物件不存在的行為）
	 */
	@Override
	public byte[] download(String path) {
		var target = resolve(path);
		if (!Files.exists(target)) {
			log.atWarn().addKeyValue("path", path).log("[FileSystem] 找不到檔案");
			throw new RuntimeException("FileSystemStorage: 找不到: " + path);
		}
		try {
			var data = Files.readAllBytes(target);
			log.atInfo()
					.addKeyValue("path", path)
					.addKeyValue("sizeBytes", data.length)
					.log("[FileSystem] 下載完成");
			return data;
		} catch (IOException e) {
			throw new UncheckedIOException("下載失敗: " + path, e);
		}
	}

	/**
	 * 刪除本機指定路徑的檔案；檔案不存在時靜默忽略。
	 *
	 * @param path 相對於 baseDir 的儲存路徑
	 */
	@Override
	public void delete(String path) {
		var target = resolve(path);
		try {
			boolean deleted = Files.deleteIfExists(target);
			log.atInfo()
					.addKeyValue("path", path)
					.addKeyValue("deleted", deleted)
					.log("[FileSystem] 刪除完成");
		} catch (IOException e) {
			throw new UncheckedIOException("刪除失敗: " + path, e);
		}
	}

	/**
	 * 將相對路徑解析為絕對 Path，並防止路徑穿越攻擊（path traversal）。
	 */
	private Path resolve(String path) {
		var resolved = baseDir.resolve(path).normalize();
		if (!resolved.startsWith(baseDir)) {
			throw new SecurityException("非法路徑（路徑穿越）: " + path);
		}
		return resolved;
	}

}
