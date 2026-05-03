package io.github.samzhu.skillshub.community;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * S096f2 — Collection 子集合 row（一對多 collection → skills 的中介列）。
 *
 * <p>透過 {@link Collection} 上 {@code @MappedCollection(idColumn="collection_id",
 * keyColumn="position")} 由 Spring Data JDBC 管理生命週期；本 record 對應
 * {@code collection_skills} 表的 row 結構（除 collection_id / position 由父 aggregate
 * 外部填入）。
 *
 * <p>不持有 own @Id — Spring Data JDBC 對 @MappedCollection element 採 derived PK
 * (parent_id, key) 模式，element 本身不需 PK 欄位。
 */
@Table("collection_skills")
public record CollectionSkillRef(@Column("skill_id") String skillId) {}
