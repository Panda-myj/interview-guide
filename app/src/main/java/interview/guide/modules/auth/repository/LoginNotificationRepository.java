package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.LoginNotificationEntity;
import interview.guide.modules.auth.model.LoginNotificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LoginNotificationRepository extends JpaRepository<LoginNotificationEntity, Long> {

    boolean existsByAuthSession_Id(UUID authSessionId);

    List<LoginNotificationEntity> findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
        LoginNotificationStatus status,
        LocalDateTime createdBefore
    );

    List<LoginNotificationEntity> findByUser_IdOrderByLoginAtDesc(Long userId, Pageable pageable);

    long countByUser_IdAndReadAtIsNull(Long userId);

    Optional<LoginNotificationEntity> findByIdAndUser_Id(Long id, Long userId);
}
