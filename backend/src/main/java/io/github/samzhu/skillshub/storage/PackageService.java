package io.github.samzhu.skillshub.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

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
