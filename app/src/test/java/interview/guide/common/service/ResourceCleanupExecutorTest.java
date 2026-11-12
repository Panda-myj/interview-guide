package interview.guide.common.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.ResourceCleanupType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资源清理补偿执行器测试：成功、失败重试、可重入与未知类型处理。
 */
@DisplayName("资源清理补偿执行器测试")
class ResourceCleanupExecutorTest {

  private static final Long TASK_ID = 5L;
  private static final Long OWNER_ID = 100L;
  private static final String RESOURCE_ID = "7";
  private static final String STORAGE_KEY = "users/100/resumes/2026/01/01/abcd_resume.pdf";

  private ResourceCleanupExecutor executor;

  @Mock
  private ResourceDeletionCompensationService compensationService;

  @Mock
  private ResourceCleanupHandler fileHandler;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(fileHandler.resourceType()).thenReturn(ResourceCleanupType.RESUME_FILE);
    executor = new ResourceCleanupExecutor(compensationService, List.of(fileHandler));
  }

  private ResourceCleanupTask task(String resourceType) {
    return new ResourceCleanupTask(TASK_ID, OWNER_ID, resourceType, RESOURCE_ID, STORAGE_KEY);
  }

  @Nested
  @DisplayName("清理成功")
  class SuccessTests {

    @Test
    @DisplayName("对象文件删除成功后标记补偿任务完成")
    void completesTaskAfterStorageCleanup() {
      when(compensationService.claimForProcessing(TASK_ID))
          .thenReturn(Optional.of(task(ResourceCleanupType.RESUME_FILE.name())));

      executor.execute(TASK_ID);

      verify(fileHandler).cleanup(any(ResourceCleanupTask.class));
      verify(compensationService).complete(TASK_ID);
      verify(compensationService, never()).fail(anyLong(), any());
    }

    @Test
    @DisplayName("向量删除成功后标记补偿任务完成")
    void completesTaskAfterVectorCleanup() {
      ResourceCleanupHandler vectorHandler = mock(ResourceCleanupHandler.class);
      when(vectorHandler.resourceType()).thenReturn(ResourceCleanupType.KNOWLEDGE_BASE_VECTOR);
      ResourceCleanupExecutor vectorExecutor =
          new ResourceCleanupExecutor(compensationService, List.of(vectorHandler));
      when(compensationService.claimForProcessing(TASK_ID))
          .thenReturn(Optional.of(task(ResourceCleanupType.KNOWLEDGE_BASE_VECTOR.name())));

      vectorExecutor.execute(TASK_ID);

      verify(vectorHandler).cleanup(any(ResourceCleanupTask.class));
      verify(compensationService).complete(TASK_ID);
    }
  }

  @Nested
  @DisplayName("清理失败")
  class FailureTests {

    @Test
    @DisplayName("对象存储删除失败时落库为可重试任务且不影响调用方")
    void recordsRetryableFailureWhenStorageFails() {
      when(compensationService.claimForProcessing(TASK_ID))
          .thenReturn(Optional.of(task(ResourceCleanupType.RESUME_FILE.name())));
      BusinessException storageError =
          new BusinessException(ErrorCode.STORAGE_DELETE_FAILED, "对象存储不可用");
      doThrow(storageError).when(fileHandler).cleanup(any(ResourceCleanupTask.class));

      assertThatNoException().isThrownBy(() -> executor.execute(TASK_ID));

      verify(compensationService).fail(TASK_ID, storageError);
      verify(compensationService, never()).complete(anyLong());
    }

    @Test
    @DisplayName("向量删除失败同样进入补偿重试")
    void recordsRetryableFailureWhenVectorDeleteFails() {
      ResourceCleanupHandler vectorHandler = mock(ResourceCleanupHandler.class);
      when(vectorHandler.resourceType()).thenReturn(ResourceCleanupType.KNOWLEDGE_BASE_VECTOR);
      ResourceCleanupExecutor vectorExecutor =
          new ResourceCleanupExecutor(compensationService, List.of(vectorHandler));
      when(compensationService.claimForProcessing(TASK_ID))
          .thenReturn(Optional.of(task(ResourceCleanupType.KNOWLEDGE_BASE_VECTOR.name())));
      BusinessException vectorError = new BusinessException(
          ErrorCode.KNOWLEDGE_BASE_VECTOR_DELETE_FAILED, "向量库不可用");
      doThrow(vectorError).when(vectorHandler).cleanup(any(ResourceCleanupTask.class));

      assertThatNoException().isThrownBy(() -> vectorExecutor.execute(TASK_ID));

      verify(compensationService).fail(TASK_ID, vectorError);
    }
  }

  @Nested
  @DisplayName("幂等与异常兜底")
  class IdempotencyTests {

    @Test
    @DisplayName("任务已被其他执行者领取时跳过，不重复清理")
    void skipsAlreadyClaimedTask() {
      when(compensationService.claimForProcessing(TASK_ID)).thenReturn(Optional.empty());

      executor.execute(TASK_ID);

      verify(fileHandler, never()).cleanup(any(ResourceCleanupTask.class));
      verify(compensationService, never()).complete(anyLong());
      verify(compensationService, never()).fail(anyLong(), any());
    }

    @Test
    @DisplayName("未注册的清理类型直接标记 DEAD，等待人工介入")
    void marksDeadForUnknownType() {
      when(compensationService.claimForProcessing(TASK_ID))
          .thenReturn(Optional.of(task("UNKNOWN_TYPE")));

      executor.execute(TASK_ID);

      verify(compensationService).markDead(eq(TASK_ID), anyString());
      verify(fileHandler, never()).cleanup(any(ResourceCleanupTask.class));
    }

    @Test
    @DisplayName("领取任务时数据库异常不影响已提交的删除业务")
    void swallowsClaimFailureAfterCommit() {
      when(compensationService.claimForProcessing(TASK_ID))
          .thenThrow(new IllegalStateException("数据库不可用"));

      assertThatNoException().isThrownBy(() -> executor.execute(TASK_ID));

      verify(fileHandler, never()).cleanup(any(ResourceCleanupTask.class));
    }

    @Test
    @DisplayName("空任务ID直接跳过")
    void ignoresNullTaskId() {
      executor.execute(null);

      verify(compensationService, never()).claimForProcessing(anyLong());
    }
  }
}
