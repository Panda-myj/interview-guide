package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.UserDeviceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserDeviceRepository extends JpaRepository<UserDeviceEntity, Long> {

    Optional<UserDeviceEntity> findByUser_IdAndDeviceKeyHash(Long userId, String deviceKeyHash);
}
