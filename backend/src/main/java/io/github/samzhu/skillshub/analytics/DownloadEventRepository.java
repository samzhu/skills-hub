package io.github.samzhu.skillshub.analytics;

import org.springframework.data.repository.ListCrudRepository;

/**
 * {@link DownloadEventReadModel} 的 Spring Data JDBC Repository，提供下載事件
 * 的基本 CRUD 操作（對應 PostgreSQL {@code download_events} 表）。
 */
public interface DownloadEventRepository extends ListCrudRepository<DownloadEventReadModel, String> {
}
