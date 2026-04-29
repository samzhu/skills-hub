package io.github.samzhu.skillshub.skill.command;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersion;
import io.github.samzhu.skillshub.skill.domain.SkillVersionRepository;

/**
 * S024 T1 RED test 用的 TX boundary helper — {@code repo.save(...)} 必須在
 * {@code @Transactional} 內才能讓 Spring Data {@code @DomainEvents} 攔截器 publish 事件至
 * Modulith {@code event_publication} outbox（per S023 / {@code TestEventTxHelper} 範式）。
 *
 * <p>Test 端不加 {@code @Transactional} 避免 default rollback；改透過呼叫本 helper 方法
 * 進入 TX boundary，方法 return 時 commit + outbox INSERT 完成，後續 raw JDBC 即可
 * 觀察 {@code event_publication} row。
 *
 * <p>位於 test source root，{@code @SpringBootTest} 啟動時 component scan 由
 * {@code SkillshubApplication} 包根（{@code io.github.samzhu.skillshub}）涵蓋本 package
 * 而被註冊為 bean。
 *
 * <p>S024 T2-T6 後 SkillCommandService 縮減為 3 行 orchestration，業務 service 即
 * {@code @Transactional} 入口；本 helper 屆時可廢除（與 {@code TestEventTxHelper} 同命運）。
 */
@Component
public class S024CrossAggregateSaveHelper {

    private final SkillRepository skillRepo;
    private final SkillVersionRepository skillVersionRepo;

    public S024CrossAggregateSaveHelper(SkillRepository skillRepo,
                                         SkillVersionRepository skillVersionRepo) {
        this.skillRepo = skillRepo;
        this.skillVersionRepo = skillVersionRepo;
    }

    /** 單純 save Skill aggregate — 觸發 @DomainEvents publish + Modulith outbox INSERT 同 TX。 */
    @Transactional
    public Skill save(Skill skill) {
        return skillRepo.save(skill);
    }

    /**
     * 同 TX 內 save 兩個 aggregate（Skill + SkillVersion）— 對應 spec §2.9 POC hypothesis：
     * 跨 aggregate 同 TX {@code @DomainEvents} publish 行為驗證。
     *
     * <p>呼叫順序：
     * <ol>
     *   <li>{@code skillRepo.save(skill)} → INSERT/UPDATE skills row + publish
     *       SkillCreatedEvent / SkillVersionPublishedFromAggregate</li>
     *   <li>{@code skillVersionRepo.save(version)} → INSERT skill_versions row + publish
     *       SkillVersionPublishedEvent</li>
     *   <li>TX commit → {@code @ApplicationModuleListener} AFTER_COMMIT async listeners 觸發</li>
     * </ol>
     */
    @Transactional
    public void saveCrossAggregate(Skill skill, SkillVersion version) {
        skillRepo.save(skill);
        skillVersionRepo.save(version);
    }

    /**
     * 同 saveCrossAggregate 但結尾強制 throw → 驗 AC-3 extended：業務 TX rollback →
     * {@code event_publication} 同 rollback（at-least-once outbox 安全性的 contract test）。
     */
    @Transactional
    public void saveCrossAggregateThenFail(Skill skill, SkillVersion version) {
        skillRepo.save(skill);
        skillVersionRepo.save(version);
        throw new RuntimeException("S024 T1 simulated TX rollback");
    }

    /** T3：單獨 save SkillVersion（給 repository 測試用，不 attach Skill aggregate）。 */
    @Transactional
    public SkillVersion saveVersionOnly(SkillVersion version) {
        return skillVersionRepo.save(version);
    }

    /** T3：attachRiskAssessment + save → 觸發 UPDATE skill_versions.risk_assessment 同 TX 內 publish event。 */
    @Transactional
    public SkillVersion attachRiskAssessmentAndSave(SkillVersion version, java.util.Map<String, Object> assessment) {
        version.attachRiskAssessment(assessment);
        return skillVersionRepo.save(version);
    }
}
