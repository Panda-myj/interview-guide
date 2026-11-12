package interview.guide.modules.auth.service;

import interview.guide.common.model.ResourceCleanupStatus;
import interview.guide.common.repository.ResourceDeletionCompensationRepository;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 注销收尾：外部资源（对象文件 / 向量）清理完成后，物理删除 users 行并释放手机号。
 *
 * <p>不能在注销事务内直接删除 users —— resource_deletion_compensations 对 users
 * 有外键，且补偿执行器需要读取 ownerUser。DEAD 任务不阻塞删除（业务数据已清空），
 * 但必须告警等待人工介入处理残留外部资源。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDeletionFinalizer {

  private static final Logger AUDIT_LOG = LoggerFactory.getLogger("ACCOUNT_DELETION_AUDIT");
  private static final List<ResourceCleanupStatus> UNFINISHED = List.of(
      ResourceCleanupStatus.PENDING,
      ResourceCleanupStatus.PROCESSING,
      ResourceCleanupStatus.FAILED
  );

  private final UserRepository userRepository;
  private final ResourceDeletionCompensationRepository compensationRepository;
  private final TransactionalExecutor transactionalExecutor;

  @Scheduled(fixedDelayString = "${app.auth.account-deletion-finalize-delay-ms:60000}")
  public void finalizeDeletingAccounts() {
    List<Long> candidates = userRepository.findByStatus(UserStatus.DELETING)
        .stream().map(UserEntity::getId).toList();
    for (Long userId : candidates) {
      try {
        transactionalExecutor.run(() -> finalizeOne(userId));
      } catch (RuntimeException e) {
        // 单个账号失败不影响其他账号，下一轮继续重试
        log.error("账号注销收尾失败: userId={}", userId, e);
      }
    }
  }

  private void finalizeOne(Long userId) {
    if (compensationRepository.existsByOwnerUserIdAndStatusIn(userId, UNFINISHED)) {
      return;
    }
    long dead = compensationRepository
        .countByOwnerUserIdAndStatus(userId, ResourceCleanupStatus.DEAD);
    if (dead > 0) {
      log.error("账号存在 DEAD 清理任务，外部资源需人工介入: userId={}, deadTasks={}", userId, dead);
      AUDIT_LOG.warn("action=DELETE_ACCOUNT userId={} outcome=EXTERNAL_RESOURCE_MANUAL_REQUIRED deadTasks={}",
          userId, dead);
    }
    compensationRepository.deleteByOwnerUserId(userId);
    userRepository.deleteById(userId);
    AUDIT_LOG.info("action=DELETE_ACCOUNT userId={} outcome=PHYSICALLY_DELETED", userId);
    log.info("账号已物理删除，手机号释放: userId={}", userId);
  }
}
