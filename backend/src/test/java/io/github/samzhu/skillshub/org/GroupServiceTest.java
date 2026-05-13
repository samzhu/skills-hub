package io.github.samzhu.skillshub.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase;

/**
 * S170-T01 — Group data foundation test（Testcontainers PostgreSQL + Spring Data JDBC slice）。
 */
@Import({GroupService.class, GroupIdGenerator.class})
class GroupServiceTest extends RepositorySliceTestBase {

    @Autowired
    private GroupService service;

    @Autowired
    private GroupRepository repo;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM group_closure", Map.of());
        jdbc.update("DELETE FROM group_members", Map.of());
        jdbc.update("DELETE FROM groups", Map.of());
    }

    @Test
    @Tag("AC-1")
    @Tag("AC-15")
    @DisplayName("AC-1/AC-15: create root + child writes parent_id and closure rows")
    void createRootAndChild_writesParentAndClosureRows() {
        var acmeId = service.createGroup(null, GroupKind.COMPANY, "Acme");
        var cloudId = service.createGroup(acmeId, GroupKind.DEPARTMENT, "Cloud");

        var cloud = repo.findById(cloudId).orElseThrow();
        assertThat(cloud.getParentId()).isEqualTo(acmeId);
        assertThat(acmeId).matches("^g_[0-9a-f]{6}$");
        assertThat(cloudId).matches("^g_[0-9a-f]{6}$");

        assertClosure(acmeId, acmeId, 0);
        assertClosure(cloudId, cloudId, 0);
        assertClosure(acmeId, cloudId, 1);
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: kind does not restrict child creation")
    void everyKindCanHaveChildren() {
        for (var kind : GroupKind.values()) {
            var parentId = service.createGroup(null, kind, kind.name() + " Root");
            var childId = service.createGroup(parentId, GroupKind.OTHER, kind.name() + " Child");

            assertThat(repo.findById(childId).orElseThrow().getParentId()).isEqualTo(parentId);
        }
    }

    @Test
    @Tag("AC-12")
    @DisplayName("AC-12: duplicate sibling slug is rejected")
    void duplicateSiblingSlugRejected() {
        var acmeId = service.createGroup(null, GroupKind.COMPANY, "Acme");
        service.createGroup(acmeId, GroupKind.DEPARTMENT, "Cloud");

        assertThatThrownBy(() -> service.createGroup(acmeId, GroupKind.TEAM, "Cloud"))
                .isInstanceOf(DuplicateKeyException.class);

        var rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM groups WHERE parent_id = :parentId AND slug = :slug",
                new MapSqlParameterSource().addValue("parentId", acmeId).addValue("slug", "cloud"),
                Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @Tag("AC-8")
    @DisplayName("AC-8: moving a group under its descendant is rejected")
    void cycleMoveRejected() {
        var acmeId = service.createGroup(null, GroupKind.COMPANY, "Acme");
        var cloudId = service.createGroup(acmeId, GroupKind.DEPARTMENT, "Cloud");
        var platformId = service.createGroup(cloudId, GroupKind.TEAM, "Platform Team");

        assertThatThrownBy(() -> service.moveGroup(acmeId, platformId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("group_cycle");

        assertClosure(acmeId, platformId, 2);
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7: moving a subtree rebuilds closure ancestors")
    void moveGroup_rebuildsSubtreeClosureRows() {
        var acmeId = service.createGroup(null, GroupKind.COMPANY, "Acme");
        var cloudId = service.createGroup(acmeId, GroupKind.DEPARTMENT, "Cloud");
        var platformId = service.createGroup(cloudId, GroupKind.TEAM, "Platform Team");
        var globalId = service.createGroup(null, GroupKind.COMPANY, "Global");

        service.moveGroup(cloudId, globalId);

        assertThat(repo.findById(cloudId).orElseThrow().getParentId()).isEqualTo(globalId);
        assertNoClosure(acmeId, cloudId);
        assertNoClosure(acmeId, platformId);
        assertClosure(globalId, cloudId, 1);
        assertClosure(globalId, platformId, 2);
        assertClosure(cloudId, platformId, 1);
    }

    @Test
    @Tag("AC-11")
    @DisplayName("AC-11: archiving a group archives the whole subtree")
    void archiveGroup_archivesSubtree() {
        var acmeId = service.createGroup(null, GroupKind.COMPANY, "Acme");
        var cloudId = service.createGroup(acmeId, GroupKind.DEPARTMENT, "Cloud");
        var platformId = service.createGroup(cloudId, GroupKind.TEAM, "Platform Team");

        service.archiveGroup(cloudId);

        assertThat(repo.findById(acmeId).orElseThrow().getStatus()).isEqualTo(GroupStatus.ACTIVE);
        assertThat(repo.findById(cloudId).orElseThrow().getStatus()).isEqualTo(GroupStatus.ARCHIVED);
        assertThat(repo.findById(platformId).orElseThrow().getStatus()).isEqualTo(GroupStatus.ARCHIVED);
    }

    @Test
    @Tag("AC-15")
    @DisplayName("AC-15: generated id collision retries before insert")
    void generatedIdCollisionRetries() {
        var serviceWithCollision = new GroupService(
                repo,
                jdbc,
                new GroupIdGenerator(List.of("g_abc123", "g_abc123", "g_def456").iterator()::next),
                event -> {});

        var first = serviceWithCollision.createGroup(null, GroupKind.COMPANY, "Acme");
        var second = serviceWithCollision.createGroup(null, GroupKind.COMPANY, "Global");

        assertThat(first).isEqualTo("g_abc123");
        assertThat(second).isEqualTo("g_def456");
    }

    private void assertClosure(String ancestorId, String descendantId, int depth) {
        var actual = jdbc.queryForObject(
                "SELECT depth FROM group_closure WHERE ancestor_id = :ancestor AND descendant_id = :descendant",
                new MapSqlParameterSource().addValue("ancestor", ancestorId).addValue("descendant", descendantId),
                Integer.class);
        assertThat(actual).isEqualTo(depth);
    }

    private void assertNoClosure(String ancestorId, String descendantId) {
        var actual = jdbc.queryForObject(
                "SELECT COUNT(*) FROM group_closure WHERE ancestor_id = :ancestor AND descendant_id = :descendant",
                new MapSqlParameterSource().addValue("ancestor", ancestorId).addValue("descendant", descendantId),
                Integer.class);
        assertThat(actual).isZero();
    }
}
