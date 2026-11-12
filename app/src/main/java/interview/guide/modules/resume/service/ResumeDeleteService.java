package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.ResourceCleanupType;
import interview.guide.common.service.ResourceCleanupRequestedEvent;
import interview.guide.common.service.ResourceDeletionCompensationService;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.auth.security.CurrentUserService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.resume.model.ResumeEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 简历删除服务。
 *
 * <p>删除顺序：同一数据库事务内删除简历、分析记录与面试会话，并登记对象文件补偿任务；
 * 事务提交后才触发对象存储清理。数据库失败会整体回滚且不会清理文件，
 * 资源清理失败则落库为可重试的补偿任务，不阻塞业务删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeDeleteService {

  private static final Logger AUDIT_LOG = LoggerFactory.getLogger("RESOURCE_DELETION_AUDIT");

  private final ResumePersistenceService persistenceService;
  private final InterviewPersistenceService interviewPersistenceService;
  private final TransactionalExecutor transactionalExecutor;
  private final ResourceDeletionCompensationService compensationService;
  private final ApplicationEventPublisher eventPublisher;
  private final CurrentUserService currentUserService;

  /**
   * 删除简历。
   *
   * @param id 简历ID，必须是当前登录用户拥有的资源
   * @throws BusinessException 简历不存在或不属于当前用户时抛出 RESUME_NOT_FOUND
   */
  public void deleteResume(Long id) {
    Long ownerUserId = currentUserService.getRequiredUserId();
    log.info("收到删除简历请求: id={}, ownerUserId={}", id, ownerUserId);

    try {
      Long compensationTaskId = transactionalExecutor.call(
          () -> deleteResumeRecords(id, ownerUserId));
      AUDIT_LOG.info(
          "action=DELETE_RESUME resourceType=RESUME resourceId={} ownerUserId={} "
              + "compensationTaskId={} outcome=SUCCESS",
          id, ownerUserId, compensationTaskId);
      log.info("简历删除完成: id={}", id);
    } catch (BusinessException e) {
      AUDIT_LOG.warn(
          "action=DELETE_RESUME resourceType=RESUME resourceId={} ownerUserId={} "
              + "outcome=REJECTED reason={}",
          id, ownerUserId, e.getMessage());
      throw e;
    } catch (RuntimeException e) {
      AUDIT_LOG.error(
          "action=DELETE_RESUME resourceType=RESUME resourceId={} ownerUserId={} outcome=FAILED",
          id, ownerUserId, e);
      throw e;
    }
  }

  private Long deleteResumeRecords(Long id, Long ownerUserId) {
    ResumeEntity resume = persistenceService.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
    String storageKey = resume.getStorageKey();

    // 1. 先在同一事务内删除数据库记录（面试会话会级联删除答案）
    interviewPersistenceService.deleteSessionsByResumeId(id);
    persistenceService.deleteResume(id);

    // 2. 再登记对象文件补偿任务，事务提交后由补偿执行器清理对象存储
    if (storageKey == null || storageKey.isBlank()) {
      log.debug("简历无对象存储键，跳过文件补偿登记: id={}", id);
      return null;
    }
    Long compensationTaskId = compensationService.enqueue(
        ResourceCleanupType.RESUME_FILE, String.valueOf(id), storageKey, ownerUserId);
    eventPublisher.publishEvent(new ResourceCleanupRequestedEvent(compensationTaskId));
    return compensationTaskId;
  }
}
