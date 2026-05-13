package io.github.samzhu.skillshub.community;

import java.util.List;

import org.springframework.data.repository.ListCrudRepository;

/**
 * S156c — RequestComment 的 Spring Data JDBC repository（ListCrudRepository pattern；
 * 對齊 RequestRepository / CollectionRepository 既有寫法）。
 *
 * <p>查詢主路徑：detail page 取某 request 的非 soft-deleted comments，ASC by createdAt
 * — 對齊 GitHub Issues 風格。Derived query method 由 Spring Data 解析；命名嚴格遵循
 * property name {@code requestId} / {@code deletedAt} / {@code createdAt}。
 */
public interface RequestCommentRepository extends ListCrudRepository<RequestComment, String> {

    /** S156c AC-4 — detail page 取 comments（過濾 soft-deleted；ASC earliest first）。 */
    List<RequestComment> findByRequestIdAndDeletedAtIsNullOrderByCreatedAtAsc(String requestId);
}
