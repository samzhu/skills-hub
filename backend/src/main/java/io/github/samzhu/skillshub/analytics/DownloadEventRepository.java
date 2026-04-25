package io.github.samzhu.skillshub.analytics;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * {@link DownloadEventReadModel} 的 MongoDB Repository，
 * 提供下載事件的基本 CRUD 操作（由 Spring Data 自動實作）。
 */
public interface DownloadEventRepository extends MongoRepository<DownloadEventReadModel, String> {
}
