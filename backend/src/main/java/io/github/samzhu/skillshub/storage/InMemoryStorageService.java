package io.github.samzhu.skillshub.storage;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 記憶體儲存服務（本機開發用）。
 *
 * <p>當 {@code spring.cloud.gcp.storage.enabled=false}（本機預設值）時啟用，
 * 以 {@link ConcurrentHashMap} 模擬 GCS 行為，無需 GCP Application Default Credentials。
 *
 * <p>此實作僅在 JVM 生命週期內持久，重啟後資料消失，不適用於生產環境。
 * 部署至 GCP Cloud Run 時需設定環境變數
 * {@code SPRING_CLOUD_GCP_STORAGE_ENABLED=true}，以啟用 {@link GcsStorageService}。
 */
@Service
@ConditionalOnProperty(name = "spring.cloud.gcp.storage.enabled", havingValue = "false")
public class InMemoryStorageService implements StorageService {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final Map<String, byte[]> store = new ConcurrentHashMap<>();

	/**
	 * 將位元組資料存入記憶體 Map。
	 *
	 * @param path 儲存路徑（key）
	 * @param data zip 位元組內容
	 */
	@Override
	public void upload(String path, byte[] data) {
		store.put(path, data);
		log.atInfo()
				.addKeyValue("path", path)
				.addKeyValue("sizeBytes", data.length)
				.log("[InMemory] uploaded");
	}

	/**
	 * 從記憶體 Map 取出資料。
	 *
	 * @param path 儲存路徑（key）
	 * @return 位元組陣列
	 * @throws RuntimeException 路徑不存在時拋出（模擬 GCS 找不到物件的行為）
	 */
	@Override
	public byte[] download(String path) {
		var data = store.get(path);
		if (data == null) {
			log.atWarn().addKeyValue("path", path).log("[InMemory] path not found");
			throw new RuntimeException("InMemoryStorage: not found: " + path);
		}
		log.atInfo().addKeyValue("path", path).log("[InMemory] downloaded");
		return data;
	}

	/**
	 * 從記憶體 Map 刪除資料。
	 *
	 * @param path 要刪除的儲存路徑（key）
	 */
	@Override
	public void delete(String path) {
		store.remove(path);
		log.atInfo().addKeyValue("path", path).log("[InMemory] deleted");
	}

}
