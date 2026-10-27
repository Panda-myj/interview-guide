# 数据库表设计文档（sql_entity.md）

> 项目：AI Interview Platform（Spring Boot 4.1 + PostgreSQL 16 + pgvector + Redis）
> 数据库：PostgreSQL 16（pgvector 插件，向量维度 1024，HNSW 索引，COSINE_DISTANCE）
> JPA 策略：`ddl-auto: update`（开发环境），生产环境手动管理 schema

---

## 一、业务域总览

整个系统划分为 6 个业务域，共 **15 张表**（含 1 张 pgvector 向量表 + 1 张 M:N 中间表）：

| 业务域 | 表 | 核心职责 |
|--------|-----|----------|
| **简历域** | `resumes`、`resume_analyses` | 简历上传去重 + AI 评测 |
| **面试域** | `interview_sessions`、`interview_answers` | 文字面试会话 + 逐题作答 |
| **语音面试域** | `voice_interview_sessions`、`voice_interview_messages`、`voice_interview_evaluations` | 语音面试 + 消息流 + 评估 |
| **知识库/RAG域** | `knowledge_bases`、`vector_store`、`rag_chat_sessions`、`rag_chat_messages`、`rag_session_knowledge_bases` | 文档向量化 + 检索问答 |
| **LLM 配置域** | `llm_provider_config`、`llm_global_setting` | 多模型供应商配置（含密钥加密） |
| **面试日程域** | `interview_schedule` | 面试日程管理 |

---

## 二、实体关系总图（ER）

```
┌──────────────┐          ┌────────────────────┐
│   resumes    │ 1 ─── N  │  resume_analyses   │
└──────┬───────┘          └────────────────────┘
       │ 1
       │
       ├───────────────┐
       │ N             │ N（可选，可无简历通用面试）
┌──────▼────────┐  ┌────▼──────────────────┐
│ interview_   │  │   voice_interview_     │
│ sessions     │  │   sessions             │
└──────┬───────┘  └────┬───────────────────┘
       │ 1             │ 1
       │               ├──────────────┬──────────────┐
       │ N             │ N            │ 1            │ 1
┌──────▼────────┐ ┌────▼─────────┐ ┌──▼─────────┐ ┌──▼────────────────┐
│ interview_    │ │ voice_interview_ │ voice_    │ │ voice_interview_  │
│ answers       │ │ messages         │ evaluation│ │ (session_id 唯一) │
└───────────────┘ └─────────────────┘ └───────────┘ └────────────────────┘

┌────────────────┐          ┌──────────────────────┐
│ knowledge_bases│ 1 ─── N  │  vector_store (pgvector) │  ← 逻辑关联（metadata 存 kb_id）
└────────┬───────┘          └──────────────────────┘
         │ M
         │            ┌───────────────────────────┐
         └───────────►│ rag_session_knowledge_bases │（M:N 中间表）
                      └──────────────┬─────────────┘
                                     │ N
                            ┌────────▼─────────┐
                            │  rag_chat_sessions│
                            └────────┬─────────┘
                                     │ 1
                                     │ N
                            ┌────────▼─────────┐
                            │  rag_chat_messages│
                            └──────────────────┘

┌────────────────────┐          ┌──────────────────────┐
│ llm_provider_config│ 1 ────► │  llm_global_setting   │  ← 逻辑外键（存 provider_id 字符串）
└────────────────────┘          └──────────────────────┘

┌────────────────────┐
│ interview_schedule │  ← 独立域，无关联
└────────────────────┘
```

---

## 三、逐表设计详解

### 3.1 简历域

#### `resumes`（简历表）

**关系**：
- `resumes` 1:N `resume_analyses`（一份简历可多次评测）
- `resumes` 1:N `interview_sessions`（一份简历可发起多次面试，可选）

**字段**：

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | BIGSERIAL | PK | 自增主键 |
| `file_hash` | VARCHAR(64) | UNIQUE, NOT NULL | SHA-256，去重核心 |
| `original_filename` | VARCHAR | NOT NULL | 原始文件名 |
| `file_size` | BIGINT | | 字节 |
| `content_type` | VARCHAR | | MIME 类型 |
| `storage_key` | VARCHAR(500) | | RustFS 存储 key |
| `storage_url` | VARCHAR(1000) | | RustFS 访问 URL |
| `resume_text` | TEXT | | 解析后的简历全文 |
| `uploaded_at` | TIMESTAMP | NOT NULL | 上传时间（`@PrePersist`） |
| `last_accessed_at` | TIMESTAMP | | 最后访问时间 |
| `access_count` | INT | DEFAULT 0 | 访问次数（冗余计数） |
| `analyze_status` | VARCHAR(20) | | PENDING/PROCESSING/COMPLETED/FAILED |
| `analyze_error` | VARCHAR(500) | | 失败错误信息 |

**索引**：

| 索引名 | 列 | 类型 | 服务的查询 |
|--------|-----|------|-----------|
| `idx_resume_hash` | `file_hash` | UNIQUE | 上传去重（`findByFileHash`）、幂等上传 |

**设计要点**：
- `file_hash` 唯一索引是**去重关键**，同一文件重复上传直接命中已存在记录，避免重复解析+向量化+存储。
- `resume_text` 用 TEXT 存全文，配合 `storage_key` 实现「元数据在 PG、大文件在对象存储」的分离。

---

#### `resume_analyses`（简历评测结果表）

**关系**：N:1 → `resumes`（`resume_id` FK）

**字段**：

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | BIGSERIAL | PK | |
| `resume_id` | BIGINT | FK, NOT NULL | 关联简历 |
| `overall_score` | INT | | 总分 0-100 |
| `content_score` | INT | | 内容完整性 0-25 |
| `structure_score` | INT | | 结构清晰度 0-20 |
| `skill_match_score` | INT | | 技能匹配度 0-25 |
| `expression_score` | INT | | 表达专业性 0-15 |
| `project_score` | INT | | 项目经验 0-15 |
| `summary` | TEXT | | AI 生成的简历摘要 |
| `strengths_json` | TEXT | | 优点列表（JSON 字符串） |
| `suggestions_json` | TEXT | | 改进建议（JSON 字符串） |
| `analyzed_at` | TIMESTAMP | NOT NULL | 评测时间 |

**索引**：无显式索引（`resume_id` FK 依赖 JPA 自动生成的 FK，查询走 `resume_id` 时需补充）

**设计要点**：
- 五维评分（25+20+25+15+15=100）拆成独立列，而非存 JSON，方便后续按维度统计排序。
- `strengths_json`/`suggestions_json` 用 TEXT 存 JSON 数组，是**非结构化数组字段的 JSON 序列化妥协**——避免为列表建子表。

---

### 3.2 面试域

#### `interview_sessions`（面试会话表）

**关系**：
- N:1 → `resumes`（可选，`resume_id` 可空）
- 1:N → `interview_answers`

**字段**：

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | BIGSERIAL | PK | |
| `session_id` | VARCHAR(36) | UNIQUE, NOT NULL | UUID 业务标识 |
| `skill_id` | VARCHAR(64) | DEFAULT 'java-backend' | 面试主题 |
| `difficulty` | VARCHAR(16) | DEFAULT 'mid' | junior/mid/senior |
| `resume_id` | BIGINT | FK（可空） | 简历 ID（**冗余列 + 关联对象双写**） |
| `resume` | - | ManyToOne LAZY | 关联简历对象 |
| `total_questions` | INT | | 问题总数 |
| `current_question_index` | INT | DEFAULT 0 | 当前题目游标 |
| `status` | VARCHAR(20) | | CREATED/IN_PROGRESS/COMPLETED/EVALUATED |
| `questions_json` | TEXT | | 问题列表（JSON） |
| `overall_score` | INT | | 总分 |
| `overall_feedback` | TEXT | | 总体评价 |
| `strengths_json` | TEXT | | 优势（JSON） |
| `improvements_json` | TEXT | | 改进建议（JSON） |
| `reference_answers_json` | TEXT | | 参考答案（JSON） |
| `created_at` | TIMESTAMP | NOT NULL | 创建时间 |
| `completed_at` | TIMESTAMP | | 完成时间 |
| `evaluate_status` | VARCHAR(20) | | 异步评估状态 |
| `evaluate_error` | VARCHAR(500) | | 评估错误 |
| `llm_provider` | VARCHAR(50) | DEFAULT 'dashscope' | LLM 供应商 |

**索引**：

| 索引名 | 列 | 类型 | 服务的查询 |
|--------|-----|------|-----------|
| `idx_interview_session_resume_created` | `resume_id, created_at` | 复合 | 查某简历的面试历史（按时间排序） |
| `idx_interview_session_resume_status_created` | `resume_id, status, created_at` | 复合 | 查某简历某状态的面试 |
| `idx_interview_session_skill_created` | `skillId, createdAt` | 复合 | 按技能维度查面试记录 |

**设计要点（重点）**：
- `resume_id` 做了**双写**：既有 `@JoinColumn(name="resume_id")` 的实体关联，又有 `@Column(name="resume_id", insertable=false, updatable=false)` 的**冗余标量列**。目的：读取会话列表时直接用 `resumeId` 标量，**避免 LAZY 加载触发 N+1 查询**。
- `questions_json`、`strengths_json` 等 5 个 JSON 列集中存储，避免为「问题」「优势」等建独立表，减少 JOIN。
- `status` 状态机：`CREATED → IN_PROGRESS → COMPLETED → EVALUATED`，评估走异步（`evaluate_status` 分离）。

---

#### `interview_answers`（面试答案表）

**关系**：N:1 → `interview_sessions`（`session_id` FK）

**字段**：

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | BIGSERIAL | PK | |
| `session_id` | BIGINT | FK, NOT NULL | 关联会话 |
| `question_index` | INT | | 题号 |
| `question` | TEXT | | 题目内容 |
| `category` | VARCHAR | | 题目类别 |
| `user_answer` | TEXT | | 用户答案 |
| `score` | INT | | 得分 0-100 |
| `feedback` | TEXT | | AI 反馈 |
| `reference_answer` | TEXT | | 参考答案 |
| `key_points_json` | TEXT | | 关键点（JSON） |
| `answered_at` | TIMESTAMP | NOT NULL | 作答时间 |

**索引**：

| 索引名 | 列 | 类型 | 服务的查询 |
|--------|-----|------|-----------|
| `uk_interview_answer_session_question` | `session_id, question_index` | UNIQUE | 防重复提交同题 |
| `idx_interview_answer_session_question` | `session_id, question_index` | 普通 | 按会话+题号查询 |

**设计要点**：
- `(session_id, question_index)` 既是唯一约束又是索引，**防止同一会话同一题重复作答**，同时加速「按题号取答案」查询。
- 注意：唯一约束已自动建索引，普通索引 `idx_interview_answer_session_question` 与之**冗余**（可合并，属轻微过度设计）。

---

### 3.3 语音面试域

#### `voice_interview_sessions`（语音面试会话表）

**关系**：
- 逻辑关联 `resumes`（存 `resume_id` 标量，无 FK 实体关联）
- 1:N → `voice_interview_messages`
- 1:1 → `voice_interview_evaluations`（通过 `session_id` 唯一约束）

**字段**：

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | BIGSERIAL | PK | |
| `user_id` | VARCHAR | | 用户标识 |
| `role_type` | VARCHAR | NOT NULL | 面试角色类型 |
| `skill_id` | VARCHAR(64) | DEFAULT 'java-backend' | 技能 |
| `difficulty` | VARCHAR(16) | DEFAULT 'mid' | 难度 |
| `custom_jd_text` | TEXT | | 自定义 JD |
| `resume_id` | BIGINT | | 简历 ID（标量，无 FK） |
| `intro_enabled` / `tech_enabled` / `project_enabled` / `hr_enabled` | BOOLEAN | DEFAULT true | 各环节开关 |
| `llm_provider` | VARCHAR(50) | DEFAULT 'dashscope' | LLM 供应商 |
| `current_phase` | VARCHAR | | INTRO/TECH/PROJECT/HR/COMPLETED |
| `status` | VARCHAR | DEFAULT IN_PROGRESS | 会话状态 |
| `planned_duration` | INT | DEFAULT 30 | 计划时长（分） |
| `actual_duration` | INT | | 实际时长 |
| `start_time` / `end_time` / `paused_at` / `resumed_at` | TIMESTAMP | | 时间轴 |
| `evaluate_status` | VARCHAR | | 异步评估状态 |
| `evaluate_error` | VARCHAR(500) | | 评估错误 |

**索引**：无显式索引

**设计要点**：
- 语音面试会话**独立于文字面试**（`interview_sessions`），因流程差异大（环节开关、暂停/恢复、时长统计）。
- `resume_id` 是**纯标量**（`@Column(name="resume_id")`），不建 `@ManyToOne`，解耦简历域。

---

#### `voice_interview_messages`（语音面试消息表）

**关系**：N:1 → `voice_interview_sessions`（存 `session_id` 标量，无实体 FK）

**字段**：

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | BIGSERIAL | PK | |
| `session_id` | BIGINT | | 会话 ID（标量） |
| `message_type` | VARCHAR | NOT NULL | USER_SPEECH/AI_SPEECH/SYSTEM |
| `phase` | VARCHAR | | 所属环节 |
| `user_recognized_text` | TEXT | | 语音识别文本 |
| `ai_generated_text` | TEXT | | AI 生成文本 |
| `timestamp` | TIMESTAMP | | 消息时间 |
| `sequence_num` | INT | | 顺序号 |
| `created_at` | TIMESTAMP | | 创建时间 |

**索引**：无显式索引（`session_id` 查询需补充）

**设计要点**：
- 语音面试的消息比文字面试多「ASR 识别文本 + AI 生成文本」双字段，且**未建实体 FK**（存 `session_id` 标量），读写更快但无级联删除保护。

---

#### `voice_interview_evaluations`（语音面试评估表）

**关系**：1:1 → `voice_interview_sessions`（`session_id` 唯一）

**字段**：

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | BIGSERIAL | PK | |
| `session_id` | BIGINT | UNIQUE | 会话 ID（1:1） |
| `overall_score` | INT | | 总分 |
| `overall_feedback` | TEXT | | 总体评价 |
| `question_evaluations_json` | TEXT | | 逐题评估（JSON） |
| `strengths_json` | TEXT | | 优势（JSON） |
| `improvements_json` | TEXT | | 改进（JSON） |
| `reference_answers_json` | TEXT | | 参考答案（JSON） |
| `interviewer_role` | VARCHAR | | 面试官角色 |
| `interview_date` | TIMESTAMP | | 面试日期 |
| `created_at` | TIMESTAMP | | 创建时间 |

**索引**：`session_id` UNIQUE（自动建唯一索引）

**设计要点**：
- 评估结果独立成表（而非塞进会话表），`session_id` 唯一约束保证 1:1。
- 评估结构对齐文字面试（`overall_score` + `strengths_json` + `improvements_json` + `reference_answers_json`），保证两套面试评估逻辑可复用。

---

### 3.4 知识库 / RAG 域

#### `knowledge_bases`（知识库表）

**关系**：
- 1:N → `vector_store`（逻辑关联，通过 metadata 中的 `kb_id`）
- M:N → `rag_chat_sessions`（通过中间表）

**字段**：

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | BIGSERIAL | PK | |
| `file_hash` | VARCHAR(64) | UNIQUE, NOT NULL | SHA-256 去重 |
| `name` | VARCHAR | NOT NULL | 知识库名称 |
| `category` | VARCHAR(100) | | 分类（"Java面试"等） |
| `original_filename` | VARCHAR | NOT NULL | 原始文件名 |
| `file_size` | BIGINT | | 字节 |
| `content_type` | VARCHAR | | MIME |
| `storage_key` | VARCHAR(500) | | RustFS key |
| `storage_url` | VARCHAR(1000) | | RustFS URL |
| `uploaded_at` | TIMESTAMP | NOT NULL | 上传时间 |
| `last_accessed_at` | TIMESTAMP | | 最后访问 |
| `access_count` | INT | DEFAULT 0 | 访问次数 |
| `question_count` | INT | DEFAULT 0 | 提问次数 |
| `vector_status` | VARCHAR(20) | DEFAULT PENDING | PENDING/PROCESSING/COMPLETED/FAILED |
| `vector_error` | VARCHAR(500) | | 向量化错误 |
| `chunk_count` | INT | DEFAULT 0 | 分块数量（冗余） |

**索引**：

| 索引名 | 列 | 类型 | 服务的查询 |
|--------|-----|------|-----------|
| `idx_kb_hash` | `file_hash` | UNIQUE | 上传去重 |
| `idx_kb_category` | `category` | 普通 | 按分类筛选知识库列表 |

**设计要点**：
- 与 `resumes` 结构高度相似（文件元数据 + RustFS 存储 + 去重哈希），但多了 `vector_status`/`chunk_count`/`question_count` 三个向量化相关字段。
- `question_count` 是**冗余计数**，在 `incrementQuestionCount()` 中自增，避免每次统计 count。
- `vector_status` 状态机驱动异步向量化流程（Redis Stream 消费端更新）。

---

#### `vector_store`（pgvector 向量表，非 JPA 实体）

> 由 Spring AI `PgVectorStore` 自动创建，非 JPA 实体，通过 `VectorRepository`（`JdbcTemplate`）直接操作。

**表结构（Spring AI 默认 schema）**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | UUID | 主键 |
| `content` | TEXT | 分块后的文本内容 |
| `metadata` | JSON | 元数据（存 `kb_id`、`kb_target_id`、`kb_vector_job_id` 等） |
| `embedding` | VECTOR(1024) | 向量（1024 维，text-embedding-v3） |

**索引**：

| 索引 | 说明 |
|------|------|
| HNSW 索引（`embedding`） | 向量相似度搜索，COSINE_DISTANCE |
| `metadata` 上的查询 | 通过 `metadata->>'kb_id'` 过滤（JSON 键查询） |

**设计要点（两阶段提交的核心）**：
- 向量写入时，`metadata` 中的 `kb_id` 先存**临时值** `pending:{kbId}:{jobId}`。
- 全部 chunk 向量化成功后，用一条 SQL `jsonb_set` **原子替换** `kb_id` 为正式值，并移除 `kb_vector_job_id`/`kb_target_id` 临时标记。
- 失败时按 `kb_vector_job_id` 删除临时向量，**避免脏向量污染检索**。
- 删除用 `metadata->>'kb_id'` 或 `kb_id_long`（BigInt 兜底）双重匹配，兼容 Spring AI 的字符串/长整型两种元数据类型。

---

#### `rag_chat_sessions`（RAG 会话表）

**关系**：
- M:N → `knowledge_bases`（通过 `rag_session_knowledge_bases`）
- 1:N → `rag_chat_messages`

**字段**：

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | BIGSERIAL | PK | |
| `title` | VARCHAR | NOT NULL | 会话标题 |
| `status` | VARCHAR(20) | DEFAULT ACTIVE | ACTIVE/ARCHIVED |
| `created_at` | TIMESTAMP | NOT NULL | 创建时间 |
| `updated_at` | TIMESTAMP | | 更新时间（最后消息） |
| `message_count` | INT | DEFAULT 0 | 消息数（冗余） |
| `is_pinned` | BOOLEAN | DEFAULT false | 是否置顶 |

**索引**：

| 索引名 | 列 | 类型 | 服务的查询 |
|--------|-----|------|-----------|
| `idx_rag_session_updated` | `updatedAt` | 普通 | 按更新时间排序会话列表 |

**设计要点**：
- 一个会话可关联**多个知识库**（M:N），支持跨库联合检索问答。
- `message_count` 冗余，`addMessage()` 时自增，列表展示无需 count 子查询。
- `updated_at` 索引支撑「最近会话列表」按时间倒序。

---

#### `rag_chat_messages`（RAG 消息表）

**关系**：N:1 → `rag_chat_sessions`

**字段**：

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | BIGSERIAL | PK | |
| `session_id` | BIGINT | FK, NOT NULL | 关联会话 |
| `type` | VARCHAR(20) | NOT NULL | USER/ASSISTANT |
| `content` | TEXT | NOT NULL | 消息内容 |
| `message_order` | INT | NOT NULL | 顺序号 |
| `created_at` | TIMESTAMP | NOT NULL | 创建时间 |
| `updated_at` | TIMESTAMP | | 更新时间（流式更新） |
| `completed` | BOOLEAN | DEFAULT true | 流式是否完成 |

**索引**：

| 索引名 | 列 | 类型 | 服务的查询 |
|--------|-----|------|-----------|
| `idx_rag_message_session` | `session_id` | 普通 | 按会话查消息 |
| `idx_rag_message_order` | `session_id, messageOrder` | 复合 | 按会话+顺序排序查询 |

**设计要点**：
- `idx_rag_message_session`（单列）与 `idx_rag_message_order`（复合）存在**冗余**——复合索引 `(session_id, message_order)` 已覆盖单列 `session_id` 查询。属轻微过度设计。
- `completed` 字段支持**流式响应**：SSE 输出期间先写 `completed=false`，完成后置 `true`，断点续传可识别未完成消息。

---

#### `rag_session_knowledge_bases`（M:N 中间表）

**关系**：M:N（`rag_chat_sessions` ↔ `knowledge_bases`）

**字段**：

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `session_id` | BIGINT | FK, PK（联合） | 会话 ID |
| `knowledge_base_id` | BIGINT | FK, PK（联合） | 知识库 ID |

**设计要点**：
- 由 `@ManyToMany` + `@JoinTable` 自动生成，联合主键保证一个会话同一知识库只关联一次。

---

### 3.5 LLM 配置域

#### `llm_provider_config`（LLM 供应商配置表）

**关系**：1:N → `llm_global_setting`（逻辑外键，存 provider_id 字符串）

**字段**：

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | VARCHAR(64) | PK（**字符串主键**） | 供应商标识（如 "dashscope"） |
| `base_url` | VARCHAR(512) | NOT NULL | API 地址 |
| `api_key_ciphertext` | VARCHAR(4096) | NOT NULL | 加密后的 API Key |
| `api_key_nonce` | VARCHAR(64) | NOT NULL | 加密 nonce |
| `model` | VARCHAR(128) | NOT NULL | 模型名 |
| `embedding_model` | VARCHAR(128) | | embedding 模型 |
| `embedding_dimensions` | INT | | 向量维度 |
| `supports_embedding` | BOOLEAN | NOT NULL | 是否支持 embedding |
| `temperature` | DOUBLE | | 采样温度 |
| `enabled` | BOOLEAN | NOT NULL | 是否启用 |
| `builtin` | BOOLEAN | NOT NULL | 是否内置 |
| `created_at` / `updated_at` | TIMESTAMP | NOT NULL | 时间戳 |

**设计要点（安全重点）**：
- **API Key 加密存储**：`api_key_ciphertext`（密文）+ `api_key_nonce`（加密随机数），**绝不存明文密钥**。
- 用**字符串主键**（如 "dashscope"）而非自增 ID，使 provider 标识稳定、可直接作为配置引用 key。

---

#### `llm_global_setting`（LLM 全局设置表）

**关系**：逻辑 1:1 → `llm_provider_config`（存默认 provider_id）

**字段**：

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | BIGINT | PK | 固定为 1（单例） |
| `default_chat_provider_id` | VARCHAR(64) | NOT NULL | 默认聊天供应商 |
| `default_embedding_provider_id` | VARCHAR(64) | NOT NULL | 默认 embedding 供应商 |
| `created_at` / `updated_at` | TIMESTAMP | NOT NULL | 时间戳 |

**设计要点**：
- **单例表**（`SINGLETON_ID = 1L`），全系统只有一行全局默认配置，避免多行冗余。
- `default_chat_provider_id` 长度 64，与 `llm_provider_config.id` 一致，保证逻辑外键引用兼容。

---

### 3.6 面试日程域

#### `interview_schedule`（面试日程表）

**关系**：独立域，无关联

**字段**：

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | BIGSERIAL | PK | |
| `company_name` | VARCHAR | NOT NULL | 公司名 |
| `position` | VARCHAR | NOT NULL | 职位 |
| `interview_time` | TIMESTAMP | NOT NULL | 面试时间 |
| `interview_type` | VARCHAR | | ONSITE/VIDEO/PHONE |
| `meeting_link` | TEXT | | 会议链接 |
| `round_number` | INT | DEFAULT 1 | 轮次 |
| `interviewer` | VARCHAR | | 面试官 |
| `notes` | TEXT | | 备注 |
| `status` | VARCHAR | NOT NULL | PENDING/...（面试状态） |
| `created_at` / `updated_at` | TIMESTAMP | | 时间戳 |

**索引**：无显式索引（`interview_time`/`status` 查询需补充）

**设计要点**：
- 纯独立域，与简历/面试域无 FK，功能简单。

---

## 四、索引汇总与优化建议

### 4.1 现有索引清单

| 表 | 索引 | 列 | 类型 | 必要性 |
|-----|------|-----|------|--------|
| `resumes` | `idx_resume_hash` | `file_hash` | UNIQUE | ✅ 去重核心 |
| `knowledge_bases` | `idx_kb_hash` | `file_hash` | UNIQUE | ✅ 去重核心 |
| `knowledge_bases` | `idx_kb_category` | `category` | 普通 | ✅ 分类筛选 |
| `interview_sessions` | `idx_interview_session_resume_created` | `resume_id, created_at` | 复合 | ✅ 历史查询 |
| `interview_sessions` | `idx_interview_session_resume_status_created` | `resume_id, status, created_at` | 复合 | ⚠️ 与上一条部分重叠 |
| `interview_sessions` | `idx_interview_session_skill_created` | `skillId, createdAt` | 复合 | ✅ 技能维度查询 |
| `interview_answers` | `uk_interview_answer_session_question` | `session_id, question_index` | UNIQUE | ✅ 防重 |
| `interview_answers` | `idx_interview_answer_session_question` | `session_id, question_index` | 普通 | ❌ 与唯一索引冗余 |
| `rag_chat_sessions` | `idx_rag_session_updated` | `updatedAt` | 普通 | ✅ 最近会话排序 |
| `rag_chat_messages` | `idx_rag_message_session` | `session_id` | 普通 | ❌ 被复合索引覆盖 |
| `rag_chat_messages` | `idx_rag_message_order` | `session_id, messageOrder` | 复合 | ✅ 按序查询 |
| `vector_store` | HNSW（embedding） | `embedding` | 向量 | ✅ 相似度搜索 |

### 4.2 建议补充的索引（当前缺失）

| 表 | 建议索引 | 原因 |
|-----|---------|------|
| `resume_analyses` | `resume_id` | 按简历查评测记录（当前 FK 未显式建索引） |
| `voice_interview_messages` | `session_id, sequence_num` | 按会话+顺序查消息 |
| `voice_interview_sessions` | `user_id, created_at` | 按用户查语音面试列表 |
| `interview_schedule` | `interview_time` / `status` | 日程按时间/状态查询 |
| `vector_store` | `metadata->>'kb_id'`（表达式索引） | 加速按知识库删除/过滤 |

### 4.3 冗余索引清理

- 删除 `idx_interview_answer_session_question`（唯一约束 `uk_...` 已建等效索引）
- 删除 `idx_rag_message_session`（复合索引 `idx_rag_message_order` 左前缀已覆盖）
- 合并 `idx_interview_session_resume_created` 与 `idx_interview_session_resume_status_created`（后者左前缀含 `resume_id`，视查询频次决定保留）

---

## 五、数据量预估与扩展策略

### 5.1 数据量增长预估

| 表 | 单条记录大小估算 | 增长率 | 1 年预估量 | 扩展压力 |
|-----|-----------------|--------|-----------|----------|
| `resumes` | ~1KB（含 TEXT 全文） | 中 | 10 万级 | 低 |
| `resume_analyses` | ~2KB | 中 | 30 万级 | 低 |
| `interview_sessions` | ~3KB（含多 JSON） | 高 | 百万级 | 中 |
| `interview_answers` | ~2KB | 高 | 千万级 | **高** |
| `voice_interview_sessions` | ~1KB | 高 | 百万级 | 中 |
| `voice_interview_messages` | ~1KB | 极高 | 亿级 | **极高** |
| `voice_interview_evaluations` | ~3KB | 高 | 百万级 | 中 |
| `knowledge_bases` | ~1KB | 中 | 10 万级 | 低 |
| `vector_store` | ~4KB（1024 维向量 = 1024×4B ≈ 4KB） | 高 | **千万级向量** | **极高** |
| `rag_chat_sessions` | ~0.5KB | 高 | 百万级 | 中 |
| `rag_chat_messages` | ~2KB | 极高 | 亿级 | **极高** |
| `llm_provider_config` | ~1KB | 极低 | 百级 | 无 |
| `llm_global_setting` | ~0.1KB | 无 | 1 行 | 无 |
| `interview_schedule` | ~1KB | 中 | 十万级 | 低 |

### 5.2 扩展瓶颈分析

**① 向量表 `vector_store`（最大瓶颈）**

- **1024 维向量 ≈ 4KB/条**，1000 万条 ≈ 40GB，远超内存。
- HNSW 索引内存占用高，需大内存机器或换 IVFFLAT/DiskANN。
- 扩展策略：
  - **水平分片**：按 `kb_id` 分片（多表/多库），检索时按知识库路由。
  - **索引降级**：>1000 万向量改用 IVFFLAT（内存友好），或引入 Milvus/Qdrant 专业向量库。
  - **冷热分离**：活跃知识库热数据存内存，冷数据落盘。

**② 消息表（`rag_chat_messages` / `voice_interview_messages` / `interview_answers`）**

- 消息类表增速极快，单表破亿后查询变慢。
- 扩展策略：
  - **按月/按用户分表**（如 `rag_chat_messages_202601`）。
  - **归档**：历史消息迁移到归档库/对象存储。
  - **读写分离**：读走从库。

**③ JSON 大字段（`questions_json`、`strengths_json` 等）**

- JSON 列无法高效索引、更新整列开销大。
- 扩展策略：高频查询的 JSON 字段**拆表**（如「问题」「优势」独立表），低频保留 JSON。

### 5.3 长期架构演进路径

```
阶段 1（当前）：单库单表 + pgvector + Redis 缓存
阶段 2：读写分离（主从复制）+ 冷热数据分离
阶段 3：消息表分表（按时间）+ 向量表按 kb_id 分片
阶段 4：向量检索迁独立引擎（Milvus/Qdrant）+ 消息走消息队列落盘
阶段 5：多租户隔离（schema-per-tenant 或库级隔离）
```

---

## 六、设计模式与最佳实践总结

| 模式 | 应用表 | 说明 |
|------|--------|------|
| **去重唯一索引** | `resumes`、`knowledge_bases` | SHA-256 `file_hash` 唯一约束 |
| **冗余标量列防 N+1** | `interview_sessions.resume_id` | FK 对象 + 标量双写 |
| **冗余计数列** | `knowledge_bases.question_count`、`rag_chat_sessions.message_count` | 避免 count 子查询 |
| **JSON 序列化非结构化数据** | 多表 `*_json` 字段 | 列表/数组字段序列化存储 |
| **两阶段提交（向量）** | `vector_store.metadata` | `pending:` 临时标记 + 原子提升 |
| **字符串主键** | `llm_provider_config.id` | 稳定业务标识作为 PK |
| **单例表** | `llm_global_setting` | 固定 ID=1，全局唯一配置 |
| **1:1 唯一约束** | `voice_interview_evaluations.session_id` | 唯一约束实现 1:1 |
| **敏感信息加密存储** | `llm_provider_config.api_key_ciphertext` | 密文 + nonce，杜绝明文密钥 |
| **异步任务状态机** | `*_status` / `*_error` 字段 | PENDING→PROCESSING→COMPLETED/FAILED |
