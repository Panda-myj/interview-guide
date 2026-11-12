package interview.guide.common.repository;

import interview.guide.common.model.ResourceCleanupStatus;
import interview.guide.common.model.ResourceDeletionCompensationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ResourceDeletionCompensationRepository
    extends JpaRepository<ResourceDeletionCompensationEntity, Long> {

    Optional<ResourceDeletionCompensationEntity> findByOwnerUserIdAndResourceTypeAndResourceIdAndStorageKey(
        Long ownerUserId, String resourceType, String resourceId, String storageKey);

    List<ResourceDeletionCompensationEntity> findByStatusOrderByUpdatedAtAsc(ResourceCleanupStatus status);

    List<ResourceDeletionCompensationEntity> findByOwnerUserIdOrderByUpdatedAtDesc(Long ownerUserId);

    @Query("SELECT c FROM ResourceDeletionCompensationEntity c WHERE c.status IN :statuses ORDER BY c.updatedAt ASC")
    List<ResourceDeletionCompensationEntity> findByStatuses(
        @Param("statuses") List<ResourceCleanupStatus> statuses, Pageable pageable);

    /**
     * 查询到期待重试的补偿任务。nextAttemptAt 为空视为立即可执行。
     */
    @Query("""
        SELECT c FROM ResourceDeletionCompensationEntity c
        WHERE c.status IN :statuses
          AND (c.nextAttemptAt IS NULL OR c.nextAttemptAt <= :now)
        ORDER BY c.updatedAt ASC
        """)
    List<ResourceDeletionCompensationEntity> findDueTasks(
        @Param("statuses") List<ResourceCleanupStatus> statuses,
        @Param("now") LocalDateTime now,
        Pageable pageable);

    /**
     * 条件更新领取任务，避免多个执行者重复处理同一条补偿任务。
     *
     * @return 更新行数，0 表示任务已被其他执行者领取或状态已终结
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ResourceDeletionCompensationEntity c
        SET c.status = :claimedStatus, c.updatedAt = :now
        WHERE c.id = :id AND c.status IN :claimableStatuses
        """)
    int claim(
        @Param("id") Long id,
        @Param("claimedStatus") ResourceCleanupStatus claimedStatus,
        @Param("claimableStatuses") List<ResourceCleanupStatus> claimableStatuses,
        @Param("now") LocalDateTime now);

    /**
     * 回收卡在 PROCESSING 状态的任务（执行者崩溃或进程重启），使其重新进入可重试队列。
     *
     * @return 更新行数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ResourceDeletionCompensationEntity c
        SET c.status = :failedStatus, c.updatedAt = :now, c.nextAttemptAt = :now
        WHERE c.status = :processingStatus AND c.updatedAt <= :staleBefore
        """)
    int releaseStaleProcessing(
        @Param("processingStatus") ResourceCleanupStatus processingStatus,
        @Param("failedStatus") ResourceCleanupStatus failedStatus,
        @Param("staleBefore") LocalDateTime staleBefore,
        @Param("now") LocalDateTime now);

    long countByStatus(ResourceCleanupStatus status);

    /** 账号注销收尾：仅当没有未完成清理任务时才允许物理删除 users。 */
    boolean existsByOwnerUserIdAndStatusIn(
        Long ownerUserId, java.util.Collection<ResourceCleanupStatus> statuses);

    void deleteByOwnerUserId(Long ownerUserId);

    long countByOwnerUserIdAndStatus(Long ownerUserId, ResourceCleanupStatus status);
}
