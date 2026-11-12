package interview.guide.common.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 资源清理补偿重试扫描器。
 *
 * <p>进程重启或清理事件丢失时，仍能通过扫描 PENDING / FAILED 任务继续清理，
 * 重试间隔由任务上的 nextAttemptAt 控制（指数退避）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceCleanupRetryScheduler {

  private static final int BATCH_SIZE = 50;
  private static final Duration STALE_PROCESSING_TIMEOUT = Duration.ofMinutes(10);

  private final ResourceDeletionCompensationService compensationService;
  private final ResourceCleanupExecutor cleanupExecutor;

  @Scheduled(fixedDelayString = "${app.resource-cleanup.retry-delay-ms:60000}")
  public void retryDueTasks() {
    compensationService.releaseStaleProcessing(STALE_PROCESSING_TIMEOUT);

    List<Long> dueTaskIds = compensationService.findDueTaskIds(BATCH_SIZE);
    if (dueTaskIds.isEmpty()) {
      return;
    }
    log.info("开始重试资源清理补偿任务: count={}", dueTaskIds.size());
    dueTaskIds.forEach(cleanupExecutor::execute);
  }
}
