package io.github.samzhu.skillshub.skill.query;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * Skill ACL 查詢服務 — 從 Skill aggregate 取出 acl_entries 並拆成 type/principal/permission tuples。
 *
 * <p>S024 ship 後改打 {@link SkillRepository}（aggregate 直接做查詢；ADR-002 §2.4）—
 * {@code Skill.getAclEntries()} 已回 {@code List.copyOf(aclEntries)} 防外部 mutate。
 * 本 service 負責 colon split + 畸形 entry skip。
 *
 * @see AclEntryResponse
 */
@Service
public class SkillAclQueryService {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final SkillRepository skillRepo;

    public SkillAclQueryService(SkillRepository skillRepo) {
        this.skillRepo = skillRepo;
    }

    /**
     * 列出指定 skill 的所有 ACL entries（已拆成結構化 tuple）。
     *
     * <p>畸形 entry（split 後不為 3 段）跳過並 log warn，不 throw — 避免一筆髒資料拖整個 list。
     *
     * @param skillId 技能 ID
     * @return AclEntryResponse list（skill 不存在或 acl_entries 全部畸形時回 empty list）
     */
    public List<AclEntryResponse> listEntries(String skillId) {
        var skill = skillRepo.findById(skillId);
        if (skill.isEmpty() || skill.get().getAclEntries() == null) {
            return List.of();
        }

        var result = new ArrayList<AclEntryResponse>();
        for (var entry : skill.get().getAclEntries()) {
            // S038: "public:*:read" 為 S026 + S114a 公開讀取 3-segment entry；
            // 識別後直接 pass-through split（public:*:read → parts[0]=public, [1]=*, [2]=read），不需特殊處理。
            // 舊格式 "*:read" 已由 V17 migration 統一轉換，此處保留向下相容識別。
            if ("*:read".equals(entry)) {
                result.add(new AclEntryResponse("public", "*", "read"));
                continue;
            }
            // 限 3 段 split — principal 內可能含 dot/email 等字元但不可含 colon（per spec §4.1 regex）；
            // 用 limit=3 確保格式違規時 fast-fail，而非把 permission 段誤切。
            var parts = entry.split(":", 3);
            if (parts.length != 3) {
                log.atWarn()
                        .addKeyValue("skillId", skillId)
                        .addKeyValue("entry", entry)
                        .log("ACL entry 格式異常（非 type:principal:permission 三段），略過");
                continue;
            }
            result.add(new AclEntryResponse(parts[0], parts[1], parts[2]));
        }
        return result;
    }
}
