package io.github.samzhu.skillshub.org;

import org.springframework.data.repository.ListCrudRepository;

/**
 * Spring Data JDBC repository for {@link Group} rows.
 */
public interface GroupRepository extends ListCrudRepository<Group, String> {
}
