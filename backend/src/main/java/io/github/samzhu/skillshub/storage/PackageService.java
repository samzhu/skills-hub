package io.github.samzhu.skillshub.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Component;

/**
 * 技能套件（zip）解析工具。
 *
 * <p>負責從符合 agentskills.io SKILL.md 規範的 zip 壓縮檔中
 * 提取關鍵檔案，供後續驗證與索引使用。
 */
@Component
public class PackageService {

	/**
	 * S053：將上傳檔 normalize 為「SKILL.md 在根目錄」的標準 zip。
	 *
	 * <p>三類上傳場景，輸出統一為 SKILL.md 位於 zip 根的標準結構（下載 / 安裝體驗一致）：
	 * <ol>
	 *   <li>zip — root SKILL.md：直接 pass-through（已是標準）</li>
	 *   <li>zip — subfolder/SKILL.md：偵測 wrapping folder（如 {@code sss/}）並 strip 全部 entry 的該前綴 repack</li>
	 *   <li>plain markdown 純文字檔（無 zip header）：包成單檔 zip 含 SKILL.md entry</li>
	 * </ol>
	 *
	 * <p>magic-byte 偵測（local file header {@code PK\x03\x04}）— 不信任 client Content-Type 或副檔名。
	 * normalize 後下游流程（extractSkillMd / storage / fileSize / 下載）都拿到一致結構。
	 *
	 * @param uploaded 上傳的原始位元組（zip 或 plain markdown）
	 * @return 標準化 zip 位元組（SKILL.md 一律於根；plain → 包；subfolder → repack）
	 * @throws IOException 寫入新 zip 串流時發生 I/O 錯誤
	 */
	public byte[] normalizeToZip(byte[] uploaded) throws IOException {
		if (!isZipFile(uploaded)) {
			// Case 3：plain markdown → 包成單檔 zip
			var baos = new ByteArrayOutputStream();
			try (var zos = new ZipOutputStream(baos)) {
				zos.putNextEntry(new ZipEntry("SKILL.md"));
				zos.write(uploaded);
				zos.closeEntry();
			}
			return baos.toByteArray();
		}
		return repackToRoot(uploaded);
	}

	/**
	 * S053：偵測 zip 內 SKILL.md 位置；若位於 subfolder（如 {@code sss/SKILL.md}），strip 該前綴 repack。
	 *
	 * <p>Wrapping folder 偵測規則：第一個遇到的 {@code <prefix>/SKILL.md} entry 的 prefix 為 wrapping folder。
	 * 其他 entry 必須以該 prefix 開頭才被保留（避免合併不相關 sibling 檔；維持「整個包都是這個 skill 的資料夾」語意）。
	 *
	 * @param zipBytes 已確認為 zip 的原始位元組
	 * @return 標準化 zip（SKILL.md 在根；若已是根則 pass-through）
	 * @throws IOException 讀寫 zip 串流時發生 I/O 錯誤
	 */
	private byte[] repackToRoot(byte[] zipBytes) throws IOException {
		// First pass：找 SKILL.md 位置 → 推算 wrapping folder
		String wrappingFolder = null;
		try (var zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
			var entry = zis.getNextEntry();
			while (entry != null) {
				var name = entry.getName();
				if (name.equals("SKILL.md")) {
					wrappingFolder = "";  // already at root
					break;
				}
				if (name.endsWith("/SKILL.md")) {
					wrappingFolder = name.substring(0, name.length() - "SKILL.md".length());
					break;
				}
				entry = zis.getNextEntry();
			}
		}
		// 找不到 SKILL.md：原樣返回，下游 extractSkillMd 會 fail 並由 service 拋 VALIDATION_ERROR
		if (wrappingFolder == null) {
			return zipBytes;
		}
		// 已在根目錄：pass-through 省 CPU
		if (wrappingFolder.isEmpty()) {
			return zipBytes;
		}
		// Second pass：strip wrappingFolder prefix repack
		var baos = new ByteArrayOutputStream();
		try (var zis = new ZipInputStream(new ByteArrayInputStream(zipBytes));
				var zos = new ZipOutputStream(baos)) {
			var entry = zis.getNextEntry();
			while (entry != null) {
				var name = entry.getName();
				if (name.startsWith(wrappingFolder) && !entry.isDirectory()) {
					var newName = name.substring(wrappingFolder.length());
					if (!newName.isEmpty()) {
						zos.putNextEntry(new ZipEntry(newName));
						zis.transferTo(zos);
						zos.closeEntry();
					}
				}
				entry = zis.getNextEntry();
			}
		}
		return baos.toByteArray();
	}

	/** S053：ZIP local file header magic — RFC 1951 規範前 4 bytes 為 {@code PK\x03\x04}。 */
	private static boolean isZipFile(byte[] bytes) {
		return bytes != null && bytes.length >= 4
				&& bytes[0] == 0x50 && bytes[1] == 0x4B
				&& bytes[2] == 0x03 && bytes[3] == 0x04;
	}


	/**
	 * 從 zip 壓縮檔中提取 {@code SKILL.md} 的文字內容。
	 *
	 * <p>同時支援根目錄（{@code SKILL.md}）與子目錄（{@code xxx/SKILL.md}）兩種位置，
	 * 以兼容不同打包方式。
	 *
	 * @param zipBytes zip 壓縮檔的位元組陣列
	 * @return {@code SKILL.md} 的 UTF-8 字串內容；若 zip 中不含該檔案則回傳 {@code null}
	 * @throws IOException 讀取 zip 串流時發生 I/O 錯誤
	 */
	public String extractSkillMd(byte[] zipBytes) throws IOException {
		try (var zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
			var entry = zis.getNextEntry();
			while (entry != null) {
				var name = entry.getName();
				// 同時比對根目錄與子目錄的 SKILL.md
				if (name.equals("SKILL.md") || name.endsWith("/SKILL.md")) {
					return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
				}
				entry = zis.getNextEntry();
			}
		}
		// zip 中未找到 SKILL.md，由呼叫端決定如何處理（通常拋出驗證錯誤）
		return null;
	}

	/**
	 * 從 zip 壓縮檔中提取 {@code scripts/} 目錄下的所有腳本檔案。
	 *
	 * <p>同時支援根目錄的 {@code scripts/} 與巢狀路徑中的 {@code /scripts/}，
	 * 並略過目錄項目（只讀取檔案）。
	 *
	 * @param zipBytes zip 壓縮檔的位元組陣列
	 * @return 以路徑為 key、UTF-8 內容為 value 的有序 Map（依 zip 順序）；
	 *         若無腳本檔案則回傳空 Map
	 * @throws IOException 讀取 zip 串流時發生 I/O 錯誤
	 */
	public Map<String, String> extractScripts(byte[] zipBytes) throws IOException {
		// 使用 LinkedHashMap 保留 zip 中的檔案順序
		var scripts = new LinkedHashMap<String, String>();
		try (var zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
			var entry = zis.getNextEntry();
			while (entry != null) {
				var name = entry.getName();
				// 略過目錄項目，只收集位於 scripts/ 路徑下的實際檔案
				if (!entry.isDirectory() && (name.startsWith("scripts/") || name.contains("/scripts/"))) {
					scripts.put(name, new String(zis.readAllBytes(), StandardCharsets.UTF_8));
				}
				entry = zis.getNextEntry();
			}
		}
		return scripts;
	}

}
