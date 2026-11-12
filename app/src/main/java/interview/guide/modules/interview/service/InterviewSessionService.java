package interview.guide.modules.interview.service;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.modules.auth.security.CurrentUserService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 面试会话管理服务
 * 管理面试会话的生命周期，使用 Redis 缓存会话状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSessionService {

    private final InterviewQuestionService questionService;
    private final AnswerEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSessionCache sessionCache;
    private final ObjectMapper objectMapper;
    private final EvaluateStreamProducer evaluateStreamProducer;
    private final LlmProviderRegistry llmProviderRegistry;
    private final CurrentUserService currentUserService;
    private final InterviewSessionRepository sessionRepository;
    private final TransactionalExecutor transactionalExecutor;

    /**
     * 校验会话状态迁移，非法迁移抛出明确业务错误。
     */
    private void assertTransition(InterviewSessionEntity.SessionStatus from,
                                  InterviewSessionEntity.SessionStatus to) {
        if (!InterviewSessionEntity.canTransition(from, to)) {
            throw new BusinessException(ErrorCode.INTERVIEW_ILLEGAL_STATE_TRANSITION,
                "面试会话状态非法迁移: " + from + " -> " + to);
        }
    }

    /**
     * 创建新的面试会话
     * 注意：如果已有未完成的会话，不会创建新的，而是返回现有会话
     * 前端应该先调用 findUnfinishedSession 检查，或者使用 forceCreate 参数强制创建
     */
    public InterviewSessionDTO createSession(CreateInterviewRequest request) {
        // 如果指定了resumeId且未强制创建，检查是否有未完成的会话
        if (request.resumeId() != null && !Boolean.TRUE.equals(request.forceCreate())) {
            Optional<InterviewSessionDTO> unfinishedOpt = findUnfinishedSession(request.resumeId());
            if (unfinishedOpt.isPresent()) {
                log.info("检测到未完成的面试会话，返回现有会话: resumeId={}, sessionId={}",
                    request.resumeId(), unfinishedOpt.get().sessionId());
                return unfinishedOpt.get();
            }
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String skillId = request.skillId() != null ? request.skillId() : InterviewDefaults.SKILL_ID;
        String difficulty = request.difficulty() != null ? request.difficulty() : InterviewDefaults.DIFFICULTY;

        log.info("创建新面试会话: {}, skill: {}, difficulty: {}, questionCount: {}, resumeId: {}",
            sessionId, skillId, difficulty, request.questionCount(), request.resumeId());

        // 获取历史问题（通用模式按 skillId 查询，有简历时按 resumeId + skillId 精确匹配）
        List<HistoricalQuestion> historicalQuestions =
            persistenceService.getHistoricalQuestions(skillId, request.resumeId());

        // 基于 Skill 生成面试问题
        List<InterviewQuestionDTO> questions = questionService.generateQuestionsBySkill(
            request.llmProvider(),
            skillId,
            difficulty,
            request.resumeText(),
            request.questionCount(),
            historicalQuestions,
            request.customCategories(),
            request.jdText()
        );

        // 保存到 Redis 缓存
        sessionCache.saveSession(
            sessionId,
            currentUserService.getRequiredUserId(),
            request.resumeText() != null ? request.resumeText() : "",
            request.resumeId(),
            questions,
            0,
            SessionStatus.CREATED
        );

        // 保存到数据库
        try {
            persistenceService.saveSession(sessionId, request.resumeId(),
                questions.size(), questions, request.llmProvider(), skillId, difficulty);
        } catch (Exception e) {
            log.warn("保存面试会话到数据库失败: {}", e.getMessage());
        }

        return new InterviewSessionDTO(
            sessionId,
            request.resumeText() != null ? request.resumeText() : "",
            questions.size(),
            0,
            questions,
            SessionStatus.CREATED
        );
    }

    /**
     * 获取会话信息（优先从缓存获取，缓存未命中则从数据库恢复）
     */
    public InterviewSessionDTO getSession(String sessionId) {
        // 1. 尝试从 Redis 缓存获取
        Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
        if (cachedOpt.isPresent()) {
            return toDTO(cachedOpt.get());
        }

        // 2. 缓存未命中，从数据库恢复
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        return toDTO(restoredSession);
    }

    /**
     * 查找并恢复未完成的面试会话
     */
    public Optional<InterviewSessionDTO> findUnfinishedSession(Long resumeId) {
        try {
            // 1. 先从 Redis 缓存查找
            Optional<String> cachedSessionIdOpt = sessionCache.findUnfinishedSessionId(
                resumeId, currentUserService.getRequiredUserId()
            );
            if (cachedSessionIdOpt.isPresent()) {
                String sessionId = cachedSessionIdOpt.get();
                Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
                if (cachedOpt.isPresent()) {
                    log.debug("从 Redis 缓存找到未完成会话: resumeId={}, sessionId={}", resumeId, sessionId);
                    return Optional.of(toDTO(cachedOpt.get()));
                }
            }

            // 2. 缓存未命中，从数据库查找
            Optional<InterviewSessionEntity> entityOpt = persistenceService.findUnfinishedSession(resumeId);
            if (entityOpt.isEmpty()) {
                return Optional.empty();
            }

            InterviewSessionEntity entity = entityOpt.get();
            CachedSession restoredSession = restoreSessionFromEntity(entity);
            if (restoredSession != null) {
                return Optional.of(toDTO(restoredSession));
            }
        } catch (Exception e) {
            log.error("恢复未完成会话失败: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * 查找并恢复未完成的面试会话，如果不存在则抛出异常
     */
    public InterviewSessionDTO findUnfinishedSessionOrThrow(Long resumeId) {
        return findUnfinishedSession(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "未找到未完成的面试会话"));
    }

    /**
     * 从数据库恢复会话并缓存到 Redis
     */
    private CachedSession restoreSessionFromDatabase(String sessionId) {
        try {
            Optional<InterviewSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
            return entityOpt.map(this::restoreSessionFromEntity).orElse(null);
        } catch (Exception e) {
            log.error("从数据库恢复会话失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从实体恢复会话并缓存到 Redis
     */
    private CachedSession restoreSessionFromEntity(InterviewSessionEntity entity) {
        try {
            // 解析问题列表
            List<InterviewQuestionDTO> questions = objectMapper.readValue(
                entity.getQuestionsJson(),
                new TypeReference<>() {}
            );

            // 恢复已保存的答案
            List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(entity.getSessionId());
            for (InterviewAnswerEntity answer : answers) {
                int index = answer.getQuestionIndex();
                if (index >= 0 && index < questions.size()) {
                    InterviewQuestionDTO question = questions.get(index);
                    questions.set(index, question.withAnswer(answer.getUserAnswer()));
                }
            }

            SessionStatus status = convertStatus(entity.getStatus());

            // 保存到 Redis 缓存
            sessionCache.saveSession(
                entity.getSessionId(),
            entity.getOwnerUser() != null ? entity.getOwnerUser().getId() : null,
                entity.getResume() != null ? entity.getResume().getResumeText() : "",
                entity.getResume() != null ? entity.getResume().getId() : null,
                questions,
                entity.getCurrentQuestionIndex(),
                status
            );

            log.info("从数据库恢复会话到 Redis: sessionId={}, currentIndex={}, status={}",
                entity.getSessionId(), entity.getCurrentQuestionIndex(), entity.getStatus());

            // 返回缓存的会话
            return sessionCache.getSession(entity.getSessionId()).orElse(null);
        } catch (Exception e) {
            log.error("恢复会话失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private SessionStatus convertStatus(InterviewSessionEntity.SessionStatus status) {
        return switch (status) {
            case CREATED -> SessionStatus.CREATED;
            case IN_PROGRESS -> SessionStatus.IN_PROGRESS;
            case COMPLETED -> SessionStatus.COMPLETED;
            case EVALUATED -> SessionStatus.EVALUATED;
        };
    }

    /**
     * 获取当前问题的响应（包含完成状态）
     */
    public Map<String, Object> getCurrentQuestionResponse(String sessionId) {
        InterviewQuestionDTO question = getCurrentQuestion(sessionId);
        if (question == null) {
            return Map.of(
                "completed", true,
                "message", "所有问题已回答完毕"
            );
        }
        return Map.of(
            "completed", false,
            "question", question
        );
    }

    /**
     * 获取当前问题
     */
    public InterviewQuestionDTO getCurrentQuestion(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        if (session.getCurrentIndex() >= questions.size()) {
            return null; // 所有问题已回答完
        }

        // 更新状态为进行中（条件更新：仅 CREATED -> IN_PROGRESS，重复调用幂等）
        if (session.getStatus() == SessionStatus.CREATED) {
            boolean moved = Boolean.TRUE.equals(transactionalExecutor.call(() ->
                sessionRepository.updateSessionStatusConditionally(
                    sessionId,
                    InterviewSessionEntity.SessionStatus.IN_PROGRESS,
                    List.of(InterviewSessionEntity.SessionStatus.CREATED)) > 0));
            if (moved) {
                session.setStatus(SessionStatus.IN_PROGRESS);
                sessionCache.updateSessionStatus(sessionId, SessionStatus.IN_PROGRESS);
            }
        }

        return questions.get(session.getCurrentIndex());
    }

    /**
     * 提交答案（并进入下一题）
     * 如果是最后一题，自动触发异步评估。
     *
     * <p>幂等：重复提交同一题不会重复推进进度，也不会重复触发评估；
     * 跳题或会话已结束时返回明确业务错误。</p>
     */
    public SubmitAnswerResponse submitAnswer(SubmitAnswerRequest request) {
        CachedSession session = getOrRestoreSession(request.sessionId());

        InterviewSessionEntity.SessionStatus dbStatus = currentDbStatus(request.sessionId());
        if (dbStatus == InterviewSessionEntity.SessionStatus.COMPLETED
            || dbStatus == InterviewSessionEntity.SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED,
                "面试已结束，无法继续提交答案");
        }

        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: " + index);
        }

        int currentIndex = session.getCurrentIndex();
        if (index < currentIndex) {
            // 重复提交（网络重试 / 双击）：直接返回当前进度，不重复写库、不重复触发评估
            log.info("检测到重复提交，返回当前进度: sessionId={}, questionIndex={}, currentIndex={}",
                request.sessionId(), index, currentIndex);
            InterviewQuestionDTO current = currentIndex < questions.size() ? questions.get(currentIndex) : null;
            return new SubmitAnswerResponse(current != null, current, currentIndex, questions.size());
        }
        if (index > currentIndex) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND,
                "问题索引无效，请按顺序作答: " + index);
        }

        // 更新问题答案
        InterviewQuestionDTO question = questions.get(index);
        InterviewQuestionDTO answeredQuestion = question.withAnswer(request.answer());
        questions.set(index, answeredQuestion);

        // 移动到下一题
        int newIndex = index + 1;

        // 检查是否全部完成
        boolean hasNextQuestion = newIndex < questions.size();
        InterviewQuestionDTO nextQuestion = hasNextQuestion ? questions.get(newIndex) : null;

        SessionStatus newStatus = hasNextQuestion ? SessionStatus.IN_PROGRESS : SessionStatus.COMPLETED;

        boolean completedNow = persistSubmittedAnswer(request, index, question, newIndex, newStatus);

        // 更新 Redis 缓存。DB 已经持久化成功，缓存失败时可由后续读取从数据库恢复。
        sessionCache.updateQuestions(request.sessionId(), questions);
        sessionCache.updateCurrentIndex(request.sessionId(), newIndex);
        if (completedNow) {
            sessionCache.updateSessionStatus(request.sessionId(), SessionStatus.COMPLETED);
            enqueueEvaluationTask(request.sessionId());
        }

        log.info("会话 {} 提交答案: 问题{}, 剩余{}题",
            request.sessionId(), index, questions.size() - newIndex);

        return new SubmitAnswerResponse(
            hasNextQuestion,
            nextQuestion,
            newIndex,
            questions.size()
        );
    }

    /**
     * 持久化已提交答案并条件推进会话状态。
     *
     * @return true 表示本次提交完成了最后一题（状态迁移到 COMPLETED）
     */
    private boolean persistSubmittedAnswer(SubmitAnswerRequest request, int index,
                                           InterviewQuestionDTO question, int newIndex,
                                           SessionStatus newStatus) {
        try {
            persistenceService.saveAnswer(
                request.sessionId(), index,
                question.question(), question.category(),
                request.answer(), 0, null  // 分数在报告生成时更新
            );

            // 条件更新：仅 CREATED/IN_PROGRESS 可以推进，避免已完成会话被重复推进或回退
            int updated = transactionalExecutor.call(() ->
                sessionRepository.updateProgressAndStatusConditionally(
                    request.sessionId(),
                    newIndex,
                    toEntityStatus(newStatus),
                    List.of(InterviewSessionEntity.SessionStatus.CREATED,
                        InterviewSessionEntity.SessionStatus.IN_PROGRESS)));

            if (updated == 0) {
                log.info("会话状态不允许继续提交答案，按重复提交处理: sessionId={}, questionIndex={}",
                    request.sessionId(), index);
                throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED);
            }
            return newStatus == SessionStatus.COMPLETED;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("保存答案到数据库失败: sessionId={}, questionIndex={}",
                request.sessionId(), index, e);
            throw new BusinessException(ErrorCode.INTERVIEW_ANSWER_SAVE_FAILED,
                "保存答案失败，请稍后重试");
        }
    }

    private InterviewSessionEntity.SessionStatus toEntityStatus(SessionStatus status) {
        return InterviewSessionEntity.SessionStatus.valueOf(status.name());
    }

    /**
     * 申请一次评估任务：仅当评估状态可迁移到 PENDING（未初始化或已失败）时才入队，
     * 保证重复交卷、手动重试、WebSocket 重连不会产生重复评估。
     */
    private void enqueueEvaluationTask(String sessionId) {
        int updated = transactionalExecutor.call(() -> {
            int initialized = sessionRepository.initializeEvaluateStatus(
                sessionId, AsyncTaskStatus.PENDING, null);
            int replayed = sessionRepository.updateEvaluateStatusConditionally(
                sessionId, AsyncTaskStatus.PENDING, List.of(AsyncTaskStatus.FAILED), null);
            return initialized + replayed;
        });

        if (updated == 0) {
            log.info("会话 {} 评估任务已在队列中或已完成，跳过重复入队", sessionId);
            return;
        }

        evaluateStreamProducer.sendEvaluateTask(sessionId);
        log.info("会话 {} 已完成所有问题，评估任务已入队", sessionId);
    }

    /**
     * 暂存答案（不进入下一题）
     */
    public void saveAnswer(SubmitAnswerRequest request) {
        CachedSession session = getOrRestoreSession(request.sessionId());

        InterviewSessionEntity.SessionStatus dbStatus = currentDbStatus(request.sessionId());
        if (dbStatus == InterviewSessionEntity.SessionStatus.COMPLETED
            || dbStatus == InterviewSessionEntity.SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED,
                "面试已结束，无法修改答案");
        }

        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: " + index);
        }

        // 更新问题答案
        InterviewQuestionDTO question = questions.get(index);
        InterviewQuestionDTO answeredQuestion = question.withAnswer(request.answer());
        questions.set(index, answeredQuestion);

        // 更新 Redis 缓存
        sessionCache.updateQuestions(request.sessionId(), questions);

        // 更新状态为进行中
        if (session.getStatus() == SessionStatus.CREATED) {
            sessionCache.updateSessionStatus(request.sessionId(), SessionStatus.IN_PROGRESS);
        }

        // 保存答案到数据库（不更新currentIndex），状态用条件更新防止已完成会话回退
        try {
            persistenceService.saveAnswer(
                request.sessionId(), index,
                question.question(), question.category(),
                request.answer(), 0, null
            );
            transactionalExecutor.call(() ->
                sessionRepository.updateSessionStatusConditionally(
                    request.sessionId(),
                    InterviewSessionEntity.SessionStatus.IN_PROGRESS,
                    List.of(InterviewSessionEntity.SessionStatus.CREATED,
                        InterviewSessionEntity.SessionStatus.IN_PROGRESS)));
        } catch (Exception e) {
            log.warn("暂存答案到数据库失败: sessionId={}, questionIndex={}",
                request.sessionId(), index, e);
        }

        log.info("会话 {} 暂存答案: 问题{}", request.sessionId(), index);
    }

    /**
     * 提前交卷（触发异步评估）。
     *
     * <p>幂等：会话已 COMPLETED/EVALUATED 时直接返回，不重复入队评估；
     * 只有在条件更新（CREATED/IN_PROGRESS → COMPLETED）成功时才触发一次评估。</p>
     */
    public void completeInterview(String sessionId) {
        // 校验会话存在（缓存或数据库），不存在时抛出明确业务错误
        getOrRestoreSession(sessionId);

        InterviewSessionEntity.SessionStatus dbStatus = currentDbStatus(sessionId);
        if (dbStatus == InterviewSessionEntity.SessionStatus.COMPLETED
            || dbStatus == InterviewSessionEntity.SessionStatus.EVALUATED) {
            log.info("会话 {} 已交卷，忽略重复交卷请求: status={}", sessionId, dbStatus);
            return;
        }

        if (dbStatus == null) {
            // 兼容历史数据：DB 记录缺失时只更新缓存，评估消费者会因实体不存在而 ACK 丢弃
            log.warn("会话数据库记录不存在，仅更新缓存状态: sessionId={}", sessionId);
            sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);
            return;
        }
        assertTransition(dbStatus, InterviewSessionEntity.SessionStatus.COMPLETED);

        int updated = transactionalExecutor.call(() ->
            sessionRepository.updateSessionStatusConditionally(
                sessionId,
                InterviewSessionEntity.SessionStatus.COMPLETED,
                List.of(InterviewSessionEntity.SessionStatus.CREATED,
                    InterviewSessionEntity.SessionStatus.IN_PROGRESS)));

        if (updated == 0) {
            // 并发重复交卷：另一个请求已经完成迁移，不再重复入队
            log.info("会话 {} 状态已被并发请求迁移为完成，跳过重复交卷", sessionId);
            return;
        }

        sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);

        // 评估任务由条件更新占位后入队，未初始化或已失败时才会真正入队
        enqueueEvaluationTask(sessionId);

        log.info("会话 {} 提前交卷，评估任务已入队", sessionId);
    }

    /**
     * 查询会话在数据库中的当前状态，不存在时返回 null（兼容仅存在于缓存的旧会话）。
     */
    private InterviewSessionEntity.SessionStatus currentDbStatus(String sessionId) {
        return persistenceService.findBySessionId(sessionId)
            .map(InterviewSessionEntity::getStatus)
            .orElse(null);
    }

    // ==================== 系统任务（消费者）评估状态迁移 ====================

    /**
     * 抢占评估任务：PENDING → PROCESSING（条件更新，按影响行数判断）。
     *
     * @return true 表示抢到处理权；false 表示消息重复、乱序或已被其它实例处理
     */
    public boolean claimEvaluationProcessing(String sessionId) {
        int updated = transactionalExecutor.call(() ->
            sessionRepository.claimEvaluateStatus(
                sessionId, AsyncTaskStatus.PENDING, AsyncTaskStatus.PROCESSING));
        if (updated == 0) {
            log.info("评估任务未被抢占，可能是重复或乱序消息: sessionId={}", sessionId);
            return false;
        }
        return true;
    }

    /**
     * 评估完成：PROCESSING → COMPLETED。
     */
    public void completeEvaluation(String sessionId) {
        int updated = transactionalExecutor.call(() ->
            sessionRepository.updateEvaluateStatusConditionally(
                sessionId, AsyncTaskStatus.COMPLETED, List.of(AsyncTaskStatus.PROCESSING), null));
        if (updated == 0) {
            log.warn("评估完成状态未写入，状态可能已被其它路径更新: sessionId={}", sessionId);
        }
    }

    /**
     * 评估失败：PENDING/PROCESSING → FAILED，并记录错误信息。
     */
    public void failEvaluation(String sessionId, String error) {
        String truncated = error != null && error.length() > 500 ? error.substring(0, 500) : error;
        int updated = transactionalExecutor.call(() ->
            sessionRepository.updateEvaluateStatusConditionally(
                sessionId, AsyncTaskStatus.FAILED,
                List.of(AsyncTaskStatus.PENDING, AsyncTaskStatus.PROCESSING), truncated));
        if (updated == 0) {
            log.warn("评估失败状态未写入，任务可能已完成: sessionId={}", sessionId);
        }
    }

    /**
     * 重试前把评估状态回退为 PENDING。
     *
     * <p>允许 PROCESSING → PENDING（执行中失败）和 PENDING → PENDING（占位阶段失败），
     * COMPLETED/FAILED 不再回退，避免重复副作用。</p>
     *
     * @return true 表示允许重新入队；false 表示任务已完成或状态已变化
     */
    public boolean resetEvaluationForRetry(String sessionId) {
        int updated = transactionalExecutor.call(() ->
            sessionRepository.updateEvaluateStatusConditionally(
                sessionId, AsyncTaskStatus.PENDING,
                List.of(AsyncTaskStatus.PROCESSING, AsyncTaskStatus.PENDING), null));
        return updated > 0;
    }

    /**
     * 获取或恢复会话（优先从缓存获取）
     */
    private CachedSession getOrRestoreSession(String sessionId) {
        // 1. 尝试从 Redis 缓存获取
        Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
        if (cachedOpt.isPresent()) {
            // 刷新 TTL
            sessionCache.refreshSessionTTL(sessionId);
            return cachedOpt.get();
        }

        // 2. 缓存未命中，从数据库恢复
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        return restoredSession;
    }

    /**
     * 生成评估报告。
     *
     * <p>幂等：会话已生成过报告时直接返回持久化结果，不重复调用 LLM；
     * 未完成时返回明确业务错误；评估进行中时不会重复触发，返回明确业务错误。</p>
     */
    public InterviewReportDTO generateReport(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);

        if (session.getStatus() != SessionStatus.COMPLETED && session.getStatus() != SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED, "面试尚未完成，无法生成报告");
        }

        InterviewSessionEntity entity = persistenceService.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));

        if (entity.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED
            && entity.getOverallScore() != null) {
            log.info("面试报告已存在，直接返回持久化结果: {}", sessionId);
            return loadPersistedReport(entity);
        }

        // 以数据库状态为准校验迁移：只有 COMPLETED 才能进入 EVALUATED
        assertTransition(entity.getStatus(), InterviewSessionEntity.SessionStatus.EVALUATED);

        // 条件抢占评估任务，避免手动重试与异步消费者重复执行
        int claimed = transactionalExecutor.call(() -> {
            int initialized = sessionRepository.initializeEvaluateStatus(
                sessionId, AsyncTaskStatus.PROCESSING, null);
            int claimedFromPending = sessionRepository.claimEvaluateStatus(
                sessionId, AsyncTaskStatus.PENDING, AsyncTaskStatus.PROCESSING);
            int replayFromFailed = sessionRepository.claimEvaluateStatus(
                sessionId, AsyncTaskStatus.FAILED, AsyncTaskStatus.PROCESSING);
            return initialized + claimedFromPending + replayFromFailed;
        });

        if (claimed == 0) {
            InterviewSessionEntity latest = persistenceService.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
            if (latest.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED
                && latest.getOverallScore() != null) {
                return loadPersistedReport(latest);
            }
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_IN_PROGRESS,
                "面试评估正在进行中，请稍后查询报告");
        }

        log.info("生成面试报告: {}", sessionId);

        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        try {
            ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(entity.getLlmProvider());
            InterviewReportDTO report = evaluationService.evaluateInterview(
                chatClient,
                sessionId,
                session.getResumeText(),
                questions
            );

            // 保存报告到数据库（事务在持久化服务内），失败时保持 PROCESSING 之外的状态需回收
            persistenceService.saveReport(sessionId, report);

            InterviewSessionEntity saved = persistenceService.findBySessionId(sessionId).orElse(null);
            if (saved == null || saved.getStatus() != InterviewSessionEntity.SessionStatus.EVALUATED) {
                throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "保存评估报告失败");
            }

            completeEvaluation(sessionId);
            sessionCache.updateSessionStatus(sessionId, SessionStatus.EVALUATED);
            return report;
        } catch (BusinessException e) {
            failEvaluation(sessionId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("生成面试报告失败: sessionId={}", sessionId, e);
            failEvaluation(sessionId, e.getMessage());
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "面试评估失败，请稍后重试");
        }
    }

    /**
     * 从数据库持久化字段还原报告，用于重复请求时避免再次调用 LLM。
     */
    private InterviewReportDTO loadPersistedReport(InterviewSessionEntity entity) {
        List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(entity.getSessionId());

        List<InterviewReportDTO.QuestionEvaluation> details = answers.stream()
            .sorted(Comparator.comparing(answer ->
                answer.getQuestionIndex() == null ? 0 : answer.getQuestionIndex()))
            .map(answer -> new InterviewReportDTO.QuestionEvaluation(
                answer.getQuestionIndex() == null ? 0 : answer.getQuestionIndex(),
                answer.getQuestion(),
                answer.getCategory(),
                answer.getUserAnswer(),
                answer.getScore() == null ? 0 : answer.getScore(),
                answer.getFeedback()))
            .toList();

        List<InterviewReportDTO.ReferenceAnswer> referenceAnswers = readJsonList(
            entity.getReferenceAnswersJson(), new TypeReference<List<InterviewReportDTO.ReferenceAnswer>>() {});
        List<String> strengths = readJsonList(
            entity.getStrengthsJson(), new TypeReference<List<String>>() {});
        List<String> improvements = readJsonList(
            entity.getImprovementsJson(), new TypeReference<List<String>>() {});

        List<InterviewReportDTO.CategoryScore> categoryScores = details.stream()
            .collect(Collectors.groupingBy(detail ->
                detail.category() == null ? "综合" : detail.category()))
            .entrySet().stream()
            .map(entry -> new InterviewReportDTO.CategoryScore(
                entry.getKey(),
                (int) Math.round(entry.getValue().stream()
                    .mapToInt(InterviewReportDTO.QuestionEvaluation::score)
                    .average()
                    .orElse(0)),
                entry.getValue().size()))
            .toList();

        return new InterviewReportDTO(
            entity.getSessionId(),
            details.size(),
            entity.getOverallScore() == null ? 0 : entity.getOverallScore(),
            categoryScores,
            details,
            entity.getOverallFeedback(),
            strengths,
            improvements,
            referenceAnswers
        );
    }

    private <T> List<T> readJsonList(String json, TypeReference<List<T>> typeReference) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (Exception e) {
            log.warn("解析持久化报告字段失败: sessionId 数据不完整", e);
            return List.of();
        }
    }

    /**
     * 将缓存会话转换为 DTO
     */
    private InterviewSessionDTO toDTO(CachedSession session) {
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);
        return new InterviewSessionDTO(
            session.getSessionId(),
            session.getResumeText(),
            questions.size(),
            session.getCurrentIndex(),
            questions,
            session.getStatus()
        );
    }
}
