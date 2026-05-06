package io.github.samzhu.skillshub.skill.query;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import io.github.samzhu.skillshub.skill.query.FileListDiffResponse.FileDiffEntry;
import io.github.samzhu.skillshub.storage.StorageService;

/**
 * S098c3 — 比較兩版本 zip 包的檔案列表差異。
 *
 * <p>使用 size 作為 modified 判斷依據（不計算 hash）— 快速，偶有 false negative
 * （size 相同但內容不同）；MVP 可接受，精準 hash 留 follow-up。
 */
@Service
public class SkillFileDiffService {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final SkillVersionRepository skillVersionRepo;
    private final StorageService storageService;

    public SkillFileDiffService(SkillVersionRepository skillVersionRepo, StorageService storageService) {
        this.skillVersionRepo = skillVersionRepo;
        this.storageService = storageService;
    }

    public FileListDiffResponse listDiff(String skillId, String fromVer, String toVer) {
        var from = skillVersionRepo.findBySkillIdAndVersion(skillId, fromVer)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + fromVer));
        var to = skillVersionRepo.findBySkillIdAndVersion(skillId, toVer)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + toVer));

        log.atDebug()
                .addKeyValue("skillId", skillId)
                .addKeyValue("from", fromVer)
                .addKeyValue("to", toVer)
                .log("computing file-list diff");

        Map<String, Long> fromEntries = listEntries(storageService.download(from.getStoragePath()));
        Map<String, Long> toEntries = listEntries(storageService.download(to.getStoragePath()));

        var entries = new ArrayList<FileDiffEntry>();
        int added = 0, removed = 0, modified = 0, unchanged = 0;

        // Check from entries
        for (var e : fromEntries.entrySet()) {
            String path = e.getKey();
            Long fromSize = e.getValue();
            Long toSize = toEntries.get(path);
            if (toSize == null) {
                entries.add(new FileDiffEntry(path, "removed", fromSize, null));
                removed++;
            } else if (!fromSize.equals(toSize)) {
                entries.add(new FileDiffEntry(path, "modified", fromSize, toSize));
                modified++;
            } else {
                unchanged++;
            }
        }
        // Check for added entries (in to but not from)
        for (var e : toEntries.entrySet()) {
            if (!fromEntries.containsKey(e.getKey())) {
                entries.add(new FileDiffEntry(e.getKey(), "added", null, e.getValue()));
                added++;
            }
        }

        return new FileListDiffResponse(skillId, fromVer, toVer, added, removed, modified, unchanged, entries);
    }

    /** 解析 zip bytes 回傳 path → size Map（目錄 entry 略過）。 */
    Map<String, Long> listEntries(byte[] zipBytes) {
        var result = new LinkedHashMap<String, Long>();
        try (var zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                result.put(entry.getName(), entry.getSize() >= 0 ? entry.getSize() : 0L);
                zin.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read zip entries", e);
        }
        return result;
    }
}
