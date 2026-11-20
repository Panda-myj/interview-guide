-- 用户级多卡片模型配置：每个用户可保存多个 Provider 卡片（文本 + 向量），
-- 并单独选择默认文本模型 / 默认向量模型；语音（ASR/TTS）配置按用户 1:1 存储。

CREATE TABLE IF NOT EXISTS user_llm_provider (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    api_key_ciphertext VARCHAR(4096) NOT NULL,
    api_key_nonce VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    embedding_model VARCHAR(128),
    embedding_dimensions INTEGER,
    supports_embedding BOOLEAN NOT NULL DEFAULT FALSE,
    temperature DOUBLE PRECISION,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_llm_provider_user ON user_llm_provider (user_id);

CREATE TABLE IF NOT EXISTS user_llm_setting (
    user_id BIGINT PRIMARY KEY,
    default_chat_provider_id BIGINT,
    default_embedding_provider_id BIGINT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS user_voice_config (
    user_id BIGINT PRIMARY KEY,
    asr_url VARCHAR(512),
    asr_model VARCHAR(128),
    asr_api_key_ciphertext VARCHAR(4096),
    asr_api_key_nonce VARCHAR(64),
    asr_language VARCHAR(32),
    asr_format VARCHAR(32),
    asr_sample_rate INTEGER,
    asr_enable_turn_detection BOOLEAN,
    asr_turn_detection_type VARCHAR(64),
    asr_turn_detection_threshold DOUBLE PRECISION,
    asr_turn_detection_silence_duration_ms INTEGER,
    tts_model VARCHAR(128),
    tts_api_key_ciphertext VARCHAR(4096),
    tts_api_key_nonce VARCHAR(64),
    tts_voice VARCHAR(128),
    tts_format VARCHAR(32),
    tts_sample_rate INTEGER,
    tts_mode VARCHAR(64),
    tts_language_type VARCHAR(64),
    tts_speech_rate DOUBLE PRECISION,
    tts_volume INTEGER,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- 迁移：旧的单条个人配置（user_llm_config）转成一张默认文本模型卡片
WITH moved AS (
    INSERT INTO user_llm_provider
        (user_id, name, base_url, api_key_ciphertext, api_key_nonce,
         model, temperature, created_at, updated_at)
    SELECT user_id, '我的模型', base_url, api_key_ciphertext, api_key_nonce,
           model, temperature, created_at, updated_at
    FROM user_llm_config
    RETURNING id, user_id
)
INSERT INTO user_llm_setting (user_id, default_chat_provider_id, created_at, updated_at)
SELECT user_id, id, now(), now() FROM moved;

DROP TABLE IF EXISTS user_llm_config;
