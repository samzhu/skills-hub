package io.github.samzhu.skillshub.skill.testsupport;

/**
 * S140 T01 — {@code POST /internal/test/seed/download-event} 入參。
 *
 * <p>{@code download_events} 為 read-side projection table（非 aggregate）；直 INSERT 不破
 * outbox / audit invariant，per spec §2.3 grill option B。
 *
 * @param skillId 要 attach events 的 skill id（必填；FK 到 {@code skills.id}）
 * @param count   要產生的 download_events row 數（必填，需 {@literal > 0}）
 * @param daysAgo 散布在過去 N 天內；0 = 全部 {@code now()}
 */
public record SeedDownloadEventRequest(
        String skillId,
        int count,
        int daysAgo) {}
