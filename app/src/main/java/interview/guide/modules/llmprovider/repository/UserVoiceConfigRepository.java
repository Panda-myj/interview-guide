package interview.guide.modules.llmprovider.repository;

import interview.guide.modules.llmprovider.model.UserVoiceConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserVoiceConfigRepository extends JpaRepository<UserVoiceConfigEntity, Long> {
}
