package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 简历Repository
 */
@Repository
public interface ResumeRepository extends JpaRepository<ResumeEntity, Long> {

    /**
     * 根据文件哈希查找简历（用于去重）
     */
    Optional<ResumeEntity> findByOwnerUserIdAndFileHash(Long ownerUserId, String fileHash);

    /**
     * 检查文件哈希是否存在
     */
    boolean existsByOwnerUserIdAndFileHash(Long ownerUserId, String fileHash);

    Optional<ResumeEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    List<ResumeEntity> findAllByOwnerUserIdOrderByUploadedAtDesc(Long ownerUserId);

    /**
     * 分页查找简历列表（列表页用，size 上限由 Service 控制，PERF-002）。
     */
    List<ResumeEntity> findAllByOwnerUserIdOrderByUploadedAtDesc(Long ownerUserId, Pageable pageable);
}
