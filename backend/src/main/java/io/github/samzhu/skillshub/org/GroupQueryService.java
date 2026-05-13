package io.github.samzhu.skillshub.org;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupQueryService {

    private final NamedParameterJdbcTemplate jdbc;

    public GroupQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<GroupTreeResponse> tree() {
        var rows = jdbc.query("""
                SELECT id, parent_id, kind, display_name
                FROM groups
                WHERE status = 'ACTIVE'
                ORDER BY parent_id NULLS FIRST, sort_order, display_name
                """, Map.of(), (rs, rowNum) -> new GroupRow(
                rs.getString("id"),
                rs.getString("parent_id"),
                GroupKind.valueOf(rs.getString("kind")),
                rs.getString("display_name")));

        var nodes = new LinkedHashMap<String, TreeNode>();
        for (var row : rows) {
            nodes.put(row.id(), new TreeNode(row));
        }

        var roots = new ArrayList<TreeNode>();
        for (var node : nodes.values()) {
            var parentId = node.row.parentId();
            var parent = parentId == null ? null : nodes.get(parentId);
            if (parent == null) {
                roots.add(node);
            } else {
                parent.children.add(node);
            }
        }
        return roots.stream().map(TreeNode::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<GroupSearchResult> search(String query) {
        var pattern = "%" + query.strip().toLowerCase() + "%";
        var groups = jdbc.query("""
                SELECT g.id,
                       g.kind,
                       g.display_name,
                       COUNT(m.user_id)::int AS member_count
                FROM groups g
                LEFT JOIN group_members m ON m.group_id = g.id
                WHERE g.status = 'ACTIVE'
                  AND LOWER(g.display_name) LIKE :pattern
                GROUP BY g.id, g.kind, g.display_name
                ORDER BY g.display_name
                LIMIT 20
                """, Map.of("pattern", pattern), (rs, rowNum) -> new SearchRow(
                rs.getString("id"),
                GroupKind.valueOf(rs.getString("kind")),
                rs.getString("display_name"),
                rs.getInt("member_count")));
        return groups.stream()
                .map(row -> new GroupSearchResult(
                        row.id(),
                        principalKey(row.id()),
                        row.kind(),
                        row.displayName(),
                        pathLabels(row.id()),
                        row.memberCount()))
                .toList();
    }

    private List<String> pathLabels(String groupId) {
        return jdbc.query("""
                SELECT ancestor.display_name
                FROM group_closure c
                JOIN groups ancestor ON ancestor.id = c.ancestor_id
                WHERE c.descendant_id = :groupId
                  AND ancestor.status = 'ACTIVE'
                ORDER BY c.depth DESC
                """, new MapSqlParameterSource("groupId", groupId), (rs, rowNum) -> rs.getString("display_name"));
    }

    private static String principalKey(String id) {
        return "group:" + id;
    }

    private record GroupRow(String id, @Nullable String parentId, GroupKind kind, String displayName) {}

    private record SearchRow(String id, GroupKind kind, String displayName, int memberCount) {}

    private static final class TreeNode {
        private final GroupRow row;
        private final List<TreeNode> children = new ArrayList<>();

        private TreeNode(GroupRow row) {
            this.row = row;
        }

        private GroupTreeResponse toResponse() {
            return new GroupTreeResponse(
                    row.id(),
                    row.parentId(),
                    row.kind(),
                    row.displayName(),
                    principalKey(row.id()),
                    children.stream().map(TreeNode::toResponse).toList());
        }
    }
}
