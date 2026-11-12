package interview.guide.common.service;

import interview.guide.common.model.ResourceCleanupType;

/**
 * 资源清理处理器。实现类必须是幂等的：重复清理同一资源不得抛错，
 * 资源已不存在时应视为清理成功。
 */
public interface ResourceCleanupHandler {

  ResourceCleanupType resourceType();

  void cleanup(ResourceCleanupTask task);
}
