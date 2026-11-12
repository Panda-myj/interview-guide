package interview.guide.modules.voiceinterview.repository;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity.InterviewPhase;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 语音面试会话Repository
 */
@Repository
public interface VoiceInterviewSessionRepository extends JpaRepository<VoiceInterviewSessionEntity, Long> {

    /**
     * 条件更新会话状态：仅当当前状态在 fromStatuses 中时才迁移，返回影响行数。
     * 用于结束、暂停、恢复等需要幂等的写操作。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE VoiceInterviewSessionEntity s
        SET s.status = :toStatus,
            s.updatedAt = CURRENT_TIMESTAMP
        WHERE s.id = :sessionId
          AND s.status IN :fromStatuses
        """)
    int updateStatusConditionally(
        Long sessionId,
        VoiceInterviewSessionStatus toStatus,
        List<VoiceInterviewSessionStatus> fromStatuses
    );

    /**
     * 抢占评估任务：expected status → PROCESSING，同时累加评估尝试次数。
     * 返回 0 表示消息重复、乱序或已被其它消费者抢占。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE VoiceInterviewSessionEntity s
        SET s.evaluateStatus = :toStatus,
            s.evaluateError = NULL,
            s.evaluateAttempt = COALESCE(s.evaluateAttempt, 0) + 1,
            s.updatedAt = CURRENT_TIMESTAMP
        WHERE s.id = :sessionId
          AND s.evaluateStatus = :fromStatus
        """)
    int claimEvaluateStatus(
        Long sessionId,
        AsyncTaskStatus fromStatus,
        AsyncTaskStatus toStatus
    );

    /**
     * 条件更新评估状态（不累加尝试次数），返回影响行数。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE VoiceInterviewSessionEntity s
        SET s.evaluateStatus = :toStatus,
            s.evaluateError = :evaluateError,
            s.updatedAt = CURRENT_TIMESTAMP
        WHERE s.id = :sessionId
          AND s.evaluateStatus IN :fromStatuses
        """)
    int updateEvaluateStatusConditionally(
        Long sessionId,
        AsyncTaskStatus toStatus,
        List<AsyncTaskStatus> fromStatuses,
        String evaluateError
    );

    /**
     * 初始化评估状态：仅当 evaluateStatus 为 NULL 时写入，返回影响行数。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE VoiceInterviewSessionEntity s
        SET s.evaluateStatus = :toStatus,
            s.evaluateError = :evaluateError,
            s.evaluateAttempt = COALESCE(s.evaluateAttempt, 0) + 1,
            s.updatedAt = CURRENT_TIMESTAMP
        WHERE s.id = :sessionId
          AND s.evaluateStatus IS NULL
        """)
    int initializeEvaluateStatus(
        Long sessionId,
        AsyncTaskStatus toStatus,
        String evaluateError
    );

    /**
     * 查找指定状态且结束时间早于给定时间的会话
     * Note: Queries the AsyncTaskStatus field, not InterviewPhase
     */
    Optional<VoiceInterviewSessionEntity> findByStatusAndEndTimeBefore(
        AsyncTaskStatus status,
        LocalDateTime time
    );

    /**
     * Find all sessions for a user, ordered by update time
     */
    List<VoiceInterviewSessionEntity> findAllByOwnerUserIdOrderByUpdatedAtDesc(Long ownerUserId);

    /**
     * Find sessions by user and status, ordered by update time
     */
    List<VoiceInterviewSessionEntity> findAllByOwnerUserIdAndStatusOrderByUpdatedAtDesc(
        Long ownerUserId,
        VoiceInterviewSessionStatus status
    );

    Optional<VoiceInterviewSessionEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    List<VoiceInterviewSessionEntity> findByStatusAndStartTimeBefore(
        VoiceInterviewSessionStatus status,
        LocalDateTime time
    );

    /**
     * 查找指定状态且更新时间早于给定时间的会话，用于孤儿会话对账。
     */
    List<VoiceInterviewSessionEntity> findByStatusAndUpdatedAtBefore(
        VoiceInterviewSessionStatus status,
        LocalDateTime time
    );

    List<VoiceInterviewSessionEntity> findByEvaluateStatusAndUpdatedAtBefore(
        AsyncTaskStatus evaluateStatus,
        LocalDateTime time
    );
}
