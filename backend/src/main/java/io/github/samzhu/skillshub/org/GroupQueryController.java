package io.github.samzhu.skillshub.org;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups")
class GroupQueryController {

    private final GroupQueryService queries;

    GroupQueryController(GroupQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/tree")
    List<GroupTreeResponse> tree() {
        return queries.tree();
    }

    @GetMapping("/search")
    List<GroupSearchResult> search(@RequestParam("q") String query) {
        return queries.search(query);
    }
}
