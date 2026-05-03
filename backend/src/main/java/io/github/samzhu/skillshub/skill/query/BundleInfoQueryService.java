package io.github.samzhu.skillshub.skill.query;

import java.time.Instant;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;

import io.github.samzhu.skillshub.shared.api.BundleNotPublishedException;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;

/**
 * S098a3-2 — Bundle metadata read service for PublishValidatePage upload-strip。
 *
 * <p>per spec §4.4：load Skill → 驗 latestVersion → load SkillVersion → 派生 filename
 * `<skill.name>-<version>.zip`（per S041 canonical naming）+ 既有 fileSize / fileCount
 * (V13 加) / publishedAt 直接 expose。caller (`SkillQueryController`) 純 routing。
 *
 * <p>404 路由：
 * <ul>
 *   <li>{@code skillRepo.findById} 缺 → {@link NoSuchElementException}（既有 GlobalExceptionHandler
 *       走 NOT_FOUND + error="NOT_FOUND"，對齊 SkillQueryService.findById 既驗 path）</li>
 *   <li>{@code latestVersion} 為 null（DRAFT skill / 從未發版）→ {@link BundleNotPublishedException}
 *       (404 + error="bundle_not_published")，距 skill_not_found 語意區分</li>
 * </ul>
 */
@Service
public class BundleInfoQueryService {

    private final SkillRepository skillRepo;
    private final SkillVersionRepository versionRepo;

    public BundleInfoQueryService(SkillRepository skillRepo, SkillVersionRepository versionRepo) {
        this.skillRepo = skillRepo;
        this.versionRepo = versionRepo;
    }

    public BundleInfoResponse get(String skillId) {
        var skill = skillRepo.findById(skillId)
                .orElseThrow(() -> new NoSuchElementException("Skill not found: " + skillId));
        var latestVersion = skill.getLatestVersion();
        if (latestVersion == null || latestVersion.isBlank()) {
            throw new BundleNotPublishedException(skillId);
        }
        var version = versionRepo.findBySkillIdAndVersion(skillId, latestVersion)
                .orElseThrow(() -> new BundleNotPublishedException(skillId));

        var filename = skill.getName() + "-" + version.getVersion() + ".zip";
        return new BundleInfoResponse(filename, version.getFileSize(),
                version.getFileCount(), version.getPublishedAt());
    }

    /**
     * Public DTO — 對齊 frontend `BundleInfo` interface (spec §4.5)。
     *
     * @param filename   canonical {@code <name>-<version>.zip} (per S041)
     * @param fileSize   bytes
     * @param fileCount  zip entry count；0 = legacy row（V13 default；frontend hide 該欄）
     * @param uploadedAt SkillVersion.publishedAt
     */
    public record BundleInfoResponse(String filename, long fileSize, int fileCount, Instant uploadedAt) {}
}
