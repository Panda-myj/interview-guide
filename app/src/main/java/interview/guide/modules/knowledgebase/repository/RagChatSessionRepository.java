package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 
 * RAG聊天会话Repository
 */
@Repository
public interface RagChatSessionRepository extends JpaRepository<RagChatSessionEntity, Long> {

    /**
     * 按更新时间倒序获取所有活跃会话
     */
    List<RagChatSessionEntity> findByStatusOrderByUpdatedAtDesc(SessionStatus status);

    /**
     * 获取所有会话（按更新时间倒序）
     */
    List<RagChatSessionEntity> findAllByOrderByUpdatedAtDesc();

    List<RagChatSessionEntity> findAllByOwnerUserIdOrderByUpdatedAtDesc(Long ownerUserId);

    List<RagChatSessionEntity> findAllByOwnerUserIdOrderByIsPinnedDescUpdatedAtDesc(Long ownerUserId);

    Optional<RagChatSessionEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    /**
     * 根据知识库ID查找相关会话
     */
    @Query("SELECT DISTINCT s FROM RagChatSessionEntity s JOIN s.knowledgeBases kb WHERE kb.id IN :kbIds ORDER BY s.updatedAt DESC")
    List<RagChatSessionEntity> findByKnowledgeBaseIds(@Param("kbIds") List<Long> knowledgeBaseIds);

    /**
     * 获取会话详情（带消息列表和知识库）
     * 注意：使用 DISTINCT 避免笛卡尔积导致的重复数据
     */
    @Query("SELECT DISTINCT s FROM RagChatSessionEntity s LEFT JOIN FETCH s.knowledgeBases WHERE s.id = :id")
    Optional<RagChatSessionEntity> findByIdWithMessagesAndKnowledgeBases(@Param("id") Long id);

    @Query("""
        SELECT DISTINCT s
        FROM RagChatSessionEntity s
        LEFT JOIN FETCH s.knowledgeBases
        WHERE s.id = :id
          AND s.ownerUser.id = :ownerUserId
        """)
    Optional<RagChatSessionEntity> findByIdWithMessagesAndKnowledgeBasesForOwner(
        @Param("id") Long id,
        @Param("ownerUserId") Long ownerUserId
    );

    /**
     * 获取会话（带知识库，不带消息）
     */
    @Query("SELECT s FROM RagChatSessionEntity s LEFT JOIN FETCH s.knowledgeBases WHERE s.id = :id")
    Optional<RagChatSessionEntity> findByIdWithKnowledgeBases(@Param("id") Long id);

    @Query("""
        SELECT s
        FROM RagChatSessionEntity s
        LEFT JOIN FETCH s.knowledgeBases
        WHERE s.id = :id
          AND s.ownerUser.id = :ownerUserId
        """)
    Optional<RagChatSessionEntity> findByIdWithKnowledgeBasesForOwner(
        @Param("id") Long id,
        @Param("ownerUserId") Long ownerUserId
    );

    /**
     * 批量获取用户所有会话关联的知识库名称（避免列表页逐会话懒加载 @ManyToMany，PERF-002）。
     * 返回列：sessionId, kbName。
     */
    @Query("""
        SELECT s.id, kb.name
        FROM RagChatSessionEntity s
        JOIN s.knowledgeBases kb
        WHERE s.ownerUser.id = :ownerUserId
        """)
    List<Object[]> findKnowledgeBaseNamesByOwnerUserId(@Param("ownerUserId") Long ownerUserId);
}
