package interview.guide.modules.interview.service;

import java.time.Clock;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.security.CurrentUserService;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * SEC-001 / QA-002：面试持久化的租户隔离与异常传播。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("面试持久化租户隔离与异常传播测试")
class InterviewPersistenceServiceTenantIsolationTest {

  private static final Long OWNER_ID = 7L;
  private static final Long OTHER_USER_RESUME_ID = 99L;
  private static final String SESSION_ID = "session-1";

  @Mock
  private InterviewSessionRepository sessionRepository;

  @Mock
  private InterviewAnswerRepository answerRepository;

  @Mock
  private ResumeRepository resumeRepository;

  @Mock
  private ObjectMapper objectMapper;

  @Mock
  private CurrentUserService currentUserService;

  private InterviewPersistenceService service;

  @BeforeEach
  void setUp() {
    service = new InterviewPersistenceService(
        sessionRepository, answerRepository, resumeRepository, objectMapper, currentUserService,
        Clock.system(ZoneId.of("Asia/Shanghai")));
  }

  @Nested
  @DisplayName("创建面试会话的简历归属校验")
  class SaveSessionOwnershipTests {

    @Test
    @DisplayName("引用其他用户的简历时拒绝创建会话")
    void rejectsResumeOwnedByAnotherUser() {
      when(currentUserService.getRequiredUser()).thenReturn(mock(UserEntity.class));
      when(currentUserService.getRequiredUserId()).thenReturn(OWNER_ID);
      when(objectMapper.writeValueAsString(any())).thenReturn("[]");
      when(resumeRepository.findByIdAndOwnerUserId(OTHER_USER_RESUME_ID, OWNER_ID))
          .thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.saveSession(
          SESSION_ID, OTHER_USER_RESUME_ID, 5, questions(), "default", null, null))
          .isInstanceOf(BusinessException.class)
          .satisfies(error -> assertThat(((BusinessException) error).getCode())
              .isEqualTo(ErrorCode.RESUME_NOT_FOUND.getCode()));

      verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("引用自己的简历时正常关联并保存会话")
    void linksOwnedResume() {
      UserEntity owner = mock(UserEntity.class);
      ResumeEntity resume = new ResumeEntity();
      when(currentUserService.getRequiredUser()).thenReturn(owner);
      when(currentUserService.getRequiredUserId()).thenReturn(OWNER_ID);
      when(objectMapper.writeValueAsString(any())).thenReturn("[]");
      when(resumeRepository.findByIdAndOwnerUserId(OTHER_USER_RESUME_ID, OWNER_ID))
          .thenReturn(Optional.of(resume));
      when(sessionRepository.save(any(InterviewSessionEntity.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      service.saveSession(
          SESSION_ID, OTHER_USER_RESUME_ID, 5, questions(), "default", "java", "MID");

      ArgumentCaptor<InterviewSessionEntity> captor =
          ArgumentCaptor.forClass(InterviewSessionEntity.class);
      verify(sessionRepository).save(captor.capture());
      assertThat(captor.getValue().getResume()).isSameAs(resume);
      assertThat(captor.getValue().getOwnerUser()).isSameAs(owner);
    }
  }

  @Nested
  @DisplayName("历史题目查询的租户隔离")
  class HistoricalQuestionOwnershipTests {

    @Test
    @DisplayName("按简历查询历史题目时带上属主条件")
    void usesOwnerScopedQueryWithResume() {
      when(currentUserService.getRequiredUserId()).thenReturn(OWNER_ID);
      when(sessionRepository.findTop10ByResumeIdAndOwnerUserIdAndSkillIdOrderByCreatedAtDesc(
          OTHER_USER_RESUME_ID, OWNER_ID, "java")).thenReturn(List.of());

      List<HistoricalQuestion> result = service.getHistoricalQuestions("java", OTHER_USER_RESUME_ID);

      assertThat(result).isEmpty();
      verify(sessionRepository, never()).findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(any(), anyString());
    }

    @Test
    @DisplayName("无简历时按属主 + 技能查询，不使用全局查询")
    void usesOwnerScopedQueryWithoutResume() {
      when(currentUserService.getRequiredUserId()).thenReturn(OWNER_ID);
      when(sessionRepository.findTop10ByOwnerUserIdAndSkillIdOrderByCreatedAtDesc(OWNER_ID, "java"))
          .thenReturn(List.of());

      service.getHistoricalQuestions("java", null);

      verify(sessionRepository).findTop10ByOwnerUserIdAndSkillIdOrderByCreatedAtDesc(OWNER_ID, "java");
      verify(sessionRepository, never()).findTop10BySkillIdOrderByCreatedAtDesc(anyString());
    }
  }

  @Nested
  @DisplayName("报告保存失败必须让任务失败")
  class SaveReportFailureTests {

    @Test
    @DisplayName("报告序列化失败抛出业务异常，不再静默吞掉")
    void throwsWhenSerializationFails() {
      InterviewSessionEntity session = new InterviewSessionEntity();
      when(sessionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(session));
      JacksonException failure = mock(JacksonException.class);
      when(objectMapper.writeValueAsString(any())).thenThrow(failure);

      assertThatThrownBy(() -> service.saveReport(SESSION_ID, report()))
          .isInstanceOf(BusinessException.class)
          .satisfies(error -> assertThat(((BusinessException) error).getCode())
              .isEqualTo(ErrorCode.INTERVIEW_REPORT_SAVE_FAILED.getCode()));

      verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("会话已被删除时丢弃报告且不抛异常，异步任务可正常 ACK")
    void dropsReportWhenSessionDeleted() {
      when(sessionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.empty());

      service.saveReport(SESSION_ID, report());

      verify(sessionRepository, never()).save(any());
      verify(answerRepository, never()).saveAll(any());
    }
  }

  private List<InterviewQuestionDTO> questions() {
    return List.of(InterviewQuestionDTO.create(
        0, "自我介绍", "GENERAL", "开场问题", "开场问题", false, null));
  }

  private InterviewReportDTO report() {
    return new InterviewReportDTO(
        SESSION_ID, 1, 80, List.of(), List.of(), "总体不错", List.of("表达清晰"),
        List.of("补充细节"), List.of());
  }
}
