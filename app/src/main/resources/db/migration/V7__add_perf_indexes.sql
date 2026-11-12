-- V7: PERF-002 列表查询索引补齐
-- 动机与对应查询：
-- 1) resume_analyses(resume_id, analyzed_at DESC)
--    简历列表页需要按简历取最新评测（findFirstByResumeIdOrderByAnalyzedAtDesc）
--    与评测次数统计（GROUP BY resume_id）。EXPLAIN (ANALYZE) 实测该查询为 Seq Scan
--    （该表此前仅有主键索引），且被简历列表的批量聚合查询按租户规模放大。
-- 2) interview_schedule(status, interview_time)
--    定时扫描任务 findByStatusAndInterviewTimeBefore 与状态过滤查询
--    findByOwnerUserIdAndStatus 的过滤列；实测 Seq Scan（表无数据，行数增长后需复测）。
-- 3) knowledge_bases(owner_user_id, vector_status)
--    知识库列表按向量化状态过滤 findByOwnerUserIdAndVectorStatusOrderByUploadedAtDesc
--    与统计接口 countByOwnerUserIdAndVectorStatus；现有 idx_kb_owner_uploaded 仅覆盖
--    owner 前缀，状态过滤需回表逐行判断。

CREATE INDEX IF NOT EXISTS idx_resume_analysis_resume_analyzed
    ON resume_analyses (resume_id, analyzed_at DESC);

CREATE INDEX IF NOT EXISTS idx_schedule_status_time
    ON interview_schedule (status, interview_time);

CREATE INDEX IF NOT EXISTS idx_kb_owner_vector
    ON knowledge_bases (owner_user_id, vector_status);
