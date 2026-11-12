package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.AuthIdentityEntity;
import interview.guide.modules.auth.model.IdentityProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AuthIdentityRepository extends JpaRepository<AuthIdentityEntity, Long> {

  Optional<AuthIdentityEntity> findByProviderAndSubject(IdentityProvider provider, String subject);

  Optional<AuthIdentityEntity> findByUser_IdAndProvider(Long userId, IdentityProvider provider);

  boolean existsByUser_IdAndProvider(Long userId, IdentityProvider provider);

  void deleteByUser_Id(Long userId);
}
