package interview.guide.common.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.ResourceCleanupStatus;
import interview.guide.common.model.ResourceCleanupType;
import interview.guide.common.model.ResourceDeletionCompensationEntity;
import interview.guide.common.repository.ResourceDeletionCompensationRepository;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 资源删除补偿服务。
 *
 * <p>数据库删除与补偿任务登记在同一个事务内完成，事务提交后由
 * {@link ResourceCleanupExecutor} 触发对象存储 / 向量等外部资源清理；
 * 清理失败的任务持久化为 FAILED，由 {@link ResourceCleanupRetryScheduler} 重试，
 * 重试次数耗尽后进入 DEAD 并告警。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResourceDeletionCompensationService {

  static final int MAX_ATTEMPTS = 5;
  private static final int LAST_ERROR_MAX_LENGTH = 500;
  private static final int DEFAULT_BATCH_SIZE = 50;
  private static final long BASE_BACKOFF_SECONDS = 30L;
  private static final long MAX_BACKOFF_SECONDS = 1800L;
  private static final List<ResourceCleanupStatus> CLAIMABLE_STATUSES =
      List.of(ResourceCleanupStatus.PENDING, ResourceCleanupStatus.FAILED);

  private final ResourceDeletionCompensationRepository repository;
  private final UserRepository userRepository;
  private final CurrentUserService currentUserService;
  private final Clock clock;

  /**
   * 在业务事务内登记一条待清理任务。
   *
   * <p>必须与业务删除处于同一事务：业务数据回滚时任务一并回滚，不会产生孤儿补偿任务；
   * 业务提交后任务已持久化，即使进程崩溃也可由定时任务继续重试。
   *
   * <p>幂等键为 (ownerUserId, resourceType, resourceId, storageKey)，重复登记会复用同一行。
   *
   * @return 补偿任务主键
   */
  @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
  public Long enqueue(
      ResourceCleanupType cleanupType,
      String resourceId,
      String storageKey,
      Long ownerUserId
  ) {
    String normalizedKey = storageKey == null ? "" : storageKey;
    ResourceDeletionCompensationEntity existing = repository
        .findByOwnerUserIdAndResourceTypeAndResourceIdAndStorageKey(
            ownerUserId, cleanupType.name(), resourceId, normalizedKey)
        .orElse(null);

    if (existing != null && existing.getStatus() == ResourceCleanupStatus.COMPLETED) {
      log.debug("资源清理任务已完成，跳过重复登记: type={}, resourceId={}",
          cleanupType, resourceId);
      return existing.getId();
    }

    ResourceDeletionCompensationEntity task = existing != null
        ? existing
        : newTask(cleanupType, resourceId, normalizedKey, ownerUserId);

    boolean wasDead = task.getStatus() == ResourceCleanupStatus.DEAD;
    task.setStatus(ResourceCleanupStatus.PENDING);
    task.setLastError(null);
    task.setNextAttemptAt(LocalDateTime.now(clock));
    if (wasDead) {
      task.setAttempts(0);
    }
    ResourceDeletionCompensationEntity saved = repository.save(task);
    log.info("已登记资源清理补偿任务: type={}, resourceId={}, ownerUserId={}, taskId={}",
        cleanupType, resourceId, ownerUserId, saved.getId());
    return saved.getId();
  }

  private ResourceDeletionCompensationEntity newTask(
      ResourceCleanupType cleanupType,
      String resourceId,
      String storageKey,
      Long ownerUserId
  ) {
    UserEntity owner = userRepository.getReferenceById(ownerUserId);
    ResourceDeletionCompensationEntity task = new ResourceDeletionCompensationEntity();
    task.setOwnerUser(owner);
    task.setResourceType(cleanupType.name());
    task.setResourceId(resourceId);
    task.setStorageKey(storageKey);
    task.setStatus(ResourceCleanupStatus.PENDING);
    task.setAttempts(0);
    return task;
  }

  /**
   * 将任务置为 PROCESSING 并返回可执行快照；任务不可领取时返回空。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public Optional<ResourceCleanupTask> claimForProcessing(Long taskId) {
    int claimed = repository.claim(
        taskId, ResourceCleanupStatus.PROCESSING, CLAIMABLE_STATUSES, LocalDateTime.now(clock));
    if (claimed == 0) {
      return Optional.empty();
    }
    return repository.findById(taskId).map(this::toTask);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public void complete(Long taskId) {
    repository.findById(taskId).ifPresent(task -> {
      task.setStatus(ResourceCleanupStatus.COMPLETED);
      task.setLastError(null);
      task.setNextAttemptAt(null);
      task.setCompletedAt(LocalDateTime.now(clock));
      repository.save(task);
      log.info("资源清理补偿任务完成: type={}, resourceId={}, taskId={}",
          task.getResourceType(), task.getResourceId(), task.getId());
    });
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public void fail(Long taskId, Exception error) {
    repository.findById(taskId).ifPresent(task -> {
      int attempts = task.getAttempts() == null ? 0 : task.getAttempts();
      attempts++;
      task.setAttempts(attempts);
      task.setLastError(truncate(error == null ? null : error.getMessage(), LAST_ERROR_MAX_LENGTH));
      if (attempts >= MAX_ATTEMPTS) {
        task.setStatus(ResourceCleanupStatus.DEAD);
        task.setNextAttemptAt(null);
        log.error("资源清理补偿重试次数耗尽，需要人工介入: type={}, resourceId={}, taskId={}, attempts={}",
            task.getResourceType(), task.getResourceId(), task.getId(), attempts);
      } else {
        task.setStatus(ResourceCleanupStatus.FAILED);
        task.setNextAttemptAt(LocalDateTime.now(clock).plusSeconds(backoffSeconds(attempts)));
        log.warn("资源清理补偿失败，等待重试: type={}, resourceId={}, taskId={}, attempts={}",
            task.getResourceType(), task.getResourceId(), task.getId(), attempts);
      }
      repository.save(task);
    });
  }

  /**
   * 将任务直接标记为 DEAD（用于无法恢复的配置性错误，例如未注册的清理类型）。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public void markDead(Long taskId, String reason) {
    repository.findById(taskId).ifPresent(task -> {
      task.setStatus(ResourceCleanupStatus.DEAD);
      task.setNextAttemptAt(null);
      task.setLastError(truncate(reason, LAST_ERROR_MAX_LENGTH));
      repository.save(task);
      log.error("资源清理补偿任务进入 DEAD: type={}, resourceId={}, taskId={}, reason={}",
          task.getResourceType(), task.getResourceId(), task.getId(), reason);
    });
  }

  @Transactional(rollbackFor = Exception.class, readOnly = true)
  public Optional<ResourceDeletionCompensationEntity> findTask(Long taskId) {
    return repository.findById(taskId);
  }

  @Transactional(rollbackFor = Exception.class, readOnly = true)
  public List<ResourceDeletionCompensationEntity> findPending() {
    return repository.findByStatuses(
        List.of(ResourceCleanupStatus.PENDING, ResourceCleanupStatus.PROCESSING,
            ResourceCleanupStatus.FAILED),
        PageRequest.of(0, 100));
  }

  /**
   * 回收长时间停留在 PROCESSING 的任务（执行进程崩溃、重启），重新放回可重试队列。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public int releaseStaleProcessing(Duration timeout) {
    LocalDateTime now = LocalDateTime.now(clock);
    LocalDateTime staleBefore = now.minus(timeout);
    int released = repository.releaseStaleProcessing(
        ResourceCleanupStatus.PROCESSING, ResourceCleanupStatus.FAILED, staleBefore, now);
    if (released > 0) {
      log.warn("回收卡在 PROCESSING 的资源清理任务: count={}, timeout={}", released, timeout);
    }
    return released;
  }

  /**
   * 后台重试入口：返回到期待重试的任务 ID。
   */
  @Transactional(rollbackFor = Exception.class, readOnly = true)
  public List<Long> findDueTaskIds(int limit) {
    int batchSize = limit > 0 ? limit : DEFAULT_BATCH_SIZE;
    return repository.findDueTasks(
            CLAIMABLE_STATUSES, LocalDateTime.now(clock), PageRequest.of(0, batchSize))
        .stream()
        .map(ResourceDeletionCompensationEntity::getId)
        .toList();
  }

  /**
   * 运维查询：按属主检索补偿任务，附带权限校验，避免越权查看他人删除记录。
   */
  @Transactional(rollbackFor = Exception.class, readOnly = true)
  public List<ResourceDeletionCompensationEntity> findForOwner(Long ownerUserId) {
    return repository.findByOwnerUserIdOrderByUpdatedAtDesc(ownerUserId);
  }

  /**
   * 当前登录用户查询自己的补偿任务。
   */
  @Transactional(rollbackFor = Exception.class, readOnly = true)
  public List<ResourceDeletionCompensationEntity> findMyTasks() {
    Long ownerUserId = currentUserService.getRequiredUserId();
    return findForOwner(ownerUserId);
  }

  @Transactional(rollbackFor = Exception.class, readOnly = true)
  public long countByStatus(ResourceCleanupStatus status) {
    return repository.countByStatus(status);
  }

  private ResourceCleanupTask toTask(ResourceDeletionCompensationEntity entity) {
    Long ownerUserId = entity.getOwnerUser() == null ? null : entity.getOwnerUser().getId();
    if (ownerUserId == null) {
      throw new BusinessException(ErrorCode.RESOURCE_CLEANUP_FAILED, "补偿任务缺少资源属主");
    }
    return new ResourceCleanupTask(
        entity.getId(),
        ownerUserId,
        entity.getResourceType(),
        entity.getResourceId(),
        entity.getStorageKey());
  }

  private long backoffSeconds(int attempts) {
    long exponential = BASE_BACKOFF_SECONDS << Math.min(Math.max(attempts - 1, 0), 6);
    return Math.min(exponential, MAX_BACKOFF_SECONDS);
  }

  private String truncate(String value, int maxLength) {
    if (value == null) {
      return null;
    }
    return value.length() > maxLength ? value.substring(0, maxLength) : value;
  }
}
