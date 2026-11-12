-- PERF-001: 记录评估尝试次数，便于检索失败任务与人工重放
-- 使用 DEFAULT 0，PostgreSQL 11+ 为元数据变更，不会重写整表
ALTER TABLE interview_sessions
    ADD COLUMN IF NOT EXISTS evaluate_attempt INTEGER DEFAULT 0;

ALTER TABLE voice_interview_sessions
    ADD COLUMN IF NOT EXISTS evaluate_attempt INTEGER DEFAULT 0;
