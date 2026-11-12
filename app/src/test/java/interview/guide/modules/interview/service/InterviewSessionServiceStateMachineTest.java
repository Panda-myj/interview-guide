package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.modules.auth.security.CurrentUserService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 面试会话服务状态机与幂等性测试（PERF-001）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("面试会话服务状态机")
class InterviewSessionServiceStateMachineTest {

    @Mock
    private InterviewQuestionService questionService;
    @Mock
    private AnswerEvaluationService evaluationService;
    @Mock
    private InterviewPersistenceService persistenceService;
    @Mock
    private InterviewSessionCache sessionCache;
    @Mock
    private EvaluateStreamProducer evaluateStreamProducer;
    @Mock
    private LlmProviderRegistry llmProviderRegistry;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private InterviewSessionRepository sessionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InterviewSessionService service;

    private static final String SESSION_ID = "session-1";

    @BeforeEach
    void setUp() {
        service = new InterviewSessionService(
            questionService,
            evaluationService,
            persistenceService,
            sessionCache,
            objectMapper,
            evaluateStreamProducer,
            llmProviderRegistry,
            currentUserService,
            sessionRepository,
            new TransactionalExecutor()
        );
    }

    @Nested
    @DisplayName("交卷幂等")
    class CompleteInterview {

        @Test
        @DisplayName("已完成的会话重复交卷直接返回，不重复入队评估")
        void completedSessionShouldNotEnqueueAgain() {
            givenCachedSession(SessionStatus.COMPLETED, 0, questions());
            when(persistenceService.findBySessionId(SESSION_ID))
                .thenReturn(Optional.of(entity(InterviewSessionEntity.SessionStatus.COMPLETED)));

            service.completeInterview(SESSION_ID);

            verify(evaluateStreamProducer, never()).sendEvaluateTask(anyString());
            verify(sessionCache, never()).updateSessionStatus(anyString(), any());
        }

        @Test
        @DisplayName("首次交卷条件迁移成功后只入队一次")
        void firstCompleteShouldEnqueueOnce() {
            givenCachedSession(SessionStatus.IN_PROGRESS, 0, questions());
            when(persistenceService.findBySessionId(SESSION_ID))
                .thenReturn(Optional.of(entity(InterviewSessionEntity.SessionStatus.IN_PROGRESS)));
            when(sessionRepository.updateSessionStatusConditionally(
                eq(SESSION_ID), eq(InterviewSessionEntity.SessionStatus.COMPLETED), anyList()))
                .thenReturn(1);
            when(sessionRepository.initializeEvaluateStatus(
                eq(SESSION_ID), eq(AsyncTaskStatus.PENDING), any()))
                .thenReturn(1);

            service.completeInterview(SESSION_ID);

            verify(evaluateStreamProducer).sendEvaluateTask(SESSION_ID);
            verify(sessionCache).updateSessionStatus(SESSION_ID, SessionStatus.COMPLETED);
        }

        @Test
        @DisplayName("并发交卷时条件更新失败，不重复入队")
        void concurrentCompleteShouldNotEnqueue() {
            givenCachedSession(SessionStatus.IN_PROGRESS, 0, questions());
            when(persistenceService.findBySessionId(SESSION_ID))
                .thenReturn(Optional.of(entity(InterviewSessionEntity.SessionStatus.IN_PROGRESS)));
            when(sessionRepository.updateSessionStatusConditionally(
                eq(SESSION_ID), eq(InterviewSessionEntity.SessionStatus.COMPLETED), anyList()))
                .thenReturn(0);

            service.completeInterview(SESSION_ID);

            verify(evaluateStreamProducer, never()).sendEvaluateTask(anyString());
        }

        @Test
        @DisplayName("评估已在队列或已完成时不再重复入队")
        void pendingOrCompletedEvaluationShouldNotEnqueueAgain() {
            givenCachedSession(SessionStatus.IN_PROGRESS, 0, questions());
            when(persistenceService.findBySessionId(SESSION_ID))
                .thenReturn(Optional.of(entity(InterviewSessionEntity.SessionStatus.IN_PROGRESS)));
            when(sessionRepository.updateSessionStatusConditionally(
                eq(SESSION_ID), eq(InterviewSessionEntity.SessionStatus.COMPLETED), anyList()))
                .thenReturn(1);
            when(sessionRepository.initializeEvaluateStatus(
                eq(SESSION_ID), eq(AsyncTaskStatus.PENDING), any()))
                .thenReturn(0);
            when(sessionRepository.updateEvaluateStatusConditionally(
                eq(SESSION_ID), eq(AsyncTaskStatus.PENDING),
                eq(List.of(AsyncTaskStatus.FAILED)), any()))
                .thenReturn(0);

            service.completeInterview(SESSION_ID);

            verify(evaluateStreamProducer, never()).sendEvaluateTask(anyString());
        }
    }

    @Nested
    @DisplayName("答案提交幂等")
    class SubmitAnswer {

        @Test
        @DisplayName("重复提交已作答的题返回当前进度，不重复写库")
        void duplicateSubmitShouldReturnCurrentProgress() {
            givenCachedSession(SessionStatus.IN_PROGRESS, 2, questions());
            when(persistenceService.findBySessionId(SESSION_ID))
                .thenReturn(Optional.of(entity(InterviewSessionEntity.SessionStatus.IN_PROGRESS)));

            SubmitAnswerResponse response = service.submitAnswer(
                new SubmitAnswerRequest(SESSION_ID, 1, "重复答案"));

            assertThat(response.currentIndex()).isEqualTo(2);
            assertThat(response.hasNextQuestion()).isTrue();
            verify(persistenceService, never()).saveAnswer(
                anyString(), anyInt(), any(), any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("乱序提交（跳过未答题）返回明确业务错误")
        void outOfOrderSubmitShouldFail() {
            givenCachedSession(SessionStatus.IN_PROGRESS, 0, questions());
            when(persistenceService.findBySessionId(SESSION_ID))
                .thenReturn(Optional.of(entity(InterviewSessionEntity.SessionStatus.IN_PROGRESS)));

            assertThatThrownBy(() -> service.submitAnswer(
                new SubmitAnswerRequest(SESSION_ID, 2, "跳题答案")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("已交卷会话拒绝继续提交，保护已评估答案")
        void completedSessionShouldRejectSubmit() {
            givenCachedSession(SessionStatus.COMPLETED, 0, questions());
            when(persistenceService.findBySessionId(SESSION_ID))
                .thenReturn(Optional.of(entity(InterviewSessionEntity.SessionStatus.COMPLETED)));

            assertThatThrownBy(() -> service.submitAnswer(
                new SubmitAnswerRequest(SESSION_ID, 0, "迟到答案")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.INTERVIEW_ALREADY_COMPLETED.getCode());
        }
    }

    @Nested
    @DisplayName("报告生成幂等")
    class GenerateReport {

        @Test
        @DisplayName("已生成报告时直接返回持久化结果，不重复调用 LLM")
        void evaluatedSessionShouldReturnPersistedReport() {
            givenCachedSession(SessionStatus.EVALUATED, 0, questions());
            InterviewSessionEntity entity = entity(InterviewSessionEntity.SessionStatus.EVALUATED);
            entity.setOverallScore(88);
            entity.setOverallFeedback("表现不错");
            when(persistenceService.findBySessionId(SESSION_ID)).thenReturn(Optional.of(entity));
            when(persistenceService.findAnswersBySessionId(SESSION_ID)).thenReturn(List.of());

            InterviewReportDTO report = service.generateReport(SESSION_ID);

            assertThat(report.overallScore()).isEqualTo(88);
            assertThat(report.overallFeedback()).isEqualTo("表现不错");
            verify(evaluationService, never()).evaluateInterview(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("数据库状态仍未完成时拒绝评估，返回非法迁移错误")
        void illegalTransitionShouldFail() {
            givenCachedSession(SessionStatus.COMPLETED, 0, questions());
            when(persistenceService.findBySessionId(SESSION_ID))
                .thenReturn(Optional.of(entity(InterviewSessionEntity.SessionStatus.IN_PROGRESS)));

            assertThatThrownBy(() -> service.generateReport(SESSION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.INTERVIEW_ILLEGAL_STATE_TRANSITION.getCode());
        }

        @Test
        @DisplayName("评估已被其它实例抢占时返回进行中错误，不重复调用 LLM")
        void concurrentEvaluationShouldFailFast() {
            givenCachedSession(SessionStatus.COMPLETED, 0, questions());
            InterviewSessionEntity entity = entity(InterviewSessionEntity.SessionStatus.COMPLETED);
            when(persistenceService.findBySessionId(SESSION_ID))
                .thenReturn(Optional.of(entity), Optional.of(entity));
            when(sessionRepository.initializeEvaluateStatus(
                eq(SESSION_ID), eq(AsyncTaskStatus.PROCESSING), any())).thenReturn(0);
            when(sessionRepository.claimEvaluateStatus(
                eq(SESSION_ID), eq(AsyncTaskStatus.PENDING), eq(AsyncTaskStatus.PROCESSING)))
                .thenReturn(0);
            when(sessionRepository.claimEvaluateStatus(
                eq(SESSION_ID), eq(AsyncTaskStatus.FAILED), eq(AsyncTaskStatus.PROCESSING)))
                .thenReturn(0);

            assertThatThrownBy(() -> service.generateReport(SESSION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.INTERVIEW_EVALUATION_IN_PROGRESS.getCode());
        }
    }

    @Nested
    @DisplayName("消费者状态迁移")
    class ConsumerTransitions {

        @Test
        @DisplayName("抢占失败时返回 false，消息应被 ACK 丢弃")
        void claimFailureShouldReturnFalse() {
            when(sessionRepository.claimEvaluateStatus(
                SESSION_ID, AsyncTaskStatus.PENDING, AsyncTaskStatus.PROCESSING)).thenReturn(0);

            assertThat(service.claimEvaluationProcessing(SESSION_ID)).isFalse();
        }

        @Test
        @DisplayName("重试回退失败表示任务已完成，不再重复入队")
        void resetRetryFailureShouldReturnFalse() {
            when(sessionRepository.updateEvaluateStatusConditionally(
                eq(SESSION_ID), eq(AsyncTaskStatus.PENDING),
                eq(List.of(AsyncTaskStatus.PROCESSING, AsyncTaskStatus.PENDING)), any()))
                .thenReturn(0);

            assertThat(service.resetEvaluationForRetry(SESSION_ID)).isFalse();
        }

        @Test
        @DisplayName("失败原因写入前截断到 500 字符")
        void failureErrorShouldBeTruncated() {
            when(sessionRepository.updateEvaluateStatusConditionally(
                eq(SESSION_ID), eq(AsyncTaskStatus.FAILED), anyList(), anyString())).thenReturn(1);

            service.failEvaluation(SESSION_ID, "x".repeat(800));

            verify(sessionRepository).updateEvaluateStatusConditionally(
                eq(SESSION_ID), eq(AsyncTaskStatus.FAILED), anyList(),
                eq("x".repeat(500)));
        }
    }

    private void givenCachedSession(SessionStatus status, int currentIndex,
                                    List<InterviewQuestionDTO> questions) {
        CachedSession cached = new CachedSession(
            SESSION_ID, 1L, "resume", null, questions, currentIndex, status, objectMapper);
        when(sessionCache.getSession(SESSION_ID)).thenReturn(Optional.of(cached));
    }

    private List<InterviewQuestionDTO> questions() {
        return List.of(
            InterviewQuestionDTO.create(0, "Q1", "JAVA", "Java"),
            InterviewQuestionDTO.create(1, "Q2", "JAVA", "Java"),
            InterviewQuestionDTO.create(2, "Q3", "JAVA", "Java"));
    }

    private InterviewSessionEntity entity(InterviewSessionEntity.SessionStatus status) {
        InterviewSessionEntity entity = new InterviewSessionEntity();
        entity.setSessionId(SESSION_ID);
        entity.setStatus(status);
        entity.setLlmProvider("dashscope");
        entity.setQuestionsJson(objectMapper.writeValueAsString(questions()));
        return entity;
    }
}
