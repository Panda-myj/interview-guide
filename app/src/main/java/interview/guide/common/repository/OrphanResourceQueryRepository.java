package interview.guide.common.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 孤儿资源查询与回收。用于扫描“数据库记录已不存在但存储/向量仍残留”的资源。
 *
 * <p>此处直接使用原生 SQL：向量表 {@code vector_store} 由 Spring AI 管理，
 * 不属于 JPA 实体，且 PostgreSQL JSONB 语法无法用 JPQL 表达。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class OrphanResourceQueryRepository {

  private final JdbcTemplate jdbcTemplate;

  /**
   * 查找 metadata 中 kb_id 指向已不存在知识库的向量数据（忽略进行中的 pending 任务）。
   */
  public List<String> findOrphanVectorKnowledgeBaseIds(int limit) {
    String sql = """
        SELECT DISTINCT v.metadata->>'kb_id' AS kb_id
        FROM vector_store v
        WHERE v.metadata->>'kb_id' IS NOT NULL
          AND v.metadata->>'kb_id' NOT LIKE 'pending:%'
          AND NOT EXISTS (
            SELECT 1 FROM knowledge_bases kb WHERE kb.id::text = v.metadata->>'kb_id'
          )
        ORDER BY kb_id
        LIMIT ?
        """;
    return jdbcTemplate.queryForList(sql, String.class, limit);
  }

  /**
   * 回收指定知识库 ID 的残留向量。
   */
  public int deleteVectorsByKnowledgeBaseId(Long knowledgeBaseId) {
    String sql = "DELETE FROM vector_store WHERE metadata->>'kb_id' = ?";
    return jdbcTemplate.update(sql, knowledgeBaseId.toString());
  }

  /**
   * 读取数据库中登记的全部对象存储键，用于与对象存储清单做差集。
   */
  public List<String> findKnownStorageKeys() {
    String sql = """
        SELECT storage_key FROM resumes
        WHERE storage_key IS NOT NULL AND storage_key <> ''
        UNION
        SELECT storage_key FROM knowledge_bases
        WHERE storage_key IS NOT NULL AND storage_key <> ''
        """;
    return jdbcTemplate.queryForList(sql, String.class);
  }
}
