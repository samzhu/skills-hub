package io.github.samzhu.skillshub.skill.query;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Set;

import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import io.github.samzhu.skillshub.shared.security.PrincipalContextService;

@Service
class JdbcSkillAclReadEvaluator implements SkillAclReadEvaluator {

    private static final String SQL = """
            SELECT EXISTS (
              SELECT 1 FROM skills
               WHERE id = :skillId
                 AND acl_entries ??| :patterns
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final PrincipalContextService principalContextService;

    JdbcSkillAclReadEvaluator(NamedParameterJdbcTemplate jdbc,
                              PrincipalContextService principalContextService) {
        this.jdbc = jdbc;
        this.principalContextService = principalContextService;
    }

    @Override
    public boolean canRead(String skillId) {
        return hasPermission(skillId, "read", true);
    }

    @Override
    public boolean canWrite(String skillId) {
        return hasPermission(skillId, "write", false);
    }

    @Override
    public boolean canDelete(String skillId) {
        return hasPermission(skillId, "delete", false);
    }

    private boolean hasPermission(String skillId, String permission, boolean includePublicRead) {
        var patterns = patterns(principalContextService.currentPrincipalKeys(), permission);
        if (includePublicRead) {
            patterns.add("public:*:read");
        }
        var params = new MapSqlParameterSource()
                .addValue("skillId", skillId)
                .addValue("patterns", new SqlParameterValue(Types.ARRAY, patterns.toArray(new String[0])));
        return Boolean.TRUE.equals(jdbc.queryForObject(SQL, params, Boolean.class));
    }

    private static ArrayList<String> patterns(Set<String> principalKeys, String permission) {
        var result = new ArrayList<String>(principalKeys.size());
        for (var key : principalKeys) {
            result.add(key + ":" + permission);
        }
        return result;
    }
}
