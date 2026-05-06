package io.github.samzhu.skillshub.skill.query;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.samzhu.skillshub.skill.domain.SkillVersion;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;
import io.github.samzhu.skillshub.skill.query.VersionDiffResponse.DiffField;
import io.github.samzhu.skillshub.skill.query.VersionDiffResponse.VersionSnapshot;

/** S098c2 — 計算兩個 SkillVersion 之間的欄位差異，回傳結構化 diff。 */
@Service
public class SkillDiffQueryService {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final SkillVersionRepository skillVersionRepo;

    public SkillDiffQueryService(SkillVersionRepository skillVersionRepo) {
        this.skillVersionRepo = skillVersionRepo;
    }

    public VersionDiffResponse diff(String skillId, String fromVer, String toVer) {
        var from = skillVersionRepo.findBySkillIdAndVersion(skillId, fromVer)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + fromVer));
        var to = skillVersionRepo.findBySkillIdAndVersion(skillId, toVer)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + toVer));

        log.atDebug()
                .addKeyValue("skillId", skillId)
                .addKeyValue("from", fromVer)
                .addKeyValue("to", toVer)
                .log("computing version diff");

        return new VersionDiffResponse(
                skillId,
                snapshot(from),
                snapshot(to),
                compareFields(from, to));
    }

    private static VersionSnapshot snapshot(SkillVersion sv) {
        return new VersionSnapshot(sv.getVersion(), sv.getPublishedAt(), sv.getFileSize(), sv.getFileCount());
    }

    private static List<DiffField> compareFields(SkillVersion from, SkillVersion to) {
        var result = new ArrayList<DiffField>();

        addIfDiff(result, "name", str(from.getFrontmatter(), "name"), str(to.getFrontmatter(), "name"));
        addIfDiff(result, "description", str(from.getFrontmatter(), "description"), str(to.getFrontmatter(), "description"));
        addIfDiff(result, "riskLevel", riskLevel(from.getRiskAssessment()), riskLevel(to.getRiskAssessment()));
        addIfDiff(result, "allowedTools", joinList(from.getAllowedTools()), joinList(to.getAllowedTools()));
        addIfDiff(result, "fileSize", String.valueOf(from.getFileSize()), String.valueOf(to.getFileSize()));
        addIfDiff(result, "fileCount", String.valueOf(from.getFileCount()), String.valueOf(to.getFileCount()));

        return List.copyOf(result);
    }

    private static void addIfDiff(List<DiffField> result, String field, String fromVal, String toVal) {
        if (Objects.equals(fromVal, toVal)) return;
        String changeType;
        if (fromVal == null) changeType = "added";
        else if (toVal == null) changeType = "removed";
        else changeType = "changed";
        result.add(new DiffField(field, fromVal, toVal, changeType));
    }

    private static String str(Map<String, Object> map, String key) {
        if (map == null) return null;
        var val = map.get(key);
        return val == null ? null : val.toString();
    }

    private static String riskLevel(Map<String, Object> riskAssessment) {
        if (riskAssessment == null) return null;
        var level = riskAssessment.get("level");
        return level == null ? null : level.toString();
    }

    private static String joinList(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        return String.join(", ", list);
    }
}
