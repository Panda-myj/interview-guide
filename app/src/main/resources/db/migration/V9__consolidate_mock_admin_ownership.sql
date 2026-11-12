-- =============================================================================
-- 登录端 Mock 模式：收敛为「唯一管理员 + 全部业务数据归属该管理员」的本地开发态。
--
-- 背景：V2 用 ${bootstrapPhone} 建立引导管理员，并把存量数据挂到该账号。迁移一旦执行完，
-- 仅修改 APP_AUTH_BOOTSTRAP_PHONE 不会重新分配数据，因此由本迁移把「管理员手机号、
-- 数据归属、账号清单」一次性对齐到当前配置（.env / .env.example 中的 +8618913371131）。
--
-- 安全边界：只在 APP_AUTH_SMS_PROVIDER=mock 时执行。真实短信通道（aliyun / pnvs）与
-- 生产环境直接跳过，避免把真实用户当作本地开发数据误删；未配置引导手机号时同样跳过。
--
-- 幂等：已收敛（仅剩唯一管理员）时为空操作，不会重复清理管理员自身的会话。
-- 手机号短信登录链路保持不变：users 行 + auth_identities(PHONE, subject) 均保留，
-- 管理员仍可通过 /api/auth/sms/send 与 /api/auth/login/sms 正常登录。
-- =============================================================================

DO $$
DECLARE
    target_phone TEXT := '${bootstrapPhone}';
    sms_provider TEXT := '${smsProvider}';
    admin_id BIGINT;
    other_count BIGINT;
    dup_rec RECORD;
BEGIN
    IF lower(btrim(coalesce(sms_provider, ''))) <> 'mock' THEN
        RAISE NOTICE '[mock-admin] skipped: sms.provider=%', sms_provider;
        RETURN;
    END IF;
    IF target_phone IS NULL OR btrim(target_phone) = '' THEN
        RAISE NOTICE '[mock-admin] skipped: bootstrapPhone is not configured';
        RETURN;
    END IF;

    -- 1. 定位或创建最终管理员，并恢复为可用的管理员账号。
    SELECT id INTO admin_id
      FROM users
     WHERE phone = target_phone
     ORDER BY id
     LIMIT 1;

    IF admin_id IS NULL THEN
        INSERT INTO users (phone, display_name, role, status, created_at, updated_at)
        VALUES (target_phone, '引导管理员', 'ADMIN', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        RETURNING id INTO admin_id;
    END IF;

    UPDATE users
       SET display_name = '引导管理员',
           role = 'ADMIN',
           status = 'ACTIVE',
           updated_at = CURRENT_TIMESTAMP
     WHERE id = admin_id;

    -- 登录按 auth_identities(provider=PHONE, subject=手机号) 找人，身份必须指向管理员。
    DELETE FROM auth_identities
     WHERE provider = 'PHONE'
       AND subject = target_phone
       AND user_id <> admin_id;

    INSERT INTO auth_identities (user_id, provider, subject, verified_at, created_at, updated_at)
    SELECT admin_id, 'PHONE', target_phone, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
     WHERE NOT EXISTS (
        SELECT 1 FROM auth_identities
         WHERE provider = 'PHONE' AND subject = target_phone
     );

    SELECT count(*) INTO other_count FROM users WHERE id <> admin_id;
    IF other_count = 0 THEN
        RAISE NOTICE '[mock-admin] already consolidated: adminId=%, phone=%', admin_id, target_phone;
        RETURN;
    END IF;

    -- 2. 文件去重：uk_resume_owner_hash / uk_kb_owner_hash 是 (owner_user_id, file_hash)
    --    唯一索引。合并归属前，把同一份文件的重复副本上的附属记录重挂到保留副本，
    --    再删除副本，保证数据不丢且更新不会触发唯一冲突。
    FOR dup_rec IN
        SELECT dup.id AS dup_id, keep.id AS keep_id
          FROM resumes dup
          JOIN LATERAL (
              SELECT candidate.id
                FROM resumes candidate
               WHERE candidate.file_hash = dup.file_hash
               ORDER BY CASE WHEN candidate.owner_user_id = admin_id THEN 0 ELSE 1 END,
                        candidate.id
               LIMIT 1
          ) keep ON keep.id <> dup.id
         ORDER BY dup.id
    LOOP
        UPDATE interview_sessions SET resume_id = dup_rec.keep_id
         WHERE resume_id = dup_rec.dup_id;
        UPDATE resume_analyses SET resume_id = dup_rec.keep_id
         WHERE resume_id = dup_rec.dup_id;
        DELETE FROM resumes WHERE id = dup_rec.dup_id;
    END LOOP;

    FOR dup_rec IN
        SELECT dup.id AS dup_id, keep.id AS keep_id
          FROM knowledge_bases dup
          JOIN LATERAL (
              SELECT candidate.id
                FROM knowledge_bases candidate
               WHERE candidate.file_hash = dup.file_hash
               ORDER BY CASE WHEN candidate.owner_user_id = admin_id THEN 0 ELSE 1 END,
                        candidate.id
               LIMIT 1
          ) keep ON keep.id <> dup.id
         ORDER BY dup.id
    LOOP
        -- (session_id, knowledge_base_id) 唯一：先删会撞键的关联，再把剩余关联重挂。
        DELETE FROM rag_session_knowledge_bases link
         WHERE link.knowledge_base_id = dup_rec.dup_id
           AND EXISTS (
               SELECT 1 FROM rag_session_knowledge_bases same
                WHERE same.session_id = link.session_id
                  AND same.knowledge_base_id = dup_rec.keep_id
           );
        UPDATE rag_session_knowledge_bases
           SET knowledge_base_id = dup_rec.keep_id
         WHERE knowledge_base_id = dup_rec.dup_id;

        IF to_regclass('public.vector_store') IS NOT NULL THEN
            UPDATE vector_store v
               SET metadata = jsonb_set(v.metadata::jsonb, '{kb_id}',
                       to_jsonb(dup_rec.keep_id::text), true)
             WHERE v.metadata->>'kb_id' = dup_rec.dup_id::text;
            UPDATE vector_store v
               SET metadata = jsonb_set(v.metadata::jsonb, '{kb_id_long}',
                       to_jsonb(dup_rec.keep_id), true)
             WHERE v.metadata->>'kb_id_long' = dup_rec.dup_id::text;
        END IF;

        DELETE FROM knowledge_bases WHERE id = dup_rec.dup_id;
    END LOOP;

    -- 3. 完整转移：所有文件（简历 / 知识库）与面试记录（文字 / 语音 / 日程 / RAG 会话）
    --    统一归入最终管理员。子表通过父记录关联，无需改写；对象文件仍按 storage_key
    --    读取，不改动对象存储中的路径。
    UPDATE resumes SET owner_user_id = admin_id WHERE owner_user_id <> admin_id;
    UPDATE knowledge_bases SET owner_user_id = admin_id WHERE owner_user_id <> admin_id;
    UPDATE interview_sessions SET owner_user_id = admin_id WHERE owner_user_id <> admin_id;
    UPDATE voice_interview_sessions SET owner_user_id = admin_id WHERE owner_user_id <> admin_id;
    UPDATE rag_chat_sessions SET owner_user_id = admin_id WHERE owner_user_id <> admin_id;
    UPDATE interview_schedule SET owner_user_id = admin_id WHERE owner_user_id <> admin_id;

    -- RAG 检索按 metadata.user_id 做租户过滤，随知识库归属刷新。
    IF to_regclass('public.vector_store') IS NOT NULL THEN
        UPDATE vector_store v
           SET metadata = jsonb_set(v.metadata::jsonb, '{user_id}',
                   to_jsonb(admin_id::text), true)
          FROM knowledge_bases kb
         WHERE v.metadata->>'kb_id' = kb.id::text
            OR (v.metadata->>'kb_id_long' IS NOT NULL
                AND (v.metadata->>'kb_id_long')::bigint = kb.id);
    END IF;

    -- 4. 注销其余账号并清除登录态：通知 -> 会话 -> 设备 -> 补偿任务 -> 身份 -> 用户。
    --    一并清空管理员的旧 refresh session：其 JWT 里的 role 仍是提升前的 USER，
    --    清空后必须重新用短信验证码登录，才能拿到 ADMIN 令牌；账号本身保持可用。
    DELETE FROM login_notifications;
    DELETE FROM auth_sessions;
    DELETE FROM user_devices WHERE user_id <> admin_id;
    DELETE FROM resource_deletion_compensations WHERE owner_user_id <> admin_id;
    DELETE FROM auth_identities WHERE user_id <> admin_id;
    DELETE FROM users WHERE id <> admin_id;

    RAISE NOTICE '[mock-admin] consolidated: adminId=%, phone=%, removedUsers=%',
        admin_id, target_phone, other_count;
END
$$;
