package io.github.samzhu.skillshub.skill.query;

public interface SkillAclReadEvaluator {

    boolean canRead(String skillId);

    boolean canWrite(String skillId);

    boolean canDelete(String skillId);
}
