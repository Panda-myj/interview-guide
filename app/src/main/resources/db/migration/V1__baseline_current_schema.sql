CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS resumes (
    id BIGSERIAL PRIMARY KEY,
    file_hash VARCHAR(64) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    file_size BIGINT,
    content_type VARCHAR(255),
    storage_key VARCHAR(500),
    storage_url VARCHAR(1000),
    resume_text TEXT,
    uploaded_at TIMESTAMP NOT NULL,
    last_accessed_at TIMESTAMP,
    access_count INTEGER DEFAULT 0,
    analyze_status VARCHAR(20),
    analyze_error VARCHAR(500)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_resume_hash ON resumes (file_hash);

CREATE TABLE IF NOT EXISTS resume_analyses (
    id BIGSERIAL PRIMARY KEY,
    resume_id BIGINT NOT NULL REFERENCES resumes(id),
    overall_score INTEGER,
    content_score INTEGER,
    structure_score INTEGER,
    skill_match_score INTEGER,
    expression_score INTEGER,
    project_score INTEGER,
    summary TEXT,
    strengths_json TEXT,
    suggestions_json TEXT,
    analyzed_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_resume_analysis_resume ON resume_analyses (resume_id);

CREATE TABLE IF NOT EXISTS interview_sessions (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL UNIQUE,
    skill_id VARCHAR(64),
    difficulty VARCHAR(16),
    resume_id BIGINT REFERENCES resumes(id),
    total_questions INTEGER,
    current_question_index INTEGER DEFAULT 0,
    status VARCHAR(20),
    questions_json TEXT,
    overall_score INTEGER,
    overall_feedback TEXT,
    strengths_json TEXT,
    improvements_json TEXT,
    reference_answers_json TEXT,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    evaluate_status VARCHAR(20),
    evaluate_error VARCHAR(500),
    llm_provider VARCHAR(50)
);
CREATE INDEX IF NOT EXISTS idx_interview_session_resume_created
    ON interview_sessions (resume_id, created_at);
CREATE INDEX IF NOT EXISTS idx_interview_session_resume_status_created
    ON interview_sessions (resume_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_interview_session_skill_created
    ON interview_sessions (skill_id, created_at);

CREATE TABLE IF NOT EXISTS interview_answers (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES interview_sessions(id),
    question_index INTEGER,
    question TEXT,
    category VARCHAR(255),
    user_answer TEXT,
    score INTEGER,
    feedback TEXT,
    reference_answer TEXT,
    key_points_json TEXT,
    answered_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_interview_answer_session_question UNIQUE (session_id, question_index)
);
CREATE INDEX IF NOT EXISTS idx_interview_answer_session_question
    ON interview_answers (session_id, question_index);

CREATE TABLE IF NOT EXISTS voice_interview_sessions (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255),
    role_type VARCHAR(255) NOT NULL,
    skill_id VARCHAR(64),
    difficulty VARCHAR(16),
    custom_jd_text TEXT,
    resume_id BIGINT,
    intro_enabled BOOLEAN,
    tech_enabled BOOLEAN,
    project_enabled BOOLEAN,
    hr_enabled BOOLEAN,
    llm_provider VARCHAR(50),
    current_phase VARCHAR(255),
    status VARCHAR(255),
    planned_duration INTEGER,
    actual_duration INTEGER,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    paused_at TIMESTAMP,
    resumed_at TIMESTAMP,
    evaluate_status VARCHAR(20),
    evaluate_error VARCHAR(500)
);

CREATE TABLE IF NOT EXISTS voice_interview_messages (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT,
    message_type VARCHAR(255) NOT NULL,
    phase VARCHAR(255),
    user_recognized_text TEXT,
    ai_generated_text TEXT,
    timestamp TIMESTAMP,
    sequence_num INTEGER,
    created_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_voice_message_session_sequence
    ON voice_interview_messages (session_id, sequence_num);

CREATE TABLE IF NOT EXISTS voice_interview_evaluations (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT UNIQUE,
    overall_score INTEGER,
    overall_feedback TEXT,
    question_evaluations_json TEXT,
    strengths_json TEXT,
    improvements_json TEXT,
    reference_answers_json TEXT,
    interviewer_role VARCHAR(255),
    interview_date TIMESTAMP,
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS knowledge_bases (
    id BIGSERIAL PRIMARY KEY,
    file_hash VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    category VARCHAR(100),
    original_filename VARCHAR(255) NOT NULL,
    file_size BIGINT,
    content_type VARCHAR(255),
    storage_key VARCHAR(500),
    storage_url VARCHAR(1000),
    uploaded_at TIMESTAMP NOT NULL,
    last_accessed_at TIMESTAMP,
    access_count INTEGER DEFAULT 0,
    question_count INTEGER DEFAULT 0,
    vector_status VARCHAR(20),
    vector_error VARCHAR(500),
    chunk_count INTEGER DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_kb_hash ON knowledge_bases (file_hash);
CREATE INDEX IF NOT EXISTS idx_kb_category ON knowledge_bases (category);

CREATE TABLE IF NOT EXISTS rag_chat_sessions (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(20),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    message_count INTEGER DEFAULT 0,
    is_pinned BOOLEAN DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_rag_session_updated ON rag_chat_sessions (updated_at);

CREATE TABLE IF NOT EXISTS rag_chat_messages (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES rag_chat_sessions(id),
    type VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    message_order INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    completed BOOLEAN DEFAULT TRUE
);
CREATE INDEX IF NOT EXISTS idx_rag_message_session ON rag_chat_messages (session_id);
CREATE INDEX IF NOT EXISTS idx_rag_message_order ON rag_chat_messages (session_id, message_order);

CREATE TABLE IF NOT EXISTS rag_session_knowledge_bases (
    session_id BIGINT NOT NULL REFERENCES rag_chat_sessions(id),
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_bases(id),
    PRIMARY KEY (session_id, knowledge_base_id)
);

CREATE TABLE IF NOT EXISTS llm_provider_config (
    id VARCHAR(64) PRIMARY KEY,
    base_url VARCHAR(512) NOT NULL,
    api_key_ciphertext VARCHAR(4096) NOT NULL,
    api_key_nonce VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    embedding_model VARCHAR(128),
    embedding_dimensions INTEGER,
    supports_embedding BOOLEAN NOT NULL,
    temperature DOUBLE PRECISION,
    enabled BOOLEAN NOT NULL,
    builtin BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS llm_global_setting (
    id BIGINT PRIMARY KEY,
    default_chat_provider_id VARCHAR(64) NOT NULL,
    default_embedding_provider_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS interview_schedule (
    id BIGSERIAL PRIMARY KEY,
    company_name VARCHAR(255) NOT NULL,
    position VARCHAR(255) NOT NULL,
    interview_time TIMESTAMP NOT NULL,
    interview_type VARCHAR(255),
    meeting_link TEXT,
    round_number INTEGER DEFAULT 1,
    interviewer VARCHAR(255),
    notes TEXT,
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_schedule_time ON interview_schedule (interview_time);
CREATE INDEX IF NOT EXISTS idx_schedule_status ON interview_schedule (status);
