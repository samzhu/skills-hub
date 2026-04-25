package io.github.samzhu.skillshub.storage;

import java.lang.invoke.MethodHandles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;

/**
 * Google Cloud Storage（GCS）儲存服務實作。
 *
 * <p>當 Spring profile 包含 {@code gcp} 時啟用，作為 {@link StorageService} 的 GCP 實作，
 * 負責技能套件 zip 的存取。本機開發請使用 {@code local} profile，
 * 對應 {@link FileSystemStorageService}。
 *
 * <p>目標 GCS Bucket 由設定值 {@code skillshub.storage.bucket} 決定，
 * 預設為 {@code skillshub-packages}。
 */
@Service
@Profile("gcp")
public class GcsStorageService implements StorageService {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final Storage storage;
	private final String bucket;

	/**
	 * 建構 GCS 儲存服務。
	 *
	 * @param storage GCP Storage 用戶端（由 Spring Cloud GCP 自動配置注入）
	 * @param bucket  目標 Bucket 名稱，預設 {@code skillshub-packages}
	 */
	public GcsStorageService(Storage storage, @Value("${skillshub.storage.bucket:skillshub-packages}") String bucket) {
		this.storage = storage;
		this.bucket = bucket;
	}

	/**
	 * 將 zip 資料上傳至 GCS，Content-Type 設為 {@code application/zip}。
	 *
	 * @param path 目標物件路徑（相對於 Bucket 根目錄）
	 * @param data zip 位元組內容
	 */
	@Override
	public void upload(String path, byte[] data) {
		log.atInfo()
				.addKeyValue("bucket", bucket)
				.addKeyValue("path", path)
				.addKeyValue("sizeBytes", data.length)
				.log("Uploading object to GCS");
		var blobId = BlobId.of(bucket, path);
		var blobInfo = BlobInfo.newBuilder(blobId).setContentType("application/zip").build();
		storage.create(blobInfo, data);
	}

	/**
	 * 從 GCS 下載指定路徑的物件內容。
	 *
	 * @param path 要下載的物件路徑
	 * @return 物件的位元組陣列
	 */
	@Override
	public byte[] download(String path) {
		log.atInfo()
				.addKeyValue("bucket", bucket)
				.addKeyValue("path", path)
				.log("Downloading object from GCS");
		return storage.readAllBytes(BlobId.of(bucket, path));
	}

	/**
	 * 刪除 GCS 上指定路徑的物件。
	 *
	 * @param path 要刪除的物件路徑
	 */
	@Override
	public void delete(String path) {
		log.atInfo()
				.addKeyValue("bucket", bucket)
				.addKeyValue("path", path)
				.log("Deleting object from GCS");
		storage.delete(BlobId.of(bucket, path));
	}

}
