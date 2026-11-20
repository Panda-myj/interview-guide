package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.security.CurrentUserService;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.dto.VoiceInterviewMessageDTO;
import interview.guide.modules.voiceinterview.dto.SessionMetaDTO;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Voice Interview Service
 * 语音面试服务
 * <p>
 * Provides business logic for voice interview session management including:
 * - Session lifecycle management (create, end, retrieve)
 * - Phase transitions and state tracking
 * - Message persistence and conversation history
 * - Redis caching for active sessions
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewService {

    private final VoiceInterviewSessionRepository sessionRepository;
    private final VoiceInterviewMessageRepository messageRepository;
    private final VoiceInterviewEvaluationRepository evaluationRepository;
    private final RedissonClient redissonClient;
    private final VoiceInterviewProperties properties;
    private final VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;
    private final LlmProviderRegistry llmProviderRegistry;
    private final CurrentUserService currentUserService;
    private final ResumeRepository resumeRepository;

    private static final String SESSION_CACHE_KEY_PREFIX = "voice:interview:session:";
    private static final int CACHE_TTL_HOURS = 1;
    private static final String DEFAULT_USER_ID = "default";

    /**
     * Create a new voice interview session
     * 创建新的语音面试会话
     *
     * @param request Session creation request with role type and phase configuration
     * @return SessionResponseDTO with session details and WebSocket URL
     */
    @Transactional
    public SessionResponseDTO createSession(CreateSessionRequest request) {
        String effectiveSkillId = request.getSkillId() != null ? request.getSkillId() : InterviewDefaults.SKILL_ID;
        String effectiveLlmProvider = (request.getLlmProvider() != null && !request.getLlmProvider().isBlank())
            ? request.getLlmProvider()
            : null;
        UserEntity ownerUser = currentUserService.getRequiredUser();
        requireOwnedResume(request.getResumeId(), ownerUser.getId());

        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                .ownerUser(ownerUser)
                .roleType(effectiveSkillId)
                .skillId(effectiveSkillId)
                .difficulty(request.getDifficulty() != null ? request.getDifficulty() : InterviewDefaults.DIFFICULTY)
                .customJdText(request.getCustomJdText())
                .resumeId(request.getResumeId())
                .introEnabled(request.getIntroEnabled())
                .techEnabled(request.getTechEnabled())
                .projectEnabled(request.getProjectEnabled())
                .hrEnabled(request.getHrEnabled())
                .llmProvider(effectiveLlmProvider)
                .plannedDuration(request.getPlannedDuration())
                .currentPhase(determineFirstPhase(request))
                .build();

        VoiceInterviewSessionEntity saved = sessionRepository.save(session);
        cacheSession(saved);

        log.info("Created voice interview session: {} with template: {}, phase: {}",
                saved.getId(), effectiveSkillId, saved.getCurrentPhase());

        return buildSessionResponse(saved);
    }

    /**
     * 仅当会话处于 IN_PROGRESS 状态时结束，用于 WebSocket 异常断开的兜底。
     * 正常结束的 endSession 已设为 COMPLETED，此方法不会重复操作，
     * 因此 WebSocket 重连不会重复触发评估。
     */
    @Transactional
    public void endSessionIfInProgress(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        if (sessionIdLong == null) {
            log.warn("Cannot auto-end session - invalid id: {}", sessionId);
            return;
        }
        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong).orElse(null);
        if (session == null || session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            return;
        }
        log.info("Auto-ending IN_PROGRESS session {} after WebSocket disconnect", sessionId);
        if (endSession(session) && claimPendingForEvaluation(sessionIdLong)) {
            sendEvaluateTaskAfterCommit(sessionIdLong);
        }
    }

    /**
     * End interview session and update status
     * 结束面试会话并更新状态
     *
     * <p>幂等：已结束的会话重复结束直接返回，不会重复入队评估。</p>
     *
     * @param sessionId Session ID (String format, will be converted to Long)
     */
    @Transactional
    public void endSession(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        if (sessionIdLong == null) {
            log.warn("Cannot end session - invalid id: {}", sessionId);
            return;
        }
        VoiceInterviewSessionEntity session = getSession(sessionIdLong);

        if (session == null) {
            log.warn("Session not found: {}", sessionId);
            return;
        }

        if (endSession(session) && claimPendingForEvaluation(sessionIdLong)) {
            sendEvaluateTaskAfterCommit(sessionIdLong);
        }
    }

    /**
     * 条件结束会话。
     *
     * @return true 表示本次调用完成了状态迁移；false 表示会话已结束或已被并发结束
     */
    private boolean endSession(VoiceInterviewSessionEntity session) {
        Long sessionId = session.getId();
        VoiceInterviewSessionStatus current = session.getStatus();

        if (current == VoiceInterviewSessionStatus.COMPLETED) {
            log.info("Session {} already completed, ignore duplicate end request", sessionId);
            return false;
        }
        if (!VoiceInterviewSessionEntity.canTransition(current, VoiceInterviewSessionStatus.COMPLETED)) {
            throw new BusinessException(ErrorCode.VOICE_ILLEGAL_STATE_TRANSITION,
                "会话状态为 " + current + "，无法结束");
        }

        // 条件更新：IN_PROGRESS/PAUSED -> COMPLETED，防止并发结束重复写
        int updated = sessionRepository.updateStatusConditionally(
            sessionId,
            VoiceInterviewSessionStatus.COMPLETED,
            List.of(VoiceInterviewSessionStatus.IN_PROGRESS, VoiceInterviewSessionStatus.PAUSED));
        if (updated == 0) {
            log.info("Session {} status was already changed by concurrent request", sessionId);
            return false;
        }

        VoiceInterviewSessionEntity latest = sessionRepository.findById(sessionId).orElse(null);
        if (latest == null) {
            log.warn("Session {} disappeared while ending", sessionId);
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        latest.setStatus(VoiceInterviewSessionStatus.COMPLETED);
        latest.setEndTime(now);
        latest.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED);
        latest.setActualDuration(latest.getStartTime() == null
            ? 0
            : (int) Duration.between(latest.getStartTime(), now).toSeconds());
        sessionRepository.save(latest);
        invalidateSessionCache(sessionId);

        log.info("Ended voice interview session: {}, duration: {} seconds",
            sessionId, latest.getActualDuration());
        return true;
    }

    /**
     * Get session by ID with Redis cache fallback
     * 通过ID获取会话，支持Redis缓存
     *
     * @param sessionId Session ID (String format, will be converted to Long)
     * @return VoiceInterviewSessionEntity or null if not found
     */
    public VoiceInterviewSessionEntity getSession(String sessionId) {
        return getSession(parseSessionId(sessionId));
    }

    /**
     * Get session by ID with Redis cache fallback
     * 通过ID获取会话，支持Redis缓存
     *
     * @param sessionId Session ID as Long
     * @return VoiceInterviewSessionEntity or null if not found
     */
    public VoiceInterviewSessionEntity getSession(Long sessionId) {
        if (sessionId == null) {
            return null;
        }

        // Try cache first。缓存反序列化失败（例如旧版本写入的含 Hibernate 代理对象）时
        // 必须降级到数据库，而不是让整个请求抛出 500。
        String cacheKey = getSessionCacheKey(sessionId);
        try {
            RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
            VoiceInterviewSessionEntity cached = bucket.get();
            if (cached != null) {
                log.debug("Session {} found in cache", sessionId);
                return cached;
            }
        } catch (Exception e) {
            log.warn("Failed to read cached session {}, falling back to database: {}", sessionId, e.getMessage());
            try {
                redissonClient.getBucket(cacheKey).delete();
            } catch (Exception ignore) {
                log.debug("Failed to delete corrupt session cache key {}", cacheKey);
            }
        }

        // Fallback to database
        return sessionRepository.findById(sessionId).orElse(null);
    }

    public VoiceInterviewSessionEntity requireOwned(Long sessionId) {
        return sessionRepository.findByIdAndOwnerUserId(sessionId, currentUserService.getRequiredUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "语音面试会话不存在"));
    }

    /**
     * 直接读库获取会话状态（绕过 Redis 快照），供 WebSocket 接入前判定会话是否已收尾。
     *
     * <p>必须读库：镜像在缓存里的快照可能是结束前的旧状态，用它判断会放过对已结束会话的连接。</p>
     *
     * @return 会话状态；会话不存在时返回 null
     */
    public VoiceInterviewSessionStatus getStatusFromDatabase(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessionRepository.findById(sessionId)
            .map(VoiceInterviewSessionEntity::getStatus)
            .orElse(null);
    }

    /**
     * 按当前登录用户查询会话并转换为响应 DTO。
     *
     * <p>绕过 Redis 缓存，避免缓存中的旧状态被返回给前端（评估状态频繁变化）。</p>
     */
    public SessionResponseDTO getOwnedSessionDTO(Long sessionId) {
        return buildSessionResponse(requireOwned(sessionId));
    }

    /**
     * 校验简历归属：只允许把当前用户自己的简历拼进 LLM 提示词。
     */
    private void requireOwnedResume(Long resumeId, Long ownerUserId) {
        if (resumeId == null) {
            return;
        }
        resumeRepository.findByIdAndOwnerUserId(resumeId, ownerUserId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在或无权访问"));
    }

    /**
     * Start a new interview phase
     * 开始新的面试阶段
     *
     * @param sessionId Session ID (String format)
     * @param phaseStr  Phase as string (INTRO, TECH, PROJECT, HR)
     */
    @Transactional
    public void startPhase(String sessionId, String phaseStr) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong);

        if (session == null) {
            log.warn("Cannot start phase - session not found: {}", sessionId);
            return;
        }
        if (session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.VOICE_ILLEGAL_STATE_TRANSITION,
                "会话状态为 " + session.getStatus() + "，无法切换阶段");
        }

        VoiceInterviewSessionEntity.InterviewPhase newPhase;
        try {
            newPhase = VoiceInterviewSessionEntity.InterviewPhase.valueOf(phaseStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的面试阶段: " + phaseStr);
        }

        VoiceInterviewSessionEntity.InterviewPhase oldPhase = session.getCurrentPhase();
        // 状态变更必须基于数据库里的托管实体：缓存命中的是脱离持久化上下文的快照，
        // 直接 save() 可能因 ownerUser 只有 id 的裁剪对象引发关联覆盖等问题。
        VoiceInterviewSessionEntity managed = sessionRepository.findById(sessionIdLong)
            .orElse(session);
        managed.setCurrentPhase(newPhase);
        sessionRepository.save(managed);
        cacheSession(managed); // Update cache

        log.info("Session {} transitioned from phase {} to {}", sessionId, oldPhase, newPhase);
    }

    /**
     * Get current phase for session
     * 获取会话当前阶段
     *
     * @param sessionId Session ID (String format)
     * @return Current InterviewPhase or null if session not found
     */
    public VoiceInterviewSessionEntity.InterviewPhase getCurrentPhase(String sessionId) {
        VoiceInterviewSessionEntity session = getSession(sessionId);
        return session != null ? session.getCurrentPhase() : null;
    }

    /**
     * Save dialogue message (user and AI text) to database
     * 保存对话消息（用户和AI文本）到数据库
     *
     * @param sessionId Session ID (String format)
     * @param userText  User's recognized speech text
     * @param aiText    AI's generated response text
     */
    @Transactional
    public void saveMessage(String sessionId, String userText, String aiText) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong);

        if (session == null) {
            log.warn("Cannot save message - session not found: {}", sessionId);
            return;
        }

        String normalizedUserText = VoiceInterviewMessageEntity.trimToNull(userText);
        String normalizedAiText = VoiceInterviewMessageEntity.trimToNull(aiText);

        boolean answerAttached = normalizedUserText != null
            && fillLatestUnansweredQuestion(sessionIdLong, normalizedUserText);
        if (normalizedAiText == null) {
            return;
        }

        VoiceInterviewMessageEntity message = VoiceInterviewMessageEntity.builder()
                .sessionId(sessionIdLong)
                .messageType("DIALOGUE")
                .phase(session.getCurrentPhase())
                .userRecognizedText(normalizedUserText != null && !answerAttached
                    ? normalizedUserText
                    : null)
                .aiGeneratedText(normalizedAiText)
                .sequenceNum(getNextSequenceNum(sessionIdLong))
                .build();

        messageRepository.save(message);
        log.debug("Saved message for session: {}, phase: {}, sequence: {}",
                sessionId, session.getCurrentPhase(), message.getSequenceNum());
    }

    private boolean fillLatestUnansweredQuestion(Long sessionId, String userText) {
        return messageRepository
            .findFirstBySessionIdAndUserRecognizedTextIsNullAndAiGeneratedTextIsNotNullOrderBySequenceNumDesc(
                sessionId)
            .map(message -> {
                message.setUserRecognizedText(userText);
                messageRepository.save(message);
                log.debug("Filled answer for voice message: sessionId={}, sequence={}",
                    sessionId, message.getSequenceNum());
                return true;
            })
            .orElse(false);
    }

    /**
     * Get conversation history for a session
     * 获取会话的对话历史记录
     *
     * @param sessionId Session ID (String format)
     * @return List of messages ordered by sequence number
     */
    public List<VoiceInterviewMessageEntity> getConversationHistory(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        return messageRepository.findBySessionIdOrderBySequenceNumAsc(sessionIdLong);
    }

    /**
     * Get conversation history as DTOs (for frontend)
     */
    public List<VoiceInterviewMessageDTO> getConversationHistoryDTO(String sessionId) {
        return getConversationHistory(sessionId).stream()
            .map(msg -> VoiceInterviewMessageDTO.builder()
                .id(msg.getId())
                .sessionId(msg.getSessionId())
                .messageType(msg.getMessageType())
                .phase(msg.getPhase() != null ? msg.getPhase().name() : null)
                .userRecognizedText(msg.getUserRecognizedText())
                .aiGeneratedText(msg.getAiGeneratedText())
                .timestamp(msg.getTimestamp())
                .sequenceNum(msg.getSequenceNum())
                .build())
            .collect(Collectors.toList());
    }

    /**
     * Pause interview session
     * 暂停面试会话
     *
     * @param sessionId Session ID
     * @param reason Pause reason (user_initiated or timeout)
     */
    @Transactional
    public void pauseSession(String sessionId, String reason) {
        Long sessionIdLong = parseSessionId(sessionId);
        if (sessionIdLong == null) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话ID无效: " + sessionId);
        }

        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));

        if (session.getStatus() == VoiceInterviewSessionStatus.PAUSED) {
            log.info("Session {} already paused, ignore duplicate pause request", sessionId);
            return;
        }
        if (session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.VOICE_ILLEGAL_STATE_TRANSITION,
                "会话状态为 " + session.getStatus() + "，无法暂停");
        }

        int updated = sessionRepository.updateStatusConditionally(
            sessionIdLong,
            VoiceInterviewSessionStatus.PAUSED,
            List.of(VoiceInterviewSessionStatus.IN_PROGRESS));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.VOICE_ILLEGAL_STATE_TRANSITION,
                "会话状态已变化，无法暂停: " + sessionId);
        }

        session.setStatus(VoiceInterviewSessionStatus.PAUSED);
        session.setPausedAt(LocalDateTime.now());
        sessionRepository.save(session);
        invalidateSessionCache(sessionIdLong);

        log.info("Session {} paused, reason: {}", sessionId, reason);
    }

    /**
     * Resume interview session
     * 恢复面试会话
     *
     * @param sessionId Session ID
     * @return SessionResponseDTO with WebSocket URL
     */
    @Transactional
    public SessionResponseDTO resumeSession(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        if (sessionIdLong == null) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话ID无效: " + sessionId);
        }

        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));

        if (session.getStatus() == VoiceInterviewSessionStatus.IN_PROGRESS) {
            log.info("Session {} already in progress, ignore duplicate resume request", sessionId);
            return buildSessionResponse(session);
        }
        if (session.getStatus() != VoiceInterviewSessionStatus.PAUSED) {
            throw new BusinessException(ErrorCode.VOICE_ILLEGAL_STATE_TRANSITION,
                "会话状态为 " + session.getStatus() + "，无法恢复");
        }

        int updated = sessionRepository.updateStatusConditionally(
            sessionIdLong,
            VoiceInterviewSessionStatus.IN_PROGRESS,
            List.of(VoiceInterviewSessionStatus.PAUSED));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.VOICE_ILLEGAL_STATE_TRANSITION,
                "会话状态已变化，无法恢复: " + sessionId);
        }

        session.setStatus(VoiceInterviewSessionStatus.IN_PROGRESS);
        session.setResumedAt(LocalDateTime.now());

        VoiceInterviewSessionEntity saved = sessionRepository.save(session);
        cacheSession(saved);

        log.info("Session {} resumed with {} messages in conversation history",
            sessionId, messageRepository.countBySessionId(sessionIdLong));

        return buildSessionResponse(saved);
    }

    /**
     * Get all sessions for a user
     * 获取用户所有会话
     *
     * @param userId User ID (optional, defaults to DEFAULT_USER_ID)
     * @param status Filter by status (optional)
     * @return List of session metadata
     */
    public List<SessionMetaDTO> getAllSessions(String ownerUserId, String status) {
        ownerUserId = ownerUserId != null ? ownerUserId : DEFAULT_USER_ID;

        List<VoiceInterviewSessionEntity> sessions;
        if (status != null && !status.isEmpty()) {
            VoiceInterviewSessionStatus statusEnum = parseSessionStatus(status);
            sessions = sessionRepository.findAllByOwnerUserIdAndStatusOrderByUpdatedAtDesc(
                parseOwnerUserId(ownerUserId), statusEnum
            );
        } else {
            sessions = sessionRepository.findAllByOwnerUserIdOrderByUpdatedAtDesc(parseOwnerUserId(ownerUserId));
        }

        // 批量取回消息数，避免列表页逐个会话 count 造成 N+1 查询
        Map<Long, Long> messageCounts = new HashMap<>();
        if (!sessions.isEmpty()) {
            List<Long> sessionIds = sessions.stream()
                .map(VoiceInterviewSessionEntity::getId)
                .toList();
            messageRepository.countBySessionIds(sessionIds).forEach(row ->
                messageCounts.put((Long) row[0], (Long) row[1]));
        }

        return sessions.stream()
            .map(session -> SessionMetaDTO.builder()
                .sessionId(session.getId())
                .roleType(session.getRoleType())
                .status(session.getStatus().name())
                .currentPhase(session.getCurrentPhase().name())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .actualDuration(session.getActualDuration())
                .messageCount(messageCounts.getOrDefault(session.getId(), 0L))
                .evaluateStatus(session.getEvaluateStatus() != null ? session.getEvaluateStatus().name() : null)
                .evaluateError(session.getEvaluateError())
                .build())
            .collect(Collectors.toList());
    }

    /**
     * Get session DTO by ID
     * 通过ID获取会话DTO
     *
     * @param sessionId Session ID as Long
     * @return SessionResponseDTO with session details or null if not found
     */
    public SessionResponseDTO getSessionDTO(Long sessionId) {
        VoiceInterviewSessionEntity session = getSession(sessionId);

        if (session == null) {
            return null;
        }

        return buildSessionResponse(session);
    }

    /**
     * Check if session should transition to next phase based on duration and question count
     * 检查是否应该转换到下一个阶段（基于时长和问题数量）
     *
     * @param session        Current session
     * @param phaseStartTime Time when current phase started
     * @param questionCount  Number of questions asked in current phase
     * @return true if should transition, false otherwise
     */
    public boolean shouldTransitionToNextPhase(VoiceInterviewSessionEntity session,
                                                LocalDateTime phaseStartTime,
                                                int questionCount) {
        VoiceInterviewSessionEntity.InterviewPhase currentPhase = session.getCurrentPhase();
        if (currentPhase == null || currentPhase == VoiceInterviewSessionEntity.InterviewPhase.COMPLETED) {
            return false;
        }

        Duration phaseDuration = Duration.between(phaseStartTime, LocalDateTime.now());
        VoiceInterviewProperties.DurationConfig config = getPhaseConfig(currentPhase);

        // Rule 1: Max duration reached (forced transition)
        if (phaseDuration.toMinutes() >= config.getMaxDuration()) {
            log.info("Phase {} reached max duration {} minutes, forcing transition",
                    currentPhase, config.getMaxDuration());
            return true;
        }

        // Rule 2: Min questions reached and sufficient information gathered (AI judgment)
        // For MVP, we use a simple heuristic based on question count
        if (questionCount >= config.getMaxQuestions()) {
            log.info("Phase {} reached max questions {}, suggesting transition",
                    currentPhase, config.getMaxQuestions());
            return true;
        }

        // Rule 3: Suggested duration reached with min questions
        if (phaseDuration.toMinutes() >= config.getSuggestedDuration()
                && questionCount >= config.getMinQuestions()) {
            log.info("Phase {} reached suggested duration {} with {} questions, suggesting transition",
                    currentPhase, config.getSuggestedDuration(), questionCount);
            return true;
        }

        return false;
    }

    /**
     * Get the next enabled phase after current phase
     * 获取当前阶段之后的下一个启用的阶段
     *
     * @param session Current session
     * @return Next InterviewPhase or COMPLETED if no more phases
     */
    public VoiceInterviewSessionEntity.InterviewPhase getNextPhase(VoiceInterviewSessionEntity session) {
        VoiceInterviewSessionEntity.InterviewPhase current = session.getCurrentPhase();
        if (current == null) {
            return getFirstEnabledPhase(session);
        }

        return switch (current) {
            case INTRO -> session.getTechEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.TECH :
                    session.getProjectEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.PROJECT :
                            session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR :
                                    VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
            case TECH -> session.getProjectEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.PROJECT :
                    session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR :
                            VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
            case PROJECT -> session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR :
                    VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
            case HR, COMPLETED -> VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
        };
    }

    // ==================== Private Helper Methods ====================

    /**
     * Determine the first phase based on enabled phases
     * 根据启用的阶段确定第一个阶段
     */
    private VoiceInterviewSessionEntity.InterviewPhase determineFirstPhase(CreateSessionRequest request) {
        if (request.getIntroEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.INTRO;
        if (request.getTechEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.TECH;
        if (request.getProjectEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.PROJECT;
        if (request.getHrEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.HR;
        return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
    }

    /**
     * Get first enabled phase from session
     */
    private VoiceInterviewSessionEntity.InterviewPhase getFirstEnabledPhase(VoiceInterviewSessionEntity session) {
        if (session.getIntroEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.INTRO;
        if (session.getTechEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.TECH;
        if (session.getProjectEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.PROJECT;
        if (session.getHrEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.HR;
        return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
    }

    private SessionResponseDTO buildSessionResponse(VoiceInterviewSessionEntity session) {
        return SessionResponseDTO.builder()
                .sessionId(session.getId())
                .roleType(session.getRoleType())
                .currentPhase(session.getCurrentPhase().name())
                .status(session.getStatus().name())
                .startTime(session.getStartTime())
                .plannedDuration(session.getPlannedDuration())
                .webSocketUrl(String.format("/ws/voice-interview/%d", session.getId()))
                .build();
    }

    /**
     * Get phase configuration from properties
     */
    private VoiceInterviewProperties.DurationConfig getPhaseConfig(VoiceInterviewSessionEntity.InterviewPhase phase) {
        return switch (phase) {
            case INTRO -> properties.getPhase().getIntro();
            case TECH -> properties.getPhase().getTech();
            case PROJECT -> properties.getPhase().getProject();
            case HR -> properties.getPhase().getHr();
            default -> new VoiceInterviewProperties.DurationConfig(0, 0, 0, 0, 0);
        };
    }

    /**
     * Get next sequence number for messages in a session
     */
    private int getNextSequenceNum(Long sessionId) {
        return (int) messageRepository.countBySessionId(sessionId) + 1;
    }

    /**
     * Update evaluation status on session entity (shared by Producer/Consumer/Controller).
     *
     * <p>按状态机执行条件更新：仅允许 PENDING→PROCESSING、PROCESSING→COMPLETED、
     * PENDING/PROCESSING→FAILED、FAILED→PENDING，非法或过期迁移会被忽略并记录日志，
     * 避免重复投递覆盖已完成结果。</p>
     */
    @Transactional
    public void updateEvaluateStatus(Long sessionId, AsyncTaskStatus status, String error) {
        if (status == null) {
            return;
        }
        switch (status) {
            case PROCESSING -> claimEvaluationProcessing(sessionId);
            case COMPLETED -> completeEvaluation(sessionId);
            case FAILED -> failEvaluation(sessionId, error);
            case PENDING -> {
                if (!claimPendingForEvaluation(sessionId)) {
                    log.info("Evaluation status cannot move to PENDING: sessionId={}", sessionId);
                }
            }
        }
    }

    /**
     * 抢占评估任务：PENDING → PROCESSING（条件更新，按影响行数判断）。
     *
     * @return true 表示抢到处理权；false 表示消息重复、乱序或已被其它实例处理
     */
    @Transactional
    public boolean claimEvaluationProcessing(Long sessionId) {
        int updated = sessionRepository.claimEvaluateStatus(
            sessionId, AsyncTaskStatus.PENDING, AsyncTaskStatus.PROCESSING);
        if (updated == 0) {
            log.info("Evaluation task not claimed (duplicate or stale): sessionId={}", sessionId);
            return false;
        }
        return true;
    }

    /**
     * 评估完成：PROCESSING → COMPLETED。
     */
    @Transactional
    public void completeEvaluation(Long sessionId) {
        int updated = sessionRepository.updateEvaluateStatusConditionally(
            sessionId, AsyncTaskStatus.COMPLETED, List.of(AsyncTaskStatus.PROCESSING), null);
        if (updated == 0) {
            log.warn("Evaluation COMPLETED not written, status may be updated elsewhere: sessionId={}",
                sessionId);
        }
    }

    /**
     * 评估失败：PENDING/PROCESSING → FAILED。
     */
    @Transactional
    public void failEvaluation(Long sessionId, String error) {
        String truncated = error != null && error.length() > 500 ? error.substring(0, 500) : error;
        int updated = sessionRepository.updateEvaluateStatusConditionally(
            sessionId, AsyncTaskStatus.FAILED,
            List.of(AsyncTaskStatus.PENDING, AsyncTaskStatus.PROCESSING), truncated);
        if (updated == 0) {
            log.warn("Evaluation FAILED not written, task may be completed: sessionId={}", sessionId);
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
    @Transactional
    public boolean resetEvaluationForRetry(Long sessionId) {
        return sessionRepository.updateEvaluateStatusConditionally(
            sessionId, AsyncTaskStatus.PENDING,
            List.of(AsyncTaskStatus.PROCESSING, AsyncTaskStatus.PENDING), null) > 0;
    }

    /**
     * 申请评估任务：未初始化或 FAILED 时才迁移到 PENDING，避免重复入队。
     */
    private boolean claimPendingForEvaluation(Long sessionId) {
        int initialized = sessionRepository.initializeEvaluateStatus(
            sessionId, AsyncTaskStatus.PENDING, null);
        int replayed = sessionRepository.updateEvaluateStatusConditionally(
            sessionId, AsyncTaskStatus.PENDING, List.of(AsyncTaskStatus.FAILED), null);
        return initialized + replayed > 0;
    }

    /**
     * Trigger async evaluation for a session (called by Controller).
     *
     * <p>幂等：仅在评估状态可迁移到 PENDING 时入队；已完成或处理中直接返回。</p>
     */
    @Transactional
    public void triggerEvaluation(Long sessionId) {
        if (!claimPendingForEvaluation(sessionId)) {
            log.info("Evaluation already queued or completed, skip duplicate trigger: sessionId={}",
                sessionId);
            return;
        }
        sendEvaluateTaskAfterCommit(sessionId);
    }

    private void sendEvaluateTaskAfterCommit(Long sessionId) {
        Runnable sendTask = () -> voiceEvaluateStreamProducer.sendEvaluateTask(sessionId.toString());
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendTask.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendTask.run();
            }
        });
    }

    /**
     * 删除语音面试会话及其关联的消息和评估记录
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        requireOwned(sessionId);
        evaluationRepository.findBySessionId(sessionId).ifPresent(evaluationRepository::delete);
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
        log.info("Deleted voice interview session: {}", sessionId);
    }

    /**
     * Cache session in Redis。
     *
     * <p>不能把 JPA 托管实体直接交给 Redisson 的 Kryo5 codec：{@code ownerUser} 是
     * LAZY 关联的 Hibernate 代理（ByteBuddyInterceptor），Kryo 反射其内部字段时会触发
     * Java 21 的模块访问限制（InaccessibleObjectException: java.lang.reflect），
     * 导致创建/恢复会话抛 500。这里只缓存一份去掉代理的快照实体，
     * 仅保留业务需要的标量字段与 ownerUser.id。</p>
     *
     * <p>缓存是加速手段而非数据源，写入失败只告警、不中断面试主流程。</p>
     */
    private void cacheSession(VoiceInterviewSessionEntity session) {
        String cacheKey = getSessionCacheKey(session.getId());
        try {
            VoiceInterviewSessionEntity snapshot = toCacheableSnapshot(session);
            RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
            bucket.set(snapshot, Duration.ofHours(CACHE_TTL_HOURS));
            log.debug("Cached session: {}", session.getId());
        } catch (Exception e) {
            log.warn("Failed to cache session {} (cache disabled for this session): {}",
                session.getId(), e.getMessage());
        }
    }

    /** 构造不含 Hibernate 代理、可被 Kryo5 安全序列化的会话快照 */
    private VoiceInterviewSessionEntity toCacheableSnapshot(VoiceInterviewSessionEntity s) {
        UserEntity owner = null;
        if (s.getOwnerUser() != null) {
            // 只取 id 构造一个普通 POJO，绝不把 HibernateProxy 放进快照。
            // 业务侧对该字段仅使用 getId()。
            owner = new UserEntity();
            owner.setId(s.getOwnerUser().getId());
        }
        return VoiceInterviewSessionEntity.builder()
            .id(s.getId())
            .ownerUser(owner)
            .roleType(s.getRoleType())
            .skillId(s.getSkillId())
            .difficulty(s.getDifficulty())
            .customJdText(s.getCustomJdText())
            .resumeId(s.getResumeId())
            .introEnabled(s.getIntroEnabled())
            .techEnabled(s.getTechEnabled())
            .projectEnabled(s.getProjectEnabled())
            .hrEnabled(s.getHrEnabled())
            .llmProvider(s.getLlmProvider())
            .currentPhase(s.getCurrentPhase())
            .status(s.getStatus())
            .plannedDuration(s.getPlannedDuration())
            .actualDuration(s.getActualDuration())
            .startTime(s.getStartTime())
            .endTime(s.getEndTime())
            .createdAt(s.getCreatedAt())
            .updatedAt(s.getUpdatedAt())
            .pausedAt(s.getPausedAt())
            .resumedAt(s.getResumedAt())
            .evaluateStatus(s.getEvaluateStatus())
            .evaluateError(s.getEvaluateError())
            .evaluateAttempt(s.getEvaluateAttempt())
            .build();
    }

    /**
     * Invalidate session cache in Redis
     */
    private void invalidateSessionCache(Long sessionId) {
        String cacheKey = getSessionCacheKey(sessionId);
        RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
        bucket.delete();
        log.debug("Invalidated cache for session: {}", sessionId);
    }

    /**
     * Generate Redis cache key for session
     */
    private String getSessionCacheKey(Long sessionId) {
        return SESSION_CACHE_KEY_PREFIX + sessionId;
    }

    /**
     * Parse session ID from String to Long with error handling
     */
    private Long parseSessionId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            return Long.parseLong(sessionId);
        } catch (NumberFormatException e) {
            log.error("Invalid session ID format: {}", sessionId, e);
            return null;
        }
    }

    private Long parseOwnerUserId(String ownerUserId) {
        try {
            return Long.parseLong(ownerUserId);
        } catch (NumberFormatException e) {
            // 格式错误属于请求参数问题，与 startPhase 等场景对齐使用 BAD_REQUEST
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的用户标识: " + ownerUserId);
        }
    }

    /**
     * 安全解析会话状态枚举，非法值按 BAD_REQUEST 处理，与 startPhase 的枚举解析方式一致。
     */
    private VoiceInterviewSessionStatus parseSessionStatus(String status) {
        try {
            return VoiceInterviewSessionStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的会话状态: " + status);
        }
    }

    /**
     * 清理超时的 IN_PROGRESS 会话和卡住的 PROCESSING 评估。
     * 由 @Scheduled 在 WebSocketHandler 中定时触发。
     */
    @Transactional
    public int cleanupStaleSessions() {
        LocalDateTime staleThreshold = LocalDateTime.now().minusHours(2);

        List<VoiceInterviewSessionEntity> staleSessions = sessionRepository
            .findByStatusAndStartTimeBefore(VoiceInterviewSessionStatus.IN_PROGRESS, staleThreshold);

        int cleaned = 0;
        for (VoiceInterviewSessionEntity session : staleSessions) {
            log.info("Cleaning up stale IN_PROGRESS session {}, started at {}",
                session.getId(), session.getStartTime());
            // 与 endSessionIfInProgress / 孤儿对账保持一致：结束时同步入队评估，
            // 否则超时中断的会话永远停留在 COMPLETED 且无评估结果
            if (endSession(session) && claimPendingForEvaluation(session.getId())) {
                sendEvaluateTaskAfterCommit(session.getId());
            }
            cleaned++;
        }

        LocalDateTime evalStaleThreshold = LocalDateTime.now().minusMinutes(30);
        List<VoiceInterviewSessionEntity> stuckEvals = sessionRepository
            .findByEvaluateStatusAndUpdatedAtBefore(AsyncTaskStatus.PROCESSING, evalStaleThreshold);

        for (VoiceInterviewSessionEntity session : stuckEvals) {
            log.info("Resetting stuck PROCESSING evaluation for session {}", session.getId());
            // 条件更新 PROCESSING → FAILED，避免覆盖并发完成的评估结果
            int updated = sessionRepository.updateEvaluateStatusConditionally(
                session.getId(), AsyncTaskStatus.FAILED, List.of(AsyncTaskStatus.PROCESSING),
                "评估超时，请重新触发");
            if (updated > 0) {
                cleaned++;
            }
        }

        return cleaned;
    }
}
