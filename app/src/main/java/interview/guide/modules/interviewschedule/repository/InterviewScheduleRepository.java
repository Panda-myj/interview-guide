package interview.guide.modules.interviewschedule.repository;

import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface InterviewScheduleRepository extends JpaRepository<InterviewScheduleEntity, Long> {
    List<InterviewScheduleEntity> findByStatusAndInterviewTimeBefore(InterviewStatus status, LocalDateTime time);

    List<InterviewScheduleEntity> findByStatus(InterviewStatus status);

    List<InterviewScheduleEntity> findByOwnerUserIdAndStatus(Long ownerUserId, InterviewStatus status);

    List<InterviewScheduleEntity> findByInterviewTimeBetween(LocalDateTime start, LocalDateTime end);

    List<InterviewScheduleEntity> findByOwnerUserIdAndInterviewTimeBetween(
        Long ownerUserId,
        LocalDateTime start,
        LocalDateTime end
    );

    /**
     * 分页版本（列表接口截断用，PERF-002）：调用方通过 Pageable 携带 size 与排序。
     */
    List<InterviewScheduleEntity> findByOwnerUserIdAndInterviewTimeBetween(
        Long ownerUserId,
        LocalDateTime start,
        LocalDateTime end,
        Pageable pageable
    );

    /**
     * 分页版本（列表接口截断用，PERF-002）。
     */
    List<InterviewScheduleEntity> findByOwnerUserIdAndStatus(
        Long ownerUserId,
        InterviewStatus status,
        Pageable pageable
    );

    List<InterviewScheduleEntity> findAllByOwnerUserIdOrderByInterviewTimeAsc(Long ownerUserId);

    /**
     * 分页版本（列表接口截断用，PERF-002）。
     */
    List<InterviewScheduleEntity> findAllByOwnerUserIdOrderByInterviewTimeAsc(Long ownerUserId, Pageable pageable);

    Optional<InterviewScheduleEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    @Modifying
    @Query("UPDATE InterviewScheduleEntity e SET e.status = :newStatus WHERE e.status = :oldStatus AND e.interviewTime < :cutoff")
    int updateStatusByStatusAndInterviewTimeBefore(
        @Param("newStatus") InterviewStatus newStatus,
        @Param("oldStatus") InterviewStatus oldStatus,
        @Param("cutoff") LocalDateTime cutoff);
}
