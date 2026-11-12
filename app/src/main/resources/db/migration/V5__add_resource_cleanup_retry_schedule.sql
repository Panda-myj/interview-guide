-- 资源删除补偿任务：增加可重试时间，支持退避重试与定时扫描
ALTER TABLE resource_deletion_compensations
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_resource_deletion_due
    ON resource_deletion_compensations (status, next_attempt_at);

-- 存量未完成任务立即进入可执行队列
UPDATE resource_deletion_compensations
SET next_attempt_at = updated_at
WHERE status IN ('PENDING', 'FAILED')
  AND next_attempt_at IS NULL;
