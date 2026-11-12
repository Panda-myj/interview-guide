package interview.guide.common.model;

/**
 * 需要异步补偿清理的资源类型。
 * 新增资源类型时同时实现 {@code ResourceCleanupHandler} 即可被补偿执行器调度。
 */
public enum ResourceCleanupType {
  RESUME_FILE,
  KNOWLEDGE_BASE_FILE,
  KNOWLEDGE_BASE_VECTOR
}
