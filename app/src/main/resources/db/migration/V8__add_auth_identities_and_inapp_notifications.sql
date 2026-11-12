-- 身份表抽象（为微信登录预留 provider=WECHAT）、站内登录通知、账号注销状态支持。
-- 设计要点：users.phone 保留为可空的展示字段，身份唯一性由 auth_identities 的
-- (provider, subject) 唯一约束保证；手机号注销后立即可释放。

CREATE TABLE IF NOT EXISTS auth_identities (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(16) NOT NULL,
    subject VARCHAR(128) NOT NULL,
    verified_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_auth_identity_provider_subject UNIQUE (provider, subject)
);

CREATE INDEX IF NOT EXISTS idx_auth_identity_user ON auth_identities (user_id);

-- 存量账号幂等回填：重复执行迁移不产生重复身份行。
-- 引导管理员同样由 V2 的 bootstrapPhone 插入 users，因此自动被覆盖。
INSERT INTO auth_identities (user_id, provider, subject, verified_at, created_at, updated_at)
SELECT id, 'PHONE', phone, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM users
WHERE phone IS NOT NULL AND phone <> ''
ON CONFLICT (provider, subject) DO NOTHING;

-- 微信等第三方身份无需手机号；保留唯一索引，多个 NULL 在 PostgreSQL 中互不冲突。
ALTER TABLE users ALTER COLUMN phone DROP NOT NULL;

-- 登录提醒由短信降级为站内通知：不再保存手机号快照，增加已读时间。
ALTER TABLE login_notifications ALTER COLUMN phone_snapshot DROP NOT NULL;
UPDATE login_notifications SET phone_snapshot = NULL WHERE phone_snapshot IS NOT NULL;
ALTER TABLE login_notifications ADD COLUMN IF NOT EXISTS read_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_login_notification_user_read
    ON login_notifications (user_id, read_at, login_at DESC);

-- 账号注销：status 列为 VARCHAR(16) 无 CHECK 约束，DELETING 由应用枚举约束，无需 DDL。
-- 补偿任务仍需 users 行存在（resource_deletion_compensations.owner_user_id 外键），
-- 因此物理删除 users 由清理完成后的收尾任务执行，见 AccountDeletionFinalizer。
