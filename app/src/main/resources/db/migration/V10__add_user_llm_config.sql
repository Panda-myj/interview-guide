-- 用户级大模型配置：每个登录用户一份自己的 Chat Provider 配置（1:1）
CREATE TABLE IF NOT EXISTS user_llm_config (
    user_id BIGINT PRIMARY KEY,
    base_url VARCHAR(512) NOT NULL,
    api_key_ciphertext VARCHAR(4096) NOT NULL,
    api_key_nonce VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    temperature DOUBLE PRECISION,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
