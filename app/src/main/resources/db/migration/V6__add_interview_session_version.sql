-- V6: 为 interview_sessions 增加乐观锁版本列。
-- InterviewSessionEntity 使用 @Version private Long version（TECH-001 事务竞态修复），
-- 但 V1 基线与 V2-V5 均未包含该列：
--   * 全新数据库（Flyway 建表 + ddl-auto=validate）启动即失败：missing column [version]
--   * 存量数据库（ddl-auto=update）补列失败：column "version" contains null values
-- 本迁移用 DEFAULT 0 回填存量行后再去除默认值，两种场景均可通过。
ALTER TABLE interview_sessions
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;

ALTER TABLE interview_sessions
    ALTER COLUMN version DROP DEFAULT;
