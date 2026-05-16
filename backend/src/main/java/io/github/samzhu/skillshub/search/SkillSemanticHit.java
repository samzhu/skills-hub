package io.github.samzhu.skillshub.search;

/**
 * One row returned by the S186 semantic SQL over {@code skills.embedding}.
 *
 * <p>The record keeps the database distance until the service maps it to the API score, so the SQL
 * projection and response projection stay explicit and testable.
 *
 * @param id skill id from {@code skills.id}
 * @param name skill name from {@code skills.name}
 * @param description skill description from {@code skills.description}
 * @param author author display/user id from {@code skills.author}
 * @param category canonical category key from {@code skills.category}
 * @param categoryDisplay display category from {@code skills.category_display}
 * @param latestVersion latest published SemVer from {@code skills.latest_version}
 * @param riskLevel risk level from {@code skills.risk_level}
 * @param downloadCount cumulative downloads from {@code skills.download_count}
 * @param distance pgvector cosine distance from query embedding to {@code skills.embedding}
 */
record SkillSemanticHit(
        String id,
        String name,
        String description,
        String author,
        String category,
        String categoryDisplay,
        String latestVersion,
        String riskLevel,
        long downloadCount,
        double distance
) {

    SemanticSearchResult toResult() {
        return new SemanticSearchResult(id, name, description, author, category, categoryDisplay,
                latestVersion, riskLevel, downloadCount, 1.0d - distance);
    }
}
