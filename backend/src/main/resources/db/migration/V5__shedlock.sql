-- ============================================================================
-- S023 V5 — ShedLock 7.7.0 distributed lock 表
-- ============================================================================
--
-- ADR-002 § implementation phase 1
-- 用途：多 Cloud Run instance @Scheduled 任務互斥
--   （避免 incomplete event publication 被多個 instance 同時 retry，
--    造成 listener 多次觸發）
--
-- DDL 來源：ShedLock 7.7.0 官方 README PostgreSQL schema
--   https://github.com/lukas-krecan/ShedLock#configure-lock-provider
--
-- 設計選擇（per deepwiki design-decisions §3 陷阱 5 + ShedLock 研究）：
--   - LockProvider 用 JdbcTemplateLockProvider + usingDbTime()
--     → 由 PostgreSQL NOW() 決定時間戳，規避 cluster clock skew
--   - lockAtMostFor=PT10M（safety net；若 JVM crash 鎖自動過期）
--   - lockAtLeastFor=PT30S（防短任務 + clock skew 重複執行）
--
-- 關聯：
--   - SchedulerConfig.java（@EnableSchedulerLock + LockProvider bean）
--   - IncompleteEventRepublishTask.java（@SchedulerLock 使用 name=
--     'republish-incomplete-events'；本 V5 ship 後仍無 row，由 task 啟動時動態 INSERT）
-- ============================================================================

CREATE TABLE IF NOT EXISTS shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
