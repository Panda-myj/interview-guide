package interview.guide.modules.auth.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 账号销户的批量数据清理。
 *
 * <p>所有业务表对 users 的外键均为 NO ACTION（见 V2），必须先按依赖顺序
 * 物理删除子表再删主表，最后才能删除 users 行。使用批量 SQL 而非循环
 * Repository 调用，避免 N+1 与逐条查询。
 *
 * <p>对象存储文件与 pgvector 向量不在本类处理：它们在事务内登记为
 * resource_deletion_compensations，提交后由既有 ResourceCleanupHandler 清理。
 */
@Repository
@RequiredArgsConstructor
public class AccountDataCleanupDao {

  private static final String RESUME_QUERY = """
      SELECT CAST(id AS VARCHAR), COALESCE(storage_key, '')
      FROM resumes WHERE owner_user_id = :userId
      """;

  private static final String KNOWLEDGE_BASE_QUERY = """
      SELECT CAST(id AS VARCHAR), COALESCE(storage_key, '')
      FROM knowledge_bases WHERE owner_user_id = :userId
      """;

  /**
   * 删除顺序固定：子表 -> 主表 -> 认证数据。users 行本身不在此删除，
   * 因为补偿任务的外键仍指向它，由 AccountDeletionFinalizer 收尾。
   */
  private static final List<String> DELETE_STATEMENTS = List.of(
      "DELETE FROM interview_answers WHERE session_id IN "
          + "(SELECT id FROM interview_sessions WHERE owner_user_id = :userId)",
      "DELETE FROM resume_analyses WHERE resume_id IN "
          + "(SELECT id FROM resumes WHERE owner_user_id = :userId)",
      "DELETE FROM rag_chat_messages WHERE session_id IN "
          + "(SELECT id FROM rag_chat_sessions WHERE owner_user_id = :userId)",
      "DELETE FROM rag_session_knowledge_bases WHERE session_id IN "
          + "(SELECT id FROM rag_chat_sessions WHERE owner_user_id = :userId) "
          + "OR knowledge_base_id IN "
          + "(SELECT id FROM knowledge_bases WHERE owner_user_id = :userId)",
      "DELETE FROM voice_interview_messages WHERE session_id IN "
          + "(SELECT id FROM voice_interview_sessions WHERE owner_user_id = :userId)",
      "DELETE FROM voice_interview_evaluations WHERE session_id IN "
          + "(SELECT id FROM voice_interview_sessions WHERE owner_user_id = :userId)",
      "DELETE FROM interview_sessions WHERE owner_user_id = :userId",
      "DELETE FROM voice_interview_sessions WHERE owner_user_id = :userId",
      "DELETE FROM rag_chat_sessions WHERE owner_user_id = :userId",
      "DELETE FROM resumes WHERE owner_user_id = :userId",
      "DELETE FROM knowledge_bases WHERE owner_user_id = :userId",
      "DELETE FROM interview_schedule WHERE owner_user_id = :userId",
      "DELETE FROM login_notifications WHERE user_id = :userId",
      "DELETE FROM auth_sessions WHERE user_id = :userId",
      "DELETE FROM auth_identities WHERE user_id = :userId",
      "DELETE FROM user_devices WHERE user_id = :userId"
  );

  private final EntityManager entityManager;

  @SuppressWarnings("unchecked")
  public List<StorageResource> findResumeFiles(Long userId) {
    return mapRows(entityManager.createNativeQuery(RESUME_QUERY)
        .setParameter("userId", userId)
        .getResultList());
  }

  @SuppressWarnings("unchecked")
  public List<StorageResource> findKnowledgeBaseFiles(Long userId) {
    return mapRows(entityManager.createNativeQuery(KNOWLEDGE_BASE_QUERY)
        .setParameter("userId", userId)
        .getResultList());
  }

  public void deleteBusinessAndIdentityData(Long userId) {
    // 先把 JPA 上下文中对 users 的停权与 PII 抹除落库，避免后续 clear() 丢失。
    entityManager.flush();
    for (String sql : DELETE_STATEMENTS) {
      entityManager.createNativeQuery(sql)
          .setParameter("userId", userId)
          .executeUpdate();
    }
    // 原生 SQL 绕过了一级缓存，清空以保证后续查询不命中已删除实体
    entityManager.clear();
  }

  private List<StorageResource> mapRows(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new StorageResource(
            String.valueOf(row[0]),
            row[1] == null ? "" : String.valueOf(row[1])))
        .toList();
  }

  public record StorageResource(String resourceId, String storageKey) {
  }
}
