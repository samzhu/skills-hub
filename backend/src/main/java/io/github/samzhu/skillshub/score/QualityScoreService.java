package io.github.samzhu.skillshub.score;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.skillshub.score.domain.QualityAxis;
import io.github.samzhu.skillshub.score.domain.SkillScore;
import io.github.samzhu.skillshub.score.domain.SkillScoreRepository;
import io.github.samzhu.skillshub.score.judge.JudgeResponse;
import io.github.samzhu.skillshub.score.judge.QualityJudge;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import io.github.samzhu.skillshub.skill.validation.SkillValidator;
import io.github.samzhu.skillshub.skill.validation.ValidationResult;
import io.github.samzhu.skillshub.storage.PackageService;
import io.github.samzhu.skillshub.storage.StorageService;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;

/**
 * S135a §4.2 — 三軸品質評分 orchestration。
 *
 * <p>每 axis 一個 SkillScore row，deterministic UUID PK 確保 idempotency。
 */
@Service
public class QualityScoreService {

    private static final Logger log = LoggerFactory.getLogger(QualityScoreService.class);

    private static final int VALIDATION_DIMENSION_COUNT = 7;
    private static final int FRONTMATTER_COMPATIBILITY_WARNING_SCORE = 80;
    private static final String FRONTMATTER_COMPATIBILITY_WARNING_PREFIX = "frontmatter_official_format:";

    private final SkillValidator validator;
    private final QualityJudge judge;
    private final SkillScoreRepository scoreRepo;
    private final SkillVersionRepository versionRepo;
    private final StorageService storageService;
    private final PackageService packageService;

    public QualityScoreService(SkillValidator validator, QualityJudge judge,
                                SkillScoreRepository scoreRepo, SkillVersionRepository versionRepo,
                                StorageService storageService, PackageService packageService) {
        this.validator = validator;
        this.judge = judge;
        this.scoreRepo = scoreRepo;
        this.versionRepo = versionRepo;
        this.storageService = storageService;
        this.packageService = packageService;
    }

    public boolean alreadyScored(String sourceEventId) {
        return scoreRepo.existsBySourceEventId(sourceEventId);
    }

    @Transactional
    public void evaluateAndPersist(SkillVersionPublishedEvent event) {
        String skillId = event.aggregateId();
        String version = event.version();
        String sourceEventId = event.sourceEventId();

        String skillVersionId = versionRepo.findBySkillIdAndVersion(skillId, version)
                .map(sv -> sv.getId())
                .orElseGet(() -> UUID.nameUUIDFromBytes(
                        (skillId + "@" + version).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString());

        String content = fetchSkillMd(event.storagePath());

        var vScore = buildValidationScore(skillId, skillVersionId, version, content, sourceEventId);
        var iScore = buildImplementationScore(skillId, skillVersionId, version, content, sourceEventId);
        var aScore = buildActivationScore(skillId, skillVersionId, version, event.frontmatter(), sourceEventId);

        scoreRepo.saveAll(List.of(vScore, iScore, aScore));

        log.atInfo()
                .addKeyValue("skillId", skillId)
                .addKeyValue("version", version)
                .addKeyValue("v", vScore.getTotalScore())
                .addKeyValue("i", iScore.getTotalScore())
                .addKeyValue("a", aScore.getTotalScore())
                .log("[quality] 3-axis scores persisted");
    }

    private String fetchSkillMd(String storagePath) {
        try {
            byte[] zipBytes = storageService.download(storagePath);
            String content = packageService.extractSkillMd(zipBytes);
            return content != null ? content : "";
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch SKILL.md from " + storagePath, e);
        }
    }

    private SkillScore buildValidationScore(String skillId, String skillVersionId, String version,
                                             String content, String sourceEventId) {
        ValidationResult result = validator.validate(content);

        long lineCount = content.isEmpty() ? 0L : content.lines().count();
        boolean lineOk = !result.errors().stream().anyMatch(e -> e.contains("line_count"));
        boolean bodyOk = !result.errors().stream().anyMatch(e -> e.contains("body_present"));
        boolean nameOk = !result.errors().stream().anyMatch(e -> e.contains("name"));
        boolean descOk = !result.errors().stream().anyMatch(e -> e.contains("description"));
        boolean fieldsOk = !result.errors().stream().anyMatch(e -> e.contains("Missing required"));
        boolean toolsOk = !result.errors().stream().anyMatch(e -> e.contains("allowed-tools"));
        var frontmatterWarnings = result.warnings().stream()
                .filter(warning -> warning.startsWith(FRONTMATTER_COMPATIBILITY_WARNING_PREFIX))
                .toList();
        boolean frontmatterOfficialOk = frontmatterWarnings.isEmpty();

        var dims = new LinkedHashMap<String, Object>();
        dims.put("lineCount", Map.of("score", lineOk ? 100 : 0, "reasoning", lineCount + " / 500 lines"));
        dims.put("bodyPresent", Map.of("score", bodyOk ? 100 : 0, "reasoning", bodyOk ? "body section present" : "missing body after frontmatter"));
        dims.put("nameFormat", Map.of("score", nameOk ? 100 : 0, "reasoning", nameOk ? "valid name format" : "name fails format rules"));
        dims.put("descriptionConstraints", Map.of("score", descOk ? 100 : 0, "reasoning", descOk ? "description within constraints" : "description constraint failed"));
        dims.put("requiredFields", Map.of("score", fieldsOk ? 100 : 0, "reasoning", fieldsOk ? "name + description present" : "missing required fields"));
        dims.put("allowedTools", Map.of("score", toolsOk ? 100 : 0, "reasoning", toolsOk ? "allowed-tools valid" : "allowed-tools constraint failed"));
        dims.put("frontmatterOfficialFormat", Map.of(
                "score", frontmatterOfficialOk ? 100 : FRONTMATTER_COMPATIBILITY_WARNING_SCORE,
                "reasoning", frontmatterOfficialOk
                        ? "frontmatter follows agentskills.io official format"
                        : String.join("; ", frontmatterWarnings)));
        if (!result.warnings().isEmpty()) {
            dims.put("warnings", result.warnings());
        }

        int scoreSum = (lineOk ? 100 : 0) + (bodyOk ? 100 : 0) + (nameOk ? 100 : 0)
                + (descOk ? 100 : 0) + (fieldsOk ? 100 : 0) + (toolsOk ? 100 : 0)
                + (frontmatterOfficialOk ? 100 : FRONTMATTER_COMPATIBILITY_WARNING_SCORE);
        var totalScore = BigDecimal.valueOf((double) scoreSum / VALIDATION_DIMENSION_COUNT)
                .setScale(2, RoundingMode.HALF_UP);

        return SkillScore.of(skillId, skillVersionId, version, QualityAxis.VALIDATION, totalScore, dims, judge.evaluatorVersion(), sourceEventId);
    }

    private SkillScore buildImplementationScore(String skillId, String skillVersionId, String version,
                                                  String content, String sourceEventId) {
        String body = extractBody(content);
        JudgeResponse response = judge.judgeImplementation(body);
        return buildLlmScore(skillId, skillVersionId, version, QualityAxis.IMPLEMENTATION, response, sourceEventId);
    }

    private SkillScore buildActivationScore(String skillId, String skillVersionId, String version,
                                              Map<String, Object> frontmatter, String sourceEventId) {
        String description = frontmatter.getOrDefault("description", "").toString();
        JudgeResponse response = judge.judgeActivation(description);
        return buildLlmScore(skillId, skillVersionId, version, QualityAxis.ACTIVATION, response, sourceEventId);
    }

    private SkillScore buildLlmScore(String skillId, String skillVersionId, String version,
                                      QualityAxis axis, JudgeResponse response, String sourceEventId) {
        var dims = new LinkedHashMap<String, Object>();
        for (var dim : response.scores()) {
            dims.put(lcFirst(dim.dimension()), Map.of("score", dim.score(), "reasoning", dim.reasoning()));
        }
        int sumScore = response.scores().stream().mapToInt(JudgeResponse.DimensionScore::score).sum();
        int maxScore = response.scores().size() * 3;
        var totalScore = BigDecimal.valueOf((double) sumScore / maxScore * 100)
                .setScale(2, RoundingMode.HALF_UP);
        return SkillScore.of(skillId, skillVersionId, version, axis, totalScore, dims, judge.evaluatorVersion(), sourceEventId);
    }

    private String extractBody(String content) {
        var trimmed = content.strip();
        if (!trimmed.startsWith("---")) return content;
        int second = trimmed.indexOf("---", 3);
        return second < 0 ? content : trimmed.substring(second + 3).strip();
    }

    private static String lcFirst(String s) {
        return s == null || s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
