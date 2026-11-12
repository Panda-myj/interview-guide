package interview.guide.modules.interview.listener;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.stream.StreamMessageId;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 面试评估消费者幂等性测试（PERF-001），全部使用 Mock，不依赖真实 Redis。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("面试评估消费者幂等性")
class EvaluateStreamConsumerIdempotencyTest {

    @Mock
    private RedisService redisService;
    @Mock
    private InterviewSessionRepository sessionRepository;
    @Mock
    private AnswerEvaluationService evaluationService;
    @Mock
    private InterviewPersistenceService persistenceService;
    @Mock
    private InterviewSessionService sessionService;
    @Mock
    private LlmProviderRegistry llmProviderRegistry;

    private TestableConsumer consumer;

    private final StreamMessageId messageId = new StreamMessageId(2L, 0L);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        consumer = new TestableConsumer(redisService, sessionRepository, evaluationService,
            persistenceService, objectMapper, llmProviderRegistry, sessionService);
    }

    @Nested
    @DisplayName("重复投递与乱序")
    class DuplicateDelivery {

        @Test
        @DisplayName("未抢到处理权的重复消息直接 ACK，不调用 LLM")
        void notClaimedShouldNotEvaluate() {
            InterviewSessionEntity session = session("session-1",
                InterviewSessionEntity.SessionStatus.COMPLETED);
            session.setEvaluateStatus(AsyncTaskStatus.PENDING);
            when(sessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
            when(sessionService.claimEvaluationProcessing(anyString())).thenReturn(false);

            consumer.process(messageId, payload("session-1", 0));

            verify(evaluationService, never()).evaluateInterview(any(), anyString(), anyString(), any());
            verify(redisService).streamAck(anyString(), anyString(), any(StreamMessageId.class));
            verify(sessionService, never()).completeEvaluation(anyString());
        }

        @Test
        @DisplayName("已完成评估的会话在跳过检查阶段直接 ACK")
        void completedEvaluationShouldBeSkipped() {
            InterviewSessionEntity session = session("session-1",
                InterviewSessionEntity.SessionStatus.COMPLETED);
            session.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
            when(sessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));

            consumer.process(messageId, payload("session-1", 0));

            verify(sessionService, never()).claimEvaluationProcessing(anyString());
            verify(evaluationService, never()).evaluateInterview(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("会话已删除时 ACK 丢弃")
        void missingSessionShouldBeAcked() {
            when(sessionRepository.findBySessionId("session-1")).thenReturn(Optional.empty());

            consumer.process(messageId, payload("session-1", 0));

            verify(redisService).streamAck(anyString(), anyString(), any(StreamMessageId.class));
            verify(evaluationService, never()).evaluateInterview(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("报告已生成时跳过重复 LLM 调用")
        void evaluatedSessionShouldNotReevaluate() {
            InterviewSessionEntity session = session("session-1",
                InterviewSessionEntity.SessionStatus.EVALUATED);
            session.setEvaluateStatus(AsyncTaskStatus.PROCESSING);
            when(sessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
            when(sessionRepository.findBySessionIdWithResume("session-1")).thenReturn(Optional.of(session));
            when(sessionService.claimEvaluationProcessing("session-1")).thenReturn(true);

            consumer.process(messageId, payload("session-1", 0));

            verify(evaluationService, never()).evaluateInterview(any(), anyString(), anyString(), any());
            verify(persistenceService, never()).saveReport(anyString(), any());
            verify(sessionService).completeEvaluation("session-1");
        }
    }

    @Nested
    @DisplayName("正常处理")
    class HappyPath {

        @Test
        @DisplayName("抢到处理权后执行一次评估并标记完成")
        void claimedShouldEvaluateOnce() {
            InterviewSessionEntity session = session("session-1",
                InterviewSessionEntity.SessionStatus.COMPLETED);
            session.setEvaluateStatus(AsyncTaskStatus.PENDING);
            when(sessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
            when(sessionRepository.findBySessionIdWithResume("session-1")).thenReturn(Optional.of(session));
            when(sessionService.claimEvaluationProcessing("session-1")).thenReturn(true);
            when(persistenceService.findAnswersBySessionId("session-1")).thenReturn(List.of());

            InterviewReportDTO report = new InterviewReportDTO("session-1", 1, 80,
                List.of(), List.of(), "ok", List.of(), List.of(), List.of());
            when(evaluationService.evaluateInterview(any(), anyString(), anyString(), any()))
                .thenReturn(report);

            consumer.process(messageId, payload("session-1", 0));

            verify(evaluationService).evaluateInterview(any(), anyString(), anyString(), any());
            verify(persistenceService).saveReport("session-1", report);
            verify(sessionService).completeEvaluation("session-1");
            verify(redisService).streamAck(anyString(), anyString(), any(StreamMessageId.class));
        }
    }

    @Nested
    @DisplayName("失败与重试")
    class RetryBehaviour {

        @Test
        @DisplayName("状态无法回退时不重复入队（避免重复副作用）")
        void retryShouldSkipWhenStatusChanged() {
            prepareFailingSession();
            when(sessionService.resetEvaluationForRetry("session-1")).thenReturn(false);

            consumer.process(messageId, payload("session-1", 0));

            verify(redisService, never()).streamAdd(anyString(), anyMap(), anyInt());
            verify(sessionService, never()).failEvaluation(anyString(), anyString());
        }

        @Test
        @DisplayName("未达上限时回退状态并重新入队")
        void retryShouldRequeueWhenClaimReleased() {
            prepareFailingSession();
            when(sessionService.resetEvaluationForRetry("session-1")).thenReturn(true);

            consumer.process(messageId, payload("session-1", 0));

            verify(redisService).streamAdd(anyString(), anyMap(), anyInt());
            verify(sessionService, never()).failEvaluation(anyString(), anyString());
        }

        @Test
        @DisplayName("达到 3 次重试后标记 FAILED，不再入队")
        void maxRetryShouldMarkFailed() {
            prepareFailingSession();

            consumer.process(messageId, payload("session-1", AsyncTaskStreamConstants.MAX_RETRY_COUNT));

            verify(sessionService).failEvaluation(eq("session-1"), anyString());
            verify(redisService, never()).streamAdd(anyString(), anyMap(), anyInt());
        }
    }

    private void prepareFailingSession() {
        InterviewSessionEntity session = session("session-1",
            InterviewSessionEntity.SessionStatus.COMPLETED);
        session.setEvaluateStatus(AsyncTaskStatus.PENDING);
        when(sessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(sessionRepository.findBySessionIdWithResume("session-1")).thenReturn(Optional.of(session));
        when(sessionService.claimEvaluationProcessing("session-1")).thenReturn(true);
        when(persistenceService.findAnswersBySessionId("session-1")).thenReturn(List.of());
        when(evaluationService.evaluateInterview(any(), anyString(), anyString(), any()))
            .thenThrow(new IllegalStateException("llm down"));
    }

    private InterviewSessionEntity session(String sessionId,
                                           InterviewSessionEntity.SessionStatus status) {
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setSessionId(sessionId);
        session.setStatus(status);
        session.setLlmProvider("dashscope");
        session.setQuestionsJson(objectMapper.writeValueAsString(
            List.of(InterviewQuestionDTO.create(0, "问题", "JAVA", "Java"))));
        return session;
    }

    private Map<String, String> payload(String sessionId, int retryCount) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_SESSION_ID, sessionId,
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
        );
    }

    /**
     * 暴露模板方法供测试直接驱动单条消息。
     */
    private static class TestableConsumer extends EvaluateStreamConsumer {

        TestableConsumer(RedisService redisService,
                         InterviewSessionRepository sessionRepository,
                         AnswerEvaluationService evaluationService,
                         InterviewPersistenceService persistenceService,
                         ObjectMapper objectMapper,
                         LlmProviderRegistry llmProviderRegistry,
                         InterviewSessionService sessionService) {
            super(redisService, sessionRepository, evaluationService, persistenceService,
                objectMapper, llmProviderRegistry, sessionService);
        }

        void process(StreamMessageId messageId, Map<String, String> data) {
            processMessage(messageId, data);
        }
    }
}
