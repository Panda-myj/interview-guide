package interview.guide.common.service;

import interview.guide.common.model.ResourceCleanupType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;

/**
 * 资源清理补偿执行器。
 *
 * <p>只监听事务提交后的清理事件，保证外部资源清理发生在数据库删除成功之后；
 * 清理过程中的任何异常都不会影响已提交的业务删除，而是落库为可重试的补偿任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceCleanupExecutor {

  private static final Logger AUDIT_LOG = LoggerFactory.getLogger("RESOURCE_DELETION_AUDIT");

  private final ResourceDeletionCompensationService compensationService;
  private final List<ResourceCleanupHandler> handlers;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onCleanupRequested(ResourceCleanupRequestedEvent event) {
    execute(event.compensationTaskId());
  }

  /**
   * 执行单条补偿任务；任务已被处理、资源已不存在或外部服务不可用时均不抛出异常。
   */
  public void execute(Long compensationTaskId) {
    if (compensationTaskId == null) {
      return;
    }
    try {
      runTask(compensationTaskId);
    } catch (Exception e) {
      // 事务提交回调不能向业务调用方抛出异常，失败任务由定时扫描继续重试
      log.error("资源清理补偿执行异常: taskId={}", compensationTaskId, e);
    }
  }

  private void runTask(Long compensationTaskId) {
    Optional<ResourceCleanupTask> claimed = compensationService.claimForProcessing(compensationTaskId);
    if (claimed.isEmpty()) {
      log.debug("补偿任务不可领取，跳过执行: taskId={}", compensationTaskId);
      return;
    }
    ResourceCleanupTask task = claimed.get();
    ResourceCleanupHandler handler = findHandler(task.resourceType());
    if (handler == null) {
      compensationService.markDead(task.id(), "未注册的资源清理类型: " + task.resourceType());
      return;
    }

    try {
      handler.cleanup(task);
      compensationService.complete(task.id());
      AUDIT_LOG.info(
          "action=CLEANUP_RESOURCE resourceType={} resourceId={} ownerUserId={} taskId={} outcome=SUCCESS",
          task.resourceType(), task.resourceId(), task.ownerUserId(), task.id());
    } catch (Exception e) {
      compensationService.fail(task.id(), e);
      AUDIT_LOG.warn(
          "action=CLEANUP_RESOURCE resourceType={} resourceId={} ownerUserId={} taskId={} "
              + "outcome=FAILED reason={}",
          task.resourceType(), task.resourceId(), task.ownerUserId(), task.id(), e.getMessage(), e);
    }
  }

  private ResourceCleanupHandler findHandler(String resourceType) {
    if (resourceType == null) {
      log.error("补偿任务缺少资源类型");
      return null;
    }
    ResourceCleanupType type;
    try {
      type = ResourceCleanupType.valueOf(resourceType);
    } catch (IllegalArgumentException e) {
      log.error("未知的清理资源类型: resourceType={}", resourceType, e);
      return null;
    }
    return handlers.stream()
        .filter(handler -> handler.resourceType() == type)
        .findFirst()
        .orElse(null);
  }
}
