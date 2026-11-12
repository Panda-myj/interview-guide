package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.ResourceCleanupType;
import interview.guide.common.service.ResourceCleanupHandler;
import interview.guide.common.service.ResourceCleanupTask;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 知识库向量清理处理器。
 *
 * <p>直接调用 {@link VectorRepository} 而不是 KnowledgeBaseVectorService，
 * 因为后者会吞掉删除异常，无法让补偿任务感知失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseVectorCleanupHandler implements ResourceCleanupHandler {

  private final VectorRepository vectorRepository;

  @Override
  public ResourceCleanupType resourceType() {
    return ResourceCleanupType.KNOWLEDGE_BASE_VECTOR;
  }

  @Override
  public void cleanup(ResourceCleanupTask task) {
    Long knowledgeBaseId = parseKnowledgeBaseId(task.resourceId());
    int deletedRows = vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId, task.ownerUserId());
    log.info("知识库向量清理完成: kbId={}, ownerUserId={}, deletedRows={}",
        knowledgeBaseId, task.ownerUserId(), deletedRows);
  }

  private Long parseKnowledgeBaseId(String resourceId) {
    try {
      return Long.valueOf(resourceId);
    } catch (NumberFormatException e) {
      throw new BusinessException(
          ErrorCode.KNOWLEDGE_BASE_VECTOR_DELETE_FAILED, "补偿任务中的知识库ID非法: " + resourceId, e);
    }
  }
}
