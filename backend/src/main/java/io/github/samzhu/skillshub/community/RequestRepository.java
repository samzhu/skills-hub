package io.github.samzhu.skillshub.community;

import java.util.List;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * S096g2 — Request aggregate 的 Spring Data JDBC Repository。
 *
 * <p>{@code save()} 經 Spring Data EventPublishing proxy 自動 publish aggregate
 * registered events 至 Modulith outbox（同 TX）。
 *
 * <p><b>Note</b>：用 {@link Query} 而非 derived query method names，因 Spring Data JDBC
 * AOT codegen 對多屬性 compound sort（如 {@code findAllByOrderByVoteCountDescCreatedAtDesc}）
 * 產生語法壞的代碼（缺逗號）— 走 explicit SQL 避開此 bug。
 */
public interface RequestRepository extends ListCrudRepository<Request, String> {

    /** AC-3 預設：votes desc，平手按 createdAt desc。 */
    @Query("SELECT * FROM requests ORDER BY vote_count DESC, created_at DESC")
    List<Request> findAllOrderByVotesDesc();

    /** AC-3 alt：sort=created → createdAt desc。 */
    @Query("SELECT * FROM requests ORDER BY created_at DESC")
    List<Request> findAllOrderByCreatedDesc();

    /** AC-4：按 status filter，內部仍 votes desc。 */
    @Query("SELECT * FROM requests WHERE status = :status ORDER BY vote_count DESC, created_at DESC")
    List<Request> findByStatusOrderByVotesDesc(@Param("status") String status);

    /** AC-4 alt：status filter + createdAt desc。 */
    @Query("SELECT * FROM requests WHERE status = :status ORDER BY created_at DESC")
    List<Request> findByStatusOrderByCreatedDesc(@Param("status") String status);
}
