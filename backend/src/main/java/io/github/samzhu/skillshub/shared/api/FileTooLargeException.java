package io.github.samzhu.skillshub.shared.api;

/**
 * S074：被請求的單一檔案超過可預覽上限（1 MB）。
 *
 * <p>由 {@code FileBrowserService.readFile} 拋出；GlobalExceptionHandler 將其轉成
 * HTTP 413 PAYLOAD_TOO_LARGE。與 {@code MaxUploadSizeExceededException}（multipart
 * 上傳上限）區分：此例外是 read-side preview 上限，user-facing 對象是 SkillDetailPage
 * 檔案瀏覽器，不是 PublishPage。
 */
public class FileTooLargeException extends RuntimeException {

	private final long actualSize;
	private final long maxSize;

	public FileTooLargeException(String path, long actualSize, long maxSize) {
		super("File '" + path + "' is " + actualSize + " bytes, exceeds preview limit of " + maxSize + " bytes");
		this.actualSize = actualSize;
		this.maxSize = maxSize;
	}

	public long getActualSize() { return actualSize; }
	public long getMaxSize() { return maxSize; }
}
