package interview.guide.modules.interview.repository;

import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 面试会话Repository
 */
@Repository
public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {

    /**
     * 根据会话ID查找
     */
    Optional<InterviewSessionEntity> findBySessionId(String sessionId);

    Optional<InterviewSessionEntity> findBySessionIdAndOwnerUserId(String sessionId, Long ownerUserId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE InterviewSessionEntity s
        SET s.currentQuestionIndex = :index,
            s.status = :status,
            s.version = s.version + 1
        WHERE s.sessionId = :sessionId
          AND s.version = :version
          AND s.status IN :allowedStatuses
        """)
    int updateProgressAndStatus(
        String sessionId,
        Long ownerUserId,
        int index,
        SessionStatus status,
        List<SessionStatus> allowedStatuses,
        Long version
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE InterviewSessionEntity s
        SET s.status = :status,
            s.completedAt = CASE WHEN :status IN (COMPLETED, EVALUATED) THEN CURRENT_TIMESTAMP ELSE s.completedAt END,
            s.version = s.version + 1
        WHERE s.sessionId = :sessionId
          AND s.ownerUser.id = :ownerUserId
          AND s.version = :version
          AND s.status IN :allowedStatuses
        """)
    int updateStatus(
        String sessionId,
        Long ownerUserId,
        SessionStatus status,
        List<SessionStatus> allowedStatuses,
        Long version
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE InterviewSessionEntity s
        SET s.evaluateStatus = :evaluateStatus,
            s.evaluateError = :evaluateError,
            s.version = s.version + 1
        WHERE s.sessionId = :sessionId
          AND s.ownerUser.id = :ownerUserId
          AND s.evaluateStatus = :expectedEvaluateStatus
        """)
    int updateEvaluateStatus(
        String sessionId,
        Long ownerUserId,
        AsyncTaskStatus evaluateStatus,
        AsyncTaskStatus expectedEvaluateStatus,
        String evaluateError
    );

    /**
     * 条件更新会话状态：仅当当前状态在 fromStatuses 中时才迁移，返回影响行数。
     * 用于交卷、开始作答等需要幂等的写操作。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE InterviewSessionEntity s
        SET s.status = :toStatus,
            s.completedAt = CASE WHEN :toStatus IN (COMPLETED, EVALUATED) THEN CURRENT_TIMESTAMP ELSE s.completedAt END,
            s.version = s.version + 1
        WHERE s.sessionId = :sessionId
          AND s.status IN :fromStatuses
        """)
    int updateSessionStatusConditionally(
        String sessionId,
        SessionStatus toStatus,
        List<SessionStatus> fromStatuses
    );

    /**
     * 条件更新作答进度与状态：仅当当前状态在 fromStatuses 中时才迁移。
     * 用于提交答案时同时推进索引和状态，防止重复提交或已完成会话被回退。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE InterviewSessionEntity s
        SET s.currentQuestionIndex = :index,
            s.status = :toStatus,
            s.completedAt = CASE WHEN :toStatus IN (COMPLETED, EVALUATED) THEN CURRENT_TIMESTAMP ELSE s.completedAt END,
            s.version = s.version + 1
        WHERE s.sessionId = :sessionId
          AND s.status IN :fromStatuses
        """)
    int updateProgressAndStatusConditionally(
        String sessionId,
        int index,
        SessionStatus toStatus,
        List<SessionStatus> fromStatuses
    );

    /**
     * 抢占评估任务：expected status → PROCESSING，同时累加评估尝试次数。
     * 返回 0 表示消息重复、乱序或已被其它消费者抢占。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE InterviewSessionEntity s
        SET s.evaluateStatus = :toStatus,
            s.evaluateError = NULL,
            s.evaluateAttempt = COALESCE(s.evaluateAttempt, 0) + 1,
            s.version = s.version + 1
        WHERE s.sessionId = :sessionId
          AND s.evaluateStatus = :fromStatus
        """)
    int claimEvaluateStatus(
        String sessionId,
        AsyncTaskStatus fromStatus,
        AsyncTaskStatus toStatus
    );

    /**
     * 条件更新评估状态（不累加尝试次数），返回影响行数。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE InterviewSessionEntity s
        SET s.evaluateStatus = :toStatus,
            s.evaluateError = :evaluateError,
            s.version = s.version + 1
        WHERE s.sessionId = :sessionId
          AND s.evaluateStatus IN :fromStatuses
        """)
    int updateEvaluateStatusConditionally(
        String sessionId,
        AsyncTaskStatus toStatus,
        List<AsyncTaskStatus> fromStatuses,
        String evaluateError
    );

    /**
     * 初始化评估状态：仅当 evaluateStatus 为 NULL 时写入，返回影响行数。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE InterviewSessionEntity s
        SET s.evaluateStatus = :toStatus,
            s.evaluateError = :evaluateError,
            s.evaluateAttempt = COALESCE(s.evaluateAttempt, 0) + 1,
            s.version = s.version + 1
        WHERE s.sessionId = :sessionId
          AND s.evaluateStatus IS NULL
        """)
    int initializeEvaluateStatus(
        String sessionId,
        AsyncTaskStatus toStatus,
        String evaluateError
    );

    /**
     * 根据会话ID查找（同时加载关联的简历）
     */
    @Query("SELECT s FROM InterviewSessionEntity s LEFT JOIN FETCH s.resume WHERE s.sessionId = :sessionId")
    Optional<InterviewSessionEntity> findBySessionIdWithResume(@Param("sessionId") String sessionId);
    
    /**
     * 根据简历查找所有面试记录
     */
    List<InterviewSessionEntity> findByResumeOrderByCreatedAtDesc(ResumeEntity resume);
    
    List<InterviewSessionEntity> findByResumeIdAndOwnerUserIdOrderByCreatedAtDesc(
        Long resumeId,
        Long ownerUserId
    );

    /**
     * 根据简历ID查找最近的面试记录（用于历史题去重）
     */
    List<InterviewSessionEntity> findTop10ByResumeIdOrderByCreatedAtDesc(Long resumeId);
    
    /**
     * 查找简历的未完成面试（CREATED或IN_PROGRESS状态）
     */
    Optional<InterviewSessionEntity> findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(
        Long resumeId, 
        List<SessionStatus> statuses
    );

    Optional<InterviewSessionEntity> findFirstByResumeIdAndOwnerUserIdAndStatusInOrderByCreatedAtDesc(
        Long resumeId,
        Long ownerUserId,
        List<SessionStatus> statuses
    );
    
    /**
     * 根据简历ID和状态查找会话
     */
    Optional<InterviewSessionEntity> findByResumeIdAndStatusIn(
        Long resumeId,
        List<SessionStatus> statuses
    );

    List<InterviewSessionEntity> findAllByOwnerUserIdOrderByCreatedAtDesc(Long ownerUserId);

    /**
     * 分页查找当前用户的面试会话（列表接口用，size 上限由 Service 控制，PERF-002）。
     */
    Page<InterviewSessionEntity> findAllByOwnerUserIdOrderByCreatedAtDesc(Long ownerUserId, Pageable pageable);

    /**
     * 批量统计各简历的面试次数（单条 GROUP BY，替代逐简历 count，PERF-002）。
     * 返回列：resumeId, cnt。
     */
    @Query("""
        SELECT s.resume.id, COUNT(s)
        FROM InterviewSessionEntity s
        WHERE s.resume.id IN :resumeIds
          AND s.ownerUser.id = :ownerUserId
        GROUP BY s.resume.id
        """)
    List<Object[]> countByResumeIdsAndOwnerUserId(
        @Param("resumeIds") List<Long> resumeIds,
        @Param("ownerUserId") Long ownerUserId
    );

    /**
     * 根据 skillId 查找最近的面试记录（用于通用模式历史题去重）
     */
    List<InterviewSessionEntity> findTop10BySkillIdOrderByCreatedAtDesc(String skillId);

    List<InterviewSessionEntity> findTop10ByOwnerUserIdAndSkillIdOrderByCreatedAtDesc(
        Long ownerUserId,
        String skillId
    );

    /**
     * 根据 resumeId + skillId 查找最近的面试记录（精确匹配）
     */
    List<InterviewSessionEntity> findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(Long resumeId, String skillId);

    List<InterviewSessionEntity> findTop10ByResumeIdAndOwnerUserIdAndSkillIdOrderByCreatedAtDesc(
        Long resumeId,
        Long ownerUserId,
        String skillId
    );
}
