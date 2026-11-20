package interview.guide.modules.llmprovider.repository;

import interview.guide.modules.llmprovider.model.UserLlmSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserLlmSettingRepository extends JpaRepository<UserLlmSettingEntity, Long> {
}
