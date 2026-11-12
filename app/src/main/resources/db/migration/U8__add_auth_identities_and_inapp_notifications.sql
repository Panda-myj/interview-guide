-- V8 回滚脚本。注意：若已有 phone IS NULL 的第三方身份账号，
-- 恢复 users.phone NOT NULL 会失败，此时需先人工处理这些账号。
DROP INDEX IF EXISTS idx_login_notification_user_read;
ALTER TABLE login_notifications DROP COLUMN IF EXISTS read_at;
UPDATE login_notifications SET phone_snapshot = '' WHERE phone_snapshot IS NULL;
ALTER TABLE login_notifications ALTER COLUMN phone_snapshot SET NOT NULL;

UPDATE users SET phone = '' WHERE phone IS NULL;
ALTER TABLE users ALTER COLUMN phone SET NOT NULL;

DROP TABLE IF EXISTS auth_identities;
