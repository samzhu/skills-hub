package io.github.samzhu.skillshub.storage;

/**
 * 物件儲存服務介面：技能套件（zip）的上傳、下載與刪除操作。
 *
 * <p>透過 Spring Profile 切換底層實作，無需修改業務邏輯：
 * <ul>
 *   <li><b>local profile</b>：{@link FileSystemStorageService}（本機目錄，無需 GCP 憑證）</li>
 *   <li><b>gcp profile</b>：{@link GcsStorageService}（Google Cloud Storage）</li>
 * </ul>
 */
public interface StorageService {

	/**
	 * 將位元組資料上傳至指定路徑。
	 *
	 * @param path 儲存路徑（例如 {@code "skills/my-skill-1.0.0.zip"}）
	 * @param data 要上傳的位元組陣列
	 */
	void upload(String path, byte[] data);

	/**
	 * 從指定路徑下載資料。
	 *
	 * @param path 儲存路徑
	 * @return 下載的位元組陣列
	 */
	byte[] download(String path);

	/**
	 * 刪除指定路徑的物件。
	 *
	 * @param path 要刪除的儲存路徑
	 */
	void delete(String path);

}
