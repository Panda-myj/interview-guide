package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.AuthSessionEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, UUID> {

    @EntityGraph(attributePaths = {"user", "device"})
    Optional<AuthSessionEntity> findByRefreshTokenHash(String refreshTokenHash);

    @Modifying
    @Query("""
        UPDATE AuthSessionEntity session
        SET session.revokedAt = :revokedAt
        WHERE session.familyId = :familyId
          AND session.revokedAt IS NULL
        """)
    int revokeFamily(
        @Param("familyId") UUID familyId,
        @Param("revokedAt") LocalDateTime revokedAt
    );

    /** 账号注销时一次性撤销该用户全部 Refresh 会话。 */
    @Modifying
    @Query("""
        UPDATE AuthSessionEntity session
        SET session.revokedAt = :revokedAt
        WHERE session.user.id = :userId
          AND session.revokedAt IS NULL
        """)
    int revokeAllForUser(
        @Param("userId") Long userId,
        @Param("revokedAt") LocalDateTime revokedAt
    );
}
