-- ============================================================================
-- EXPLAIN 实验脚本 | AI Interview Platform (PostgreSQL 16 + pgvector)
--
-- 运行（在仓库根目录，Git Bash）：
--   docker exec -i interview-postgres psql -U postgres -d interview_guide -X -P pager=off \
--     -f - < perf/sql/explain-lab.sql
--
-- 零污染：全部实验表都是 TEMP 表，结构/索引按 V1 / V2 / V7 migration 复刻，
--         psql 会话结束时自动删除，不写入也不修改任何业务表。
-- 查询来源：ResumeAnalysisRepository / InterviewScheduleRepository /
--           KnowledgeBaseRepository 的方法名对应的真实 SQL 形态。
-- ============================================================================
\set ON_ERROR_STOP on
\pset pager off

-- ---------------------------------------------------------------------------
-- 0. 建表：列名、类型、索引定义与项目一致，省略无关列
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE resume_analyses (
    id            BIGINT PRIMARY KEY,
    resume_id     BIGINT NOT NULL,
    overall_score INTEGER,
    summary       TEXT,
    analyzed_at   TIMESTAMP NOT NULL
);
-- V7: findFirstByResumeIdOrderByAnalyzedAtDesc
CREATE INDEX idx_resume_analysis_resume_analyzed
    ON resume_analyses (resume_id, analyzed_at DESC);

-- 无索引对照组（LIKE 不带 INCLUDING INDEXES，连主键索引都不复制）
CREATE TEMP TABLE resume_analyses_noidx (LIKE resume_analyses);

CREATE TEMP TABLE interview_schedule (
    id             BIGINT PRIMARY KEY,
    owner_user_id  BIGINT NOT NULL,
    status         VARCHAR(255) NOT NULL,
    company_name   VARCHAR(255) NOT NULL,
    position       VARCHAR(255) NOT NULL,
    interview_time TIMESTAMP NOT NULL,
    created_at     TIMESTAMP
);
CREATE INDEX idx_schedule_status_time ON interview_schedule (status, interview_time);   -- V7
CREATE INDEX idx_schedule_owner_time ON interview_schedule (owner_user_id, interview_time); -- V2

CREATE TEMP TABLE knowledge_bases (
    id                BIGINT PRIMARY KEY,
    owner_user_id     BIGINT NOT NULL,
    file_hash         VARCHAR(64) NOT NULL,
    name              VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    category          VARCHAR(100),
    vector_status     VARCHAR(20),
    uploaded_at       TIMESTAMP NOT NULL
);
CREATE INDEX idx_kb_owner_uploaded ON knowledge_bases (owner_user_id, uploaded_at);   -- V2
CREATE INDEX idx_kb_owner_vector   ON knowledge_bases (owner_user_id, vector_status); -- V7

CREATE TEMP TABLE interview_sessions (
    id         BIGINT PRIMARY KEY,
    skill_id   VARCHAR(64),
    status     VARCHAR(20),
    created_at TIMESTAMP
);
CREATE TEMP TABLE interview_answers (
    id             BIGINT PRIMARY KEY,
    session_id     BIGINT NOT NULL,
    question_index INTEGER,
    answered_at    TIMESTAMP
);
CREATE INDEX idx_interview_answer_session_question
    ON interview_answers (session_id, question_index);

-- ---------------------------------------------------------------------------
-- 1. 灌测试数据：5 万评测 / 2 万日程 / 3 万知识库 / 4 万答案
-- ---------------------------------------------------------------------------
INSERT INTO pg_temp.resume_analyses
SELECT g, (g % 500) + 1, g % 101, 'summary-' || g,
       timestamp '2026-01-01' + (g || ' seconds')::interval
FROM generate_series(1, 50000) g;
INSERT INTO pg_temp.resume_analyses_noidx SELECT * FROM pg_temp.resume_analyses;

-- owner 用质数 17，避免与 status(g%4)、vector_status(g%5) 的取值相关，保证过滤有选择性
INSERT INTO pg_temp.interview_schedule
SELECT g, (g % 17) + 1,
       (ARRAY['PENDING','COMPLETED','CANCELLED','RESCHEDULED'])[(g % 4) + 1],
       'company-' || g, 'position-' || g,
       timestamp '2026-01-01' + (g || ' minutes')::interval,
       timestamp '2026-01-01'
FROM generate_series(1, 20000) g;

INSERT INTO pg_temp.knowledge_bases
SELECT g, (g % 17) + 1, 'hash-' || g, 'kb-' || g, 'file-' || g,
       'cat-' || (g % 10),
       CASE WHEN g % 5 = 0 THEN 'COMPLETED' ELSE 'PENDING' END,
       timestamp '2026-01-01' + (g || ' minutes')::interval
FROM generate_series(1, 30000) g;

INSERT INTO pg_temp.interview_sessions
SELECT g, 'java-backend', 'COMPLETED',
       timestamp '2026-01-01' + (g || ' minutes')::interval
FROM generate_series(1, 2000) g;
INSERT INTO pg_temp.interview_answers
SELECT g, (g % 2000) + 1, g % 10, timestamp '2026-01-02'
FROM generate_series(1, 40000) g;

-- 统计信息 + visibility map：让 planner 的估算和 Index Only Scan 判断贴近真实情况
VACUUM ANALYZE pg_temp.resume_analyses;
VACUUM ANALYZE pg_temp.resume_analyses_noidx;
VACUUM ANALYZE pg_temp.interview_schedule;
VACUUM ANALYZE pg_temp.knowledge_bases;
VACUUM ANALYZE pg_temp.interview_sessions;
VACUUM ANALYZE pg_temp.interview_answers;

-- ===========================================================================
-- 实验 1：真实业务表只有 1 行时，有索引也不一定走（V7 注释里"表无数据"的由来）
-- 预期：Seq Scan + Sort，而不是 Index Scan；小表全扫比索引更便宜，这是正确选择
-- ===========================================================================
\echo '== 1. public.resume_analyses (1 行)：小表偏好 Seq Scan =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM public.resume_analyses
WHERE resume_id = 1 ORDER BY analyzed_at DESC LIMIT 1;

-- ===========================================================================
-- 实验 2：V7 索引① —— 取某简历最新一次评测
-- 对照 2a 无索引 / 2b 有索引 / 2c 覆盖索引 / 2d 索引失效写法
-- ===========================================================================
\echo '== 2a. 无索引 5 万行：Seq Scan + Sort，Rows Removed by Filter 约 5 万 =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM pg_temp.resume_analyses_noidx
WHERE resume_id = 42 ORDER BY analyzed_at DESC LIMIT 1;

\echo '== 2b. V7 复合索引：Index Scan，无 Sort（索引本身有序） =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM pg_temp.resume_analyses
WHERE resume_id = 42 ORDER BY analyzed_at DESC LIMIT 1;

\echo '== 2c. 只取索引列：Index Only Scan，Heap Fetches 0，不回表 =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT resume_id, analyzed_at FROM pg_temp.resume_analyses
WHERE resume_id = 42 ORDER BY analyzed_at DESC LIMIT 1;

-- ===========================================================================
-- 实验 3：联合索引最左前缀 + 索引失效（面试高频）
-- ===========================================================================
\echo '== 3a. 命中索引最左列 resume_id =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM pg_temp.resume_analyses WHERE resume_id = 42;

\echo '== 3b. 跳过最左列，只用 analyzed_at：Seq Scan / Parallel Seq Scan =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM pg_temp.resume_analyses
WHERE analyzed_at > timestamp '2026-01-01 13:00:00';

\echo '== 3c. 列上做运算：索引失效（Seq Scan） =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM pg_temp.resume_analyses WHERE resume_id + 0 = 42;

\echo '== 3d. 列上套函数 + 跳过最左列：Seq Scan（双重原因） =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM pg_temp.resume_analyses
WHERE analyzed_at::date = date '2026-01-01';

\echo '== 3e. 等价改写：最左列等值 + 次列范围，索引条件全部用上 =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM pg_temp.resume_analyses
WHERE resume_id = 42
  AND analyzed_at >= timestamp '2026-01-01 00:00:00'
  AND analyzed_at <  timestamp '2026-01-01 00:10:00';

\echo '== 3f. 缺失索引：只按 analyzed_at 查询时补一个单列索引，观察计划翻转 =='
CREATE INDEX idx_lab_analyzed_at ON resume_analyses (analyzed_at);
ANALYZE pg_temp.resume_analyses;
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM pg_temp.resume_analyses
WHERE analyzed_at >= timestamp '2026-01-01 00:00:00'
  AND analyzed_at <  timestamp '2026-01-01 00:01:00';

-- ===========================================================================
-- 实验 4：V7 索引② —— 定时扫描待开始的日程 + 多租户过滤
-- ===========================================================================
\echo '== 4a. findByStatusAndInterviewTimeBefore：走 idx_schedule_status_time =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, company_name FROM pg_temp.interview_schedule
WHERE status = 'PENDING' AND interview_time < timestamp '2026-01-10'
ORDER BY interview_time LIMIT 50;

\echo '== 4b. 再加 owner_user_id：观察 Bitmap Heap Scan 是否合并两个索引 =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM pg_temp.interview_schedule
WHERE owner_user_id = 1 AND status = 'PENDING';

\echo '== 4c. 对照：只按 status + 时间，V7 索引直接给出有序结果 =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM pg_temp.interview_schedule
WHERE status = 'PENDING' ORDER BY interview_time LIMIT 50;

-- ===========================================================================
-- 实验 5：V7 索引③ —— 状态过滤 + 按上传时间排序的取舍
-- 实测提示：带 LIMIT 20 时优化器往往选择"有序索引 + 逐行过滤"，
--           因为倒序扫几行就能凑够 20 条；V7 新索引在下面的统计查询里才显出价值。
-- ===========================================================================
\echo '== 5a. findByOwnerUserIdAndVectorStatusOrderByUploadedAtDesc =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, name FROM pg_temp.knowledge_bases
WHERE owner_user_id = 1 AND vector_status = 'COMPLETED'
ORDER BY uploaded_at DESC LIMIT 20;

\echo '== 5b. 对照：无状态过滤时走 idx_kb_owner_uploaded，无 Sort =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, name FROM pg_temp.knowledge_bases
WHERE owner_user_id = 1 ORDER BY uploaded_at DESC LIMIT 20;

\echo '== 5c. countByOwnerUserIdAndVectorStatus：无排序需求，V7 索引被选中 =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM pg_temp.knowledge_bases
WHERE owner_user_id = 1 AND vector_status = 'COMPLETED';

-- ===========================================================================
-- 实验 6：深分页 OFFSET vs 游标分页
-- ===========================================================================
\echo '== 6a. 首页 OFFSET 0：只读 20 行 =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, uploaded_at FROM pg_temp.knowledge_bases
WHERE owner_user_id = 1 ORDER BY uploaded_at DESC LIMIT 20 OFFSET 0;

\echo '== 6b. 深分页 OFFSET 1000：需要先扫描并丢弃 1000 行，代价随页码增长 =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, uploaded_at FROM pg_temp.knowledge_bases
WHERE owner_user_id = 1 ORDER BY uploaded_at DESC LIMIT 20 OFFSET 1000;

\echo '== 6c. 游标分页：Index Scan + Limit，代价与页码无关 =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, uploaded_at FROM pg_temp.knowledge_bases
WHERE owner_user_id = 1 AND uploaded_at < timestamp '2026-01-15'
ORDER BY uploaded_at DESC LIMIT 20;

-- ===========================================================================
-- 实验 7：JOIN 策略（对应项目里"列表页避免 N+1"的话题）
-- ===========================================================================
\echo '== 7a. 点查单会话：Nested Loop + 内外两层 Index Only Scan =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT s.id, a.question_index
FROM pg_temp.interview_sessions s
JOIN pg_temp.interview_answers a ON a.session_id = s.id
WHERE s.id = 42;

\echo '== 7b. 全量聚合：Hash Join（大结果集不需要索引逐行探测） =='
EXPLAIN (ANALYZE, BUFFERS)
SELECT s.status, count(*)
FROM pg_temp.interview_sessions s
JOIN pg_temp.interview_answers a ON a.session_id = s.id
GROUP BY s.status;

\echo '== 实验结束：TEMP 表随会话结束自动删除，业务数据未被修改 =='
