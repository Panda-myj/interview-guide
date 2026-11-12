package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.ResourceCleanupType;
import interview.guide.common.service.ResourceCleanupRequestedEvent;
import interview.guide.common.service.ResourceDeletionCompensationService;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.auth.security.CurrentUserService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库删除服务。
 *
 * <p>删除顺序：同一数据库事务内校验归属、解除 RAG 会话关联、删除知识库记录，并登记向量与对象文件
 * 补偿任务；事务提交后才清理 pgvector 与对象存储。会话消息随会话本身存在，不随知识库删除，
 * 因此不存在跨事务的消息清理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseDeleteService {

  private static final Logger AUDIT_LOG = LoggerFactory.getLogger("RESOURCE_DELETION_AUDIT");

  private final KnowledgeBaseRepository knowledgeBaseRepository;
  private final RagChatSessionRepository sessionRepository;
  private final TransactionalExecutor transactionalExecutor;
  private final CurrentUserService currentUserService;
  private final ResourceDeletionCompensationService compensationService;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * 删除知识库，包括会话关联、数据库记录、向量数据与对象文件。
   *
   * @param id 知识库ID，必须是当前登录用户拥有的资源
   * @throws BusinessException 知识库不存在或不属于当前用户时抛出
   */
  public void deleteKnowledgeBase(Long id) {
    Long ownerUserId = currentUserService.getRequiredUserId();
    log.info("收到删除知识库请求: id={}, ownerUserId={}", id, ownerUserId);

    try {
      List<Long> compensationTaskIds = transactionalExecutor.call(
          () -> deleteKnowledgeBaseRecords(id, ownerUserId));
      AUDIT_LOG.info(
          "action=DELETE_KNOWLEDGE_BASE resourceType=KNOWLEDGE_BASE resourceId={} ownerUserId={} "
              + "compensationTaskIds={} outcome=SUCCESS",
          id, ownerUserId, compensationTaskIds);
      log.info("知识库删除完成: id={}", id);
    } catch (BusinessException e) {
      AUDIT_LOG.warn(
          "action=DELETE_KNOWLEDGE_BASE resourceType=KNOWLEDGE_BASE resourceId={} ownerUserId={} "
              + "outcome=REJECTED reason={}",
          id, ownerUserId, e.getMessage());
      throw e;
    } catch (RuntimeException e) {
      AUDIT_LOG.error(
          "action=DELETE_KNOWLEDGE_BASE resourceType=KNOWLEDGE_BASE resourceId={} ownerUserId={} "
              + "outcome=FAILED",
          id, ownerUserId, e);
      throw e;
    }
  }

  private List<Long> deleteKnowledgeBaseRecords(Long id, Long ownerUserId) {
    KnowledgeBaseEntity knowledgeBase = knowledgeBaseRepository
        .findByIdAndOwnerUserId(id, ownerUserId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
    String storageKey = knowledgeBase.getStorageKey();

    // 1. 解除会话与知识库的关联（外键约束要求先删除关联）
    detachFromSessions(id, ownerUserId);

    // 2. 删除知识库记录
    knowledgeBaseRepository.deleteById(id);

    // 3. 登记外部资源清理任务，事务提交后执行
    List<Long> compensationTaskIds = new ArrayList<>(2);
    registerCleanup(ResourceCleanupType.KNOWLEDGE_BASE_VECTOR, id, null, ownerUserId,
        compensationTaskIds);
    if (storageKey != null && !storageKey.isBlank()) {
      registerCleanup(ResourceCleanupType.KNOWLEDGE_BASE_FILE, id, storageKey, ownerUserId,
          compensationTaskIds);
    } else {
      log.debug("知识库无对象存储键，跳过文件补偿登记: id={}", id);
    }
    return compensationTaskIds;
  }

  private void detachFromSessions(Long id, Long ownerUserId) {
    List<RagChatSessionEntity> sessions = sessionRepository.findByKnowledgeBaseIds(List.of(id));
    int detached = 0;
    for (RagChatSessionEntity session : sessions) {
      Long sessionOwnerId = session.getOwnerUser() == null ? null : session.getOwnerUser().getId();
      if (!ownerUserId.equals(sessionOwnerId)) {
        log.error("检测到跨租户知识库关联，跳过修改其他用户会话: kbId={}, sessionId={}, sessionOwnerId={}",
            id, session.getId(), sessionOwnerId);
        continue;
      }
      session.getKnowledgeBases().removeIf(kb -> kb.getId().equals(id));
      sessionRepository.save(session);
      detached++;
      log.debug("已从会话中移除知识库关联: sessionId={}, kbId={}", session.getId(), id);
    }
    if (detached > 0) {
      log.info("已从 {} 个会话中移除知识库关联: kbId={}", detached, id);
    }
  }

  private void registerCleanup(
      ResourceCleanupType cleanupType,
      Long knowledgeBaseId,
      String storageKey,
      Long ownerUserId,
      List<Long> compensationTaskIds
  ) {
    Long compensationTaskId = compensationService.enqueue(
        cleanupType, String.valueOf(knowledgeBaseId), storageKey, ownerUserId);
    compensationTaskIds.add(compensationTaskId);
    eventPublisher.publishEvent(new ResourceCleanupRequestedEvent(compensationTaskId));
  }
}
