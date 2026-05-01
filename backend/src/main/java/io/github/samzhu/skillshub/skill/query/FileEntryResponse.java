package io.github.samzhu.skillshub.skill.query;

/**
 * S074：skill zip 內單一 entry 的 metadata（給 SkillDetailPage 檔案瀏覽器列表用）。
 *
 * @param path  zip 內 entry 路徑（已過 zip-slip 防禦，不含 {@code ..} / 開頭 {@code /}）
 * @param size  解壓後位元組數
 * @param type  推測的 MIME（依副檔名；無法判別則 {@code application/octet-stream}）
 */
public record FileEntryResponse(String path, long size, String type) {}
