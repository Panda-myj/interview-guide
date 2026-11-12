package interview.guide.common.service;

import interview.guide.common.model.ResourceCleanupStatus;
import interview.guide.common.model.ResourceCleanupType;
import interview.guide.common.model.ResourceDeletionCompensationEntity;
import interview.guide.common.repository.ResourceDeletionCompensationRepository;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资源删除补偿服务测试：幂等登记、状态机、重试退避与按属主查询。
 */
@DisplayName("资源删除补偿服务测试")
class ResourceDeletionCompensationServiceTest {

  private static final Long OWNER_ID = 100L;
  private static final Long TASK_ID = 9L;
  private static final String RESOURCE_ID = "7";

  private ResourceDeletionCompensationService compensationService;

  @Mock
  private ResourceDeletionCompensationRepository repository;

  @Mock
  private UserRepository userRepository;

  @Mock
  private CurrentUserService currentUserService;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    compensationService = new ResourceDeletionCompensationService(
        repository, userRepository, currentUserService, Clock.system(ZoneId.of("Asia/Shanghai")));
  }

  private ResourceDeletionCompensationEntity task(
      ResourceCleanupStatus status, int attempts, String storageKey) {
    ResourceDeletionCompensationEntity entity = new ResourceDeletionCompensationEntity();
    ReflectionTestUtils.setField(entity, "id", TASK_ID);
    ReflectionTestUtils.setField(entity, "ownerUser", owner());
    ReflectionTestUtils.setField(entity, "resourceType", ResourceCleanupType.RESUME_FILE.name());
    ReflectionTestUtils.setField(entity, "resourceId", RESOURCE_ID);
    ReflectionTestUtils.setField(entity, "storageKey", storageKey);
    ReflectionTestUtils.setField(entity, "status", status);
    ReflectionTestUtils.setField(entity, "attempts", attempts);
    return entity;
  }

  private UserEntity owner() {
    UserEntity user = new UserEntity();
    user.setId(OWNER_ID);
    return user;
  }

  @Nested
  @DisplayName("补偿任务登记")
  class EnqueueTests {

    @Test
    @DisplayName("首次登记创建 PENDING 任务并清空错误信息")
    void createsPendingTask() {
      when(userRepository.getReferenceById(OWNER_ID)).thenReturn(owner());
      when(repository.findByOwnerUserIdAndResourceTypeAndResourceIdAndStorageKey(
          OWNER_ID, ResourceCleanupType.RESUME_FILE.name(), RESOURCE_ID, "key"))
          .thenReturn(Optional.empty());
      when(repository.save(any(ResourceDeletionCompensationEntity.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      compensationService.enqueue(
          ResourceCleanupType.RESUME_FILE, RESOURCE_ID, "key", OWNER_ID);

      ArgumentCaptor<ResourceDeletionCompensationEntity> captor =
          ArgumentCaptor.forClass(ResourceDeletionCompensationEntity.class);
      verify(repository).save(captor.capture());
      ResourceDeletionCompensationEntity saved = captor.getValue();
      assertThat(saved.getStatus()).isEqualTo(ResourceCleanupStatus.PENDING);
      assertThat(saved.getAttempts()).isZero();
      assertThat(saved.getNextAttemptAt()).isNotNull();
      assertThat(saved.getResourceType()).isEqualTo(ResourceCleanupType.RESUME_FILE.name());
      assertThat(saved.getOwnerUser().getId()).isEqualTo(OWNER_ID);
    }

    @Test
    @DisplayName("重复登记复用同一幂等键任务，不新增记录")
    void reusesTaskOnDuplicateRegistration() {
      ResourceDeletionCompensationEntity existing =
          task(ResourceCleanupStatus.FAILED, 2, "key");
      when(repository.findByOwnerUserIdAndResourceTypeAndResourceIdAndStorageKey(
          OWNER_ID, ResourceCleanupType.RESUME_FILE.name(), RESOURCE_ID, "key"))
          .thenReturn(Optional.of(existing));
      when(repository.save(any(ResourceDeletionCompensationEntity.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      Long taskId = compensationService.enqueue(
          ResourceCleanupType.RESUME_FILE, RESOURCE_ID, "key", OWNER_ID);

      assertThat(taskId).isEqualTo(TASK_ID);
      assertThat(existing.getStatus()).isEqualTo(ResourceCleanupStatus.PENDING);
      assertThat(existing.getAttempts()).isEqualTo(2);
      verify(repository).save(existing);
    }

    @Test
    @DisplayName("已完成的任务不再重复清理")
    void skipsCompletedTask() {
      ResourceDeletionCompensationEntity existing =
          task(ResourceCleanupStatus.COMPLETED, 1, "key");
      when(repository.findByOwnerUserIdAndResourceTypeAndResourceIdAndStorageKey(
          OWNER_ID, ResourceCleanupType.RESUME_FILE.name(), RESOURCE_ID, "key"))
          .thenReturn(Optional.of(existing));

      Long taskId = compensationService.enqueue(
          ResourceCleanupType.RESUME_FILE, RESOURCE_ID, "key", OWNER_ID);

      assertThat(taskId).isEqualTo(TASK_ID);
      verify(repository, never()).save(any(ResourceDeletionCompensationEntity.class));
    }

    @Test
    @DisplayName("重试耗尽的任务重新删除时重置重试次数")
    void resetsAttemptsForDeadTask() {
      ResourceDeletionCompensationEntity existing =
          task(ResourceCleanupStatus.DEAD, 5, "key");
      when(repository.findByOwnerUserIdAndResourceTypeAndResourceIdAndStorageKey(
          OWNER_ID, ResourceCleanupType.RESUME_FILE.name(), RESOURCE_ID, "key"))
          .thenReturn(Optional.of(existing));
      when(repository.save(any(ResourceDeletionCompensationEntity.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      compensationService.enqueue(ResourceCleanupType.RESUME_FILE, RESOURCE_ID, "key", OWNER_ID);

      assertThat(existing.getStatus()).isEqualTo(ResourceCleanupStatus.PENDING);
      assertThat(existing.getAttempts()).isZero();
    }

    @Test
    @DisplayName("存储键为空时归一化为空串，保证唯一约束生效")
    void normalizesNullStorageKey() {
      when(userRepository.getReferenceById(OWNER_ID)).thenReturn(owner());
      when(repository.findByOwnerUserIdAndResourceTypeAndResourceIdAndStorageKey(
          OWNER_ID, ResourceCleanupType.KNOWLEDGE_BASE_VECTOR.name(), RESOURCE_ID, ""))
          .thenReturn(Optional.empty());
      when(repository.save(any(ResourceDeletionCompensationEntity.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      compensationService.enqueue(
          ResourceCleanupType.KNOWLEDGE_BASE_VECTOR, RESOURCE_ID, null, OWNER_ID);

      ArgumentCaptor<ResourceDeletionCompensationEntity> captor =
          ArgumentCaptor.forClass(ResourceDeletionCompensationEntity.class);
      verify(repository).save(captor.capture());
      assertThat(captor.getValue().getStorageKey()).isEmpty();
    }
  }

  @Nested
  @DisplayName("任务领取与状态流转")
  class StateMachineTests {

    @Test
    @DisplayName("领取成功返回带属主的任务快照")
    void claimsTaskAndReturnsSnapshot() {
      ResourceDeletionCompensationEntity entity =
          task(ResourceCleanupStatus.PROCESSING, 0, "key");
      when(repository.claim(eq(TASK_ID), eq(ResourceCleanupStatus.PROCESSING), anyList(), any()))
          .thenReturn(1);
      when(repository.findById(TASK_ID)).thenReturn(Optional.of(entity));

      Optional<ResourceCleanupTask> claimed = compensationService.claimForProcessing(TASK_ID);

      assertThat(claimed).isPresent();
      assertThat(claimed.get().ownerUserId()).isEqualTo(OWNER_ID);
      assertThat(claimed.get().resourceId()).isEqualTo(RESOURCE_ID);
      assertThat(claimed.get().storageKey()).isEqualTo("key");
    }

    @Test
    @DisplayName("任务已完成时不再领取，保证重复删除幂等")
    void doesNotClaimCompletedTask() {
      when(repository.claim(anyLong(), any(), anyList(), any())).thenReturn(0);

      Optional<ResourceCleanupTask> claimed = compensationService.claimForProcessing(TASK_ID);

      assertThat(claimed).isEmpty();
      verify(repository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("清理成功后标记 COMPLETED 并记录完成时间")
    void marksCompleted() {
      ResourceDeletionCompensationEntity entity =
          task(ResourceCleanupStatus.PROCESSING, 1, "key");
      when(repository.findById(TASK_ID)).thenReturn(Optional.of(entity));

      compensationService.complete(TASK_ID);

      assertThat(entity.getStatus()).isEqualTo(ResourceCleanupStatus.COMPLETED);
      assertThat(entity.getCompletedAt()).isNotNull();
      assertThat(entity.getNextAttemptAt()).isNull();
      verify(repository).save(entity);
    }

    @Test
    @DisplayName("清理失败累加重试次数并设置退避时间")
    void schedulesRetryOnFailure() {
      ResourceDeletionCompensationEntity entity =
          task(ResourceCleanupStatus.PROCESSING, 0, "key");
      when(repository.findById(TASK_ID)).thenReturn(Optional.of(entity));

      compensationService.fail(TASK_ID, new IllegalStateException("S3 不可用"));

      assertThat(entity.getStatus()).isEqualTo(ResourceCleanupStatus.FAILED);
      assertThat(entity.getAttempts()).isEqualTo(1);
      assertThat(entity.getLastError()).contains("S3 不可用");
      assertThat(entity.getNextAttemptAt()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("重试次数耗尽后进入 DEAD 并清空下次重试时间")
    void marksDeadAfterMaxAttempts() {
      ResourceDeletionCompensationEntity entity =
          task(ResourceCleanupStatus.PROCESSING, 4, "key");
      when(repository.findById(TASK_ID)).thenReturn(Optional.of(entity));

      compensationService.fail(TASK_ID, new IllegalStateException("持续失败"));

      assertThat(entity.getAttempts()).isEqualTo(5);
      assertThat(entity.getStatus()).isEqualTo(ResourceCleanupStatus.DEAD);
      assertThat(entity.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("错误信息超长时截断，避免写入超长字段失败")
    void truncatesLastError() {
      ResourceDeletionCompensationEntity entity =
          task(ResourceCleanupStatus.PROCESSING, 0, "key");
      when(repository.findById(TASK_ID)).thenReturn(Optional.of(entity));
      String longMessage = "x".repeat(600);

      compensationService.fail(TASK_ID, new IllegalStateException(longMessage));

      assertThat(entity.getLastError()).hasSize(500);
    }

    @Test
    @DisplayName("未注册清理类型可直接标记 DEAD")
    void marksDeadDirectly() {
      ResourceDeletionCompensationEntity entity =
          task(ResourceCleanupStatus.PROCESSING, 0, "key");
      when(repository.findById(TASK_ID)).thenReturn(Optional.of(entity));

      compensationService.markDead(TASK_ID, "未注册的清理类型");

      assertThat(entity.getStatus()).isEqualTo(ResourceCleanupStatus.DEAD);
      assertThat(entity.getLastError()).contains("未注册");
    }
  }

  @Nested
  @DisplayName("查询与重试扫描")
  class QueryTests {

    @Test
    @DisplayName("到期待重试任务按 ID 返回，供定时任务消费")
    void findsDueTaskIds() {
      when(repository.findDueTasks(anyList(), any(), any(Pageable.class)))
          .thenReturn(List.of(task(ResourceCleanupStatus.FAILED, 1, "key")));

      List<Long> dueTaskIds = compensationService.findDueTaskIds(10);

      assertThat(dueTaskIds).containsExactly(TASK_ID);
    }

    @Test
    @DisplayName("回收卡在 PROCESSING 的任务，避免进程崩溃后任务永久停滞")
    void releasesStaleProcessingTasks() {
      when(repository.releaseStaleProcessing(
          eq(ResourceCleanupStatus.PROCESSING), eq(ResourceCleanupStatus.FAILED), any(), any()))
          .thenReturn(2);

      int released = compensationService.releaseStaleProcessing(Duration.ofMinutes(10));

      assertThat(released).isEqualTo(2);
    }

    @Test
    @DisplayName("按属主查询补偿任务，避免越权查看他人删除记录")
    void queriesTasksByOwner() {
      when(repository.findByOwnerUserIdOrderByUpdatedAtDesc(OWNER_ID))
          .thenReturn(List.of(task(ResourceCleanupStatus.PENDING, 0, "key")));

      List<ResourceDeletionCompensationEntity> tasks =
          compensationService.findForOwner(OWNER_ID);

      assertThat(tasks).hasSize(1);
      verify(repository).findByOwnerUserIdOrderByUpdatedAtDesc(OWNER_ID);
    }

    @Test
    @DisplayName("当前用户只能查询自己的补偿任务")
    void findMyTasksUsesCurrentUser() {
      when(currentUserService.getRequiredUserId()).thenReturn(OWNER_ID);
      when(repository.findByOwnerUserIdOrderByUpdatedAtDesc(OWNER_ID)).thenReturn(List.of());

      assertThat(compensationService.findMyTasks()).isEmpty();

      verify(repository).findByOwnerUserIdOrderByUpdatedAtDesc(OWNER_ID);
    }
  }
}
