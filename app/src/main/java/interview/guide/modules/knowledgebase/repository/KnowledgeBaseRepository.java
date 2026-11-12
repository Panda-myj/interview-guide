package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 知识库Repository
 */
@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {

    Optional<KnowledgeBaseEntity> findByOwnerUserIdAndFileHash(Long ownerUserId, String fileHash);

    List<KnowledgeBaseEntity> findAllByOwnerUserIdOrderByUploadedAtDesc(Long ownerUserId);

    List<KnowledgeBaseEntity> findByOwnerUserIdAndVectorStatusOrderByUploadedAtDesc(
        Long ownerUserId,
        VectorStatus vectorStatus
    );

    /**
     * 按任意排序字段查询当前用户的知识库（排序下推数据库，PERF-002）。
     */
    List<KnowledgeBaseEntity> findByOwnerUserId(Long ownerUserId, Sort sort);

    /**
     * 按向量化状态过滤并按任意排序字段查询（排序下推数据库，PERF-002）。
     */
    List<KnowledgeBaseEntity> findByOwnerUserIdAndVectorStatus(
        Long ownerUserId,
        VectorStatus vectorStatus,
        Sort sort
    );

    Optional<KnowledgeBaseEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    List<KnowledgeBaseEntity> findAllByIdInAndOwnerUserId(List<Long> ids, Long ownerUserId);

    /**
     * 获取所有不同的分类
     */
    @Query("""
        SELECT DISTINCT k.category
        FROM KnowledgeBaseEntity k
        WHERE k.ownerUser.id = :ownerUserId
          AND k.category IS NOT NULL
        ORDER BY k.category
        """)
    List<String> findAllCategories(@Param("ownerUserId") Long ownerUserId);

    List<KnowledgeBaseEntity> findByOwnerUserIdAndCategoryOrderByUploadedAtDesc(
        Long ownerUserId,
        String category
    );

    List<KnowledgeBaseEntity> findByOwnerUserIdAndCategoryIsNullOrderByUploadedAtDesc(Long ownerUserId);

    /**
     * 按名称或文件名模糊搜索（不区分大小写）
     */
    @Query("""
        SELECT k
        FROM KnowledgeBaseEntity k
        WHERE k.ownerUser.id = :ownerUserId
          AND (
            LOWER(k.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
            OR LOWER(k.originalFilename) LIKE LOWER(CONCAT('%', :keyword, '%'))
          )
        ORDER BY k.uploadedAt DESC
        """)
    List<KnowledgeBaseEntity> searchByKeyword(
        @Param("ownerUserId") Long ownerUserId,
        @Param("keyword") String keyword
    );

    // ==================== 批量更新 ====================

    /**
     * 批量增加知识库提问计数
     * @param ids 知识库ID列表
     * @return 更新的行数
     */
    @Modifying
    @Query("""
        UPDATE KnowledgeBaseEntity k
        SET k.questionCount = k.questionCount + 1
        WHERE k.id IN :ids
          AND k.ownerUser.id = :ownerUserId
        """)
    int incrementQuestionCountBatch(
        @Param("ids") List<Long> ids,
        @Param("ownerUserId") Long ownerUserId
    );

    // ==================== 统计查询 ====================

    /**
     * 统计总提问次数
     */
    @Query("""
        SELECT COALESCE(SUM(k.questionCount), 0)
        FROM KnowledgeBaseEntity k
        WHERE k.ownerUser.id = :ownerUserId
        """)
    long sumQuestionCount(@Param("ownerUserId") Long ownerUserId);

    /**
     * 统计总访问次数
     */
    @Query("""
        SELECT COALESCE(SUM(k.accessCount), 0)
        FROM KnowledgeBaseEntity k
        WHERE k.ownerUser.id = :ownerUserId
        """)
    long sumAccessCount(@Param("ownerUserId") Long ownerUserId);

    long countByOwnerUserIdAndVectorStatus(Long ownerUserId, VectorStatus vectorStatus);

    long countByOwnerUserId(Long ownerUserId);

}

