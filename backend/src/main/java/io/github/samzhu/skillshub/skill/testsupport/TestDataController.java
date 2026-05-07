package io.github.samzhu.skillshub.skill.testsupport;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.samzhu.skillshub.skill.command.SkillCommandService;
import io.github.samzhu.skillshub.skill.domain.Visibility;
import io.github.samzhu.skillshub.storage.PackageService;

/**
 * S140 — Test-only fixture seeding endpoints used by Playwright E2E suites。
 *
 * <p><b>Profile-gated</b>：只在 {@code local} / {@code dev} / {@code e2e} 三個 profile 下註冊；
 * production native binary（{@code prod} profile）完全不含此 bean。security-by-build-time —
 * 即便意外把 {@code /internal/test/**} URL 暴露到公網，路徑 404 因 controller 不存在。
 *
 * <p>3 個 endpoint：
 * <ul>
 *   <li>{@code POST /internal/test/reset} — 單次 {@code TRUNCATE} 涵蓋 allowlist 16 張
 *       application data tables（Flyway {@code schema_history} 保留），讓每個 Playwright
 *       test 走 deterministic empty state</li>
 *   <li>{@code POST /internal/test/seed/skill} — 透過 {@link SkillCommandService#uploadSkill}
 *       走完整 aggregate + outbox + audit path，<b>不繞</b> domain layer 維持 invariant</li>
 *   <li>{@code POST /internal/test/seed/download-event} — 直 INSERT {@code download_events}
 *       projection table；該表為 read-side、非 aggregate，直 INSERT 不破 outbox / audit
 *       invariant（per spec §2.6 grill option B）</li>
 * </ul>
 *
 * @see io.github.samzhu.skillshub.shared.security.SecurityConfig {@code anyRequest().permitAll()}
 *     涵蓋 {@code /internal/test/**}
 */
@RestController
@RequestMapping("/internal/test")
@Profile({"local", "dev", "e2e"})
class TestDataController {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /**
     * Reset allowlist — 新表上線時必須在這裡明示加入；不在 allowlist 的表 reset 後仍保留資料
     * （per spec §2.6 grill option B）。順序不影響：單次 {@code TRUNCATE ... CASCADE} 由 PG 一致性處理 FK。
     */
    private static final List<String> RESET_ALLOWLIST = List.of(
            "skills", "skill_versions", "skill_scores", "skill_subscriptions",
            "collections", "collection_skills", "vector_store",
            "download_events", "domain_events", "event_publication",
            "notifications", "notification_preferences",
            "requests", "request_votes", "reviews", "flags");

    private final SkillCommandService skillCommandService;
    private final NamedParameterJdbcTemplate jdbc;
    private final PackageService packageService;

    TestDataController(SkillCommandService skillCommandService,
                       NamedParameterJdbcTemplate jdbc,
                       PackageService packageService) {
        this.skillCommandService = skillCommandService;
        this.jdbc = jdbc;
        this.packageService = packageService;
    }

    @PostMapping("/reset")
    ResponseEntity<Map<String, Object>> reset() {
        // 單次 TRUNCATE multi-table — PG 內部處理 FK 依賴順序，比逐張呼叫安全；
        // RESTART IDENTITY 重置 sequence；CASCADE 兜底任何漏列的 FK target
        var sql = "TRUNCATE TABLE " + String.join(", ", RESET_ALLOWLIST)
                + " RESTART IDENTITY CASCADE";
        Map<String, Object> noParams = Map.of();

        // S140-T09: TRUNCATE 拿 AccessExclusiveLock 與 AFTER_COMMIT async listener
        // (Modulith outbox dispatcher) 寫 projection (RowShareLock) 互鎖 →
        // PG deadlock_timeout 預設 1s。前一 test 累積的 listener queue 排空前
        // 直接 TRUNCATE 必死。retry 5 次 + 200ms backoff（5 × ~1.2s ≈ 6s 上限），
        // 足以涵蓋 10 skill seed 的所有 listener (search/audit/ACL/notification ×
        // 平均 100ms async)。production 不啟此路徑。
        org.springframework.dao.PessimisticLockingFailureException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                jdbc.update(sql, noParams);
                last = null;
                break;
            } catch (org.springframework.dao.PessimisticLockingFailureException e) {
                last = e;
                try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        if (last != null) throw last;

        log.atInfo()
                .addKeyValue("event", "test_data_reset")
                .addKeyValue("tablesCleared", RESET_ALLOWLIST.size())
                .log("E2E test data reset complete");
        return ResponseEntity.ok(Map.of("tablesCleared", RESET_ALLOWLIST));
    }

    @PostMapping("/seed/skill")
    ResponseEntity<Map<String, String>> seedSkill(@RequestBody SeedSkillRequest req) throws IOException {
        var skillMd = req.skillMdContent() != null
                ? req.skillMdContent()
                : synthesizeMinimalSkillMd(req.name(), req.description(), req.author());
        var rawBytes = skillMd.getBytes(StandardCharsets.UTF_8);
        var zipBytes = packageService.normalizeToZip(rawBytes);
        var version = req.version() != null ? req.version() : "1.0.0";
        var visibility = req.visibility() != null ? req.visibility() : Visibility.PUBLIC;
        var id = skillCommandService.uploadSkill(
                zipBytes, version, req.author(), req.category(), visibility);
        log.atInfo()
                .addKeyValue("event", "test_data_seed_skill")
                .addKeyValue("skillId", id)
                .addKeyValue("name", req.name())
                .addKeyValue("author", req.author())
                .addKeyValue("version", version)
                .log("E2E seed skill complete");
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PostMapping("/seed/download-event")
    ResponseEntity<Map<String, Integer>> seedDownloadEvent(@RequestBody SeedDownloadEventRequest req) {
        // 平均散布 N 筆 events 在過去 daysAgo 天內：i = 0..count-1，
        // offsetSeconds = (daysAgo * 86400 * i) / max(1, count-1)
        var divisor = Math.max(1, req.count() - 1);
        for (int i = 0; i < req.count(); i++) {
            long offsetSeconds = (long) req.daysAgo() * 86400L * i / divisor;
            var params = new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID().toString())
                    .addValue("skillId", req.skillId())
                    .addValue("offsetSeconds", offsetSeconds);
            // 直 INSERT 不破 outbox/audit invariant — download_events 為 read-side projection
            jdbc.update(
                    """
                    INSERT INTO download_events (id, skill_id, version, downloaded_at)
                    VALUES (:id, :skillId, '1.0.0', now() - make_interval(secs => :offsetSeconds))
                    """,
                    params.getValues());
        }
        // S140-T09: 同步累進 skills.download_count — 對齊 production
        // SkillRepository.incrementDownloadCount 行為。AnalyticsService.getTopSkills
        // 從 skills.download_count 排序（不是 download_events COUNT），少這步 Top 10
        // 永遠顯示 0，AC-6「熱門排行 5 次下載」會掉。
        jdbc.update(
                "UPDATE skills SET download_count = download_count + :delta WHERE id = :skillId",
                new MapSqlParameterSource()
                        .addValue("delta", req.count())
                        .addValue("skillId", req.skillId())
                        .getValues());
        log.atInfo()
                .addKeyValue("event", "test_data_seed_download_event")
                .addKeyValue("skillId", req.skillId())
                .addKeyValue("count", req.count())
                .addKeyValue("daysAgo", req.daysAgo())
                .log("E2E seed download events complete");
        return ResponseEntity.ok(Map.of("count", req.count()));
    }

    /** 自動合成 minimal SKILL.md — agentskills.io frontmatter 必填欄位 (name/description/author/version) 皆有。 */
    private static String synthesizeMinimalSkillMd(String name, String description, String author) {
        return """
                ---
                name: %s
                description: %s
                author: %s
                version: 1.0.0
                license: MIT
                ---

                # %s

                %s
                """.formatted(name, description, author, name, description);
    }
}
