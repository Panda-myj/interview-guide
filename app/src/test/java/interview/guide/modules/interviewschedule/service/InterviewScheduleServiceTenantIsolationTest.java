package interview.guide.modules.interviewschedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.security.CurrentUserService;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SEC-001：面试日程的写操作必须带属主条件。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("面试日程租户隔离测试")
class InterviewScheduleServiceTenantIsolationTest {

  private static final Long OWNER_ID = 7L;
  private static final Long SCHEDULE_ID = 42L;

  @Mock
  private InterviewScheduleRepository repository;

  @Mock
  private CurrentUserService currentUserService;

  private InterviewScheduleService service;

  @BeforeEach
  void setUp() {
    service = new InterviewScheduleService(repository, currentUserService);
  }

  @Nested
  @DisplayName("删除日程")
  class DeleteTests {

    @Test
    @DisplayName("删除其他用户的日程返回不存在且不执行删除")
    void rejectsDeletingOthersSchedule() {
      when(currentUserService.getRequiredUserId()).thenReturn(OWNER_ID);
      when(repository.findByIdAndOwnerUserId(SCHEDULE_ID, OWNER_ID)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.delete(SCHEDULE_ID))
          .isInstanceOf(BusinessException.class)
          .satisfies(error -> assertThat(((BusinessException) error).getCode())
              .isEqualTo(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND.getCode()));

      verify(repository, never()).delete(any());
      verify(repository, never()).deleteById(any());
    }

    @Test
    @DisplayName("删除自己的日程执行实体删除")
    void deletesOwnedSchedule() {
      InterviewScheduleEntity entity = new InterviewScheduleEntity();
      entity.setStatus(InterviewStatus.PENDING);
      when(currentUserService.getRequiredUserId()).thenReturn(OWNER_ID);
      when(repository.findByIdAndOwnerUserId(SCHEDULE_ID, OWNER_ID)).thenReturn(Optional.of(entity));

      service.delete(SCHEDULE_ID);

      verify(repository).delete(entity);
      verify(repository, never()).deleteById(any());
    }
  }
}
