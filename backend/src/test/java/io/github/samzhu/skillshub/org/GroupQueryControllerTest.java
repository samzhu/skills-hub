package io.github.samzhu.skillshub.org;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S170-T04 — group tree/search API integration test（真 PostgreSQL + 真 closure rows）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GroupService groups;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM group_members", Map.of());
        jdbc.update("DELETE FROM group_closure", Map.of());
        jdbc.update("DELETE FROM groups", Map.of());
    }

    @Test
    @Tag("AC-9")
    @DisplayName("AC-9: GET /api/v1/groups/tree returns nested active groups")
    void tree_returnsNestedGroups() throws Exception {
        groups.createGroup(null, GroupKind.COMPANY, "Global");
        var acmeId = groups.createGroup(null, GroupKind.COMPANY, "Acme");
        var cloudId = groups.createGroup(acmeId, GroupKind.DEPARTMENT, "Cloud");
        var platformId = groups.createGroup(cloudId, GroupKind.TEAM, "Platform Team");

        mockMvc.perform(get("/api/v1/groups/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(acmeId))
                .andExpect(jsonPath("$[0].parentId").doesNotExist())
                .andExpect(jsonPath("$[0].kind").value("COMPANY"))
                .andExpect(jsonPath("$[0].displayName").value("Acme"))
                .andExpect(jsonPath("$[0].principalKey").value("group:" + acmeId))
                .andExpect(jsonPath("$[0].children[0].id").value(cloudId))
                .andExpect(jsonPath("$[0].children[0].parentId").value(acmeId))
                .andExpect(jsonPath("$[0].children[0].children[0].id").value(platformId))
                .andExpect(jsonPath("$[0].children[0].children[0].displayName").value("Platform Team"));
    }

    @Test
    @Tag("AC-10")
    @DisplayName("AC-10: GET /api/v1/groups/search returns principal key and root-to-leaf path")
    void search_returnsPrincipalAndPathLabels() throws Exception {
        var acmeId = groups.createGroup(null, GroupKind.COMPANY, "Acme");
        var cloudId = groups.createGroup(acmeId, GroupKind.DEPARTMENT, "Cloud");
        groups.createGroup(cloudId, GroupKind.TEAM, "Platform Team");

        mockMvc.perform(get("/api/v1/groups/search").param("q", "cloud"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(cloudId))
                .andExpect(jsonPath("$[0].principalKey").value("group:" + cloudId))
                .andExpect(jsonPath("$[0].kind").value("DEPARTMENT"))
                .andExpect(jsonPath("$[0].displayName").value("Cloud"))
                .andExpect(jsonPath("$[0].path[0]").value("Acme"))
                .andExpect(jsonPath("$[0].path[1]").value("Cloud"))
                .andExpect(jsonPath("$[0].memberCount").value(0));
    }
}
