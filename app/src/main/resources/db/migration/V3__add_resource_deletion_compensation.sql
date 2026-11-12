CREATE TABLE IF NOT EXISTS resource_deletion_compensations (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES users(id),
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    storage_key VARCHAR(500),
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    CONSTRAINT uk_resource_deletion_compensation UNIQUE (
        owner_user_id, resource_type, resource_id, storage_key
    )
);

CREATE INDEX IF NOT EXISTS idx_resource_deletion_pending
    ON resource_deletion_compensations (status, updated_at);
CREATE INDEX IF NOT EXISTS idx_resource_deletion_owner
    ON resource_deletion_compensations (owner_user_id, resource_type);

