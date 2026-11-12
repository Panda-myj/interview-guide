package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.ResourceCleanupType;
import interview.guide.common.service.ResourceCleanupRequestedEvent;
import interview.guide.common.service.ResourceDeletionCompensationService;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.security.CurrentUserService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库删除链路测试：先删库后清理、归属校验、会话关联与补偿登记。
 */
@DisplayName("知识库删除服务测试")
class KnowledgeBaseDeleteServiceTest {

  private static final Long OWNER_ID = 100L;
  private static final Long KB_ID = 21L;
  private static final String STORAGE_KEY = "users/100/knowledgebases/2026/01/01/abcd_doc.pdf";
  private static final Long VECTOR_TASK_ID = 301L;
  private static final Long FILE_TASK_ID = 302L;

  private KnowledgeBaseDeleteService deleteService;

  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;

  @Mock
  private RagChatSessionRepository sessionRepository;

  @Mock
  private TransactionalExecutor transactionalExecutor;

  @Mock
  private CurrentUserService currentUserService;

  @Mock
  private ResourceDeletionCompensationService compensationService;

  @Mock
  private ApplicationEventPublisher eventPublisher;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(transactionalExecutor.call(any())).thenAnswer(invocation -> {
      Supplier<?> supplier = invocation.getArgument(0);
      return supplier.get();
    });
    when(currentUserService.getRequiredUserId()).thenReturn(OWNER_ID);
    deleteService = new KnowledgeBaseDeleteService(
        knowledgeBaseRepository,
        sessionRepository,
        transactionalExecutor,
        currentUserService,
        compensationService,
        eventPublisher);
  }

  private UserEntity user(Long id) {
    UserEntity user = new UserEntity();
    user.setId(id);
    return user;
  }

  private KnowledgeBaseEntity knowledgeBase(String storageKey) {
    KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
    entity.setId(KB_ID);
    entity.setStorageKey(storageKey);
    entity.setName("Java 面试知识库");
    entity.setOwnerUser(user(OWNER_ID));
    return entity;
  }

  private RagChatSessionEntity session(Long sessionId, Long ownerId, KnowledgeBaseEntity kb) {
    RagChatSessionEntity session = new RagChatSessionEntity();
    session.setId(sessionId);
    session.setOwnerUser(user(ownerId));
    Set<KnowledgeBaseEntity> knowledgeBases = new HashSet<>();
    if (kb != null) {
      knowledgeBases.add(kb);
    }
    session.setKnowledgeBases(knowledgeBases);
    return session;
  }

  @Nested
  @DisplayName("正常删除与删除顺序")
  class DeleteOrderTests {

    @Test
    @DisplayName("先删数据库记录，再登记向量与文件清理任务")
    void deletesDatabaseBeforeRegisteringCleanup() {
      KnowledgeBaseEntity kb = knowledgeBase(STORAGE_KEY);
      when(knowledgeBaseRepository.findByIdAndOwnerUserId(KB_ID, OWNER_ID))
          .thenReturn(Optional.of(kb));
      when(sessionRepository.findByKnowledgeBaseIds(List.of(KB_ID)))
          .thenReturn(List.of(session(1L, OWNER_ID, kb)));
      when(compensationService.enqueue(
          ResourceCleanupType.KNOWLEDGE_BASE_VECTOR, String.valueOf(KB_ID), null, OWNER_ID))
          .thenReturn(VECTOR_TASK_ID);
      when(compensationService.enqueue(
          ResourceCleanupType.KNOWLEDGE_BASE_FILE, String.valueOf(KB_ID), STORAGE_KEY, OWNER_ID))
          .thenReturn(FILE_TASK_ID);

      deleteService.deleteKnowledgeBase(KB_ID);

      InOrder order = inOrder(sessionRepository, knowledgeBaseRepository, compensationService);
      order.verify(sessionRepository).save(any(RagChatSessionEntity.class));
      order.verify(knowledgeBaseRepository).deleteById(KB_ID);
      order.verify(compensationService).enqueue(
          ResourceCleanupType.KNOWLEDGE_BASE_VECTOR, String.valueOf(KB_ID), null, OWNER_ID);
      order.verify(compensationService).enqueue(
          ResourceCleanupType.KNOWLEDGE_BASE_FILE, String.valueOf(KB_ID), STORAGE_KEY, OWNER_ID);

      verify(eventPublisher).publishEvent(new ResourceCleanupRequestedEvent(VECTOR_TASK_ID));
      verify(eventPublisher).publishEvent(new ResourceCleanupRequestedEvent(FILE_TASK_ID));
    }

    @Test
    @DisplayName("删除知识库时不删除会话本身")
    void keepsChatSessions() {
      KnowledgeBaseEntity kb = knowledgeBase(STORAGE_KEY);
      when(knowledgeBaseRepository.findByIdAndOwnerUserId(KB_ID, OWNER_ID))
          .thenReturn(Optional.of(kb));
      when(sessionRepository.findByKnowledgeBaseIds(List.of(KB_ID)))
          .thenReturn(List.of(session(1L, OWNER_ID, kb)));
      when(compensationService.enqueue(any(), anyString(), any(), anyLong()))
          .thenReturn(VECTOR_TASK_ID);

      deleteService.deleteKnowledgeBase(KB_ID);

      verify(sessionRepository, never()).delete(any(RagChatSessionEntity.class));
      verify(sessionRepository, never()).deleteById(anyLong());
    }
  }

  @Nested
  @DisplayName("权限校验与重复删除")
  class OwnershipAndIdempotencyTests {

    @Test
    @DisplayName("删除其他用户的知识库被拒绝且不触发会话或清理操作")
    void rejectsDeletingOtherUsersKnowledgeBase() {
      when(knowledgeBaseRepository.findByIdAndOwnerUserId(KB_ID, OWNER_ID))
          .thenReturn(Optional.empty());

      assertThatThrownBy(() -> deleteService.deleteKnowledgeBase(KB_ID))
          .isInstanceOf(BusinessException.class)
          .satisfies(error -> assertThat(((BusinessException) error).getCode())
              .isEqualTo(ErrorCode.NOT_FOUND.getCode()));

      verify(sessionRepository, never()).findByKnowledgeBaseIds(any());
      verify(knowledgeBaseRepository, never()).deleteById(anyLong());
      verify(compensationService, never()).enqueue(any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("重复删除同一知识库不会重复登记清理任务")
    void doesNotDuplicateCleanupOnRepeatedDelete() {
      KnowledgeBaseEntity kb = knowledgeBase(STORAGE_KEY);
      when(knowledgeBaseRepository.findByIdAndOwnerUserId(KB_ID, OWNER_ID))
          .thenReturn(Optional.of(kb))
          .thenReturn(Optional.empty());
      when(sessionRepository.findByKnowledgeBaseIds(List.of(KB_ID))).thenReturn(List.of());
      when(compensationService.enqueue(any(), anyString(), any(), anyLong()))
          .thenReturn(VECTOR_TASK_ID, FILE_TASK_ID);

      deleteService.deleteKnowledgeBase(KB_ID);

      assertThatThrownBy(() -> deleteService.deleteKnowledgeBase(KB_ID))
          .isInstanceOf(BusinessException.class);

      verify(knowledgeBaseRepository, times(1)).deleteById(KB_ID);
      verify(compensationService, times(2)).enqueue(any(), anyString(), any(), anyLong());
    }
  }

  @Nested
  @DisplayName("资源缺失与跨租户会话")
  class MissingResourceTests {

    @Test
    @DisplayName("对象存储键为空时仍完成删除，只登记向量清理")
    void deletesWithoutFileTaskWhenStorageKeyMissing() {
      when(knowledgeBaseRepository.findByIdAndOwnerUserId(KB_ID, OWNER_ID))
          .thenReturn(Optional.of(knowledgeBase(null)));
      when(sessionRepository.findByKnowledgeBaseIds(List.of(KB_ID))).thenReturn(List.of());
      when(compensationService.enqueue(
          ResourceCleanupType.KNOWLEDGE_BASE_VECTOR, String.valueOf(KB_ID), null, OWNER_ID))
          .thenReturn(VECTOR_TASK_ID);

      deleteService.deleteKnowledgeBase(KB_ID);

      verify(knowledgeBaseRepository).deleteById(KB_ID);
      verify(compensationService, times(1)).enqueue(any(), anyString(), any(), anyLong());
      verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("其他用户的会话关联不被修改")
    void doesNotModifySessionsOwnedByOtherUsers() {
      KnowledgeBaseEntity kb = knowledgeBase(STORAGE_KEY);
      when(knowledgeBaseRepository.findByIdAndOwnerUserId(KB_ID, OWNER_ID))
          .thenReturn(Optional.of(kb));
      when(sessionRepository.findByKnowledgeBaseIds(List.of(KB_ID)))
          .thenReturn(List.of(session(2L, 999L, kb)));
      when(compensationService.enqueue(any(), anyString(), any(), anyLong()))
          .thenReturn(VECTOR_TASK_ID);

      deleteService.deleteKnowledgeBase(KB_ID);

      verify(sessionRepository, never()).save(any(RagChatSessionEntity.class));
      verify(knowledgeBaseRepository).deleteById(KB_ID);
    }
  }

  @Nested
  @DisplayName("失败场景")
  class FailureTests {

    @Test
    @DisplayName("数据库删除失败时不登记补偿任务")
    void doesNotRegisterCleanupWhenDatabaseDeleteFails() {
      KnowledgeBaseEntity kb = knowledgeBase(STORAGE_KEY);
      when(knowledgeBaseRepository.findByIdAndOwnerUserId(KB_ID, OWNER_ID))
          .thenReturn(Optional.of(kb));
      when(sessionRepository.findByKnowledgeBaseIds(List.of(KB_ID))).thenReturn(List.of());
      doThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除失败"))
          .when(knowledgeBaseRepository).deleteById(KB_ID);

      assertThatThrownBy(() -> deleteService.deleteKnowledgeBase(KB_ID))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("删除失败");

      verify(compensationService, never()).enqueue(any(), anyString(), any(), anyLong());
      verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("知识库文件补偿任务只登记一次")
    void registersFileTaskOnce() {
      KnowledgeBaseEntity kb = knowledgeBase(STORAGE_KEY);
      when(knowledgeBaseRepository.findByIdAndOwnerUserId(KB_ID, OWNER_ID))
          .thenReturn(Optional.of(kb));
      when(sessionRepository.findByKnowledgeBaseIds(List.of(KB_ID))).thenReturn(List.of());
      when(compensationService.enqueue(any(), anyString(), any(), anyLong()))
          .thenReturn(VECTOR_TASK_ID, FILE_TASK_ID);

      deleteService.deleteKnowledgeBase(KB_ID);

      verify(compensationService).enqueue(
          eq(ResourceCleanupType.KNOWLEDGE_BASE_FILE), eq(String.valueOf(KB_ID)), eq(STORAGE_KEY),
          eq(OWNER_ID));
    }
  }
}
