package interview.guide.common.service;

/**
 * 补偿任务的执行快照，不持有 JPA 实体，避免在事务外触发懒加载。
 */
public record ResourceCleanupTask(
    Long id,
    Long ownerUserId,
    String resourceType,
    String resourceId,
    String storageKey
) {
}
