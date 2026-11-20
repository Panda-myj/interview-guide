package interview.guide.modules.llmprovider.repository;

import interview.guide.modules.llmprovider.model.UserLlmProviderEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserLlmProviderRepository extends JpaRepository<UserLlmProviderEntity, Long> {

  List<UserLlmProviderEntity> findByUserIdOrderByCreatedAtAsc(Long userId);
}
