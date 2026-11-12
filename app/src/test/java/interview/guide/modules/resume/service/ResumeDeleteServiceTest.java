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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 简历删除链路测试：删除顺序、归属校验、幂等与补偿登记。
 */
@DisplayName("简历删除服务测试")
class ResumeDeleteServiceTest {

  private static final Long OWNER_ID = 100L;
  private static final Long RESUME_ID = 7L;
  private static final String STORAGE_KEY = "users/100/resumes/2026/01/01/abcd_resume.pdf";
  private static final Long COMPENSATION_ID = 88L;

  private ResumeDeleteService deleteService;

  @Mock
  private ResumePersistenceService persistenceService;

  @Mock
  private InterviewPersistenceService interviewPersistenceService;

  @Mock
  private TransactionalExecutor transactionalExecutor;

  @Mock
  private ResourceDeletionCompensationService compensationService;

  @Mock
  private ApplicationEventPublisher eventPublisher;

  @Mock
  private CurrentUserService currentUserService;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(transactionalExecutor.call(any())).thenAnswer(invocation -> {
      Supplier<?> supplier = invocation.getArgument(0);
      return supplier.get();
    });
    when(currentUserService.getRequiredUserId()).thenReturn(OWNER_ID);
    deleteService = new ResumeDeleteService(
        persistenceService,
        interviewPersistenceService,
        transactionalExecutor,
        compensationService,
        eventPublisher,
        currentUserService);
  }

  private ResumeEntity resumeWith(String storageKey) {
    ResumeEntity resume = new ResumeEntity();
    resume.setId(RESUME_ID);
    resume.setStorageKey(storageKey);
    resume.setOriginalFilename("resume.pdf");
    return resume;
  }

  @Nested
  @DisplayName("正常删除与删除顺序")
  class DeleteOrderTests {

    @Test
    @DisplayName("先删除数据库记录，再登记并触发对象文件清理")
    void deletesDatabaseBeforeTriggeringStorageCleanup() {
      when(persistenceService.findById(RESUME_ID))
          .thenReturn(Optional.of(resumeWith(STORAGE_KEY)));
      when(compensationService.enqueue(
          ResourceCleanupType.RESUME_FILE, String.valueOf(RESUME_ID), STORAGE_KEY, OWNER_ID))
          .thenReturn(COMPENSATION_ID);

      deleteService.deleteResume(RESUME_ID);

      InOrder order = inOrder(interviewPersistenceService, persistenceService,
          compensationService, eventPublisher);
      order.verify(interviewPersistenceService).deleteSessionsByResumeId(RESUME_ID);
      order.verify(persistenceService).deleteResume(RESUME_ID);
      order.verify(compensationService).enqueue(
          ResourceCleanupType.RESUME_FILE, String.valueOf(RESUME_ID), STORAGE_KEY, OWNER_ID);
      order.verify(eventPublisher).publishEvent(new ResourceCleanupRequestedEvent(COMPENSATION_ID));
    }

    @Test
    @DisplayName("对象存储键为空时不登记文件补偿任务")
    void skipsCompensationWhenStorageKeyMissing() {
      when(persistenceService.findById(RESUME_ID)).thenReturn(Optional.of(resumeWith(null)));

      deleteService.deleteResume(RESUME_ID);

      verify(persistenceService).deleteResume(RESUME_ID);
      verify(compensationService, never()).enqueue(any(), anyString(), any(), anyLong());
      verify(eventPublisher, never()).publishEvent(any(Object.class));
    }
  }

  @Nested
  @DisplayName("权限校验与重复删除")
  class OwnershipAndIdempotencyTests {

    @Test
    @DisplayName("删除其他用户的简历返回资源不存在且不触发任何删除")
    void rejectsDeletingOtherUsersResume() {
      when(persistenceService.findById(RESUME_ID)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> deleteService.deleteResume(RESUME_ID))
          .isInstanceOf(BusinessException.class)
          .satisfies(error -> assertThat(((BusinessException) error).getCode())
              .isEqualTo(ErrorCode.RESUME_NOT_FOUND.getCode()));

      verify(persistenceService, never()).deleteResume(anyLong());
      verify(interviewPersistenceService, never()).deleteSessionsByResumeId(anyLong());
      verify(compensationService, never()).enqueue(any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("重复删除同一简历不会重复登记清理任务")
    void doesNotDuplicateCleanupOnRepeatedDelete() {
      when(persistenceService.findById(RESUME_ID))
          .thenReturn(Optional.of(resumeWith(STORAGE_KEY)))
          .thenReturn(Optional.empty());
      when(compensationService.enqueue(any(), anyString(), anyString(), anyLong()))
          .thenReturn(COMPENSATION_ID);

      deleteService.deleteResume(RESUME_ID);

      assertThatThrownBy(() -> deleteService.deleteResume(RESUME_ID))
          .isInstanceOf(BusinessException.class);

      verify(persistenceService, times(1)).deleteResume(RESUME_ID);
      verify(compensationService, times(1))
          .enqueue(any(), anyString(), anyString(), anyLong());
      verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }
  }

  @Nested
  @DisplayName("失败场景")
  class FailureTests {

    @Test
    @DisplayName("数据库删除失败时不登记补偿任务")
    void doesNotRegisterCleanupWhenDatabaseDeleteFails() {
      when(persistenceService.findById(RESUME_ID))
          .thenReturn(Optional.of(resumeWith(STORAGE_KEY)));
      doThrow(new BusinessException(ErrorCode.RESUME_NOT_FOUND, "删除失败"))
          .when(persistenceService).deleteResume(RESUME_ID);

      assertThatThrownBy(() -> deleteService.deleteResume(RESUME_ID))
          .isInstanceOf(BusinessException.class);

      verify(compensationService, never()).enqueue(any(), anyString(), any(), anyLong());
      verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("补偿任务登记失败时异常向上抛出，不发布清理事件")
    void propagatesCompensationRegistrationFailure() {
      when(persistenceService.findById(RESUME_ID))
          .thenReturn(Optional.of(resumeWith(STORAGE_KEY)));
      when(compensationService.enqueue(any(), anyString(), anyString(), anyLong()))
          .thenThrow(new BusinessException(ErrorCode.RESOURCE_CLEANUP_FAILED, "登记失败"));

      assertThatThrownBy(() -> deleteService.deleteResume(RESUME_ID))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("登记失败");

      verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("补偿任务登记使用属主与存储键快照")
    void registersCompensationWithOwnerSnapshot() {
      when(persistenceService.findById(RESUME_ID))
          .thenReturn(Optional.of(resumeWith(STORAGE_KEY)));
      when(compensationService.enqueue(any(), anyString(), anyString(), anyLong()))
          .thenReturn(COMPENSATION_ID);

      deleteService.deleteResume(RESUME_ID);

      ArgumentCaptor<String> resourceId = ArgumentCaptor.forClass(String.class);
      ArgumentCaptor<String> storageKey = ArgumentCaptor.forClass(String.class);
      ArgumentCaptor<Long> ownerId = ArgumentCaptor.forClass(Long.class);
      verify(compensationService).enqueue(
          any(ResourceCleanupType.class), resourceId.capture(), storageKey.capture(),
          ownerId.capture());
      assertThat(resourceId.getValue()).isEqualTo(String.valueOf(RESUME_ID));
      assertThat(storageKey.getValue()).isEqualTo(STORAGE_KEY);
      assertThat(ownerId.getValue()).isEqualTo(OWNER_ID);
    }
  }
}
