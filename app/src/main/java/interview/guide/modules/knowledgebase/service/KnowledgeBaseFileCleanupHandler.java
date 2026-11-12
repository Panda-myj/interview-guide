package interview.guide.modules.knowledgebase.service;

import interview.guide.common.model.ResourceCleanupType;
import interview.guide.common.service.ResourceCleanupHandler;
import interview.guide.common.service.ResourceCleanupTask;
import interview.guide.infrastructure.file.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 知识库对象文件清理处理器，幂等：文件已不存在时视为清理成功。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseFileCleanupHandler implements ResourceCleanupHandler {

  private final FileStorageService storageService;

  @Override
  public ResourceCleanupType resourceType() {
    return ResourceCleanupType.KNOWLEDGE_BASE_FILE;
  }

  @Override
  public void cleanup(ResourceCleanupTask task) {
    if (task.storageKey() == null || task.storageKey().isBlank()) {
      log.debug("知识库无对象存储键，跳过文件清理: kbId={}", task.resourceId());
      return;
    }
    storageService.deleteKnowledgeBase(task.storageKey());
  }
}
