CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    phone VARCHAR(20) NOT NULL UNIQUE,
    display_name VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    last_login_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

INSERT INTO users (phone, display_name, role, status, created_at, updated_at)
SELECT '${bootstrapPhone}', '引导管理员', 'ADMIN', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE '${bootstrapPhone}' <> ''
ON CONFLICT (phone) DO UPDATE SET role = 'ADMIN', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

CREATE TABLE user_devices (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    device_key_hash VARCHAR(64) NOT NULL,
    user_agent_hash VARCHAR(64) NOT NULL,
    user_agent_label VARCHAR(255),
    first_seen_ip VARCHAR(45),
    last_seen_ip VARCHAR(45),
    first_seen_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    CONSTRAINT uk_user_device_key UNIQUE (user_id, device_key_hash)
);

CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    family_id UUID NOT NULL,
    user_id BIGINT NOT NULL REFERENCES users(id),
    device_id BIGINT NOT NULL REFERENCES user_devices(id),
    refresh_token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    rotated_at TIMESTAMP,
    replaced_by_session_id UUID,
    revoked_at TIMESTAMP,
    last_used_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_auth_session_family ON auth_sessions (family_id);
CREATE INDEX idx_auth_session_user ON auth_sessions (user_id, created_at);

CREATE TABLE login_notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    auth_session_id UUID NOT NULL UNIQUE REFERENCES auth_sessions(id),
    phone_snapshot VARCHAR(20) NOT NULL,
    device_label VARCHAR(255),
    login_ip VARCHAR(45),
    login_at TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    sent_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_login_notification_status ON login_notifications (status, created_at);

ALTER TABLE resumes ADD COLUMN owner_user_id BIGINT;
ALTER TABLE knowledge_bases ADD COLUMN owner_user_id BIGINT;
ALTER TABLE interview_sessions ADD COLUMN owner_user_id BIGINT;
ALTER TABLE voice_interview_sessions ADD COLUMN owner_user_id BIGINT;
ALTER TABLE rag_chat_sessions ADD COLUMN owner_user_id BIGINT;
ALTER TABLE interview_schedule ADD COLUMN owner_user_id BIGINT;

DO $$
DECLARE
    bootstrap_admin_id BIGINT;
BEGIN
    SELECT id INTO bootstrap_admin_id
    FROM users
    WHERE role = 'ADMIN'
    ORDER BY id
    LIMIT 1;

    IF bootstrap_admin_id IS NULL AND (
        EXISTS (SELECT 1 FROM resumes)
        OR EXISTS (SELECT 1 FROM knowledge_bases)
        OR EXISTS (SELECT 1 FROM interview_sessions)
        OR EXISTS (SELECT 1 FROM voice_interview_sessions)
        OR EXISTS (SELECT 1 FROM rag_chat_sessions)
        OR EXISTS (SELECT 1 FROM interview_schedule)
    ) THEN
        RAISE EXCEPTION 'APP_AUTH_BOOTSTRAP_PHONE must be configured before migrating existing data';
    END IF;

    IF bootstrap_admin_id IS NOT NULL THEN
        UPDATE resumes SET owner_user_id = bootstrap_admin_id WHERE owner_user_id IS NULL;
        UPDATE knowledge_bases SET owner_user_id = bootstrap_admin_id WHERE owner_user_id IS NULL;
        UPDATE interview_sessions SET owner_user_id = bootstrap_admin_id WHERE owner_user_id IS NULL;
        UPDATE voice_interview_sessions SET owner_user_id = bootstrap_admin_id WHERE owner_user_id IS NULL;
        UPDATE rag_chat_sessions SET owner_user_id = bootstrap_admin_id WHERE owner_user_id IS NULL;
        UPDATE interview_schedule SET owner_user_id = bootstrap_admin_id WHERE owner_user_id IS NULL;
    END IF;
END
$$;

ALTER TABLE resumes ALTER COLUMN owner_user_id SET NOT NULL;
ALTER TABLE knowledge_bases ALTER COLUMN owner_user_id SET NOT NULL;
ALTER TABLE interview_sessions ALTER COLUMN owner_user_id SET NOT NULL;
ALTER TABLE voice_interview_sessions ALTER COLUMN owner_user_id SET NOT NULL;
ALTER TABLE rag_chat_sessions ALTER COLUMN owner_user_id SET NOT NULL;
ALTER TABLE interview_schedule ALTER COLUMN owner_user_id SET NOT NULL;

ALTER TABLE resumes
    ADD CONSTRAINT fk_resume_owner FOREIGN KEY (owner_user_id) REFERENCES users(id);
ALTER TABLE knowledge_bases
    ADD CONSTRAINT fk_kb_owner FOREIGN KEY (owner_user_id) REFERENCES users(id);
ALTER TABLE interview_sessions
    ADD CONSTRAINT fk_interview_owner FOREIGN KEY (owner_user_id) REFERENCES users(id);
ALTER TABLE voice_interview_sessions
    ADD CONSTRAINT fk_voice_owner FOREIGN KEY (owner_user_id) REFERENCES users(id);
ALTER TABLE rag_chat_sessions
    ADD CONSTRAINT fk_rag_session_owner FOREIGN KEY (owner_user_id) REFERENCES users(id);
ALTER TABLE interview_schedule
    ADD CONSTRAINT fk_schedule_owner FOREIGN KEY (owner_user_id) REFERENCES users(id);

ALTER TABLE resumes DROP CONSTRAINT IF EXISTS idx_resume_hash;
ALTER TABLE knowledge_bases DROP CONSTRAINT IF EXISTS idx_kb_hash;
DROP INDEX IF EXISTS idx_resume_hash;
DROP INDEX IF EXISTS idx_kb_hash;
CREATE UNIQUE INDEX uk_resume_owner_hash ON resumes (owner_user_id, file_hash);
CREATE UNIQUE INDEX uk_kb_owner_hash ON knowledge_bases (owner_user_id, file_hash);
CREATE INDEX idx_resume_owner_uploaded ON resumes (owner_user_id, uploaded_at);
CREATE INDEX idx_kb_owner_uploaded ON knowledge_bases (owner_user_id, uploaded_at);
CREATE INDEX idx_interview_owner_created ON interview_sessions (owner_user_id, created_at);
CREATE INDEX idx_voice_owner_updated ON voice_interview_sessions (owner_user_id, updated_at);
CREATE INDEX idx_rag_session_owner_updated ON rag_chat_sessions (owner_user_id, updated_at);
CREATE INDEX idx_schedule_owner_time ON interview_schedule (owner_user_id, interview_time);

ALTER TABLE voice_interview_sessions DROP COLUMN IF EXISTS user_id;

DO $$
BEGIN
    IF to_regclass('public.vector_store') IS NOT NULL THEN
        UPDATE vector_store vectors
        SET metadata = jsonb_set(
            vectors.metadata::jsonb,
            '{user_id}',
            to_jsonb(knowledge_bases.owner_user_id::text),
            true
        )
        FROM knowledge_bases
        WHERE vectors.metadata->>'kb_id' = knowledge_bases.id::text
           OR (
               vectors.metadata->>'kb_id_long' IS NOT NULL
               AND (vectors.metadata->>'kb_id_long')::bigint = knowledge_bases.id
           );
    END IF;
END
$$;
