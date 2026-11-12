-- 回滚 V4：移除补偿任务重试时间列与索引
DROP INDEX IF EXISTS idx_resource_deletion_due;

ALTER TABLE resource_deletion_compensations
    DROP COLUMN IF EXISTS next_attempt_at;
